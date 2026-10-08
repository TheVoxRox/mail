package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.*;
import jakarta.mail.internet.*;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.exception.MailFailureCause;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.AttachmentResponse;
import org.voxrox.mailbackend.feature.mail.dto.DraftRequest;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.feature.mail.entity.DraftRecipientsEntity;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.mapper.MessageMapper;
import org.voxrox.mailbackend.feature.mail.mapper.MessageStableId;
import org.voxrox.mailbackend.feature.mail.repository.DraftRecipientsRepository;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
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

    private final AccountService accountService;
    private final ImapFolderService imapFolderService;
    private final MessageService messageService;
    private final ImapActionService imapActionService;
    private final ImapAppendService appendService;
    private final MimeMessageBuilder mimeMessageBuilder;
    private final MessageMapper messageMapper;
    private final AccountRepository accountRepository;
    private final DraftRecipientsRepository draftRecipientsRepository;

    /**
     * How many drafts an account keeps typed recipients for, each with all its
     * entries, newest draft first (see {@link #keepTypedRecipients}). A draft is a
     * chain of revisions saved here, so this counts the drafts no send or delete of
     * this client has retired, and none is retired before this bound: a draft still
     * open, one deleted elsewhere, one the user discarded mid-compose (a discard
     * forgets no entry), and one sent, whose entries are set aside rather than
     * dropped since a server can claim the delete after the send and keep the
     * draft. Only the user starts a draft, so a thousand is a thousand messages
     * composed since, and the bound is what stops those from piling up (B1-5,
     * reopened at 1.31, 1.46, 1.48, 1.53 and 1.57).
     */
    static final int KEPT_DRAFTS = 1_000;

    /**
     * How many of a draft's followed revisions — replaced by a later save of the
     * same draft and not current — keep their entries, newest first. A replaced
     * revision keeps its entry whatever the server answers to its delete, since a
     * server can claim the delete and keep the revision (B1-5, reopened at 1.57),
     * so a draft adds one per autosave and they need a bound of their own; only the
     * draft's own saves can reach it. The newest revision a server can present
     * without its entry is a hundred autosaves behind the one the user last saw, a
     * draft that visibly is not the one they were writing.
     */
    static final int KEPT_REVISIONS_PER_DRAFT = 100;

    public DraftPersistenceService(AccountService accountService, ImapFolderService imapFolderService,
            MessageService messageService, ImapActionService imapActionService, ImapAppendService appendService,
            MimeMessageBuilder mimeMessageBuilder, MessageMapper messageMapper, AccountRepository accountRepository,
            DraftRecipientsRepository draftRecipientsRepository) {
        this.accountService = accountService;
        this.imapFolderService = imapFolderService;
        this.messageService = messageService;
        this.imapActionService = imapActionService;
        this.appendService = appendService;
        this.mimeMessageBuilder = mimeMessageBuilder;
        this.messageMapper = messageMapper;
        this.accountRepository = accountRepository;
        this.draftRecipientsRepository = draftRecipientsRepository;
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
     * Reserves the identity a draft will be stored under: its Message-ID, the
     * resolved Drafts folder and the stableId derived from both, all settled before
     * any IMAP write happens.
     *
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
     * Accepts a draft save: reserves its identity ({@link #prepareDraftIdentity})
     * and keeps what the user typed, with the draft's chain, before the controller
     * answers 202 and dispatches {@link #saveDraftAsync}. Synchronous on purpose
     * (B1-5, reopened at 1.41). The client names the stableId of this 202 in its
     * next save, and that save looks its chain up by it, so the entry must exist by
     * then; written in the async task, it raced the next request, and a server
     * holding the APPEND lane could split the chain. And nothing is appended
     * without it: an entry that cannot be written fails the save here, visibly,
     * rather than leaving a revision checked against the server's own copy.
     *
     * @throws RuntimeException
     *             when the chain cannot be looked up or the entry cannot be
     *             written; nothing has been dispatched then.
     */
    public DraftIdentity acceptDraftSave(Long accountId, DraftRequest request, @Nullable String replacesStableId) {
        DraftIdentity identity = prepareDraftIdentity(accountId);
        keepTypedRecipients(accountId, identity, request, chainOf(accountId, replacesStableId, identity.stableId()));
        return identity;
    }

    /**
     * Asynchronously saves a draft to the IMAP Drafts folder. The MIME message is
     * assembled with the same pipeline as
     * {@code SmtpMessageService#sendEmailAsync}, but it is not sent anywhere and
     * the {@code \Draft} flag is set. The message persists under {@code identity}
     * (from {@link #acceptDraftSave}, which has kept what the user typed by then):
     * its Message-ID is assigned before the append, so the stableId the controller
     * already returned in the 202 is the one the row gets — immediately when the
     * server supports UIDPLUS (local upsert), otherwise with the next sync.
     *
     * The entry kept at acceptance becomes current only once the APPEND has stored
     * the revision ({@link #appendDraftMessage}). A task that stores nothing,
     * whichever way it leaves, or that waits or never runs, leaves it accepted,
     * with nothing to set aside: it holds its draft's newest place until the
     * draft's next save, and current only its own. Until 1.46 the entry was current
     * from acceptance and each way out had to set it aside; a task that was still
     * waiting had not left, so a server holding the APPEND lane kept every later
     * autosave current (B1-5, reopened at 1.43 and 1.46).
     *
     * After a successful append the draft's earlier revisions are hard-deleted
     * (IMAP expunge + DB row): the one {@code replacesStableId} names, and every
     * other earlier revision of the draft the server still holds a row for
     * ({@link #replacedRevisions}). Order matters: append-new must succeed,
     * otherwise the user would lose content. A failed hard-delete is logged but
     * does not fail the operation; the next stored save deletes the copy it left.
     * The old revision's typed recipients stay, set aside by the append's
     * {@link #markStored}, whatever the server answered to the delete: a server can
     * claim it and keep the revision, and until 1.58 that one autosave left the
     * revision before the newest with no entry, its untouched send checked against
     * the server's own copy (B1-5, reopened at 1.57).
     */
    @Async("userMailExecutor")
    public void saveDraftAsync(Long accountId, DraftRequest request, String replacesStableId, DraftIdentity identity) {
        log.info("{} Saving draft for account ID: {} (replaces={})", LogCategory.SMTP, accountId, replacesStableId);

        try {
            /*
             * Resolve the old revisions before the append so we remember their UIDs and
             * folders. The actual delete runs only after a successful append.
             */
            List<MessageEntity> replaced = replacedRevisions(accountId, replacesStableId, identity.stableId());

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
                                java.util.Map.of(AccountLastErrorCode.CAUSE, MailFailureCause.SERVER_REJECTED.name()),
                                "Draft save failed: append to Drafts folder failed"),
                        LocalDateTime.now());
                return;
            }

            for (MessageEntity old : replaced) {
                try {
                    imapActionService.hardDelete(accountId, old.getFolderName(), old.getUid());
                    messageService.deleteByStableId(old.getStableId());
                } catch (Exception cleanupEx) {
                    log.warn("{} Failed to delete previous draft revision {} (UID {} in {}): {}", LogCategory.SMTP,
                            old.getStableId(), old.getUid(), old.getFolderName(), cleanupEx.getMessage());
                }
            }

            accountRepository.clearLastErrorIfCodeIn(accountId, AccountLastErrorCode.SEND_PIPELINE_CODES);
        } catch (Exception e) {
            log.error("{} Draft save failed for account ID {}", LogCategory.SMTP, accountId, e);
            AuditLog.failure("draft_save", "account=" + accountId, e.getClass().getSimpleName());
            MailFailureCause failure = MailFailureCause.classify(e);
            accountRepository.updateLastError(accountId, AccountLastError.of(AccountLastErrorCode.DRAFT_SAVE_FAILED,
                    java.util.Map.of(AccountLastErrorCode.CAUSE, failure.name()), "Draft save failed: " + failure),
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
     *
     * <p>
     * The body is stored under {@link MimeMessageBuilder.BodyFormat#PLAIN} so the
     * draft carries the typed Markdown source, not its rendering — see that
     * constant for why the round-trip through Drafts would otherwise lose it.
     *
     * <p>
     * The typed recipients are kept before this runs, by {@link #acceptDraftSave}
     * or {@link #saveRecoveryDraft}. A stored revision's entry becomes the draft's
     * current one here, retiring its earlier revisions; one this did not store
     * stays accepted, whichever way this leaves.
     */
    private boolean appendDraftMessage(AccountEntity account, DraftIdentity identity, DraftRequest request)
            throws MessagingException, java.io.UnsupportedEncodingException {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = mimeMessageBuilder.build(session, account, request.toMailRequest(),
                MimeMessageBuilder.AddressPolicy.DRAFT, MimeMessageBuilder.BodyFormat.PLAIN);
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
        if (!appendOutcome.appended()) {
            return false;
        }
        markStored(account.getId(), identity);
        upsertLocalDraftRow(account, identity, request, message, appendOutcome);
        return true;
    }

    /**
     * Failure tail for a brand-new message: parks the composed content in Drafts,
     * best-effort, so the send_failed notification can point the user at a
     * recoverable copy — the composer is typically unmounted by the time the async
     * outcome arrives, and without this the content exists nowhere.
     *
     * <p>
     * Unlike a save, the recovery draft is appended even when its typed recipients
     * cannot be kept: it is the only copy of what the user wrote, and losing it is
     * worse than its untouched send being checked against the server's copy.
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
            try {
                keepTypedRecipients(accountId, identity, draftRequest, identity.stableId());
            } catch (Exception e) {
                log.warn("{} Could not keep the recipients of recovery draft {}; sending it untouched checks the row "
                        + "instead: {}", LogCategory.SMTP, identity.stableId(), e.getMessage());
            }
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
     * reconcile. The draft's earlier revisions the server still holds a row for go
     * as well, for the reason a stored save deletes them
     * ({@link #replacedRevisions}): after a rejected autosave the send names a
     * revision the server never stored, and the last stored one stayed in Drafts
     * after the message went out.
     */
    public void deleteSupersededDraft(Long accountId, String stableId) {
        try {
            MessageEntity draft = awaitSupersededDraft(stableId);
            if (draft == null) {
                log.warn("{} supersedes: draft {} not found (after {} attempts).", LogCategory.SMTP, stableId,
                        SUPERSEDE_DRAFT_LOOKUP_ATTEMPTS);
            } else if (!isReplaceableDraft(accountId, draft)) {
                log.warn("{} supersedes: {} is not a Drafts message of account {}; keeping it.", LogCategory.SMTP,
                        stableId, accountId);
            } else {
                deleteSentRevision(accountId, draft);
            }
            for (MessageEntity earlier : earlierRevisionRows(accountId, stableId)) {
                deleteSentRevision(accountId, earlier);
            }
        } catch (Exception e) {
            log.warn("{} Failed to delete superseded draft {} after a successful send: {}", LogCategory.SMTP, stableId,
                    e.getMessage());
        }
    }

    private void deleteSentRevision(Long accountId, MessageEntity draft) {
        imapActionService.hardDelete(accountId, draft.getFolderName(), draft.getUid());
        messageService.deleteByStableId(draft.getStableId());
        setAsideTypedRecipients(accountId, draft.getMessageId());
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
                    Thread.sleep(SUPERSEDE_DRAFT_LOOKUP_DELAY);
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
        Long uid = outcome.uid();
        Long uidValidity = outcome.uidValidity();
        if (uid == null || uidValidity == null) {
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
            FetchedMessage appended = new FetchedMessage(uid, request.subject(), sender, request.to(), request.cc(),
                    request.bcc(), LocalDateTime.now(), false, false, false, identity.messageId(), request.inReplyTo(),
                    request.references(), attachments);
            MessageEntity entity = messageMapper.toEntity(appended, account, identity.draftsFolder(), uidValidity);
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
     * Keeps what the user addressed the draft to, for {@code SmtpMessageService} to
     * check the server's copy against when the draft is sent untouched. The draft's
     * row cannot serve: the sync writes it from the server's copy whenever it
     * creates it — without APPENDUID, after a UIDVALIDITY change, or when the
     * server expunges the draft and presents it again under a new UID — and the
     * check would then compare the server against itself (B1-5).
     *
     * <p>
     * Written before the save is accepted ({@link #acceptDraftSave}), so before the
     * append — once the server holds the draft it can announce it and a send can
     * follow at once — and before the client can name the revision in its next
     * save. The entry is keyed by the Message-ID this save minted, which no folder
     * the server moves the draft to changes, and carries the stableId the next save
     * will name as the revision it replaces and the chain of the draft it belongs
     * to. Written by a plain INSERT, which waits for SQLite's write lock where the
     * merge {@code save} did failed at once
     * ({@link DraftRecipientsRepository#insertEntry}). The entry is accepted, not
     * current: it becomes current only once the server stores the revision
     * ({@link #markStored}). A save that starts a draft — its chain is its own
     * stableId — drops the account's drafts beyond its newest {@link #KEPT_DRAFTS}
     * less one, the place the new draft takes, each with every entry it has
     * ({@link DraftRecipientsRepository#deleteDraftsButNewest}); only such a save
     * adds a draft, so the others skip that statement, which reads every entry of
     * the account (some 80 ms over a hundred thousand, against some 12 for a save
     * of a draft already kept, measured). Every save drops its draft's followed
     * revisions beyond its newest {@link #KEPT_REVISIONS_PER_DRAFT} less one, the
     * place the revision it follows takes
     * ({@link DraftRecipientsRepository#deleteRevisionsButNewest}). Whatever the
     * server does with this save or any other, no other draft's saves push out a
     * revision of this one.
     *
     * @throws RuntimeException
     *             when the entry cannot be written; the caller decides whether the
     *             draft may be appended without it.
     */
    private void keepTypedRecipients(Long accountId, DraftIdentity identity, DraftRequest request, String chainId) {
        if (chainId.equals(identity.stableId())) {
            draftRecipientsRepository.deleteDraftsButNewest(accountId, chainId, KEPT_DRAFTS - 1);
        }
        draftRecipientsRepository.deleteRevisionsButNewest(accountId, chainId, KEPT_REVISIONS_PER_DRAFT - 1);
        draftRecipientsRepository.insertEntry(accountId, identity.messageId(), identity.stableId(), chainId,
                request.to(), request.cc(), request.bcc(), LocalDateTime.now());
    }

    /**
     * What the user addressed a draft saved here to, as
     * {@link #keepTypedRecipients} kept it under the draft's Message-ID; empty for
     * a draft composed in another client, one the server presents under a
     * Message-ID of its own, or one saved before the entry could be written.
     */
    public Optional<DraftRecipientsEntity> typedRecipients(Long accountId, @Nullable String messageId) {
        return messageId == null
                ? Optional.empty()
                : draftRecipientsRepository.findById(new DraftRecipientsEntity.Key(accountId, messageId));
    }

    /**
     * The draft a save's typed recipients belong to: the chain of the revision the
     * client names as the one it replaces, or a new chain, named by this save's own
     * stableId, when it names none or one with no entry here — the first save of a
     * compose, or the first save here of a draft composed elsewhere. Every stableId
     * a save of this client returned has its entry by then, since
     * {@link #acceptDraftSave} writes it before the 202. A failed lookup fails the
     * save rather than starting a chain the draft's earlier revisions are not in.
     */
    private String chainOf(Long accountId, @Nullable String replacesStableId, String stableId) {
        if (replacesStableId == null || replacesStableId.isBlank()) {
            return stableId;
        }
        return draftRecipientsRepository.findChainId(accountId, replacesStableId).orElse(stableId);
    }

    /**
     * Makes a stored revision's typed recipients the draft's current entry, and
     * sets aside those of every earlier revision of the draft: the revision the
     * save named and any it did not, stored or still waiting. The client names the
     * stableId the previous save returned, and the 202 returns it before the
     * APPEND's outcome, so after a rejected save the next one names the rejected
     * revision, and the last stored revision is named by no save. Retiring only the
     * named one left that revision current each time, so a server rejecting every
     * other APPEND grew the current entries by one per two autosaves (B1-5, found
     * by the verification pass over 1.39). Decided by the chain this client keeps,
     * not by the server's answers. The entries set aside stay, for revisions the
     * server keeps and the user may still send, until
     * {@link #KEPT_REVISIONS_PER_DRAFT} newer revisions of the same draft push them
     * out, or the draft goes whole. The chain is the one the stored revision's own
     * entry carries; a recovery draft whose entry could not be written has none and
     * changes nothing. Best-effort: the draft is stored by now, and an entry left
     * accepted stays with its draft like any other (B1-5, reopened at 1.48).
     */
    private void markStored(Long accountId, DraftIdentity identity) {
        try {
            draftRecipientsRepository.findChainId(accountId, identity.stableId())
                    .ifPresent(chainId -> draftRecipientsRepository.markStored(accountId, chainId, identity.messageId(),
                            LocalDateTime.now()));
        } catch (Exception e) {
            log.debug("{} Could not make draft {} of account {} the current revision: {}", LogCategory.SMTP,
                    identity.stableId(), accountId, e.getMessage());
        }
    }

    /**
     * Sets aside the typed recipients of a draft this client has just sent: the
     * revision is no longer its draft's current one, but its entry stays. The send
     * then deletes the draft, and a server can claim that delete and keep it; were
     * the entry dropped, as it was until 1.58, the server could present the sent
     * revision again with a Bcc added and its untouched send would be checked
     * against the server's own copy (B1-5, reopened at 1.57). The entry goes with
     * its draft, once the account has {@link #KEPT_DRAFTS} newer drafts, or as a
     * followed revision when the user saves the draft again. A plain UPDATE that
     * waits for SQLite's write lock ({@link DraftRecipientsRepository#setAside}).
     * Best-effort: the message is delivered by now, and an entry left current still
     * keeps what the user typed.
     */
    public void setAsideTypedRecipients(Long accountId, @Nullable String messageId) {
        if (messageId == null) {
            return;
        }
        try {
            draftRecipientsRepository.setAside(accountId, messageId, LocalDateTime.now());
        } catch (Exception e) {
            log.debug("{} Could not set aside the kept recipients of sent draft {} of account {}: {}", LogCategory.SMTP,
                    messageId, accountId, e.getMessage());
        }
    }

    /**
     * The rows of the revisions a stored save deletes from the server: the one the
     * client names, and every earlier revision of the same draft the server still
     * holds a row for. The client names the stableId the previous save returned,
     * which the 202 returns before that save's APPEND has an outcome
     * ({@code saveOnce} in {@code frontend/src/lib/compose/session.ts}), so after a
     * rejected save it names a revision the server never stored, which has no row:
     * the last stored revision was named by no save and stayed on the server as a
     * second copy of the draft (IMAP/SMTP audit §4e, the residual of 1.40). A
     * delete that failed left one the same way. Each row passes the guard the named
     * one does.
     */
    private List<MessageEntity> replacedRevisions(Long accountId, @Nullable String replacesStableId, String stableId) {
        Map<String, MessageEntity> rows = new LinkedHashMap<>();
        if (replacesStableId != null && !replacesStableId.isBlank()) {
            MessageEntity old = messageService.getByStableId(replacesStableId).orElse(null);
            if (old == null) {
                log.warn("{} replaces: draft {} not found, continuing without deleting it.", LogCategory.SMTP,
                        replacesStableId);
            } else if (!isReplaceableDraft(accountId, old)) {
                /*
                 * The replaces target is only ever hard-deleted (IMAP expunge) if it is this
                 * account's own message in the Drafts folder. A wrong stableId (client bug)
                 * must never expunge received mail or another account's message — keep it.
                 */
                log.warn("{} replaces: {} is not a Drafts message of account {}; keeping it.", LogCategory.SMTP,
                        replacesStableId, accountId);
            } else {
                rows.put(replacesStableId, old);
            }
        }
        for (MessageEntity earlier : earlierRevisionRows(accountId, stableId)) {
            rows.putIfAbsent(earlier.getStableId(), earlier);
        }
        return List.copyOf(rows.values());
    }

    /**
     * The rows of the earlier revisions of {@code stableId}'s draft that the server
     * still holds in this account's Drafts. Best-effort: a failed lookup leaves the
     * copies for the next save or send rather than failing this one.
     */
    private List<MessageEntity> earlierRevisionRows(Long accountId, String stableId) {
        try {
            return draftRecipientsRepository.findEarlierRevisions(accountId, stableId).stream()
                    .flatMap(earlier -> messageService.getByStableId(earlier).stream())
                    .filter(row -> isReplaceableDraft(accountId, row)).toList();
        } catch (RuntimeException e) {
            log.warn("{} Could not look up the earlier revisions of draft {}: {}", LogCategory.SMTP, stableId,
                    e.getMessage());
            return List.of();
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
}
