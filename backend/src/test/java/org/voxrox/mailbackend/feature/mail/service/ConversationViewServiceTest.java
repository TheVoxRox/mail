package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.*;
import org.voxrox.mailbackend.feature.mail.mapper.MessageMapper;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * Unit tests for {@link ConversationViewService}: the conversation-grouped
 * listing and the thread a row expands into, each in its own @Nested block. All
 * dependencies are mocked.
 */
@ExtendWith(MockitoExtension.class)
class ConversationViewServiceTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageMapper mapper;
    @Mock
    private MailSyncService mailSyncService;
    @Mock
    private ImapFolderService imapFolderService;
    @Mock
    private AccountService accountService;

    @InjectMocks
    private ConversationViewService conversationViewService;

    private static final Long ACCOUNT_ID = 1L;
    private static final String FOLDER_INBOX = "INBOX";
    private static final String FOLDER_TRASH = "[Gmail]/Trash";
    private static final String FOLDER_DRAFTS = "[Gmail]/Drafts";

    private AccountEntity account;

    @BeforeEach
    void setUp() {
        account = new AccountEntity();
        account.setId(ACCOUNT_ID);
        account.setEmail("user@example.com");
    }

    @Nested
    @DisplayName("getConversations")
    class GetConversations {

        // A regular folder view is cross-folder; with no folder roles recorded the
        // excluded list is just the NOT IN sentinel.
        private static final List<String> NO_EXCLUDED = List.of("");

        @Test
        @DisplayName("Maps count rows to DTOs in query order, re-attaching the unordered summaries by id")
        void mapsRowsInOrderAndReattachesSummaries() {
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            // [representativeId, messageCount, unreadCount]; A newer than B.
            Object[] rowA = new Object[]{2L, 3, 1};
            Object[] rowB = new Object[]{5L, 1, 0};
            when(messageRepository.findConversationRepresentatives(ACCOUNT_ID, FOLDER_INBOX, 50, 0L))
                    .thenReturn(List.of(rowA, rowB));
            when(messageRepository.countConversationsByAccountAndFolder(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(2L);
            MailSummaryResponse s2 = summaryWithThread(2L, "t-A");
            MailSummaryResponse s5 = summaryWithThread(5L, "t-B");
            // Returned in a different order than the rows — proves the facade re-orders.
            when(messageRepository.findSummariesByIds(List.of(2L, 5L))).thenReturn(List.of(s5, s2));
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            Page<ConversationSummaryResponse> page = conversationViewService.getConversations(ACCOUNT_ID, FOLDER_INBOX,
                    0, 50);

            assertThat(page.getTotalElements()).isEqualTo(2L);
            assertThat(page.getContent()).hasSize(2);
            ConversationSummaryResponse first = page.getContent().get(0);
            assertThat(first.threadId()).isEqualTo("t-A");
            assertThat(first.latest()).isEqualTo(s2);
            assertThat(first.messageCount()).isEqualTo(3);
            assertThat(first.unreadCount()).isEqualTo(1);
            ConversationSummaryResponse second = page.getContent().get(1);
            assertThat(second.threadId()).isEqualTo("t-B");
            assertThat(second.messageCount()).isEqualTo(1);
            assertThat(second.unreadCount()).isZero();
            verify(mailSyncService).syncAndBackfillAsync(account, FOLDER_INBOX, 0);
        }

        @Test
        @DisplayName("Representative deleted between the two reads -> that row is skipped, total unchanged")
        void skipsRepresentativeMissingFromSummaries() {
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            Object[] rowA = new Object[]{2L, 2, 0};
            Object[] rowB = new Object[]{5L, 1, 0}; // representative 5 vanished concurrently
            when(messageRepository.findConversationRepresentatives(ACCOUNT_ID, FOLDER_INBOX, 50, 0L))
                    .thenReturn(List.of(rowA, rowB));
            when(messageRepository.countConversationsByAccountAndFolder(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(2L);
            MailSummaryResponse s2 = summaryWithThread(2L, "t-A");
            when(messageRepository.findSummariesByIds(List.of(2L, 5L))).thenReturn(List.of(s2));
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            Page<ConversationSummaryResponse> page = conversationViewService.getConversations(ACCOUNT_ID, FOLDER_INBOX,
                    0, 50);

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).threadId()).isEqualTo("t-A");
            // PageImpl normalizes the last-page total down to what is actually present
            // (offset + pageSize > total, content non-empty), so the skipped row is
            // reflected honestly rather than the raw count of 2.
            assertThat(page.getTotalElements()).isEqualTo(1L);
        }

        @Test
        @DisplayName("No conversations -> empty page with the count total, no summary lookup")
        void emptyFolderReturnsEmptyPage() {
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(messageRepository.findConversationRepresentatives(ACCOUNT_ID, FOLDER_INBOX, 50, 0L))
                    .thenReturn(List.of());
            when(messageRepository.countConversationsByAccountAndFolder(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(0L);

            Page<ConversationSummaryResponse> page = conversationViewService.getConversations(ACCOUNT_ID, FOLDER_INBOX,
                    0, 50);

            assertThat(page.getContent()).isEmpty();
            assertThat(page.getTotalElements()).isZero();
            verify(messageRepository, never()).findSummariesByIds(any());
        }

        @Test
        @DisplayName("Cross-folder size replaces the folder-scoped messageCount; unreadCount keeps its folder value")
        void crossFolderSizeOverridesMessageCount() {
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            stubRolesResolveToNothing();
            // Folder-scoped row: 2 messages here, 1 of them unread.
            when(messageRepository.findConversationRepresentatives(ACCOUNT_ID, FOLDER_INBOX, 50, 0L))
                    .thenReturn(List.<Object[]>of(new Object[]{2L, 2, 1}));
            when(messageRepository.countConversationsByAccountAndFolder(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(1L);
            MailSummaryResponse s2 = summaryWithThread(2L, "t-A");
            when(messageRepository.findSummariesByIds(List.of(2L))).thenReturn(List.of(s2));
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepository.countCrossFolderConversationSizes(ACCOUNT_ID, List.of("t-A"), NO_EXCLUDED))
                    .thenReturn(List.<Object[]>of(new Object[]{"t-A", 4}));

            Page<ConversationSummaryResponse> page = conversationViewService.getConversations(ACCOUNT_ID, FOLDER_INBOX,
                    0, 50);

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).messageCount()).isEqualTo(4);
            assertThat(page.getContent().get(0).unreadCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("An unthreaded representative is a singleton — it never reaches the cross-folder size query")
        void unthreadedRepresentativeKeepsItsFolderCount() {
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(messageRepository.findConversationRepresentatives(ACCOUNT_ID, FOLDER_INBOX, 50, 0L))
                    .thenReturn(List.<Object[]>of(new Object[]{2L, 1, 0}));
            when(messageRepository.countConversationsByAccountAndFolder(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(1L);
            when(messageRepository.findSummariesByIds(List.of(2L))).thenReturn(List.of(summaryWithThread(2L, null)));
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            Page<ConversationSummaryResponse> page = conversationViewService.getConversations(ACCOUNT_ID, FOLDER_INBOX,
                    0, 50);

            assertThat(page.getContent().get(0).messageCount()).isEqualTo(1);
            verify(messageRepository, never()).countCrossFolderConversationSizes(anyLong(), any(), any());
        }

        @Test
        @DisplayName("Trash, junk, drafts and sent folders are all excluded from the cross-folder counts")
        void excludesTrashJunkAndDraftsFromCrossFolderCounts() {
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            // Every folder claiming a role is excluded, not just the first — role
            // detection is not unique.
            stubRoleFolders(FolderRole.TRASH, FOLDER_TRASH, "Recycle bin");
            stubRoleFolders(FolderRole.JUNK, "Spam");
            stubRoleFolders(FolderRole.DRAFTS, FOLDER_DRAFTS);
            stubRoleFolders(FolderRole.SENT, "[Gmail]/Sent Mail");
            when(messageRepository.findConversationRepresentatives(ACCOUNT_ID, FOLDER_INBOX, 50, 0L))
                    .thenReturn(List.<Object[]>of(new Object[]{2L, 1, 0}));
            when(messageRepository.countConversationsByAccountAndFolder(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(1L);
            when(messageRepository.findSummariesByIds(List.of(2L))).thenReturn(List.of(summaryWithThread(2L, "t-A")));
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            conversationViewService.getConversations(ACCOUNT_ID, FOLDER_INBOX, 0, 50);

            verify(messageRepository).countCrossFolderConversationSizes(ACCOUNT_ID, List.of("t-A"),
                    List.of("", FOLDER_TRASH, "Recycle bin", "Spam", FOLDER_DRAFTS, "[Gmail]/Sent Mail"));
        }

        /**
         * The roles are resolved through {@link ImapFolderService}, whose live-IMAP
         * fallback covers a folder the sync has not recorded yet. Reading
         * {@code folder_sync_state} directly would classify the first visit to the
         * trash on a fresh account as a regular folder and serve it cross-folder
         * counts.
         */
        @ParameterizedTest(name = "{0} view stays folder-scoped")
        @EnumSource(names = {"TRASH", "JUNK", "DRAFTS", "SENT"})
        void folderScopedViews(FolderRole role) {
            String folderName = "folder-of-" + role;
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            // Every excluded role, not just the one under test: an unstubbed lookup throws
            // a strict stubbing mismatch that the fail-closed branch swallows, and the
            // test would prove the degraded path instead of the role check.
            for (FolderRole candidate : List.of(FolderRole.TRASH, FolderRole.JUNK, FolderRole.DRAFTS,
                    FolderRole.SENT)) {
                stubRoleFolders(candidate, candidate == role ? folderName : "unrelated-" + candidate);
            }
            when(messageRepository.findConversationRepresentatives(ACCOUNT_ID, folderName, 50, 0L))
                    .thenReturn(List.of());
            when(messageRepository.countConversationsByAccountAndFolder(ACCOUNT_ID, folderName)).thenReturn(0L);

            Page<ConversationSummaryResponse> page = conversationViewService.getConversations(ACCOUNT_ID, folderName, 0,
                    50);

            assertThat(page.getContent()).isEmpty();
            verify(messageRepository, never()).countCrossFolderConversationSizes(anyLong(), any(), any());
        }

        @Test
        @DisplayName("Roles unresolvable (IMAP down, nothing recorded) -> folder-scoped, not a cross-folder guess")
        void unresolvableRolesFallBackToFolderScoped() {
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(imapFolderService.findFolderNamesByRoleWithoutWaiting(eq(ACCOUNT_ID), any()))
                    .thenThrow(new MailOperationException(ErrorCode.MAIL_CONNECTION_ERROR, "Connection refused"));
            when(messageRepository.findConversationRepresentatives(ACCOUNT_ID, FOLDER_INBOX, 50, 0L))
                    .thenReturn(List.of());
            when(messageRepository.countConversationsByAccountAndFolder(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(0L);

            Page<ConversationSummaryResponse> page = conversationViewService.getConversations(ACCOUNT_ID, FOLDER_INBOX,
                    0, 50);

            // Degrading to folder-scoped counts is safe; a cross-folder query with an
            // unknown exclusion set would pull trash and junk into live conversations.
            assertThat(page.getContent()).isEmpty();
            verify(messageRepository, never()).countCrossFolderConversationSizes(anyLong(), any(), any());
        }

        @Test
        @DisplayName("Connection busy with a sync -> folder-scoped too, rather than waiting for the lock")
        void busyConnectionFallsBackToFolderScoped() {
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            /*
             * The other degradation route: not an exception but an empty Optional, the read
             * path's "I could not find out without waiting for the sync". It has to fail
             * closed exactly like the error case — an empty exclusion set would mean
             * "exclude nothing", i.e. trash and junk inside live conversations.
             */
            when(imapFolderService.findFolderNamesByRoleWithoutWaiting(eq(ACCOUNT_ID), any()))
                    .thenReturn(Optional.empty());
            when(messageRepository.findConversationRepresentatives(ACCOUNT_ID, FOLDER_INBOX, 50, 0L))
                    .thenReturn(List.of());
            when(messageRepository.countConversationsByAccountAndFolder(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(0L);

            Page<ConversationSummaryResponse> page = conversationViewService.getConversations(ACCOUNT_ID, FOLDER_INBOX,
                    0, 50);

            assertThat(page.getContent()).isEmpty();
            verify(messageRepository, never()).countCrossFolderConversationSizes(anyLong(), any(), any());
        }

        /**
         * Every excluded role resolves, to nothing — the account has no trash, junk,
         * drafts or sent folder. Spelled out rather than left to the Mockito default:
         * an unstubbed {@code Optional} method yields {@link Optional#empty()}, which
         * the read path reads as "could not resolve" and answers folder-scoped, so the
         * test would assert cross-folder behaviour while exercising the degraded path.
         */
        private void stubRolesResolveToNothing() {
            for (FolderRole role : List.of(FolderRole.TRASH, FolderRole.JUNK, FolderRole.DRAFTS, FolderRole.SENT)) {
                stubRoleFolders(role);
            }
        }

        private void stubRoleFolders(FolderRole role, String... folderNames) {
            when(imapFolderService.findFolderNamesByRoleWithoutWaiting(ACCOUNT_ID, role))
                    .thenReturn(Optional.of(List.of(folderNames)));
        }

        private MailSummaryResponse summaryWithThread(long id, String threadId) {
            return new MailSummaryResponse(id, "s" + id, FOLDER_INBOX, "Subject " + id, "from@x.cz", "to@x.cz",
                    LocalDateTime.of(2026, 1, 1, 10, 0), false, false, false, false, threadId, "<m" + id + "@x.cz>",
                    100L);
        }
    }

    @Nested
    @DisplayName("getThread")
    class GetThread {

        private static final String THREAD_ID = "8b4abcde-uuid";

        @Test
        @DisplayName("No folderRef -> the full, unscoped thread ordered by threadPosition, unreadCount populated")
        void shouldReturnThreadWithMembersAndUnreadCount() {
            MailSummaryResponse s1 = summaryWithSeen(1L, true);
            MailSummaryResponse s2 = summaryWithSeen(2L, false);
            MailSummaryResponse s3 = summaryWithSeen(3L, false);
            when(messageRepository.findSummariesByAccountIdAndThreadId(ACCOUNT_ID, THREAD_ID))
                    .thenReturn(List.of(s1, s2, s3));
            when(messageRepository.findThreadRootMessageIds(eq(ACCOUNT_ID), eq(THREAD_ID), any(Pageable.class)))
                    .thenReturn(List.of("<root@x.cz>"));
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            ThreadResponse result = conversationViewService.getThread(ACCOUNT_ID, THREAD_ID, null);

            assertThat(result.threadId()).isEqualTo(THREAD_ID);
            assertThat(result.rootMessageId()).isEqualTo("<root@x.cz>");
            assertThat(result.participantsTotal()).isEqualTo(3);
            assertThat(result.unreadCount()).isEqualTo(2);
            assertThat(result.messages()).containsExactly(s1, s2, s3);
            // The unscoped read must not go looking for folder roles at all.
            verify(imapFolderService, never()).findFolderNamesByRoleWithoutWaiting(anyLong(), any());
        }

        /**
         * An empty folderRef is a client bug, not a request for the raw thread.
         * Treating it as absent would answer it with the unscoped list — trashed and
         * half-written members included — inside a live conversation. The empty-string
         * NOT IN sentinel classifies it as folder-scoped instead, so it degrades to
         * nothing rather than to everything.
         */
        @Test
        @DisplayName("Empty folderRef yields no members — it is never treated as 'unscoped'")
        void emptyFolderRefIsNotUnscoped() {
            MailSummaryResponse inbox = summary(1L, FOLDER_INBOX, "<a@x.cz>", true);
            MailSummaryResponse trash = summary(2L, FOLDER_TRASH, "<b@x.cz>", true);
            stubThread(inbox, trash);

            ThreadResponse result = conversationViewService.getThread(ACCOUNT_ID, THREAD_ID, "");

            assertThat(result.messages()).isEmpty();
            assertThat(result.participantsTotal()).isZero();
        }

        /**
         * The representative is the newest message of the conversation in the folder in
         * view. When that folder holds two copies of one Message-ID (delivery via two
         * aliases, a re-import), the collapse must keep the newest — otherwise the
         * representative comes back as a member and the client, which drops the parent
         * row by stableId, renders it twice.
         */
        @Test
        @DisplayName("Two copies of one Message-ID in the folder in view -> the newest (the representative) survives")
        void collapseKeepsTheRepresentativeAmongSameFolderCopies() {
            MailSummaryResponse older = summaryAt(1L, FOLDER_INBOX, "<a@x.cz>", LocalDateTime.of(2026, 1, 1, 10, 0));
            MailSummaryResponse representative = summaryAt(2L, FOLDER_INBOX, "<a@x.cz>",
                    LocalDateTime.of(2026, 1, 1, 12, 0));
            stubRolesResolveToNothing();
            stubThread(older, representative);
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            ThreadResponse result = conversationViewService.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX);

            assertThat(result.messages()).containsExactly(representative);
        }

        @Test
        @DisplayName("Same receivedAt -> the higher id wins, matching the listing's id tie-break")
        void collapseTieBreaksOnIdLikeTheListing() {
            LocalDateTime sameInstant = LocalDateTime.of(2026, 1, 1, 10, 0);
            MailSummaryResponse lower = summaryAt(1L, FOLDER_INBOX, "<a@x.cz>", sameInstant);
            MailSummaryResponse higher = summaryAt(9L, FOLDER_INBOX, "<a@x.cz>", sameInstant);
            // Higher id first, so "later wins" alone would keep the wrong one.
            stubRolesResolveToNothing();
            stubThread(higher, lower);
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            ThreadResponse result = conversationViewService.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX);

            assertThat(result.messages()).containsExactly(higher);
        }

        /**
         * The whole point of {@code folderRef}: what comes back is the set the row's
         * badge was counted from, so the client renders it without re-deriving the
         * scope from its own copy of the folder roles.
         */
        @Test
        @DisplayName("Cross-folder view drops the trash, junk, drafts and sent members")
        void crossFolderViewDropsExcludedFolders() {
            stubRole(FolderRole.TRASH, FOLDER_TRASH);
            stubRole(FolderRole.JUNK, "Spam");
            stubRole(FolderRole.DRAFTS, FOLDER_DRAFTS);
            stubRole(FolderRole.SENT, "[Gmail]/Sent Mail");
            MailSummaryResponse inbox = summary(1L, FOLDER_INBOX, "<a@x.cz>", true);
            MailSummaryResponse archive = summary(2L, "Archive", "<b@x.cz>", true);
            MailSummaryResponse trash = summary(3L, FOLDER_TRASH, "<c@x.cz>", true);
            MailSummaryResponse junk = summary(4L, "Spam", "<d@x.cz>", true);
            MailSummaryResponse draft = summary(5L, FOLDER_DRAFTS, "<e@x.cz>", true);
            // The user's own reply. A received conversation is about the mail that
            // arrived, and a folder-scoped bulk action from the inbox could not touch
            // this copy anyway — so it is out, like the trashed and junked ones.
            MailSummaryResponse sent = summary(6L, "[Gmail]/Sent Mail", "<f@x.cz>", true);
            stubThread(inbox, archive, trash, junk, draft, sent);
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            ThreadResponse result = conversationViewService.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX);

            assertThat(result.messages()).containsExactly(inbox, archive);
            assertThat(result.participantsTotal()).isEqualTo(2);
        }

        /**
         * Mirrors {@code COUNT(DISTINCT COALESCE(message_id, stable_id))}: on Gmail the
         * same mail sits in INBOX and All Mail with one Message-ID, and the badge
         * counts it once. The copy in the folder in view is the one kept — it is the
         * representative the client drops and the one its bulk actions reach — and it
         * takes the position of the first copy, so threadPosition order survives.
         */
        @Test
        @DisplayName("Cross-folder view collapses copies of one mail, keeping the copy in the folder in view")
        void crossFolderViewCollapsesDuplicatesPreferringTheViewCopy() {
            MailSummaryResponse allMailCopy = summary(1L, "[Gmail]/All Mail", "<a@x.cz>", true);
            MailSummaryResponse inboxCopy = summary(2L, FOLDER_INBOX, "<a@x.cz>", true);
            MailSummaryResponse newer = summary(3L, FOLDER_INBOX, "<b@x.cz>", true);
            // All Mail copy first, so a naive "first wins" would keep the wrong one.
            stubThread(allMailCopy, inboxCopy, newer);
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            ThreadResponse result = conversationViewService.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX);

            assertThat(result.messages()).containsExactly(inboxCopy, newer);
            assertThat(result.participantsTotal()).isEqualTo(2);
        }

        @Test
        @DisplayName("Members without a Message-ID never merge — they fall back to their own stableId")
        void membersWithoutMessageIdNeverMerge() {
            MailSummaryResponse first = summary(1L, FOLDER_INBOX, null, true);
            MailSummaryResponse second = summary(2L, FOLDER_INBOX, null, true);
            stubThread(first, second);
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            ThreadResponse result = conversationViewService.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX);

            assertThat(result.messages()).containsExactly(first, second);
        }

        @ParameterizedTest(name = "{0} view returns only its own messages")
        @EnumSource(names = {"TRASH", "JUNK", "DRAFTS", "SENT"})
        void folderScopedViewsReturnOnlyTheirOwnMessages(FolderRole role) {
            String folderName = "folder-of-" + role;
            stubAllRoles(role, folderName);
            MailSummaryResponse inbox = summary(1L, FOLDER_INBOX, "<a@x.cz>", true);
            MailSummaryResponse own = summary(2L, folderName, "<b@x.cz>", true);
            stubThread(inbox, own);
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            ThreadResponse result = conversationViewService.getThread(ACCOUNT_ID, THREAD_ID, folderName);

            assertThat(result.messages()).containsExactly(own);
            assertThat(result.participantsTotal()).isEqualTo(1);
        }

        /**
         * Same fail-closed degradation the listing makes: with the roles unresolvable
         * the view falls back to folder-scoped, which can never surface another
         * folder's messages inside this conversation.
         */
        @Test
        @DisplayName("Unresolvable folder roles -> folder-scoped members, not the whole account")
        void unresolvableRolesDegradeToFolderScoped() {
            when(imapFolderService.findFolderNamesByRoleWithoutWaiting(eq(ACCOUNT_ID), any()))
                    .thenThrow(new IllegalStateException("IMAP down"));
            MailSummaryResponse inbox = summary(1L, FOLDER_INBOX, "<a@x.cz>", true);
            MailSummaryResponse sent = summary(2L, "[Gmail]/Sent Mail", "<b@x.cz>", true);
            stubThread(inbox, sent);
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            ThreadResponse result = conversationViewService.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX);

            assertThat(result.messages()).containsExactly(inbox);
        }

        /**
         * unreadCount mirrors the row's, which is folder-scoped in every view: an
         * unread copy sitting in another folder must not make the inbox row report
         * unread mail that marking read from that row cannot clear.
         */
        @Test
        @DisplayName("unreadCount stays folder-scoped even though the member list is cross-folder")
        void unreadCountIsFolderScopedLikeTheRow() {
            MailSummaryResponse inbox = summary(1L, FOLDER_INBOX, "<a@x.cz>", false);
            MailSummaryResponse unreadElsewhere = summary(2L, "Archive", "<b@x.cz>", false);
            stubRolesResolveToNothing();
            stubThread(inbox, unreadElsewhere);
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            ThreadResponse result = conversationViewService.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX);

            // Both members come back...
            assertThat(result.messages()).containsExactly(inbox, unreadElsewhere);
            // ...but only the in-folder one counts as unread.
            assertThat(result.unreadCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when no message in the account belongs to the threadId")
        void shouldThrowWhenThreadHasNoMembers() {
            when(messageRepository.findSummariesByAccountIdAndThreadId(ACCOUNT_ID, THREAD_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> conversationViewService.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX))
                    .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining(THREAD_ID);
        }

        @Test
        @DisplayName("Ownership enforced by accountId — different accountId returns its own (potentially empty) result")
        void shouldScopeQueryByAccountId() {
            // For account 999 the repo returns nothing — the query is correctly
            // narrowed by accountId, so we get a 404 even if account 1 has the
            // thread.
            when(messageRepository.findSummariesByAccountIdAndThreadId(999L, THREAD_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> conversationViewService.getThread(999L, THREAD_ID, null))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        private void stubThread(MailSummaryResponse... members) {
            when(messageRepository.findSummariesByAccountIdAndThreadId(ACCOUNT_ID, THREAD_ID))
                    .thenReturn(List.of(members));
            when(messageRepository.findThreadRootMessageIds(eq(ACCOUNT_ID), eq(THREAD_ID), any(Pageable.class)))
                    .thenReturn(List.of("<root@x.cz>"));
        }

        private void stubRole(FolderRole role, String... folderNames) {
            when(imapFolderService.findFolderNamesByRoleWithoutWaiting(ACCOUNT_ID, role))
                    .thenReturn(Optional.of(List.of(folderNames)));
        }

        /**
         * The account genuinely has no trash, junk, drafts or sent folder — every role
         * resolves, to nothing. Must be spelled out rather than left to the Mockito
         * default: an unstubbed {@code Optional} method returns
         * {@link Optional#empty()}, which the read path reads as "could not resolve"
         * and answers with the folder-scoped fallback. The test would then assert
         * cross-folder behaviour while silently exercising the degraded path.
         */
        private void stubRolesResolveToNothing() {
            for (FolderRole role : List.of(FolderRole.TRASH, FolderRole.JUNK, FolderRole.DRAFTS, FolderRole.SENT)) {
                stubRole(role);
            }
        }

        /**
         * Stubs every excluded role, giving {@code folderName} to {@code role}. Every
         * role must be stubbed: leaving one out makes the lookup throw a strict
         * stubbing mismatch, which the fail-closed branch swallows — the test would
         * then pass through the degraded path instead of the one it names.
         */
        private void stubAllRoles(FolderRole role, String folderName) {
            for (FolderRole candidate : List.of(FolderRole.TRASH, FolderRole.JUNK, FolderRole.DRAFTS,
                    FolderRole.SENT)) {
                stubRole(candidate, candidate == role ? folderName : "unrelated-" + candidate);
            }
        }

        private MailSummaryResponse summary(long id, String folderName, String messageId, boolean seen) {
            return new MailSummaryResponse(id, "s" + id, folderName, "Subject " + id, "from@x.cz", "to@x.cz",
                    LocalDateTime.of(2026, 1, 1, 10, 0), seen, false, false, false, THREAD_ID, messageId, 100L);
        }

        private MailSummaryResponse summaryAt(long id, String folderName, String messageId, LocalDateTime receivedAt) {
            return new MailSummaryResponse(id, "s" + id, folderName, "Subject " + id, "from@x.cz", "to@x.cz",
                    receivedAt, true, false, false, false, THREAD_ID, messageId, 100L);
        }

        private MailSummaryResponse summaryWithSeen(long id, boolean seen) {
            return summary(id, "INBOX", "<m" + id + "@x.cz>", seen);
        }
    }
}
