package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.core.config.StorageProperties;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

@Service
public class AttachmentService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final ImapFolderExecutor folderExecutor;
    private final MessageRepository messageRepository;
    private final Path privateTempDir;

    public AttachmentService(ImapFolderExecutor folderExecutor, MessageRepository messageRepository,
            StorageProperties storageProperties) {
        this.folderExecutor = folderExecutor;
        this.messageRepository = messageRepository;
        // Directory is guaranteed to exist — created by StorageContextInitializer at
        // startup
        this.privateTempDir = storageProperties.getTmpPath();
    }

    /**
     * Runs once on {@link ApplicationReadyEvent}, off the boot thread on
     * {@code mailEventExecutor}, so the temp-dir scan does not delay Spring context
     * startup. Kept out of the constructor / {@code @PostConstruct} so a failure to
     * scan the temp directory does not leave a partially-initialised instance
     * behind (SpotBugs CT_CONSTRUCTOR_THROW) and so cold start is not blocked by
     * potentially slow filesystem IO on a stale {@code tmp/} directory.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async("mailEventExecutor")
    public void initStaleTempCleanup() {
        cleanupStaleTempFiles();
    }

    /**
     * Main entry point for obtaining an attachment stream. Implements a two-phase
     * download to minimize the time the IMAP lock is held.
     */
    public InputStream getAttachmentStreamByStableId(String stableId, String partPath) {
        log.debug("{} Attachment request: stableId={}, path={}", LogCategory.ATTACHMENT, stableId, partPath);

        MessageEntity entity = messageRepository.findByStableId(stableId)
                .orElseThrow(() -> new ResourceNotFoundException("Message " + stableId + " not found."));

        // Phase 1: download the attachment from the server into a temp file (under the
        // IMAP lock)
        Path tempFile = downloadToTempFile(entity, partPath, stableId);

        // Phase 2: return a stream over the temp file (OUTSIDE the lock — the lock is
        // already released)
        try {
            return new DeleteOnCloseFileInputStream(tempFile.toFile());
        } catch (FileNotFoundException e) {
            throw new MailOperationException(ErrorCode.INTERNAL_ERROR,
                    "Temp file disappeared before the stream was opened: " + tempFile);
        }
    }

    private Path downloadToTempFile(MessageEntity entity, String partPath, String stableId) {
        return folderExecutor.executeReadOnly(entity.getAccount().getId(), entity.getFolderName(),
                (folder, uidFolder) -> {
                    Path tempFile = null;
                    try {
                        jakarta.mail.Message msg = uidFolder.getMessageByUID(entity.getUid());

                        if (msg == null) {
                            throw new ResourceNotFoundException(
                                    "Message uid=" + entity.getUid() + " does not exist on the server.");
                        }

                        Part part = findPartByPath(msg, partPath);

                        tempFile = Files.createTempFile(privateTempDir, "attach_" + stableId + "_", ".tmp");

                        try (InputStream imapIs = part.getInputStream()) {
                            long bytesCopied = Files.copy(imapIs, tempFile, StandardCopyOption.REPLACE_EXISTING);

                            /*
                             * Integrity check: detect empty download even though the server reports
                             * non-zero data.
                             */
                            if (bytesCopied == 0 && part.getSize() > 0) {
                                throw new IOException(
                                        "Downloaded file is empty although the server reports size " + part.getSize());
                            }
                        }

                        log.debug("{} Attachment downloaded: {} ({} bytes)", LogCategory.ATTACHMENT,
                                tempFile.getFileName(), Files.size(tempFile));
                        return tempFile;

                    } catch (MessagingException | IOException e) {
                        if (tempFile != null) {
                            try {
                                Files.deleteIfExists(tempFile);
                            } catch (IOException cleanupEx) {
                                log.debug("{} Deleting a partial temp file failed: {}", LogCategory.ATTACHMENT,
                                        cleanupEx.getMessage());
                            }
                        }
                        log.error("{} Failed to download attachment {}: {}", LogCategory.ATTACHMENT, stableId,
                                e.getMessage());
                        /*
                         * MessagingException propagates to ImapFolderExecutor which translates it into
                         * MailOperationException(MAIL_CONNECTION_ERROR). A ResourceNotFoundException
                         * (RuntimeException) bubbles up without being caught.
                         */
                        if (e instanceof MessagingException me) {
                            throw me;
                        }
                        throw new MailOperationException(ErrorCode.INTERNAL_ERROR,
                                "Error while downloading the attachment: " + e.getMessage());
                    }
                });
    }

    /**
     * Cleanup of old temp files that may have been left on disk after an
     * application crash.
     */
    private void cleanupStaleTempFiles() {
        try (var stream = Files.list(privateTempDir)) {
            long cutoff = System.currentTimeMillis() - 3600_000L; // 1 hour
            stream.filter(p -> p.getFileName().toString().startsWith("attach_")).filter(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis() < cutoff;
                } catch (IOException e) {
                    return false;
                }
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                    log.debug("{} Deleted stale temp file: {}", LogCategory.ATTACHMENT, p.getFileName());
                } catch (IOException e) {
                    log.debug("{} Deleting stale temp file {} failed: {}", LogCategory.ATTACHMENT, p.getFileName(),
                            e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("{} Error during temp file cleanup: {}", LogCategory.ATTACHMENT, e.getMessage());
        }
    }

    /**
     * Recursively looks up a specific MIME part by the given path (e.g. "2.1").
     */
    private Part findPartByPath(Part parent, String path) throws MessagingException, IOException {
        if (path == null || path.isEmpty())
            return parent;

        String[] parts = path.split("\\.", 2);
        int targetIdx;
        try {
            targetIdx = Integer.parseInt(parts[0]) - 1;
        } catch (NumberFormatException e) {
            throw new MessagingException("Invalid attachment path format: " + parts[0]);
        }

        Object content = parent.getContent();

        if (content instanceof jakarta.mail.Message innerMsg) {
            return findPartByPath(innerMsg, path);
        }

        if (content instanceof Multipart mp) {
            if (targetIdx >= 0 && targetIdx < mp.getCount()) {
                Part child = mp.getBodyPart(targetIdx);
                if (parts.length == 1)
                    return child;
                return findPartByPath(child, parts[1]);
            }
        }

        throw new MessagingException("MIME part '" + path + "' was not found in the e-mail.");
    }

    /**
     * Stream that automatically removes the underlying file from disk on close.
     */
    private static class DeleteOnCloseFileInputStream extends FileInputStream {
        private final File file;
        private boolean closed = false;

        DeleteOnCloseFileInputStream(File file) throws FileNotFoundException {
            super(file);
            this.file = file;
        }

        @Override
        public void close() throws IOException {
            if (closed)
                return;
            try {
                super.close();
            } finally {
                closed = true;
                if (file.exists()) {
                    boolean deleted = file.delete();
                    log.debug("{} Cleanup: temp file {} deleted={}", LogCategory.ATTACHMENT, file.getName(), deleted);
                }
            }
        }
    }
}
