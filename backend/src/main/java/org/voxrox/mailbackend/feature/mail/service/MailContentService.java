package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.util.HtmlSanitizer;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.MimePartExtractor;

import module java.base;

@Service
public class MailContentService {
    private static final Logger log = LoggerFactory.getLogger(MailContentService.class);

    private final ImapFolderExecutor folderExecutor;
    private final MessageRepository messageRepository;
    private final MessageContentPersister contentPersister;

    public MailContentService(ImapFolderExecutor folderExecutor, MessageRepository messageRepository,
            MessageContentPersister contentPersister) {
        this.folderExecutor = folderExecutor;
        this.messageRepository = messageRepository;
        this.contentPersister = contentPersister;
    }

    /**
     * Main entry point for retrieving message content. If it is not in the DB,
     * fetches it from IMAP.
     * <p>
     * Errors are <strong>not suppressed</strong> — typed exceptions propagate:
     * <ul>
     * <li>{@link ResourceNotFoundException} — the message does not exist in the DB,
     * or has already been deleted on the server (UID has no matching message on
     * IMAP).</li>
     * <li>{@link MailOperationException} — IMAP communication failure, MIME parsing
     * failure, or an unexpected internal error. The caller (typically a controller)
     * lets it bubble up to GlobalExceptionHandler which returns ProblemDetail.</li>
     * </ul>
     */
    public String getOrFetchMessageContent(Long messageInternalId) {
        MessageEntity messageEntity = messageRepository.findById(messageInternalId)
                .orElseThrow(() -> new ResourceNotFoundException("Message id=" + messageInternalId + " not found."));

        String content = messageEntity.getContent();
        if (content != null && !content.isBlank()) {
            return content;
        }

        log.info("{} Body missing. Fetching from IMAP server (uid={}, folder={})", LogCategory.SYNC,
                messageEntity.getUid(), messageEntity.getFolderName());

        long startTime = System.currentTimeMillis();

        /*
         * The network operation runs outside the main transaction. MessagingException
         * is translated by ImapFolderExecutor; IOException is wrapped here because
         * ImapFolderAction does not declare it.
         */
        String fetchedContent = folderExecutor.executeReadOnly(messageEntity.getAccount().getId(),
                messageEntity.getFolderName(), (folder, uidFolder) -> {
                    Message message = uidFolder.getMessageByUID(messageEntity.getUid());
                    if (message == null) {
                        return null; // signal to outer code: the message is no longer on the server
                    }
                    try {
                        String rawText = MimePartExtractor.extractText(message);
                        return HtmlSanitizer.sanitize(rawText);
                    } catch (IOException | RuntimeException e) {
                        // RuntimeException too: a malformed MIME structure can throw an
                        // unchecked JavaMail error. Wrap it so MailFacade surfaces a
                        // contentError on the cached body instead of a 500.
                        throw new MailOperationException(ErrorCode.MAIL_CONNECTION_ERROR,
                                "Error while extracting message text: " + e.getMessage());
                    }
                });

        if (fetchedContent == null) {
            log.warn("{} Message uid={} no longer exists on the server.", LogCategory.SYNC, messageEntity.getUid());
            throw new ResourceNotFoundException(
                    "Message uid=" + messageEntity.getUid() + " no longer exists on the server.");
        }

        log.debug("{} Fetched and sanitized in {} ms", LogCategory.SYNC, System.currentTimeMillis() - startTime);

        return contentPersister.updateLocalCache(messageInternalId, fetchedContent);
    }
}
