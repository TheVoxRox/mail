package org.voxrox.mailbackend.feature.mail.service;

import org.jspecify.annotations.Nullable;
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

    /**
     * Folder roles that stay out of the cross-folder conversation aggregation and
     * whose own views stay folder-scoped — see
     * {@link #conversationExcludedFolders}.
     */
    private static final List<FolderRole> CONVERSATION_EXCLUDED_ROLES = List.of(FolderRole.TRASH, FolderRole.JUNK,
            FolderRole.DRAFTS);

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
     * conversation with a member in the folder, represented by its newest message
     * <em>in that folder</em>. Like {@link #getEmails} it kicks off a background
     * sync so new mail flows in, but it is a purely local-DB view — the paginator
     * total is the number of conversations mirrored locally and it does NOT
     * lazy-fetch older messages (grouping the not-yet-mirrored tail is impossible
     * without holding the whole thread). The flat {@link #getEmails} listing stays
     * the path that pulls older history into the local window; once mirrored, those
     * messages fold into their conversations here.
     *
     * <p>
     * Outlook-style cross-folder scope: for a regular folder {@code messageCount}
     * spans the whole thread across the account minus the trash, junk and drafts
     * folders ({@link #conversationExcludedFolders}), so a conversation you replied
     * to counts your sent reply (and shows it in the expanded member list, which
     * the client builds from the cross-folder thread endpoint). Copies of one mail
     * in several folders — Gmail's INBOX + All Mail — count once, keyed by
     * Message-ID.
     *
     * <p>
     * {@code unreadCount} deliberately stays folder-scoped even there: mark-as-read
     * from this listing acts on the folder's own messages, so a cross-folder number
     * would leave a conversation whose only unread copy sits in another folder
     * reporting "1 unread" with no way to clear it. The count and the action the
     * row offers describe the same set of messages.
     *
     * <p>
     * Trash, Junk and Drafts views stay folder-scoped — the trash must only ever
     * show what is actually in the trash, and a cross-folder Drafts view would list
     * full conversations behind rows that open the composer.
     */
    public Page<ConversationSummaryResponse> getConversations(Long accountId, String folderName, int page, int size) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        mailSyncService.syncAndBackfillAsync(account, folderName, page);

        long offset = (long) page * size;
        ConversationScope scope = conversationScope(accountId, folderName);

        // The page is always the folder-scoped query, in both modes. It produces
        // exactly the same rows in the same order as a cross-folder window query
        // would — the representative is the newest member in this folder either
        // way, and "has a member in the folder" is implicit when only the folder's
        // rows are scanned — but it can ride the (account_id, folder_name,
        // received_at) index instead of materializing and sorting the whole
        // account on every page load and every sync_completed refetch.
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
        Map<String, Integer> crossFolderSizes = scope.crossFolder()
                ? crossFolderConversationSizes(accountId, byId.values(), scope.excludedFolders())
                : Map.of();

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
            if (latest.threadId() != null) {
                // Cross-folder mode replaces the folder-scoped size; an unthreaded
                // representative is a singleton by construction, so it keeps its own.
                messageCount = crossFolderSizes.getOrDefault(latest.threadId(), messageCount);
            }
            content.add(new ConversationSummaryResponse(latest.threadId(), latest, messageCount, unreadCount));
        }
        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Cross-folder {@code messageCount} for the conversations on one page, keyed by
     * thread id. Bounded work: at most {@code size} thread ids go into an indexed
     * {@code IN} lookup, unlike a window aggregate that would have to partition the
     * whole account before the page can be cut.
     *
     * <p>
     * Representatives without a thread id are left out — the backfill has not
     * threaded them, so their conversation is a singleton and the folder-scoped
     * count of 1 is already right. Grouping on {@code COALESCE(thread_id,
     * stable_id)} would have been the literal translation of the listing's grouping
     * key, but it is not indexable; the plain {@code thread_id} lookup rides
     * {@code idx_messages_account_thread}.
     */
    private Map<String, Integer> crossFolderConversationSizes(Long accountId,
            Collection<MailSummaryResponse> representatives, List<String> excludedFolders) {
        List<String> threadIds = representatives.stream().map(MailSummaryResponse::threadId).filter(Objects::nonNull)
                .distinct().toList();
        if (threadIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> sizes = new HashMap<>();
        for (Object[] row : messageRepository.countCrossFolderConversationSizes(accountId, threadIds,
                excludedFolders)) {
            sizes.put((String) row[0], ((Number) row[1]).intValue());
        }
        return sizes;
    }

    /**
     * Folder names kept out of the cross-folder conversation aggregation: the
     * account's trash, junk and drafts folders. Deleted or junked replies must not
     * resurface inside a live conversation, and an unsent draft is not a message of
     * the conversation yet — counting it would bump the inbox badge while the user
     * is still typing. Doubles as the folder-scoped-view test in
     * {@link #getConversations}: those three views must only ever show their own
     * contents.
     *
     * <p>
     * Resolved through {@link ImapFolderService}, like every other role lookup in
     * this class — the plain {@code folder_sync_state} read misses a folder the
     * sync has not recorded yet (first visit to the trash on a fresh account),
     * which would silently promote the trash to a cross-folder view.
     *
     * <p>
     * Bounded: the lookup gives up rather than waiting for the account's IMAP
     * connection. A sync holds that lock for a whole folder cycle, and the case
     * where the DB has no record yet is precisely the case where a long initial
     * sync is running — so the blocking variant would stall the user's message list
     * behind it. Waiting on a lock raises nothing, so the symptom would be a list
     * that just hangs.
     *
     * <p>
     * Returns an empty list when the roles cannot be resolved (IMAP busy or down
     * and nothing recorded locally). That is the fail-closed signal — the caller
     * falls back to the folder-scoped listing, which can never show foreign
     * folders' messages. Otherwise the list always contains the empty-string
     * sentinel so the native {@code NOT IN} clause stays well-formed when the
     * account has none of the three folders; a folder name is never empty.
     */
    private List<String> conversationExcludedFolders(Long accountId) {
        List<String> excluded = new ArrayList<>();
        excluded.add("");
        try {
            for (FolderRole role : CONVERSATION_EXCLUDED_ROLES) {
                Optional<List<String>> names = imapFolderService.findFolderNamesByRoleWithoutWaiting(accountId, role);
                if (names.isEmpty()) {
                    log.debug(
                            "{} Role {} of account {} could not be resolved without waiting for IMAP; "
                                    + "serving the folder-scoped conversation listing.",
                            LogCategory.SYNC, role, accountId);
                    return List.of();
                }
                excluded.addAll(names.get());
            }
        } catch (RuntimeException e) {
            log.warn(
                    "{} Could not resolve trash/junk/drafts folders of account {} ({}); "
                            + "serving the folder-scoped conversation listing.",
                    LogCategory.SYNC, accountId, e.getMessage());
            return List.of();
        }
        return excluded;
    }

    /**
     * The set of messages one conversation view counts and lists — the single
     * resolution of "which folders does this view's conversation span". Both the
     * grouped listing's {@code messageCount} and the member list served by
     * {@link #getThread} are derived from it, so a row's badge and the rows that
     * appear under it can no longer disagree.
     *
     * @param excludedFolders
     *            the account's trash/junk/drafts folder names (with the {@code NOT
     *            IN} sentinel), or empty when the roles could not be resolved
     * @param crossFolder
     *            whether this view spans the account minus {@code excludedFolders};
     *            false means folder-scoped, which is both the Trash/Junk/Drafts
     *            behaviour and the fail-closed fallback
     * @param viewFolder
     *            the folder this scope was resolved for — kept in the record rather
     *            than passed back in per call, so {@code crossFolder} can never be
     *            evaluated against a different folder than the one it describes
     */
    private record ConversationScope(List<String> excludedFolders, boolean crossFolder, String viewFolder) {

        /** Whether {@code message} belongs to the conversation as this view sees it. */
        boolean includes(MailSummaryResponse message) {
            return crossFolder
                    ? !excludedFolders.contains(message.folderName())
                    : viewFolder.equals(message.folderName());
        }
    }

    /**
     * Resolves the conversation scope of {@code folderName}. A view is
     * folder-scoped exactly when its own folder is one of the folders the
     * cross-folder counts skip — one resolution drives both decisions.
     */
    private ConversationScope conversationScope(Long accountId, String folderName) {
        List<String> excludedFolders = conversationExcludedFolders(accountId);
        return new ConversationScope(excludedFolders,
                !excludedFolders.isEmpty() && !excludedFolders.contains(folderName), folderName);
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
     * <p>
     * With {@code folderRef} the thread is returned <em>as that folder's
     * conversation view sees it</em>: exactly the messages its
     * {@link ConversationScope} spans, deduplicated the same way its badge counts
     * them, and {@code unreadCount} folder-scoped exactly like the row's. The
     * response therefore mirrors the row field for field — {@code messages.size()}
     * equals its {@code messageCount} and {@code unreadCount} equals its
     * {@code unreadCount} — so the client renders the list and never re-derives the
     * scope.
     *
     * <p>
     * Only a {@code null} {@code folderRef} means "unscoped, account-wide", which
     * is the raw view support tooling wants. A present-but-empty value is
     * deliberately <em>not</em> treated as absent: it is a client bug, and
     * answering it with the unscoped thread would put trashed and half-written
     * members into a live conversation. It falls through to the normal resolution,
     * where the empty-string {@code NOT IN} sentinel classifies it as folder-scoped
     * and it yields an empty member list.
     *
     * @param accountId
     *            owning account id
     * @param threadId
     *            stable thread identifier from {@code MailSummaryResponse.threadId}
     * @param folderRef
     *            folder whose conversation view scopes the member list, or
     *            {@code null} for the unscoped thread
     * @return the populated {@link ThreadResponse}, its counts mirroring the
     *         conversation row of {@code folderRef}
     * @throws ResourceNotFoundException
     *             when no message in {@code accountId} belongs to {@code threadId}
     *             (either the id never existed, or every member was deleted)
     */
    public ThreadResponse getThread(Long accountId, String threadId, @Nullable String folderRef) {
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
        if (folderRef == null) {
            return threadResponse(threadId, rootMessageId, summaries,
                    (int) summaries.stream().filter(s -> !s.seen()).count());
        }
        List<MailSummaryResponse> members = scopeToConversationView(accountId, folderRef, summaries);
        return threadResponse(threadId, rootMessageId, members, folderScopedUnread(members, folderRef));
    }

    private ThreadResponse threadResponse(String threadId, @Nullable String rootMessageId,
            List<MailSummaryResponse> members, int unread) {
        List<MailSummaryResponse> display = members.stream().map(mapper::withDisplayFallbacks).toList();
        return new ThreadResponse(threadId, rootMessageId, members.size(), unread, display);
    }

    /**
     * Unread members living in {@code viewFolder}. Deliberately folder-scoped even
     * when the member list is cross-folder, so it matches
     * {@link ConversationSummaryResponse#unreadCount()} of the row this thread was
     * fetched for: marking read from that row only reaches the folder's own
     * messages, so a cross-folder number would report unread mail the row cannot
     * clear.
     */
    private static int folderScopedUnread(List<MailSummaryResponse> members, String viewFolder) {
        return (int) members.stream().filter(s -> !s.seen() && viewFolder.equals(s.folderName())).count();
    }

    /**
     * Narrows a thread's members to what {@code folderRef}'s conversation view
     * spans, mirroring how that view's {@code messageCount} was counted.
     *
     * <p>
     * In cross-folder mode that means dropping the excluded folders' members and
     * collapsing copies of one mail stored in several folders — Gmail keeps the
     * same {@code message_id} and {@code thread_id} in INBOX and All Mail — because
     * {@code countCrossFolderConversationSizes} counts them once via
     * {@code COUNT(DISTINCT COALESCE(message_id, stable_id))}. A duplicate keeps
     * the position of its first occurrence, so the {@code threadPosition} ordering
     * survives the collapse; which copy survives is decided by
     * {@link #survivesCollapse}.
     *
     * <p>
     * Folder-scoped views need no dedup — one folder cannot hold two copies of the
     * same mail — so they filter on the folder alone, matching their plain
     * {@code COUNT(*)}.
     */
    private List<MailSummaryResponse> scopeToConversationView(Long accountId, String folderRef,
            List<MailSummaryResponse> summaries) {
        ConversationScope scope = conversationScope(accountId, folderRef);
        if (!scope.crossFolder()) {
            return summaries.stream().filter(scope::includes).toList();
        }
        Map<String, MailSummaryResponse> byIdentity = new LinkedHashMap<>();
        for (MailSummaryResponse summary : summaries) {
            if (!scope.includes(summary)) {
                continue;
            }
            String identity = conversationMemberIdentity(summary);
            MailSummaryResponse kept = byIdentity.get(identity);
            // put() on an existing key keeps its insertion position, so replacing the
            // surviving copy never reorders the list.
            if (kept == null || survivesCollapse(summary, kept, folderRef)) {
                byIdentity.put(identity, summary);
            }
        }
        return List.copyOf(byIdentity.values());
    }

    /**
     * Whether {@code candidate} should replace {@code kept} as the surviving copy
     * of one mail held in several folders.
     *
     * <p>
     * A copy in the folder in view always beats one outside it: that is the copy
     * the row's bulk actions can reach. Among copies <em>inside</em> the folder the
     * newest wins, id as the tie-break — the same pick
     * {@link MessageRepository#findConversationRepresentatives} makes with
     * {@code ROW_NUMBER() OVER (… ORDER BY received_at DESC, id DESC)}. That
     * alignment is what makes the row's representative always survive the collapse,
     * which in turn is what lets the client drop the parent row by {@code stableId}
     * alone. Without it a folder holding two copies of one Message-ID (delivery via
     * two aliases, a re-import) would keep the older copy, the representative would
     * come back as a member, and the client would render it twice — once as the
     * parent, once as a child.
     */
    private static boolean survivesCollapse(MailSummaryResponse candidate, MailSummaryResponse kept,
            String viewFolder) {
        boolean candidateInView = viewFolder.equals(candidate.folderName());
        if (candidateInView != viewFolder.equals(kept.folderName())) {
            return candidateInView;
        }
        if (!candidateInView) {
            // Neither is reachable from this row; first occurrence wins, so the
            // threadPosition order decides and the result stays deterministic.
            return false;
        }
        int byReceivedAt = candidate.receivedAt().compareTo(kept.receivedAt());
        return byReceivedAt > 0 || (byReceivedAt == 0 && candidate.id() > kept.id());
    }

    /**
     * Dedup key for a conversation member — the Java side of the query's
     * {@code COALESCE(message_id, stable_id)}. The prefixes keep a Message-ID from
     * ever colliding with a stable id.
     */
    private static String conversationMemberIdentity(MailSummaryResponse summary) {
        return summary.messageId() != null ? "mid:" + summary.messageId() : "sid:" + summary.stableId();
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
