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
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager.Lane;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

@Service
public class AttachmentService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    /**
     * A MIME part path the way {@code MimePartExtractor} writes it and IMAP numbers
     * parts (RFC 3501 §6.4.5): positive part numbers joined by dots, "1" or
     * "2.1.3". The download endpoint validates against it too, so a malformed path
     * is a 400 before any IMAP work.
     */
    public static final String PART_PATH_PATTERN = "[1-9][0-9]*(\\.[1-9][0-9]*)*";
    private static final Pattern PART_PATH = Pattern.compile(PART_PATH_PATTERN);

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

        /*
         * Checked here as well as on the endpoint, and before the folder executor: an
         * exception the download lambda raised for a malformed path would reach the
         * executor, which turns anything but a not-found or a mail operation error into
         * a 500.
         */
        if (!PART_PATH.matcher(partPath).matches()) {
            throw new ValidationException("Invalid attachment part path.", "validation.attachment.partPath");
        }

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
        // The action either returns a written temp file or throws — it never
        // yields null, so the nullable executor result can be required here.
        return java.util.Objects.requireNonNull(folderExecutor.executeReadOnly(entity.getAccount().getId(),
                Lane.INTERACTIVE, entity.getFolderName(), (folder, uidFolder) -> {
                    Path tempFile = null;
                    try {
                        jakarta.mail.Message msg = uidFolder.getMessageByUID(entity.getUid());

                        if (msg == null) {
                            throw new ResourceNotFoundException(
                                    "Message uid=" + entity.getUid() + " does not exist on the server.");
                        }

                        Part part = findPartByPath(msg, partPath, partPath);

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
                }));
    }

    /**
     * Cleanup of old temp files that may have been left on disk after an
     * application crash.
     */
    private void cleanupStaleTempFiles() {
        try (var stream = Files.list(privateTempDir)) {
            long cutoff = System.currentTimeMillis() - 3600_000L; // 1 hour
            stream.filter(p -> {
                Path name = p.getFileName();
                return name != null && name.toString().startsWith("attach_");
            }).filter(p -> {
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
     * The MIME part at {@code path} ("2.1"), numbered the way IMAP numbers parts
     * (RFC 3501 §6.4.5) and {@code MimePartExtractor} writes them. The path has
     * already matched {@link #PART_PATH_PATTERN}.
     *
     * <p>
     * A path that leads nowhere is a {@link ResourceNotFoundException} naming the
     * attachment. It used to be a {@link MessagingException}, which the folder
     * executor reads by its text: "not found" in it became "Folder 'INBOX' was not
     * found on the server", a 404 about the wrong thing.
     */
    private Part findPartByPath(Part part, String path, String fullPath) throws MessagingException, IOException {
        String[] segments = path.split("\\.", 2);
        int index = partIndex(segments[0], fullPath);
        Object content = part.getContent();

        // An encapsulated message (message/rfc822) is numbered from its own body.
        if (content instanceof jakarta.mail.Message innerMsg) {
            return findPartByPath(innerMsg, path, fullPath);
        }

        if (content instanceof Multipart multipart) {
            if (index >= multipart.getCount()) {
                throw partNotFound(fullPath);
            }
            Part child = multipart.getBodyPart(index);
            return segments.length == 1 ? child : findPartByPath(child, segments[1], fullPath);
        }

        // A message whose body is not multipart has that body as its part 1, and "1"
        // is the path the extractor gives an attachment that is a whole message's
        // body. Only a message: a single part inside a multipart has no parts below.
        if (index == 0 && segments.length == 1 && part instanceof jakarta.mail.Message) {
            return part;
        }
        throw partNotFound(fullPath);
    }

    /**
     * Zero-based index of a part number; one too large for an int finds no part.
     */
    private static int partIndex(String segment, String fullPath) {
        try {
            return Integer.parseInt(segment) - 1;
        } catch (NumberFormatException e) {
            throw partNotFound(fullPath);
        }
    }

    private static ResourceNotFoundException partNotFound(String fullPath) {
        return new ResourceNotFoundException("MIME part " + fullPath + " was not found in the message.",
                "error.attachment.partNotFound");
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
