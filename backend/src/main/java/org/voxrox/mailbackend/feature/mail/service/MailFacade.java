package org.voxrox.mailbackend.feature.mail.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.exception.AppException;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.*;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.mapper.MessageMapper;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

import module java.base;

@Service
public class MailFacade {

    private static final Logger log = LoggerFactory.getLogger(MailFacade.class);

    private final MessageRepository messageRepository;
    private final MessageMapper mapper;
    private final MailSyncService mailSyncService;
    private final MailContentService mailContentService;
    private final ImapActionService imapActionService;
    private final ImapFolderService imapFolderService;
    private final AttachmentService attachmentService;
    private final AccountService accountService;
    private final MessageService messageService;
    private final MailDraftService mailDraftService;
    private final FolderCountCache folderCountCache;
    private final RemoteImageAllowlistService remoteImageAllowlistService;
    private final RetryTemplate dbWriteRetryTemplate;

    public MailFacade(MessageRepository messageRepository, MessageMapper mapper, MailSyncService mailSyncService,
            MailContentService mailContentService, ImapActionService imapActionService,
            ImapFolderService imapFolderService, AttachmentService attachmentService, AccountService accountService,
            MessageService messageService, MailDraftService mailDraftService, FolderCountCache folderCountCache,
            RemoteImageAllowlistService remoteImageAllowlistService,
            @Qualifier("dbWriteRetryTemplate") RetryTemplate dbWriteRetryTemplate) {
        this.messageRepository = messageRepository;
        this.mapper = mapper;
        this.mailSyncService = mailSyncService;
        this.mailContentService = mailContentService;
        this.imapActionService = imapActionService;
        this.imapFolderService = imapFolderService;
        this.attachmentService = attachmentService;
        this.accountService = accountService;
        this.messageService = messageService;
        this.mailDraftService = mailDraftService;
        this.folderCountCache = folderCountCache;
        this.remoteImageAllowlistService = remoteImageAllowlistService;
        this.dbWriteRetryTemplate = dbWriteRetryTemplate;
    }

    /*
     * Wraps a local DB write so a transient SQLITE_BUSY (a concurrent writer — a
     * multi-select trash firing several DELETEs at once, or a user action racing
     * the background sync) is retried instead of surfacing as a 500. Each attempt
     * must be self-contained: the wrapped call re-invokes a @Transactional write,
     * so a fresh transaction runs after the previous one rolled back.
     */
    private void withDbWriteRetry(Runnable write) {
        dbWriteRetryTemplate.execute(ctx -> {
            write.run();
            return null;
        });
    }

    /*
     * Deliberately not @Transactional (same for prepareForward): fetchContentSafe
     * goes to IMAP when the body is not cached locally — a network round-trip
     * behind the per-account IMAP lock, which a running sync can hold for minutes.
     * A transaction here would pin one of the four pool connections for that long
     * (plus a second one for the REQUIRES_NEW content persister — a pool-deadlock
     * recipe). The reads involved need no shared transaction.
     */
    public MailRequest prepareReply(String stableId, boolean replyAll) {
        MessageEntity original = messageService.getByStableId(stableId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + stableId));
        String content = fetchContentSafe(original);
        return mailDraftService.createReplyDraft(original, content, replyAll);
    }

    public MailRequest prepareForward(String stableId) {
        MessageEntity original = messageService.getByStableId(stableId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + stableId));
        String content = fetchContentSafe(original);
        return mailDraftService.createForwardDraft(original, content);
    }

    public List<FolderResponse> getFolders(Long accountId) {
        return imapFolderService.getFolders(accountId);
    }

    /**
     * Synchronous pre-validation for {@code POST /drafts/{stableId}/send}. Verifies
     * that the draft exists, belongs to the given account and actually lives in the
     * Drafts folder. An account mismatch is reported as 404 (not 403) so that the
     * existence of other users' messages is not leaked.
     */
    public MessageEntity verifyDraftForSend(Long accountId, String stableId) {
        MessageEntity entity = messageService.getByStableId(stableId)
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found: " + stableId));
        if (!entity.getAccount().getId().equals(accountId)) {
            throw new ResourceNotFoundException("Draft not found: " + stableId);
        }
        String draftsFolder = imapFolderService.findFolderNameByRoleOrThrow(accountId, FolderRole.DRAFTS);
        if (!draftsFolder.equals(entity.getFolderName())) {
            throw new ValidationException("Message " + stableId + " is not in the Drafts folder",
                    "validation.draft.notInDrafts", stableId);
        }
        return entity;
    }

    /**
     * Lists drafts for the given account — internally resolves the IMAP Drafts
     * folder (by role) and delegates to the standard paginated load.
     */
    public Page<MailSummaryResponse> listDrafts(Long accountId, int page, int size) {
        String draftsFolder = imapFolderService.findFolderNameByRoleOrThrow(accountId, FolderRole.DRAFTS);
        return getEmails(accountId, draftsFolder, page, size);
    }

    public Page<MailSummaryResponse> getEmails(Long accountId, String folderName, int page, int size) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);

        // Periodic sync (new mail, flag updates, optional page-0 backfill) runs in
        // the background — the user gets cached data immediately.
        mailSyncService.syncAndBackfillAsync(account, folderName, page);

        // Fast path: if the cached server count is still hot AND the requested page
        // fits within the locally cached rows, serve everything from the DB without
        // opening IMAP. This is the common case (page 0 of a warm folder) and
        // matters for clickability — the alternative is a 100–500 ms round-trip per
        // page navigation.
        long localCount = messageRepository.countByAccountIdAndFolderName(accountId, folderName);
        long needed = ((long) page + 1) * size;
        OptionalLong cached = folderCountCache.get(accountId, folderName);

        long serverCount;
        if (cached.isPresent() && needed <= localCount) {
            serverCount = cached.getAsLong();
        } else {
            // Either the count is stale OR the page falls beyond the local window —
            // open IMAP for a fresh count, and lazy-fetch the missing range if
            // required so the user can browse the whole folder without us
            // mirroring it up front. Catches the narrow runtime-exception band
            // (IMAP wrappers throw RuntimeException for connection issues); checked
            // exceptions are handled inside the helper.
            try {
                serverCount = mailSyncService.fetchServerCountAndEnsurePageLocally(account, folderName, page, size);
            } catch (RuntimeException e) {
                log.warn("{} Server count unavailable for account {} folder {} ({}); serving local cache only.",
                        LogCategory.SYNC, accountId, folderName, e.getMessage());
                serverCount = cached.orElse(localCount);
            }
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<MailSummaryResponse> localPage = messageRepository.findSummariesByAccountAndFolder(accountId, folderName,
                pageable);
        return new PageImpl<>(localPage.getContent(), pageable, serverCount);
    }

    /**
     * Conversation-grouped folder listing (Threading Phase 2): one row per
     * conversation in the folder, represented by its newest message plus the
     * folder-scoped message / unread counts. Like {@link #getEmails} it kicks off a
     * background sync so new mail flows in, but it is a purely local-DB view — the
     * paginator total is the number of conversations mirrored locally and it does
     * NOT lazy-fetch older messages (grouping the not-yet-mirrored tail is
     * impossible without holding the whole thread). The flat {@link #getEmails}
     * listing stays the path that pulls older history into the local window; once
     * mirrored, those messages fold into their conversations here.
     */
    public Page<ConversationSummaryResponse> getConversations(Long accountId, String folderName, int page, int size) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        mailSyncService.syncAndBackfillAsync(account, folderName, page);

        long offset = (long) page * size;
        List<Object[]> rows = messageRepository.findConversationRepresentatives(accountId, folderName, size, offset);
        long total = messageRepository.countConversationsByAccountAndFolder(accountId, folderName);
        Pageable pageable = PageRequest.of(page, size);

        if (rows.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        // Load the representatives' summaries in one query (undefined order) and
        // re-attach them to the count-row order that carries the pagination sort.
        List<Long> repIds = rows.stream().map(r -> ((Number) r[0]).longValue()).toList();
        Map<Long, MailSummaryResponse> byId = messageRepository.findSummariesByIds(repIds).stream()
                .collect(Collectors.toMap(MailSummaryResponse::id, mapper::withDisplayFallbacks));

        List<ConversationSummaryResponse> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            MailSummaryResponse latest = byId.get(((Number) r[0]).longValue());
            if (latest == null) {
                // A concurrent delete dropped the representative between the two reads —
                // skip it; the next load reflects the new state.
                continue;
            }
            int messageCount = ((Number) r[1]).intValue();
            int unreadCount = ((Number) r[2]).intValue();
            content.add(new ConversationSummaryResponse(latest.threadId(), latest, messageCount, unreadCount));
        }
        return new PageImpl<>(content, pageable, total);
    }

    @Transactional(readOnly = true)
    public Page<MailSummaryResponse> searchEmails(Long accountId, String query, int page, int size) {
        return messageService.search(accountId, query, page, size).map(mapper::withDisplayFallbacks);
    }

    /**
     * Returns the message detail. Metadata (subject, sender, attachments...) is
     * always valid from the local DB. If the current content cannot be fetched from
     * IMAP, returns the cached version from the DB (if it exists) along with the
     * error description in {@code contentError} — the client then has a clear
     * signal that the content may not be up-to-date.
     * <p>
     * Unexpected runtime errors (not {@link AppException}) are propagated to
     * {@code GlobalExceptionHandler} — those mean a bug, not "content unavailable".
     */
    public MailDetailResponse getEmailDetailByStableId(String stableId) {
        /*
         * findByStableIdWithAttachments loads attachments via JOIN FETCH in a single
         * query instead of lazy N+1 selects.
         */
        MessageEntity entity = messageRepository.findByStableIdWithAttachments(stableId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + stableId));
        try {
            String content = mailContentService.getOrFetchMessageContent(entity.getId());
            return mapper.toDto(entity, content);
        } catch (MailOperationException | ResourceNotFoundException e) {
            log.warn("{} Failed to load current content of message {} ({}), returning cached + contentError.",
                    LogCategory.SYNC, stableId, e.getCode(), e);
            return mapper.toDto(entity, entity.getContent(), e.getMessage());
        }
    }

    /**
     * Returns the conversation (thread) with the given identifier. Ownership is
     * enforced by the query — the lookup is scoped to {@code accountId}, so a
     * caller can never reach a thread owned by another account.
     *
     * <p>
     * The response is built from the local DB only (no IMAP roundtrip). The
     * {@code messages} list is the same {@link MailSummaryResponse} shape used by
     * the folder listing, ordered by {@code threadPosition} ascending.
     *
     * @param accountId
     *            owning account id
     * @param threadId
     *            stable thread identifier from {@code MailSummaryResponse.threadId}
     * @return the populated {@link ThreadResponse}
     * @throws ResourceNotFoundException
     *             when no message in {@code accountId} belongs to {@code threadId}
     *             (either the id never existed, or every member was deleted)
     */
    public ThreadResponse getThread(Long accountId, String threadId) {
        /*
         * Summary projection instead of entities — thread members carry the @Lob body
         * and a long conversation loaded as entities does not fit the 384m heap. The
         * root Message-ID is shared by every member by construction, so it is read with
         * a separate ordered LIMIT-1 query.
         */
        List<MailSummaryResponse> summaries = messageRepository.findSummariesByAccountIdAndThreadId(accountId,
                threadId);
        if (summaries.isEmpty()) {
            throw new ResourceNotFoundException("Thread not found: " + threadId);
        }
        // No Stream.findFirst() here — the single element may legitimately be null
        // (root without a Message-ID) and findFirst() throws NPE on a null element.
        List<String> roots = messageRepository.findThreadRootMessageIds(accountId, threadId, PageRequest.of(0, 1));
        String rootMessageId = roots.isEmpty() ? null : roots.get(0);
        int unread = (int) summaries.stream().filter(s -> !s.seen()).count();
        List<MailSummaryResponse> display = summaries.stream().map(mapper::withDisplayFallbacks).toList();
        return new ThreadResponse(threadId, rootMessageId, summaries.size(), unread, display);
    }

    /*
     * Deliberately not @Transactional (same for moveToFolder): the folder
     * resolution can fall back to a live IMAP LIST behind the per-account lock, and
     * the only local write is the single-statement delete inside executeMove, which
     * manages its own short transaction. Holding a write transaction across the
     * IMAP wait would stall every other writer on the single-writer SQLite.
     */
    public void moveToTrash(String stableId) {
        MessageEntity entity = messageService.getByStableId(stableId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found for deletion: " + stableId));

        Long accountId = entity.getAccount().getId();

        String trashFolderName = imapFolderService.findFolderNameByRoleOrThrow(accountId, FolderRole.TRASH);

        /*
         * A message already sitting in the trash cannot be "moved to trash" again — a
         * trash→trash IMAP MOVE is a server-side no-op, so the message survives on the
         * server and the next folder sync resurrects the locally deleted row. Delete in
         * trash therefore means permanent delete (\Deleted + EXPUNGE).
         */
        if (trashFolderName.equals(entity.getFolderName())) {
            executePurge(entity);
            return;
        }

        executeMove(entity, trashFolderName, "mail_trash");
    }

    /**
     * Permanent delete for a message already in the trash folder: sync local delete
     * + async server expunge + audit log. Same local-write-first ordering as
     * {@link #executeMove} — the provider action is dispatched only once the local
     * delete has committed.
     */
    private void executePurge(MessageEntity entity) {
        Long accountId = entity.getAccount().getId();
        String folderName = entity.getFolderName();
        String stableId = entity.getStableId();

        withDbWriteRetry(() -> messageService.deleteByStableId(stableId));
        imapActionService.hardDeleteAsync(accountId, folderName, entity.getUid());

        AuditLog.success("mail_purge", LogMasker.maskEmail(entity.getAccount().getEmail()),
                "stable_id=" + stableId + " folder=" + folderName);
        log.info("{} Message {} permanently deleted from {} (deleted locally, provider expunge dispatched).",
                LogCategory.DATABASE, stableId, folderName);
    }

    /**
     * Moves the message to a user-selected folder. Validation runs synchronously
     * (folder existence, source != target); the actual provider action runs
     * asynchronously. The local entity is deleted immediately and reappears after
     * the target folder sync according to the provider.
     * <p>
     * The source folder corresponds to {@code entity.getFolderName()}; the target
     * is supplied by the caller as an opaque folderRef chosen by the client from
     * the folder list.
     */
    public void moveToFolder(String stableId, String targetFolderRef) {
        if (targetFolderRef == null || targetFolderRef.isBlank()) {
            throw new ValidationException("Target folder must not be empty.", "validation.mail.targetFolderRequired");
        }
        MessageEntity entity = messageService.getByStableId(stableId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found for move: " + stableId));

        Long accountId = entity.getAccount().getId();
        String sourceFolder = entity.getFolderName();

        if (sourceFolder.equals(targetFolderRef)) {
            throw new ValidationException("Source and target folders are the same: " + targetFolderRef,
                    "validation.mail.sameSourceAndTargetFolder", targetFolderRef);
        }

        /*
         * Verify the target folder against the provider folder list. Look up by
         * folderRef (not role), because the user moves to a specific folder.
         */
        boolean targetExists = imapFolderService.getFolders(accountId).stream()
                .anyMatch(f -> targetFolderRef.equals(f.folderRef()));
        if (!targetExists) {
            throw new ResourceNotFoundException("Target folder does not exist: " + targetFolderRef);
        }

        executeMove(entity, targetFolderRef, "mail_move");
    }

    /**
     * Shared path for moving a message between folders: sync local delete + async
     * provider move + audit log. Called from {@link #moveToTrash} and
     * {@link #moveToFolder}; the only difference is the audit action name.
     */
    private void executeMove(MessageEntity entity, String targetFolder, String auditAction) {
        Long accountId = entity.getAccount().getId();
        String sourceFolder = entity.getFolderName();
        String stableId = entity.getStableId();

        /*
         * Local delete first — it commits in its own transaction, so the provider
         * action is only dispatched once the local write has definitely succeeded. The
         * previous order dispatched from inside a still-open transaction: a rollback
         * after the dispatch left the server-side move running anyway and the local row
         * resurrected.
         */
        withDbWriteRetry(() -> messageService.deleteByStableId(stableId));
        imapActionService.moveOnServerAsync(accountId, sourceFolder, targetFolder, entity.getUid());

        AuditLog.success(auditAction, LogMasker.maskEmail(entity.getAccount().getEmail()),
                "stable_id=" + stableId + " from=" + sourceFolder + " to=" + targetFolder);
        log.info("{} Message {} moved from {} to {} (deleted locally, provider action dispatched).",
                LogCategory.DATABASE, stableId, sourceFolder, targetFolder);
    }

    /*
     * Deliberately not @Transactional: the local flag update is a single
     * self-committing @Modifying statement, so by the time the async server action
     * is dispatched the local write is durable — a rollback can no longer undo it
     * while the server-side flag change proceeds.
     */
    public void updateMessageFlag(String stableId, MessageFlag flag, boolean value) {
        MessageEntity entity = messageService.getByStableId(stableId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found for flag update: " + stableId));

        withDbWriteRetry(() -> {
            switch (flag) {
                case SEEN -> messageRepository.updateSeenStatus(stableId, value);
                case FLAGGED -> messageRepository.updateFlaggedStatus(stableId, value);
                case ANSWERED -> messageRepository.updateAnsweredStatus(stableId, value);
            }
        });

        imapActionService.updateFlagsOnServerAsync(entity.getAccount().getId(), entity.getFolderName(), entity.getUid(),
                flag, value);
    }

    public InputStream getAttachment(String stableId, String partPath) {
        return attachmentService.getAttachmentStreamByStableId(stableId, partPath);
    }

    /**
     * All-or-nothing endpoint for the message content itself. On failure the
     * exception propagates — the caller (controller) lets it fall through to
     * {@code GlobalExceptionHandler}, which returns a ProblemDetail with
     * {@code errorCode}. No error-fallback strings in the {@code content} field;
     * the one deliberate substitution is the B1-1 oversize placeholder, which
     * {@link MailContentService} serves as the canonical content of a body over the
     * byte cap.
     */
    public MailContentResponse getMessageContentOnly(String stableId) {
        MessageEntity entity = messageService.getByStableId(stableId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + stableId));
        String content = mailContentService.getOrFetchMessageContent(entity.getId());
        // Metadata for the remote-image opt-in (audit F2): the bare sender is the
        // allow-list key, and whether it is already trusted lets the client auto-load.
        String senderEmail = entity.getFromEmailOnly();
        boolean allowed = remoteImageAllowlistService.isAllowed(entity.getAccount().getId(), senderEmail);
        return new MailContentResponse(content, senderEmail, allowed);
    }

    /**
     * Per-item isolation for reply/forward drafts: if the current content cannot be
     * loaded, the draft is not abandoned — it uses the cached DB version or an
     * empty string. The caller (UI) may surface a user-friendly hint, but the draft
     * is functional. An oversized body (B1-1) quotes as empty — never as the
     * "message too large" placeholder (see
     * {@link MailContentService#getOrFetchQuotableContent}).
     * <p>
     * The failure is logged loudly with a full stack trace — this is not a "silent
     * fallback".
     */
    private String fetchContentSafe(MessageEntity entity) {
        try {
            return mailContentService.getOrFetchQuotableContent(entity.getId());
        } catch (MailOperationException | ResourceNotFoundException e) {
            log.warn("{} Unable to load content for draft (message {}, {}): {}", LogCategory.SYNC, entity.getStableId(),
                    e.getCode(), e.getMessage(), e);
            return Objects.requireNonNullElse(entity.getContent(), "");
        }
    }
}
