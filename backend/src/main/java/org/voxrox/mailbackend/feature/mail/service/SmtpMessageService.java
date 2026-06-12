package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.*;
import jakarta.mail.internet.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.core.metrics.MailMetrics;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionDetails;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountConnectionDetailsService;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.DraftRequest;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.feature.mail.dto.SendNotification;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

import module java.base;

@Service
public class SmtpMessageService {

    private static final Logger log = LoggerFactory.getLogger(SmtpMessageService.class);

    /*
     * last_error is a single account-scoped slot shared with the sync pipeline. A
     * successful send may only clear errors this pipeline could have written —
     * never a standing sync failure (the same masking class as the per-folder clear
     * in MailSyncService: the user would lose the diagnostic for a persistently
     * failing INBOX just because one e-mail went out).
     */
    private static final List<String> SEND_PIPELINE_ERROR_CODES = List.of(AccountLastErrorCode.SMTP_SEND_FAILED.name(),
            AccountLastErrorCode.DRAFT_SAVE_FAILED.name(), AccountLastErrorCode.DRAFT_SEND_FAILED.name(),
            AccountLastErrorCode.DRAFT_NOT_FOUND_ON_SERVER.name());

    private final AccountService accountService;
    private final AccountConnectionDetailsService connectionDetailsService;
    private final AccountRepository accountRepository;
    private final MessageService messageService;
    private final ImapActionService imapActionService;
    private final ImapAppendService appendService;
    private final MailMetrics metrics;
    private final MimeMessageBuilder mimeMessageBuilder;
    private final SmtpTransportFactory transportFactory;
    private final SseNotificationService sseNotificationService;

    public SmtpMessageService(AccountService accountService, AccountConnectionDetailsService connectionDetailsService,
            AccountRepository accountRepository, MessageService messageService, ImapActionService imapActionService,
            ImapAppendService appendService, MailMetrics metrics, MimeMessageBuilder mimeMessageBuilder,
            SmtpTransportFactory transportFactory, SseNotificationService sseNotificationService) {
        this.accountService = accountService;
        this.connectionDetailsService = connectionDetailsService;
        this.accountRepository = accountRepository;
        this.messageService = messageService;
        this.imapActionService = imapActionService;
        this.appendService = appendService;
        this.metrics = metrics;
        this.mimeMessageBuilder = mimeMessageBuilder;
        this.transportFactory = transportFactory;
        this.sseNotificationService = sseNotificationService;
    }

    @Async("userMailExecutor")
    public void sendEmailAsync(Long accountId, MailRequest request, String sendId) {
        log.info("{} Starting e-mail send from account ID: {} for recipient: {}", LogCategory.SMTP, accountId,
                LogMasker.maskEmail(request.to()));

        Transport transport = null;
        var sample = metrics.startSmtpSend();
        String outcome = MailMetrics.OUTCOME_SUCCESS;

        try {
            AccountConnectionDetails details = connectionDetailsService.getSmtpConnectionDetails(accountId);
            AccountEntity account = accountService.getAccountOrThrow(accountId);

            Session session = transportFactory.createSession(details);
            transport = transportFactory.openTransport(accountId, session, details);

            MimeMessage message = mimeMessageBuilder.build(session, account, request);
            transport.sendMessage(message, message.getAllRecipients());

            log.info("{} E-mail sent successfully from account ID: {}", LogCategory.SMTP, accountId);
            AuditLog.success("mail_send", LogMasker.maskEmail(account.getEmail()),
                    "to=" + LogMasker.maskEmail(request.to()));

            appendService.appendByRole(accountId, FolderRole.SENT, message, true);

            accountRepository.clearLastErrorIfCodeIn(accountId, SEND_PIPELINE_ERROR_CODES);
            sseNotificationService.broadcast(SendNotification.completed(sendId, accountId));

        } catch (Exception e) {
            outcome = MailMetrics.OUTCOME_FAILURE;
            log.error("{} E-mail send failed from account ID {}", LogCategory.SMTP, accountId, e);
            AuditLog.failure("mail_send", "account=" + accountId, e.getClass().getSimpleName());
            recordSendFailure(accountId, sendId, AccountLastErrorCode.SMTP_SEND_FAILED, "Send failed: ", e);
        } finally {
            transportFactory.closeQuietly(transport, accountId);
            metrics.recordSmtpSend(sample, outcome);
        }
    }

    /**
     * Asynchronously saves a draft to the IMAP Drafts folder. The MIME message is
     * assembled with the same pipeline as {@link #sendEmailAsync}, but it is not
     * sent anywhere and the {@code \Draft} flag is set. The client obtains the
     * stableId after the next Drafts folder sync.
     *
     * When {@code replacesStableId} is provided, the old draft is hard-deleted
     * (IMAP expunge + DB row) after a successful append. Order matters: append-new
     * must succeed, otherwise the user would lose content. A failed hard-delete is
     * logged but does not fail the operation — the duplicate is reconciled by the
     * next sync.
     */
    @Async("userMailExecutor")
    public void saveDraftAsync(Long accountId, DraftRequest request, String replacesStableId) {
        log.info("{} Saving draft for account ID: {} (replaces={})", LogCategory.SMTP, accountId, replacesStableId);

        try {
            /*
             * Resolve the old draft before the append so we remember its UID/folder. The
             * actual delete runs only after a successful append.
             */
            String oldFolder = null;
            Long oldUid = null;
            if (replacesStableId != null && !replacesStableId.isBlank()) {
                MessageEntity old = messageService.getByStableId(replacesStableId).orElse(null);
                if (old == null) {
                    log.warn("{} replaces: draft {} not found, continuing without deleting the old one.",
                            LogCategory.SMTP, replacesStableId);
                } else {
                    oldFolder = old.getFolderName();
                    oldUid = old.getUid();
                }
            }

            AccountEntity account = accountService.getAccountOrThrow(accountId);
            Session session = Session.getInstance(new Properties());
            MimeMessage message = mimeMessageBuilder.build(session, account, request.toMailRequest());
            message.setFlag(Flags.Flag.DRAFT, true);
            message.setSentDate(Date.from(Instant.now()));

            boolean appended = appendService.appendByRole(accountId, FolderRole.DRAFTS, message, false);
            if (!appended) {
                /*
                 * The new revision could not be stored. Keep the previous draft intact and
                 * surface the failure — deleting the old copy now would destroy the only
                 * remaining version of the user's content.
                 */
                log.error("{} Draft save for account {} could not append the new revision; keeping the previous draft.",
                        LogCategory.SMTP, accountId);
                AuditLog.failure("draft_save", "account=" + accountId, "append_failed");
                accountRepository.updateLastError(accountId,
                        AccountLastError.of(AccountLastErrorCode.DRAFT_SAVE_FAILED,
                                java.util.Map.of("detail", "append to Drafts folder failed"),
                                "Draft save failed: append to Drafts folder failed"),
                        LocalDateTime.now());
                return;
            }

            if (oldFolder != null && oldUid != null) {
                try {
                    imapActionService.hardDelete(accountId, oldFolder, oldUid);
                    messageService.deleteByStableId(replacesStableId);
                } catch (Exception cleanupEx) {
                    log.warn("{} Failed to delete previous draft revision {} (UID {} in {}): {}", LogCategory.SMTP,
                            replacesStableId, oldUid, oldFolder, cleanupEx.getMessage());
                }
            }

            accountRepository.clearLastErrorIfCodeIn(accountId, SEND_PIPELINE_ERROR_CODES);
        } catch (Exception e) {
            log.error("{} Draft save failed for account ID {}", LogCategory.SMTP, accountId, e);
            AuditLog.failure("draft_save", "account=" + accountId, e.getClass().getSimpleName());
            accountRepository.updateLastError(accountId,
                    AccountLastError.of(AccountLastErrorCode.DRAFT_SAVE_FAILED,
                            java.util.Map.of("detail", safeDetail(e)), "Draft save failed: " + safeDetail(e)),
                    LocalDateTime.now());
        }
    }

    /**
     * Sends an existing draft over SMTP. Pipeline:
     * <ol>
     * <li>Fetch raw MIME from the IMAP Drafts folder (the previous DB state gives
     * us the UID).</li>
     * <li>Detach into an independent {@link MimeMessage} via write-to-bytes +
     * re-parse — this disconnects the message from the IMAP store while preserving
     * all headers including Message-ID, In-Reply-To, References (to keep threading)
     * and the original MIME structure (multipart, encoding, attachments).</li>
     * <li>Clear the {@code \Draft} flag, set the current Date.</li>
     * <li>SMTP send.</li>
     * <li>Append into the Sent folder (best-effort).</li>
     * <li>Hard delete the original draft from Drafts (best-effort).</li>
     * </ol>
     *
     * MIME is deliberately NOT rebuilt from {@link MessageEntity} fields — doing so
     * would drop Message-ID, threading headers and binary fidelity of attachments.
     */
    @Async("userMailExecutor")
    public void sendDraftAsync(Long accountId, String stableId, String sendId) {
        log.info("{} Sending draft {} for account ID: {}", LogCategory.SMTP, stableId, accountId);

        MessageEntity draft;
        try {
            /*
             * Re-load in async context — the controller already validated ownership, but
             * the draft may have been deleted in the meantime (race with another client).
             */
            draft = messageService.getByStableId(stableId).orElse(null);
        } catch (Exception e) {
            log.error("{} Failed to load draft {} for account ID {}", LogCategory.SMTP, stableId, accountId, e);
            AuditLog.failure("mail_send", "account=" + accountId,
                    "draft=" + stableId + " " + e.getClass().getSimpleName());
            recordSendFailure(accountId, sendId, AccountLastErrorCode.DRAFT_SEND_FAILED, "Draft send failed: ", e);
            return;
        }
        if (draft == null || !draft.getAccount().getId().equals(accountId)) {
            log.warn("{} Draft {} no longer exists or does not belong to account {}", LogCategory.SMTP, stableId,
                    accountId);
            sseNotificationService.broadcast(
                    SendNotification.failed(sendId, accountId, AccountLastErrorCode.DRAFT_NOT_FOUND_ON_SERVER.name()));
            return;
        }

        String draftFolder = draft.getFolderName();
        long draftUid = draft.getUid();
        String maskedAccountEmail = LogMasker.maskEmail(draft.getAccount().getEmail());

        Transport transport = null;
        var sample = metrics.startSmtpSend();
        String outcome = MailMetrics.OUTCOME_SUCCESS;

        try {
            AccountConnectionDetails details = connectionDetailsService.getSmtpConnectionDetails(accountId);

            Session session = transportFactory.createSession(details);

            Optional<MimeMessage> detachedOpt = appendService.fetchAndDetachMime(accountId, draftFolder, draftUid,
                    session);
            if (detachedOpt.isEmpty()) {
                log.warn("{} Draft UID {} not found in IMAP folder {} (account {})", LogCategory.SMTP, draftUid,
                        draftFolder, accountId);
                outcome = MailMetrics.OUTCOME_FAILURE;
                AuditLog.failure("mail_send", "account=" + accountId,
                        "draft=" + stableId + " reason=draft_not_found_on_server");
                accountRepository.updateLastError(accountId,
                        AccountLastError.of(AccountLastErrorCode.DRAFT_NOT_FOUND_ON_SERVER,
                                "Draft to send was not found on the server."),
                        LocalDateTime.now());
                sseNotificationService.broadcast(SendNotification.failed(sendId, accountId,
                        AccountLastErrorCode.DRAFT_NOT_FOUND_ON_SERVER.name()));
                return;
            }
            MimeMessage detached = detachedOpt.get();

            // Clear \Draft — a sent message is no longer a draft.
            detached.setFlag(Flags.Flag.DRAFT, false);
            detached.setSentDate(Date.from(Instant.now()));
            detached.saveChanges();

            transport = transportFactory.openTransport(accountId, session, details);
            transport.sendMessage(detached, detached.getAllRecipients());
            log.info("{} Draft {} sent successfully from account ID: {}", LogCategory.SMTP, stableId, accountId);
            AuditLog.success("mail_send", maskedAccountEmail, "draft=" + stableId);

            appendService.appendByRole(accountId, FolderRole.SENT, detached, true);

            /*
             * Hard-delete the old draft from Drafts. A failure does not abort the whole
             * operation — the message has already been sent and a duplicate is a smaller
             * evil than a false 500.
             */
            try {
                imapActionService.hardDelete(accountId, draftFolder, draftUid);
                messageService.deleteByStableId(stableId);
            } catch (Exception cleanupEx) {
                log.warn("{} Failed to delete sent draft {} (UID {} in {}): {}", LogCategory.SMTP, stableId, draftUid,
                        draftFolder, cleanupEx.getMessage());
            }

            accountRepository.clearLastErrorIfCodeIn(accountId, SEND_PIPELINE_ERROR_CODES);
            sseNotificationService.broadcast(SendNotification.completed(sendId, accountId));
        } catch (Exception e) {
            outcome = MailMetrics.OUTCOME_FAILURE;
            log.error("{} Failed to send draft {} from account ID {}", LogCategory.SMTP, stableId, accountId, e);
            AuditLog.failure("mail_send", "account=" + accountId,
                    "draft=" + stableId + " " + e.getClass().getSimpleName());
            recordSendFailure(accountId, sendId, AccountLastErrorCode.DRAFT_SEND_FAILED, "Draft send failed: ", e);
        } finally {
            transportFactory.closeQuietly(transport, accountId);
            metrics.recordSmtpSend(sample, outcome);
        }
    }

    /**
     * Shared failure tail of the send pipelines: records last_error with the
     * exception detail and pushes the SSE failure notification for the send the
     * client is watching. Log + audit stay at the call sites — their wording and
     * context differ per pipeline step.
     */
    private void recordSendFailure(Long accountId, String sendId, AccountLastErrorCode code, String messagePrefix,
            Exception e) {
        accountRepository.updateLastError(accountId,
                AccountLastError.of(code, java.util.Map.of("detail", safeDetail(e)), messagePrefix + safeDetail(e)),
                LocalDateTime.now());
        sseNotificationService.broadcast(SendNotification.failed(sendId, accountId, code.name()));
    }

    private static String safeDetail(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "" : e.getMessage();
    }

}
