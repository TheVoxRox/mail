package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.*;
import jakarta.mail.internet.*;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.AttachmentResponse;
import org.voxrox.mailbackend.feature.mail.dto.DraftRequest;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.dto.MailDetailResponse;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.mapper.MessageMapper;
import org.voxrox.mailbackend.feature.mail.mapper.MessageStableId;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;
import org.voxrox.mailbackend.util.MimePartExtractor;

import module java.base;

/**
 * Draft-message lifecycle on the IMAP Drafts folder plus the local index: mint
 * a deterministic identity, append a saved draft (autosave / explicit save),
 * upsert its local row, and remove drafts on supersede or park failed sends as
 * recovery drafts. Split out of {@link SmtpMessageService} (which owns SMTP
 * delivery); the send path calls {@link #deleteSupersededDraft} and
 * {@link #saveRecoveryDraft} on this service — a one-way dependency (this class
 * never sends).
 *
 * <p>
 * Not to be confused with {@link MailDraftService}, which composes
 * reply/forward draft <em>bodies</em>; this class handles draft
 * <em>persistence</em>.
 */
@Service
public class DraftPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(DraftPersistenceService.class);

    /*
     * Mirrors SmtpMessageService.SEND_PIPELINE_ERROR_CODES: the mail-write-pipeline
     * last_error codes a successful write (send or draft save) may clear, without
     * touching a standing sync failure.
     */
    private static final List<String> SEND_PIPELINE_ERROR_CODES = List.of(AccountLastErrorCode.SMTP_SEND_FAILED.name(),
            AccountLastErrorCode.DRAFT_SAVE_FAILED.name(), AccountLastErrorCode.DRAFT_SEND_FAILED.name(),
            AccountLastErrorCode.DRAFT_NOT_FOUND_ON_SERVER.name());

    private final AccountService accountService;
    private final ImapFolderService imapFolderService;
    private final MessageService messageService;
    private final ImapActionService imapActionService;
    private final ImapAppendService appendService;
    private final MimeMessageBuilder mimeMessageBuilder;
    private final MessageMapper messageMapper;
    private final AccountRepository accountRepository;

    public DraftPersistenceService(AccountService accountService, ImapFolderService imapFolderService,
            MessageService messageService, ImapActionService imapActionService, ImapAppendService appendService,
            MimeMessageBuilder mimeMessageBuilder, MessageMapper messageMapper, AccountRepository accountRepository) {
        this.accountService = accountService;
        this.imapFolderService = imapFolderService;
        this.messageService = messageService;
        this.imapActionService = imapActionService;
        this.appendService = appendService;
        this.mimeMessageBuilder = mimeMessageBuilder;
        this.messageMapper = messageMapper;
        this.accountRepository = accountRepository;
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
     * Asynchronously saves a draft to the IMAP Drafts folder. The MIME message is
     * assembled with the same pipeline as
     * {@code SmtpMessageService#sendEmailAsync}, but it is not sent anywhere and
     * the {@code \Draft} flag is set. The message persists under {@code identity}
     * (from {@link #prepareDraftIdentity}): its Message-ID is assigned before the
     * append, so the stableId the controller already returned in the 202 is the one
     * the row gets — immediately when the server supports UIDPLUS (local upsert),
     * otherwise with the next sync.
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
     *
     * <p>
     * Addresses are parsed under {@link MimeMessageBuilder.AddressPolicy#DRAFT}:
     * neither pipeline may fail over a recipient the user has not finished typing —
     * autosave fires mid-token, and the recovery pipeline exists precisely to
     * salvage content whose send already failed.
     */
    private boolean appendDraftMessage(AccountEntity account, DraftIdentity identity, DraftRequest request)
            throws MessagingException, java.io.UnsupportedEncodingException {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = mimeMessageBuilder.build(session, account, request.toMailRequest(),
                MimeMessageBuilder.AddressPolicy.DRAFT);
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
    public @Nullable String saveRecoveryDraft(Long accountId, MailRequest request) {
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

    /*
     * The draft-save that minted this stableId runs on its own async task. When the
     * user hits Send right after an autosave, the send can reach the post-delivery
     * supersede before that task has appended the draft and upserted its local row
     * — so a single getByStableId would miss it and the draft would survive the
     * send. Poll briefly to let the row appear (mirrors the client-side discard
     * retry, ComposeSession#discard). Cheap on the virtual-thread executor and it
     * only runs post-delivery, after the client already got send_completed.
     */
    private static final int SUPERSEDE_DRAFT_LOOKUP_ATTEMPTS = 3;
    private static final Duration SUPERSEDE_DRAFT_LOOKUP_DELAY = Duration.ofMillis(400);

    /**
     * Post-delivery removal of the draft the sent message was edited from. Same
     * ownership/folder guard as the {@code replaces} flow — a wrong id must never
     * expunge received mail. Best-effort: the message is already delivered, so a
     * failure here only leaves a stale draft for the user or the next sync to
     * reconcile.
     */
    public void deleteSupersededDraft(Long accountId, String stableId) {
        try {
            MessageEntity draft = awaitSupersededDraft(stableId);
            if (draft == null) {
                log.warn("{} supersedes: draft {} not found (after {} attempts); nothing to delete.", LogCategory.SMTP,
                        stableId, SUPERSEDE_DRAFT_LOOKUP_ATTEMPTS);
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
     * Resolves the draft row for the supersede, retrying briefly to close the race
     * with the still-running draft-save append/upsert. Returns {@code null} only
     * when the row is still absent after every attempt (or the wait was
     * interrupted).
     */
    private @Nullable MessageEntity awaitSupersededDraft(String stableId) {
        for (int attempt = 0; attempt < SUPERSEDE_DRAFT_LOOKUP_ATTEMPTS; attempt++) {
            MessageEntity draft = messageService.getByStableId(stableId).orElse(null);
            if (draft != null) {
                return draft;
            }
            if (attempt < SUPERSEDE_DRAFT_LOOKUP_ATTEMPTS - 1) {
                try {
                    Thread.sleep(SUPERSEDE_DRAFT_LOOKUP_DELAY.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
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
            MailDetailResponse dto = new MailDetailResponse(null, outcome.uid(), identity.draftsFolder(),
                    request.subject(), sender, request.to(), request.cc(), request.bcc(), null, LocalDateTime.now(),
                    false, false, false, identity.messageId(), request.inReplyTo(), request.references(),
                    !attachments.isEmpty(), attachments, null, null);
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
