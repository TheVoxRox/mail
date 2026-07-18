package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.*;
import jakarta.mail.internet.*;

import org.jspecify.annotations.Nullable;
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
    private final DraftPersistenceService draftPersistenceService;

    public SmtpMessageService(AccountService accountService, AccountConnectionDetailsService connectionDetailsService,
            AccountRepository accountRepository, MessageService messageService, ImapActionService imapActionService,
            ImapAppendService appendService, MailMetrics metrics, MimeMessageBuilder mimeMessageBuilder,
            SmtpTransportFactory transportFactory, SseNotificationService sseNotificationService,
            DraftPersistenceService draftPersistenceService) {
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
        this.draftPersistenceService = draftPersistenceService;
    }

    /**
     * @param supersedesDraftId
     *            stableId of the draft this message was edited from, or
     *            {@code null} for a brand-new message. The draft is hard-deleted
     *            only AFTER successful delivery (the client must not delete it on
     *            the 202 — a failed async send would otherwise leave no copy of the
     *            content); on failure of a brand-new message the content is parked
     *            as a recovery draft instead.
     */
    @Async("userMailExecutor")
    public void sendEmailAsync(Long accountId, MailRequest request, String sendId, @Nullable String supersedesDraftId) {
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

            MimeMessage message = mimeMessageBuilder.build(session, account, request,
                    MimeMessageBuilder.AddressPolicy.STRICT);
            transport.sendMessage(message, message.getAllRecipients());

            // Delivered. From here the send has happened, so nothing below may report a
            // send failure — that would make the user re-send and double-deliver. Resolve
            // the client's pending state on the fact of delivery, then do best-effort
            // bookkeeping whose failure is logged, not surfaced as a failed send.
            sseNotificationService.broadcast(SendNotification.completed(sendId, accountId));
            try {
                log.info("{} E-mail sent successfully from account ID: {}", LogCategory.SMTP, accountId);
                AuditLog.success("mail_send", LogMasker.maskEmail(account.getEmail()),
                        "to=" + LogMasker.maskEmail(request.to()));
                appendService.appendByRole(accountId, FolderRole.SENT, message, true);
                if (supersedesDraftId != null && !supersedesDraftId.isBlank()) {
                    draftPersistenceService.deleteSupersededDraft(accountId, supersedesDraftId);
                }
                accountRepository.clearLastErrorIfCodeIn(accountId, AccountLastErrorCode.SEND_PIPELINE_CODES);
            } catch (Exception bookkeepingEx) {
                log.warn("{} Post-send bookkeeping failed for account {} after a successful send: {}", LogCategory.SMTP,
                        accountId, bookkeepingEx.getMessage());
            }

        } catch (Exception e) {
            outcome = MailMetrics.OUTCOME_FAILURE;
            log.error("{} E-mail send failed from account ID {}", LogCategory.SMTP, accountId, e);
            AuditLog.failure("mail_send", "account=" + accountId, e.getClass().getSimpleName());
            /*
             * A brand-new message has no other copy anywhere once the composer is gone —
             * park it in Drafts before broadcasting the failure so the notification can
             * point at it. An edited draft still exists (its delete runs only post-delivery
             * above), so there is nothing to park.
             */
            String recoveryDraftId = (supersedesDraftId == null || supersedesDraftId.isBlank())
                    ? draftPersistenceService.saveRecoveryDraft(accountId, request)
                    : null;
            recordSendFailure(accountId, sendId, AccountLastErrorCode.SMTP_SEND_FAILED, "Send failed: ", e,
                    recoveryDraftId);
        } finally {
            transportFactory.closeQuietly(transport, accountId);
            metrics.recordSmtpSend(sample, outcome);
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

        Transport transport = null;
        var sample = metrics.startSmtpSend();
        String outcome = MailMetrics.OUTCOME_SUCCESS;

        try {
            // Read inside the try: getUid() unboxes a Long, so a corrupt draft row with a
            // null UID would NPE. Outside the try that NPE escapes the @Async method with
            // no send_failed broadcast, leaving the client's "sending…" indicator hung.
            String draftFolder = draft.getFolderName();
            long draftUid = draft.getUid();
            String maskedAccountEmail = LogMasker.maskEmail(draft.getAccount().getEmail());

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

            // Delivered. Everything below is post-send bookkeeping whose failure must not
            // be reported as a send failure — worse still here than in sendEmailAsync,
            // since a re-send has no draft to launch from once the delete below runs.
            sseNotificationService.broadcast(SendNotification.completed(sendId, accountId));
            log.info("{} Draft {} sent successfully from account ID: {}", LogCategory.SMTP, stableId, accountId);
            AuditLog.success("mail_send", maskedAccountEmail, "draft=" + stableId);
            try {
                appendService.appendByRole(accountId, FolderRole.SENT, detached, true);
                /*
                 * Hard-delete the old draft from Drafts. The message has already been sent, so
                 * a duplicate is a smaller evil than a false failure.
                 */
                imapActionService.hardDelete(accountId, draftFolder, draftUid);
                messageService.deleteByStableId(stableId);
                accountRepository.clearLastErrorIfCodeIn(accountId, AccountLastErrorCode.SEND_PIPELINE_CODES);
            } catch (Exception bookkeepingEx) {
                log.warn("{} Post-send bookkeeping failed for sent draft {} (UID {} in {}): {}", LogCategory.SMTP,
                        stableId, draftUid, draftFolder, bookkeepingEx.getMessage());
            }
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
        recordSendFailure(accountId, sendId, code, messagePrefix, e, null);
    }

    private void recordSendFailure(Long accountId, String sendId, AccountLastErrorCode code, String messagePrefix,
            Exception e, @Nullable String recoveryDraftStableId) {
        // The client's pending indicator is resolved only by the SSE event, so the
        // broadcast must run even if persisting last_error fails — otherwise a DB error
        // while recording the failure would leave the send "sending…" forever.
        try {
            accountRepository.updateLastError(accountId,
                    AccountLastError.of(code, java.util.Map.of("detail", safeDetail(e)), messagePrefix + safeDetail(e)),
                    LocalDateTime.now());
        } catch (Exception dbEx) {
            log.error("{} Failed to persist last_error for account {} while recording a send failure: {}",
                    LogCategory.SMTP, accountId, dbEx.getMessage());
        }
        sseNotificationService
                .broadcast(SendNotification.failed(sendId, accountId, code.name(), recoveryDraftStableId));
    }

    private static String safeDetail(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "" : e.getMessage();
    }

}
