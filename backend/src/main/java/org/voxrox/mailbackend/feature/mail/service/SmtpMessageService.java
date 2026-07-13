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
import org.voxrox.mailbackend.feature.mail.dto.AttachmentResponse;
import org.voxrox.mailbackend.feature.mail.dto.DraftRequest;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.dto.MailDetailResponse;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.feature.mail.dto.SendNotification;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.mapper.MessageMapper;
import org.voxrox.mailbackend.feature.mail.mapper.MessageStableId;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;
import org.voxrox.mailbackend.util.MimePartExtractor;

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
    private final ImapFolderService imapFolderService;
    private final MailMetrics metrics;
    private final MimeMessageBuilder mimeMessageBuilder;
    private final SmtpTransportFactory transportFactory;
    private final SseNotificationService sseNotificationService;
    private final MessageMapper messageMapper;

    public SmtpMessageService(AccountService accountService, AccountConnectionDetailsService connectionDetailsService,
            AccountRepository accountRepository, MessageService messageService, ImapActionService imapActionService,
            ImapAppendService appendService, ImapFolderService imapFolderService, MailMetrics metrics,
            MimeMessageBuilder mimeMessageBuilder, SmtpTransportFactory transportFactory,
            SseNotificationService sseNotificationService, MessageMapper messageMapper) {
        this.accountService = accountService;
        this.connectionDetailsService = connectionDetailsService;
        this.accountRepository = accountRepository;
        this.messageService = messageService;
        this.imapActionService = imapActionService;
        this.appendService = appendService;
        this.imapFolderService = imapFolderService;
        this.metrics = metrics;
        this.mimeMessageBuilder = mimeMessageBuilder;
        this.transportFactory = transportFactory;
        this.sseNotificationService = sseNotificationService;
        this.messageMapper = messageMapper;
    }

    /**
     * Identity a draft save will persist under, resolved synchronously before the
     * async append is dispatched. {@code stableId} is deterministic —
     * {@link MessageStableId} over the pre-assigned {@code messageId} — so the
     * controller can return it in the 202 while the append is still running, and
     * the row the next sync persists carries the very same id.
     */
    public record DraftIdentity(String messageId, String draftsFolder, String stableId) {
    }

    /**
     * @throws org.voxrox.mailbackend.exception.MailOperationException
     *             when the account has no detectable Drafts folder (surfaces
     *             synchronously instead of a silent async failure).
     */
    public DraftIdentity prepareDraftIdentity(Long accountId) {
        accountService.getAccountOrThrow(accountId);
        String draftsFolder = imapFolderService.findFolderNameByRoleOrThrow(accountId, FolderRole.DRAFTS);
        String messageId = "<" + UUID.randomUUID() + "@voxrox.org>";
        String stableId = MessageStableId.compute(accountId, draftsFolder, messageId, null, null);
        return new DraftIdentity(messageId, draftsFolder, stableId);
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

            MimeMessage message = mimeMessageBuilder.build(session, account, request);
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
                    deleteSupersededDraft(accountId, supersedesDraftId);
                }
                accountRepository.clearLastErrorIfCodeIn(accountId, SEND_PIPELINE_ERROR_CODES);
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
                    ? saveRecoveryDraft(accountId, request)
                    : null;
            recordSendFailure(accountId, sendId, AccountLastErrorCode.SMTP_SEND_FAILED, "Send failed: ", e,
                    recoveryDraftId);
        } finally {
            transportFactory.closeQuietly(transport, accountId);
            metrics.recordSmtpSend(sample, outcome);
        }
    }

    /**
     * Asynchronously saves a draft to the IMAP Drafts folder. The MIME message is
     * assembled with the same pipeline as {@link #sendEmailAsync}, but it is not
     * sent anywhere and the {@code \Draft} flag is set. The message persists under
     * {@code identity} (from {@link #prepareDraftIdentity}): its Message-ID is
     * assigned before the append, so the stableId the controller already returned
     * in the 202 is the one the row gets — immediately when the server supports
     * UIDPLUS (local upsert), otherwise with the next sync.
     *
     * When {@code replacesStableId} is provided, the old draft is hard-deleted
     * (IMAP expunge + DB row) after a successful append. Order matters: append-new
     * must succeed, otherwise the user would lose content. A failed hard-delete is
     * logged but does not fail the operation — the duplicate is reconciled by the
     * next sync.
     */
    @Async("userMailExecutor")
    public void saveDraftAsync(Long accountId, DraftRequest request, String replacesStableId, DraftIdentity identity) {
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
                } else if (!isReplaceableDraft(accountId, old)) {
                    /*
                     * The replaces target is only ever hard-deleted (IMAP expunge) if it is this
                     * account's own message in the Drafts folder. A wrong stableId (client bug)
                     * must never expunge received mail or another account's message — keep it.
                     */
                    log.warn("{} replaces: {} is not a Drafts message of account {}; keeping it.", LogCategory.SMTP,
                            replacesStableId, accountId);
                } else {
                    oldFolder = old.getFolderName();
                    oldUid = old.getUid();
                }
            }

            AccountEntity account = accountService.getAccountOrThrow(accountId);
            if (!appendDraftMessage(account, identity, request)) {
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
     * Shared append tail of the draft-save and recovery-draft pipelines: builds the
     * MIME under the pre-assigned identity, appends it to the Drafts folder and
     * indexes the local row.
     */
    private boolean appendDraftMessage(AccountEntity account, DraftIdentity identity, DraftRequest request)
            throws MessagingException, java.io.UnsupportedEncodingException {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = mimeMessageBuilder.build(session, account, request.toMailRequest());
        message.setFlag(Flags.Flag.DRAFT, true);
        message.setSentDate(Date.from(Instant.now()));
        /*
         * The pre-assigned Message-ID is the identity contract: the stableId the
         * controller already returned derives from it. saveChanges() first — it would
         * otherwise regenerate the header at write-out and orphan that id.
         */
        message.saveChanges();
        message.setHeader("Message-ID", identity.messageId());

        var appendOutcome = appendService.appendDraft(account.getId(), identity.draftsFolder(), message);
        if (appendOutcome.appended()) {
            upsertLocalDraftRow(account, identity, request, message, appendOutcome);
        }
        return appendOutcome.appended();
    }

    /**
     * Failure tail for a brand-new message: parks the composed content in Drafts,
     * best-effort, so the send_failed notification can point the user at a
     * recoverable copy — the composer is typically unmounted by the time the async
     * outcome arrives, and without this the content exists nowhere.
     *
     * @return the recovery draft's stableId, or {@code null} when it could not be
     *         saved (the failure notification then carries no pointer).
     */
    private @Nullable String saveRecoveryDraft(Long accountId, MailRequest request) {
        try {
            DraftIdentity identity = prepareDraftIdentity(accountId);
            AccountEntity account = accountService.getAccountOrThrow(accountId);
            DraftRequest draftRequest = new DraftRequest(request.to(), request.cc(), request.bcc(), request.subject(),
                    request.body(), request.attachments(), request.inReplyTo(), request.references());
            if (appendDraftMessage(account, identity, draftRequest)) {
                log.info("{} Failed send parked as recovery draft {} for account {}.", LogCategory.SMTP,
                        identity.stableId(), accountId);
                return identity.stableId();
            }
            return null;
        } catch (Exception e) {
            log.warn("{} Could not park the failed send as a draft for account {}: {}", LogCategory.SMTP, accountId,
                    e.getMessage());
            return null;
        }
    }

    /**
     * Post-delivery removal of the draft the sent message was edited from. Same
     * ownership/folder guard as the {@code replaces} flow — a wrong id must never
     * expunge received mail. Best-effort: the message is already delivered, so a
     * failure here only leaves a stale draft for the user or the next sync to
     * reconcile.
     */
    private void deleteSupersededDraft(Long accountId, String stableId) {
        try {
            MessageEntity draft = messageService.getByStableId(stableId).orElse(null);
            if (draft == null) {
                log.warn("{} supersedes: draft {} not found; nothing to delete.", LogCategory.SMTP, stableId);
                return;
            }
            if (!isReplaceableDraft(accountId, draft)) {
                log.warn("{} supersedes: {} is not a Drafts message of account {}; keeping it.", LogCategory.SMTP,
                        stableId, accountId);
                return;
            }
            imapActionService.hardDelete(accountId, draft.getFolderName(), draft.getUid());
            messageService.deleteByStableId(stableId);
        } catch (Exception e) {
            log.warn("{} Failed to delete superseded draft {} after a successful send: {}", LogCategory.SMTP, stableId,
                    e.getMessage());
        }
    }

    /**
     * Makes the just-appended draft addressable without waiting for a sync: the row
     * is inserted under the same deterministic stableId the controller already
     * returned ({@link MessageMapper#toEntity} re-derives it from the pre-assigned
     * Message-ID). Best-effort on two axes: without UIDPLUS there is no UID to
     * store (the schema requires one), and any persistence error only defers the
     * row to the next sync — the append itself already succeeded, so nothing here
     * may fail the save.
     */
    private void upsertLocalDraftRow(AccountEntity account, DraftIdentity identity, DraftRequest request,
            MimeMessage message, ImapAppendService.DraftAppendOutcome outcome) {
        if (outcome.uid() == null || outcome.uidValidity() == null) {
            log.debug("{} Draft {} appended without APPENDUID; the local row appears with the next sync.",
                    LogCategory.SMTP, identity.stableId());
            return;
        }
        try {
            List<AttachmentResponse> attachments;
            try {
                // Same extractor the sync fetch path uses, so partPaths match what a
                // later reconcile would compute.
                attachments = MimePartExtractor.extractAttachmentMetadata(message, "");
            } catch (Exception e) {
                attachments = List.of();
            }
            Address[] from = message.getFrom();
            String sender = (from != null && from.length > 0)
                    ? MessageFetcher.formatAddress(from[0])
                    : account.getEmail();
            MailDetailResponse dto = new MailDetailResponse(null, outcome.uid(), request.subject(), sender,
                    request.to(), request.cc(), request.bcc(), null, LocalDateTime.now(), false, false, false,
                    identity.messageId(), request.inReplyTo(), request.references(), !attachments.isEmpty(),
                    attachments, null, null);
            MessageEntity entity = messageMapper.toEntity(dto, account, identity.draftsFolder(), outcome.uidValidity());
            if (!identity.stableId().equals(entity.getStableId())) {
                // Never expected — both sides derive from the same Message-ID. Guards
                // against a silent contract drift between the two derivations.
                log.warn("{} Draft identity mismatch for {} (mapper derived {}); leaving the row to the next sync.",
                        LogCategory.SMTP, identity.stableId(), entity.getStableId());
                return;
            }
            messageService.insertIfAbsent(entity);
        } catch (Exception e) {
            log.warn("{} Failed to upsert the local row for draft {} — it appears with the next sync instead: {}",
                    LogCategory.SMTP, identity.stableId(), e.getMessage());
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
                accountRepository.clearLastErrorIfCodeIn(accountId, SEND_PIPELINE_ERROR_CODES);
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

    /**
     * A {@code replaces} / draft target may only be hard-deleted if it is this
     * account's own message and actually lives in the Drafts folder. Fails closed:
     * if the Drafts folder cannot be resolved we cannot prove the target is a
     * draft, so we refuse the delete rather than risk expunging received mail.
     */
    private boolean isReplaceableDraft(Long accountId, MessageEntity candidate) {
        if (candidate.getAccount() == null || !accountId.equals(candidate.getAccount().getId())) {
            return false;
        }
        try {
            String draftsFolder = imapFolderService.findFolderNameByRoleOrThrow(accountId, FolderRole.DRAFTS);
            return draftsFolder.equals(candidate.getFolderName());
        } catch (Exception e) {
            log.warn("{} Could not resolve the Drafts folder for account {} while validating a replace target: {}",
                    LogCategory.SMTP, accountId, e.getMessage());
            return false;
        }
    }

    private static String safeDetail(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "" : e.getMessage();
    }

}
