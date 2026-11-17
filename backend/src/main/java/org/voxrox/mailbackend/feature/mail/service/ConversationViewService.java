package org.voxrox.mailbackend.feature.mail.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.ConversationSummaryResponse;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.dto.MailSummaryResponse;
import org.voxrox.mailbackend.feature.mail.dto.ThreadResponse;
import org.voxrox.mailbackend.feature.mail.mapper.MessageMapper;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

/**
 * The conversation-grouped view of a folder (Threading Phase 2): the grouped
 * listing, and the thread one of its rows expands into. The two live together
 * because both are derived from one {@link ConversationScope} — the single
 * answer to which folders a view's conversation spans — so a row's badge and
 * the members listed under it cannot disagree. {@link MailFacade} serves both
 * endpoints through this class, next to the flat listing and the message
 * actions it keeps.
 */
@Service
public class ConversationViewService {

    private static final Logger log = LoggerFactory.getLogger(ConversationViewService.class);

    /**
     * Folder roles that stay out of the cross-folder conversation aggregation and
     * whose own views stay folder-scoped — see
     * {@link #conversationExcludedFolders}.
     */
    private static final List<FolderRole> CONVERSATION_EXCLUDED_ROLES = List.of(FolderRole.TRASH, FolderRole.JUNK,
            FolderRole.DRAFTS, FolderRole.SENT);

    private final MessageRepository messageRepository;
    private final MessageMapper mapper;
    private final MailSyncService mailSyncService;
    private final ImapFolderService imapFolderService;
    private final AccountService accountService;

    public ConversationViewService(MessageRepository messageRepository, MessageMapper mapper,
            MailSyncService mailSyncService, ImapFolderService imapFolderService, AccountService accountService) {
        this.messageRepository = messageRepository;
        this.mapper = mapper;
        this.mailSyncService = mailSyncService;
        this.imapFolderService = imapFolderService;
        this.accountService = accountService;
    }

    /**
     * Conversation-grouped folder listing (Threading Phase 2): one row per
     * conversation with a member in the folder, represented by its newest message
     * <em>in that folder</em>. Like {@link MailFacade#getEmails} it kicks off a
     * background sync so new mail flows in, but it is a purely local-DB view — the
     * paginator total is the number of conversations mirrored locally and it does
     * NOT lazy-fetch older messages (grouping the not-yet-mirrored tail is
     * impossible without holding the whole thread). The flat
     * {@link MailFacade#getEmails} listing stays the path that pulls older history
     * into the local window; once mirrored, those messages fold into their
     * conversations here.
     *
     * <p>
     * Cross-folder scope: for a regular folder {@code messageCount} spans the whole
     * thread across the account minus the trash, junk, drafts and sent folders
     * ({@link #conversationExcludedFolders}), so a conversation the user filed into
     * an archive still counts the copies that stayed in the inbox, and the expanded
     * member list the client builds from the thread endpoint shows exactly those.
     * Copies of one mail in several folders — Gmail's INBOX + All Mail — count
     * once, keyed by Message-ID.
     *
     * <p>
     * {@code unreadCount} deliberately stays folder-scoped even there: mark-as-read
     * from this listing acts on the folder's own messages, so a cross-folder number
     * would leave a conversation whose only unread copy sits in another folder
     * reporting "1 unread" with no way to clear it. The count and the action the
     * row offers describe the same set of messages.
     *
     * <p>
     * Trash, Junk, Drafts and Sent views stay folder-scoped — the trash must only
     * ever show what is actually in the trash, and a cross-folder Drafts view would
     * list full conversations behind rows that open the composer.
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
     * account's trash, junk, drafts and sent folders. Deleted or junked replies
     * must not resurface inside a live conversation, and an unsent draft is not a
     * message of the conversation yet — counting it would bump the inbox badge
     * while the user is still typing. The user's own sent copies are out because a
     * conversation in a receiving folder is about the mail that arrived: folding
     * the replies back in doubles every exchange the user took part in, and the
     * folder-scoped bulk actions could never touch them anyway. Doubles as the
     * folder-scoped-view test in {@link #getConversations}: those four views must
     * only ever show their own contents.
     *
     * <p>
     * Resolved through {@link ImapFolderService}, like every other role lookup in
     * {@link MailFacade} — the plain {@code folder_sync_state} read misses a folder
     * the sync has not recorded yet (first visit to the trash on a fresh account),
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
                    "{} Could not resolve trash/junk/drafts/sent folders of account {} ({}); "
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
     *            the account's trash/junk/drafts/sent folder names (with the
     *            {@code NOT IN} sentinel), or empty when the roles could not be
     *            resolved
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
            throw new ResourceNotFoundException("Thread not found: " + threadId, "error.thread.notFound");
        }
        // No Stream.findFirst() here — the single element may legitimately be null
        // (root without a Message-ID) and findFirst() throws NPE on a null element.
        List<String> roots = messageRepository.findThreadRootMessageIds(accountId, threadId, PageRequest.of(0, 1));
        String rootMessageId = roots.isEmpty() ? null : roots.getFirst();
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
}
