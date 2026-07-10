package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
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
    private final MessageSource messageSource;

    public MailContentService(ImapFolderExecutor folderExecutor, MessageRepository messageRepository,
            MessageContentPersister contentPersister, MessageSource messageSource) {
        this.folderExecutor = folderExecutor;
        this.messageRepository = messageRepository;
        this.contentPersister = contentPersister;
        this.messageSource = messageSource;
    }

    /**
     * IMAP fetch result. {@code html} is the sanitized body; {@code oversize} marks
     * a body over the extractor's byte cap (audit B1-1), in which case {@code html}
     * is empty and the caller serves {@link #oversizePlaceholder()}.
     * Package-private for tests.
     */
    record FetchedBody(String html, boolean oversize) {
        private static final FetchedBody OVERSIZE = new FetchedBody("", true);
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
     * A body over the extractor's byte cap (audit B1-1) is not an error: the method
     * returns a localized placeholder and persists {@code bodyOversize} so later
     * opens skip IMAP entirely.
     */
    public String getOrFetchMessageContent(Long messageInternalId) {
        return getOrFetch(messageInternalId, true);
    }

    /**
     * Body for quoting into a reply/forward draft. Identical to
     * {@link #getOrFetchMessageContent} except an oversized body (B1-1) quotes as
     * an empty string — quoting the "message too large" placeholder would send that
     * sentence to the recipient as if it were the original text.
     */
    public String getOrFetchQuotableContent(Long messageInternalId) {
        return getOrFetch(messageInternalId, false);
    }

    private String getOrFetch(Long messageInternalId, boolean placeholderWhenOversize) {
        MessageEntity messageEntity = messageRepository.findById(messageInternalId)
                .orElseThrow(() -> new ResourceNotFoundException("Message id=" + messageInternalId + " not found."));

        if (messageEntity.isBodyOversize()) {
            // B1-1: the body blew the extractor's byte cap on an earlier open.
            // Answer without touching IMAP again.
            return placeholderWhenOversize ? oversizePlaceholder() : "";
        }

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
        FetchedBody fetched = folderExecutor.executeReadOnly(messageEntity.getAccount().getId(),
                messageEntity.getFolderName(), (folder, uidFolder) -> {
                    Message message = uidFolder.getMessageByUID(messageEntity.getUid());
                    if (message == null) {
                        return null; // signal to outer code: the message is no longer on the server
                    }
                    try {
                        MimePartExtractor.ExtractedBody body = MimePartExtractor.extractBody(message);
                        if (body.oversize()) {
                            return FetchedBody.OVERSIZE;
                        }
                        if (!body.isHtml()) {
                            // A genuine text/plain body is escaped and wrapped, never parsed
                            // as HTML, so literal <...> sequences (code snippets, a<b and c>d)
                            // render verbatim instead of being dropped as bogus tags (F3).
                            return new FetchedBody(HtmlSanitizer.escapePlainText(body.text()), false);
                        }
                        String rawText = body.text();
                        // Only walk the MIME tree when the body actually references a cid:
                        // image — the common case (no embedded images) stays a single
                        // extraction pass, and only referenced parts are ever read.
                        Set<String> referencedCids = HtmlSanitizer.referencedCids(rawText);
                        Map<String, String> inlineImages = referencedCids.isEmpty()
                                ? Map.of()
                                : MimePartExtractor.collectInlineImages(message, referencedCids);
                        return new FetchedBody(HtmlSanitizer.sanitize(rawText, inlineImages), false);
                    } catch (IOException | RuntimeException e) {
                        // RuntimeException too: a malformed MIME structure can throw an
                        // unchecked JavaMail error. Wrap it so MailFacade surfaces a
                        // contentError on the cached body instead of a 500.
                        throw new MailOperationException(ErrorCode.MAIL_CONNECTION_ERROR,
                                "Error while extracting message text: " + e.getMessage());
                    }
                });

        if (fetched == null) {
            log.warn("{} Message uid={} no longer exists on the server.", LogCategory.SYNC, messageEntity.getUid());
            throw new ResourceNotFoundException(
                    "Message uid=" + messageEntity.getUid() + " no longer exists on the server.");
        }

        if (fetched.oversize()) {
            log.warn("{} Body of uid={} exceeds the byte cap; persisting the oversize flag (audit B1-1).",
                    LogCategory.SYNC, messageEntity.getUid());
            try {
                contentPersister.markBodyOversize(messageInternalId);
            } catch (RuntimeException e) {
                // The flag only spares later opens the bounded re-fetch; a transient
                // persist failure must not turn the placeholder into a 500.
                log.warn("{} Could not persist the oversize flag for id={}: {}", LogCategory.SYNC, messageInternalId,
                        e.getMessage());
            }
            return placeholderWhenOversize ? oversizePlaceholder() : "";
        }

        log.debug("{} Fetched and sanitized in {} ms", LogCategory.SYNC, System.currentTimeMillis() - startTime);

        return contentPersister.updateLocalCache(messageInternalId, fetched.html());
    }

    /**
     * Localized "message too large" placeholder (audit B1-1), rendered through the
     * same plain-text wrapper as a real body so the client content path is
     * identical. Resolved per request locale and never persisted — {@code content}
     * stays NULL, keeping the FTS index free of placeholder text and letting the
     * wording follow the app language.
     */
    private String oversizePlaceholder() {
        String text = messageSource.getMessage("mail.message.bodyTooLarge", new Object[0],
                LocaleContextHolder.getLocale());
        return HtmlSanitizer.escapePlainText(text);
    }
}
