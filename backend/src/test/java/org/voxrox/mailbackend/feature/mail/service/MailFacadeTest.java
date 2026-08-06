package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.retry.support.RetryTemplate;
import org.voxrox.mailbackend.core.config.RetryConfig;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.*;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.mapper.MessageMapper;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * Unit tests for {@link MailFacade}.
 *
 * Every public method of the facade is covered in a separate @Nested block. All
 * dependencies are mocked — we test only the coordination logic.
 */
@ExtendWith(MockitoExtension.class)
class MailFacadeTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageMapper mapper;
    @Mock
    private MailSyncService mailSyncService;
    @Mock
    private MailContentService mailContentService;
    @Mock
    private ImapActionService imapActionService;
    @Mock
    private ImapFolderService imapFolderService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private AccountService accountService;
    @Mock
    private MessageService messageService;
    @Mock
    private MailDraftService mailDraftService;
    @Mock
    private FolderCountCache folderCountCache;
    @Mock
    private RemoteImageAllowlistService remoteImageAllowlistService;

    /*
     * Real (not mocked) so the wrapped write actually runs: withDbWriteRetry
     * delegates to this template, which executes the callback once on success. Uses
     * the production retry policy so only TransientDataAccessException would be
     * retried — non-transient throws from the wrapped write still propagate on the
     * first attempt, keeping the verify(...)-once assertions valid.
     */
    @Spy
    private RetryTemplate dbWriteRetryTemplate = new RetryConfig().dbWriteRetryTemplate();

    @InjectMocks
    private MailFacade mailFacade;

    private static final String STABLE_ID = "abc123stable";
    private static final Long ACCOUNT_ID = 1L;
    private static final Long MESSAGE_ID = 10L;
    private static final Long UID = 42L;
    private static final String FOLDER_INBOX = "INBOX";
    private static final String FOLDER_TRASH = "[Gmail]/Trash";
    private static final String FOLDER_DRAFTS = "[Gmail]/Drafts";

    private AccountEntity account;
    private MessageEntity entity;

    @BeforeEach
    void setUp() {
        account = new AccountEntity();
        account.setId(ACCOUNT_ID);
        account.setEmail("user@example.com");

        entity = new MessageEntity();
        entity.setId(MESSAGE_ID);
        entity.setStableId(STABLE_ID);
        entity.setAccount(account);
        entity.setFolderName(FOLDER_INBOX);
        entity.setUid(UID);
        entity.setUidValidity(1L);
        entity.setSubject("Test subject");
        entity.setSender("Alice <alice@example.com>");
        entity.setReceivedAt(LocalDateTime.of(2026, 1, 15, 10, 0));
        entity.setContent("Cached content");
    }

    @Nested
    @DisplayName("prepareReply")
    class PrepareReply {

        @Test
        @DisplayName("Returns a reply draft when the message exists")
        void shouldReturnReplyDraftWhenMessageExists() {
            // Setup: message found, content fetched from IMAP
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(mailContentService.getOrFetchQuotableContent(MESSAGE_ID)).thenReturn("<p>Hello</p>");
            MailRequest expectedDraft = dummyMailRequest("Re: Test subject");
            when(mailDraftService.createReplyDraft(entity, "<p>Hello</p>", false)).thenReturn(expectedDraft);

            MailRequest result = mailFacade.prepareReply(STABLE_ID, false);

            assertThat(result).isSameAs(expectedDraft);
            verify(mailDraftService).createReplyDraft(entity, "<p>Hello</p>", false);
        }

        @Test
        @DisplayName("replyAll=true is forwarded into createReplyDraft")
        void shouldPassReplyAllFlag() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(mailContentService.getOrFetchQuotableContent(MESSAGE_ID)).thenReturn("content");
            MailRequest expectedDraft = dummyMailRequest("Re: Test subject");
            when(mailDraftService.createReplyDraft(entity, "content", true)).thenReturn(expectedDraft);

            mailFacade.prepareReply(STABLE_ID, true);

            verify(mailDraftService).createReplyDraft(entity, "content", true);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when the message does not exist")
        void shouldThrowWhenMessageNotFound() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> mailFacade.prepareReply(STABLE_ID, false))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("On content-fetch failure, falls back to cached content")
        void shouldFallbackToCachedContentOnMailOperationException() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(mailContentService.getOrFetchQuotableContent(MESSAGE_ID))
                    .thenThrow(new MailOperationException(ErrorCode.MAIL_CONNECTION_ERROR, "IMAP down"));
            MailRequest expectedDraft = dummyMailRequest("Re: Test subject");
            when(mailDraftService.createReplyDraft(entity, "Cached content", false)).thenReturn(expectedDraft);

            MailRequest result = mailFacade.prepareReply(STABLE_ID, false);

            assertThat(result).isSameAs(expectedDraft);
            // Uses cached content from the entity.
            verify(mailDraftService).createReplyDraft(entity, "Cached content", false);
        }

        @Test
        @DisplayName("On content-fetch failure with null cache, uses an empty string")
        void shouldFallbackToEmptyStringWhenCachedContentIsNull() {
            entity.setContent(null);
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(mailContentService.getOrFetchQuotableContent(MESSAGE_ID))
                    .thenThrow(new ResourceNotFoundException("Content not found"));
            MailRequest expectedDraft = dummyMailRequest("Re: Test subject");
            when(mailDraftService.createReplyDraft(entity, "", false)).thenReturn(expectedDraft);

            MailRequest result = mailFacade.prepareReply(STABLE_ID, false);

            assertThat(result).isSameAs(expectedDraft);
            verify(mailDraftService).createReplyDraft(entity, "", false);
        }
    }

    @Nested
    @DisplayName("prepareForward")
    class PrepareForward {

        @Test
        @DisplayName("Returns a forward draft when the message exists")
        void shouldReturnForwardDraftWhenMessageExists() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(mailContentService.getOrFetchQuotableContent(MESSAGE_ID)).thenReturn("<p>Original</p>");
            MailRequest expectedDraft = dummyMailRequest("Fwd: Test subject");
            when(mailDraftService.createForwardDraft(entity, "<p>Original</p>")).thenReturn(expectedDraft);

            MailRequest result = mailFacade.prepareForward(STABLE_ID);

            assertThat(result).isSameAs(expectedDraft);
            verify(mailDraftService).createForwardDraft(entity, "<p>Original</p>");
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when the message does not exist")
        void shouldThrowWhenMessageNotFound() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> mailFacade.prepareForward(STABLE_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("On IMAP failure, falls back to cached content")
        void shouldFallbackToCachedContentOnError() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(mailContentService.getOrFetchQuotableContent(MESSAGE_ID))
                    .thenThrow(new MailOperationException(ErrorCode.MAIL_CONNECTION_ERROR, "timeout"));
            MailRequest expectedDraft = dummyMailRequest("Fwd: Test subject");
            when(mailDraftService.createForwardDraft(entity, "Cached content")).thenReturn(expectedDraft);

            MailRequest result = mailFacade.prepareForward(STABLE_ID);

            assertThat(result).isSameAs(expectedDraft);
        }
    }

    @Nested
    @DisplayName("getFolders")
    class GetFolders {

        @Test
        @DisplayName("Delegates to imapFolderService and returns the result")
        void shouldDelegateToImapFolderService() {
            List<FolderResponse> folders = List.of(new FolderResponse("Inbox", "INBOX", 5, FolderRole.INBOX),
                    new FolderResponse("Sent", "Sent", 0, FolderRole.SENT));
            when(imapFolderService.getFolders(ACCOUNT_ID)).thenReturn(folders);

            List<FolderResponse> result = mailFacade.getFolders(ACCOUNT_ID);

            assertThat(result).isEqualTo(folders);
            verify(imapFolderService).getFolders(ACCOUNT_ID);
        }
    }

    @Nested
    @DisplayName("getEmails")
    class GetEmails {

        @Test
        @DisplayName("Cache miss -> lazy-fetches/reads server count, returns page with server total")
        void cacheMissFallsThroughToImapAndReturnsServerCount() {
            Page<MailSummaryResponse> localPage = new PageImpl<>(List.of());
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(messageRepository.findSummariesByAccountAndFolder(eq(ACCOUNT_ID), eq(FOLDER_INBOX),
                    any(Pageable.class))).thenReturn(localPage);
            when(folderCountCache.get(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(OptionalLong.empty());
            when(mailSyncService.fetchServerCountAndEnsurePageLocally(account, FOLDER_INBOX, 0, 20)).thenReturn(1790L);

            Page<MailSummaryResponse> result = mailFacade.getEmails(ACCOUNT_ID, FOLDER_INBOX, 0, 20);

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isEqualTo(1790L);
            verify(accountService).getAccountOrThrow(ACCOUNT_ID);
            verify(mailSyncService).syncAndBackfillAsync(account, FOLDER_INBOX, 0);
            verify(mailSyncService).fetchServerCountAndEnsurePageLocally(account, FOLDER_INBOX, 0, 20);
            verify(messageRepository).findSummariesByAccountAndFolder(eq(ACCOUNT_ID), eq(FOLDER_INBOX),
                    any(Pageable.class));
        }

        @Test
        @DisplayName("Cache hit + page fits locally -> skips IMAP roundtrip, returns cached server count")
        void cacheHitWithLocalCoverageSkipsImap() {
            Page<MailSummaryResponse> localPage = new PageImpl<>(List.of());
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(messageRepository.findSummariesByAccountAndFolder(eq(ACCOUNT_ID), eq(FOLDER_INBOX),
                    any(Pageable.class))).thenReturn(localPage);
            // localCount=50 covers page 0 of size 20 (needed=20 <= 50), so the cache
            // short-circuit fires.
            when(messageRepository.countByAccountIdAndFolderName(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(50L);
            when(folderCountCache.get(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(OptionalLong.of(1790L));

            Page<MailSummaryResponse> result = mailFacade.getEmails(ACCOUNT_ID, FOLDER_INBOX, 0, 20);

            assertThat(result.getTotalElements()).isEqualTo(1790L);
            verify(mailSyncService, never()).fetchServerCountAndEnsurePageLocally(any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Server count unavailable (IMAP down) -> falls back to local count, still returns local page")
        void fallsBackToLocalCountOnImapError() {
            Page<MailSummaryResponse> localPage = new PageImpl<>(List.of());
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(messageRepository.findSummariesByAccountAndFolder(eq(ACCOUNT_ID), eq(FOLDER_INBOX),
                    any(Pageable.class))).thenReturn(localPage);
            when(folderCountCache.get(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(OptionalLong.empty());
            when(mailSyncService.fetchServerCountAndEnsurePageLocally(account, FOLDER_INBOX, 0, 20))
                    .thenThrow(new RuntimeException("IMAP unreachable"));
            when(messageRepository.countByAccountIdAndFolderName(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(42L);

            Page<MailSummaryResponse> result = mailFacade.getEmails(ACCOUNT_ID, FOLDER_INBOX, 0, 20);

            assertThat(result.getTotalElements()).isEqualTo(42L);
        }
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

            Page<ConversationSummaryResponse> page = mailFacade.getConversations(ACCOUNT_ID, FOLDER_INBOX, 0, 50);

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

            Page<ConversationSummaryResponse> page = mailFacade.getConversations(ACCOUNT_ID, FOLDER_INBOX, 0, 50);

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

            Page<ConversationSummaryResponse> page = mailFacade.getConversations(ACCOUNT_ID, FOLDER_INBOX, 0, 50);

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

            Page<ConversationSummaryResponse> page = mailFacade.getConversations(ACCOUNT_ID, FOLDER_INBOX, 0, 50);

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

            Page<ConversationSummaryResponse> page = mailFacade.getConversations(ACCOUNT_ID, FOLDER_INBOX, 0, 50);

            assertThat(page.getContent().get(0).messageCount()).isEqualTo(1);
            verify(messageRepository, never()).countCrossFolderConversationSizes(anyLong(), any(), any());
        }

        @Test
        @DisplayName("Trash, junk and drafts folders are all excluded from the cross-folder counts")
        void excludesTrashJunkAndDraftsFromCrossFolderCounts() {
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            // Every folder claiming a role is excluded, not just the first — role
            // detection is not unique.
            stubRoleFolders(FolderRole.TRASH, FOLDER_TRASH, "Recycle bin");
            stubRoleFolders(FolderRole.JUNK, "Spam");
            stubRoleFolders(FolderRole.DRAFTS, FOLDER_DRAFTS);
            when(messageRepository.findConversationRepresentatives(ACCOUNT_ID, FOLDER_INBOX, 50, 0L))
                    .thenReturn(List.<Object[]>of(new Object[]{2L, 1, 0}));
            when(messageRepository.countConversationsByAccountAndFolder(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(1L);
            when(messageRepository.findSummariesByIds(List.of(2L))).thenReturn(List.of(summaryWithThread(2L, "t-A")));
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            mailFacade.getConversations(ACCOUNT_ID, FOLDER_INBOX, 0, 50);

            verify(messageRepository).countCrossFolderConversationSizes(ACCOUNT_ID, List.of("t-A"),
                    List.of("", FOLDER_TRASH, "Recycle bin", "Spam", FOLDER_DRAFTS));
        }

        /**
         * The roles are resolved through {@link ImapFolderService}, whose live-IMAP
         * fallback covers a folder the sync has not recorded yet. Reading
         * {@code folder_sync_state} directly would classify the first visit to the
         * trash on a fresh account as a regular folder and serve it cross-folder
         * counts.
         */
        @ParameterizedTest(name = "{0} view stays folder-scoped")
        @EnumSource(names = {"TRASH", "JUNK", "DRAFTS"})
        void folderScopedViews(FolderRole role) {
            String folderName = "folder-of-" + role;
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            // All three roles, not just the one under test: an unstubbed lookup throws
            // a strict stubbing mismatch that the fail-closed branch swallows, and the
            // test would prove the degraded path instead of the role check.
            for (FolderRole candidate : List.of(FolderRole.TRASH, FolderRole.JUNK, FolderRole.DRAFTS)) {
                stubRoleFolders(candidate, candidate == role ? folderName : "unrelated-" + candidate);
            }
            when(messageRepository.findConversationRepresentatives(ACCOUNT_ID, folderName, 50, 0L))
                    .thenReturn(List.of());
            when(messageRepository.countConversationsByAccountAndFolder(ACCOUNT_ID, folderName)).thenReturn(0L);

            Page<ConversationSummaryResponse> page = mailFacade.getConversations(ACCOUNT_ID, folderName, 0, 50);

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

            Page<ConversationSummaryResponse> page = mailFacade.getConversations(ACCOUNT_ID, FOLDER_INBOX, 0, 50);

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

            Page<ConversationSummaryResponse> page = mailFacade.getConversations(ACCOUNT_ID, FOLDER_INBOX, 0, 50);

            assertThat(page.getContent()).isEmpty();
            verify(messageRepository, never()).countCrossFolderConversationSizes(anyLong(), any(), any());
        }

        /**
         * Every excluded role resolves, to nothing — the account has no trash, junk or
         * drafts. Spelled out rather than left to the Mockito default: an unstubbed
         * {@code Optional} method yields {@link Optional#empty()}, which the read path
         * reads as "could not resolve" and answers folder-scoped, so the test would
         * assert cross-folder behaviour while exercising the degraded path.
         */
        private void stubRolesResolveToNothing() {
            for (FolderRole role : List.of(FolderRole.TRASH, FolderRole.JUNK, FolderRole.DRAFTS)) {
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
    @DisplayName("searchEmails")
    class SearchEmails {

        @Test
        @DisplayName("Delegates to messageService.search and applies display fallbacks via the mapper")
        void shouldDelegateToMessageServiceAndMap() {
            MailSummaryResponse raw = new MailSummaryResponse(1L, "s1", "INBOX", null, null, "c@d.com",
                    LocalDateTime.now(), false, false, false, false, null, null, 1L);
            when(messageService.search(ACCOUNT_ID, "query", 0, 20)).thenReturn(new PageImpl<>(List.of(raw)));

            MailSummaryResponse display = new MailSummaryResponse(1L, "s1", "INBOX", "(no subject)", "(unknown sender)",
                    "c@d.com", raw.receivedAt(), false, false, false, false, null, null, 1L);
            when(mapper.withDisplayFallbacks(raw)).thenReturn(display);

            Page<MailSummaryResponse> result = mailFacade.searchEmails(ACCOUNT_ID, "query", 0, 20);

            assertThat(result.getContent()).containsExactly(display);
            verify(messageService).search(ACCOUNT_ID, "query", 0, 20);
        }
    }

    @Nested
    @DisplayName("getEmailDetailByStableId")
    class GetEmailDetail {

        @Test
        @DisplayName("Returns the detail with freshly fetched content")
        void shouldReturnDetailWithFreshContent() {
            when(messageRepository.findByStableIdWithAttachments(STABLE_ID)).thenReturn(Optional.of(entity));
            when(mailContentService.getOrFetchMessageContent(MESSAGE_ID)).thenReturn("<p>Fresh</p>");
            MailDetailResponse expectedDetail = dummyDetailResponse(null);
            when(mapper.toDto(entity, "<p>Fresh</p>")).thenReturn(expectedDetail);

            MailDetailResponse result = mailFacade.getEmailDetailByStableId(STABLE_ID);

            assertThat(result).isSameAs(expectedDetail);
            verify(mapper).toDto(entity, "<p>Fresh</p>");
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when the message does not exist")
        void shouldThrowWhenMessageNotFound() {
            when(messageRepository.findByStableIdWithAttachments(STABLE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> mailFacade.getEmailDetailByStableId(STABLE_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("On MailOperationException, returns cached content with contentError")
        void shouldReturnCachedContentOnMailOperationException() {
            when(messageRepository.findByStableIdWithAttachments(STABLE_ID)).thenReturn(Optional.of(entity));
            when(mailContentService.getOrFetchMessageContent(MESSAGE_ID))
                    .thenThrow(new MailOperationException(ErrorCode.MAIL_CONNECTION_ERROR, "IMAP down"));
            MailDetailResponse cachedDetail = dummyDetailResponse("IMAP down");
            when(mapper.toDto(entity, "Cached content", "IMAP down")).thenReturn(cachedDetail);

            MailDetailResponse result = mailFacade.getEmailDetailByStableId(STABLE_ID);

            assertThat(result).isSameAs(cachedDetail);
            verify(mapper).toDto(entity, "Cached content", "IMAP down");
        }

        @Test
        @DisplayName("On ResourceNotFoundException from the content service, returns cached content")
        void shouldReturnCachedContentOnResourceNotFoundException() {
            when(messageRepository.findByStableIdWithAttachments(STABLE_ID)).thenReturn(Optional.of(entity));
            when(mailContentService.getOrFetchMessageContent(MESSAGE_ID))
                    .thenThrow(new ResourceNotFoundException("Content not found"));
            MailDetailResponse cachedDetail = dummyDetailResponse("Content not found");
            when(mapper.toDto(entity, "Cached content", "Content not found")).thenReturn(cachedDetail);

            MailDetailResponse result = mailFacade.getEmailDetailByStableId(STABLE_ID);

            assertThat(result).isSameAs(cachedDetail);
        }
    }

    @Nested
    @DisplayName("moveToTrash")
    class MoveToTrash {

        @Test
        @DisplayName("Moves the message to trash — IMAP action + local delete")
        void shouldMoveToTrashSuccessfully() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.TRASH)).thenReturn(FOLDER_TRASH);

            mailFacade.moveToTrash(STABLE_ID);

            verify(imapActionService).moveOnServerAsync(ACCOUNT_ID, FOLDER_INBOX, FOLDER_TRASH, UID);
            verify(messageService).deleteByStableId(STABLE_ID);
        }

        @Test
        @DisplayName("A message already in the trash is purged — server expunge instead of a trash-to-trash move")
        void shouldPurgeWhenMessageAlreadyInTrash() {
            entity.setFolderName(FOLDER_TRASH);
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.TRASH)).thenReturn(FOLDER_TRASH);

            mailFacade.moveToTrash(STABLE_ID);

            verify(messageService).deleteByStableId(STABLE_ID);
            verify(imapActionService).hardDeleteAsync(ACCOUNT_ID, FOLDER_TRASH, UID);
            // A trash-to-trash move is a server no-op that resurrects the row on
            // the next sync — it must never be dispatched.
            verify(imapActionService, never()).moveOnServerAsync(anyLong(), anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("Purge dispatches the server expunge only after the local delete succeeds")
        void shouldNotDispatchPurgeWhenLocalDeleteFails() {
            entity.setFolderName(FOLDER_TRASH);
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.TRASH)).thenReturn(FOLDER_TRASH);
            doThrow(new DataIntegrityViolationException("constraint")).when(messageService).deleteByStableId(STABLE_ID);

            assertThatThrownBy(() -> mailFacade.moveToTrash(STABLE_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);

            verify(imapActionService, never()).hardDeleteAsync(anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when the message does not exist")
        void shouldThrowWhenMessageNotFound() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> mailFacade.moveToTrash(STABLE_ID)).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Throws MailOperationException when the trash folder is missing")
        void shouldThrowWhenTrashFolderNotFound() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.TRASH)).thenThrow(
                    new MailOperationException(ErrorCode.FOLDER_ROLE_NOT_FOUND, "No TRASH folder for account"));

            assertThatThrownBy(() -> mailFacade.moveToTrash(STABLE_ID)).isInstanceOf(MailOperationException.class)
                    .extracting(e -> ((MailOperationException) e).getCode()).isEqualTo(ErrorCode.FOLDER_ROLE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("moveToFolder")
    class MoveToFolder {

        private static final String TARGET = "[Gmail]/Archive";

        @Test
        @DisplayName("Moves the message — IMAP action + local delete + audit")
        void shouldMoveSuccessfully() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(imapFolderService.getFolders(ACCOUNT_ID))
                    .thenReturn(List.of(new FolderResponse("Inbox", FOLDER_INBOX, 0, FolderRole.INBOX),
                            new FolderResponse("Archive", TARGET, 0, FolderRole.ARCHIVE)));

            mailFacade.moveToFolder(STABLE_ID, TARGET);

            verify(imapActionService).moveOnServerAsync(ACCOUNT_ID, FOLDER_INBOX, TARGET, UID);
            verify(messageService).deleteByStableId(STABLE_ID);
        }

        @Test
        @DisplayName("Throws ValidationException when target is blank")
        void shouldThrowOnBlankTarget() {
            assertThatThrownBy(() -> mailFacade.moveToFolder(STABLE_ID, "  ")).isInstanceOf(ValidationException.class);
            verifyNoInteractions(imapActionService, messageService);
        }

        @Test
        @DisplayName("Throws ValidationException when target is null")
        void shouldThrowOnNullTarget() {
            assertThatThrownBy(() -> mailFacade.moveToFolder(STABLE_ID, null)).isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when the message does not exist")
        void shouldThrowWhenMessageNotFound() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> mailFacade.moveToFolder(STABLE_ID, TARGET))
                    .isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(imapActionService);
        }

        @Test
        @DisplayName("Throws ValidationException when source == target")
        void shouldThrowWhenSourceEqualsTarget() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> mailFacade.moveToFolder(STABLE_ID, FOLDER_INBOX))
                    .isInstanceOf(ValidationException.class);
            verifyNoInteractions(imapActionService);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when the target folder is missing on the server")
        void shouldThrowWhenTargetFolderNotFound() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(imapFolderService.getFolders(ACCOUNT_ID))
                    .thenReturn(List.of(new FolderResponse("Inbox", FOLDER_INBOX, 0, FolderRole.INBOX)));

            assertThatThrownBy(() -> mailFacade.moveToFolder(STABLE_ID, TARGET))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(imapActionService, never()).moveOnServerAsync(anyLong(), anyString(), anyString(), anyLong());
            verify(messageService, never()).deleteByStableId(anyString());
        }
    }

    @Nested
    @DisplayName("updateMessageFlag")
    class UpdateMessageFlag {

        @Test
        @DisplayName("Sets the SEEN flag in the DB and pushes it to IMAP")
        void shouldUpdateSeenFlag() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));

            mailFacade.updateMessageFlag(STABLE_ID, MessageFlag.SEEN, true);

            verify(messageRepository).updateSeenStatus(STABLE_ID, true);
            verify(imapActionService).updateFlagsOnServerAsync(ACCOUNT_ID, FOLDER_INBOX, UID, MessageFlag.SEEN, true);
        }

        @Test
        @DisplayName("Sets the FLAGGED flag in the DB and pushes it to IMAP")
        void shouldUpdateFlaggedFlag() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));

            mailFacade.updateMessageFlag(STABLE_ID, MessageFlag.FLAGGED, true);

            verify(messageRepository).updateFlaggedStatus(STABLE_ID, true);
            verify(imapActionService).updateFlagsOnServerAsync(ACCOUNT_ID, FOLDER_INBOX, UID, MessageFlag.FLAGGED,
                    true);
        }

        @Test
        @DisplayName("Sets the ANSWERED flag in the DB and pushes it to IMAP")
        void shouldUpdateAnsweredFlag() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));

            mailFacade.updateMessageFlag(STABLE_ID, MessageFlag.ANSWERED, false);

            verify(messageRepository).updateAnsweredStatus(STABLE_ID, false);
            verify(imapActionService).updateFlagsOnServerAsync(ACCOUNT_ID, FOLDER_INBOX, UID, MessageFlag.ANSWERED,
                    false);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when the message does not exist")
        void shouldThrowWhenMessageNotFound() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> mailFacade.updateMessageFlag(STABLE_ID, MessageFlag.SEEN, true))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getAttachment")
    class GetAttachment {

        @Test
        @DisplayName("Delegates to attachmentService and returns the stream")
        void shouldDelegateToAttachmentService() {
            InputStream expectedStream = new ByteArrayInputStream(
                    "data".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            when(attachmentService.getAttachmentStreamByStableId(STABLE_ID, "1.2")).thenReturn(expectedStream);

            InputStream result = mailFacade.getAttachment(STABLE_ID, "1.2");

            assertThat(result).isSameAs(expectedStream);
            verify(attachmentService).getAttachmentStreamByStableId(STABLE_ID, "1.2");
        }
    }

    @Nested
    @DisplayName("getMessageContentOnly")
    class GetMessageContentOnly {

        @Test
        @DisplayName("Returns the message content with no fallback")
        void shouldReturnContentResponse() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(mailContentService.getOrFetchMessageContent(MESSAGE_ID)).thenReturn("<p>Body</p>");

            MailContentResponse result = mailFacade.getMessageContentOnly(STABLE_ID);

            assertThat(result.content()).isEqualTo("<p>Body</p>");
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when the message does not exist")
        void shouldThrowWhenMessageNotFound() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> mailFacade.getMessageContentOnly(STABLE_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Propagates MailOperationException — no fallback")
        void shouldPropagateMailOperationException() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(mailContentService.getOrFetchMessageContent(MESSAGE_ID))
                    .thenThrow(new MailOperationException(ErrorCode.MAIL_CONNECTION_ERROR, "IMAP error"));

            assertThatThrownBy(() -> mailFacade.getMessageContentOnly(STABLE_ID))
                    .isInstanceOf(MailOperationException.class);
        }
    }

    @Nested
    @DisplayName("listDrafts")
    class ListDrafts {

        @Test
        @DisplayName("Resolves the Drafts folder by role and delegates to getEmails")
        void shouldFindDraftsFolderAndDelegateToGetEmails() {
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS))
                    .thenReturn(FOLDER_DRAFTS);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            Page<MailSummaryResponse> localPage = new PageImpl<>(List.of());
            when(messageRepository.findSummariesByAccountAndFolder(eq(ACCOUNT_ID), eq(FOLDER_DRAFTS),
                    any(Pageable.class))).thenReturn(localPage);
            when(folderCountCache.get(ACCOUNT_ID, FOLDER_DRAFTS)).thenReturn(OptionalLong.empty());
            when(mailSyncService.fetchServerCountAndEnsurePageLocally(account, FOLDER_DRAFTS, 0, 20)).thenReturn(7L);

            Page<MailSummaryResponse> result = mailFacade.listDrafts(ACCOUNT_ID, 0, 20);

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isEqualTo(7L);
            verify(imapFolderService).findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS);
        }

        @Test
        @DisplayName("Throws MailOperationException when the Drafts folder is missing")
        void shouldThrowWhenDraftsFolderNotFound() {
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenThrow(
                    new MailOperationException(ErrorCode.FOLDER_ROLE_NOT_FOUND, "No DRAFTS folder for account"));

            assertThatThrownBy(() -> mailFacade.listDrafts(ACCOUNT_ID, 0, 20))
                    .isInstanceOf(MailOperationException.class).extracting(e -> ((MailOperationException) e).getCode())
                    .isEqualTo(ErrorCode.FOLDER_ROLE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("verifyDraftForSend")
    class VerifyDraftForSend {

        @BeforeEach
        void setUpDraftEntity() {
            entity.setFolderName(FOLDER_DRAFTS);
        }

        @Test
        @DisplayName("Returns the entity when the draft exists, belongs to the account, and is in the right folder")
        void shouldReturnEntityWhenValid() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS))
                    .thenReturn(FOLDER_DRAFTS);

            MessageEntity result = mailFacade.verifyDraftForSend(ACCOUNT_ID, STABLE_ID);

            assertThat(result).isSameAs(entity);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when the draft does not exist")
        void shouldThrowWhenDraftNotFound() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> mailFacade.verifyDraftForSend(ACCOUNT_ID, STABLE_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when the draft belongs to a different account (security-driven 404)")
        void shouldThrowWhenDraftBelongsToDifferentAccount() {
            // Message belongs to account 1, but we are calling with accountId 999.
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> mailFacade.verifyDraftForSend(999L, STABLE_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Throws MailOperationException when the Drafts folder is missing on the server")
        void shouldThrowWhenDraftsFolderRoleNotFound() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenThrow(
                    new MailOperationException(ErrorCode.FOLDER_ROLE_NOT_FOUND, "No DRAFTS folder for account"));

            assertThatThrownBy(() -> mailFacade.verifyDraftForSend(ACCOUNT_ID, STABLE_ID))
                    .isInstanceOf(MailOperationException.class).extracting(e -> ((MailOperationException) e).getCode())
                    .isEqualTo(ErrorCode.FOLDER_ROLE_NOT_FOUND);
        }

        @Test
        @DisplayName("Throws ValidationException when the message is not in the Drafts folder")
        void shouldThrowWhenMessageNotInDraftsFolder() {
            entity.setFolderName(FOLDER_INBOX); // message lives in INBOX, not Drafts
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS))
                    .thenReturn(FOLDER_DRAFTS);

            assertThatThrownBy(() -> mailFacade.verifyDraftForSend(ACCOUNT_ID, STABLE_ID))
                    .isInstanceOf(ValidationException.class);
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
            when(messageRepository.findThreadRootMessageIds(eq(ACCOUNT_ID), eq(THREAD_ID),
                    any(org.springframework.data.domain.Pageable.class))).thenReturn(List.of("<root@x.cz>"));
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            ThreadResponse result = mailFacade.getThread(ACCOUNT_ID, THREAD_ID, null);

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

            ThreadResponse result = mailFacade.getThread(ACCOUNT_ID, THREAD_ID, "");

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

            ThreadResponse result = mailFacade.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX);

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

            ThreadResponse result = mailFacade.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX);

            assertThat(result.messages()).containsExactly(higher);
        }

        /**
         * The whole point of {@code folderRef}: what comes back is the set the row's
         * badge was counted from, so the client renders it without re-deriving the
         * scope from its own copy of the folder roles.
         */
        @Test
        @DisplayName("Cross-folder view drops the trash, junk and drafts members")
        void crossFolderViewDropsExcludedFolders() {
            stubRole(FolderRole.TRASH, FOLDER_TRASH);
            stubRole(FolderRole.JUNK, "Spam");
            stubRole(FolderRole.DRAFTS, FOLDER_DRAFTS);
            MailSummaryResponse inbox = summary(1L, FOLDER_INBOX, "<a@x.cz>", true);
            MailSummaryResponse sent = summary(2L, "[Gmail]/Sent Mail", "<b@x.cz>", true);
            MailSummaryResponse trash = summary(3L, FOLDER_TRASH, "<c@x.cz>", true);
            MailSummaryResponse junk = summary(4L, "Spam", "<d@x.cz>", true);
            MailSummaryResponse draft = summary(5L, FOLDER_DRAFTS, "<e@x.cz>", true);
            stubThread(inbox, sent, trash, junk, draft);
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            ThreadResponse result = mailFacade.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX);

            assertThat(result.messages()).containsExactly(inbox, sent);
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

            ThreadResponse result = mailFacade.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX);

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

            ThreadResponse result = mailFacade.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX);

            assertThat(result.messages()).containsExactly(first, second);
        }

        @ParameterizedTest(name = "{0} view returns only its own messages")
        @EnumSource(names = {"TRASH", "JUNK", "DRAFTS"})
        void folderScopedViewsReturnOnlyTheirOwnMessages(FolderRole role) {
            String folderName = "folder-of-" + role;
            stubAllRoles(role, folderName);
            MailSummaryResponse inbox = summary(1L, FOLDER_INBOX, "<a@x.cz>", true);
            MailSummaryResponse own = summary(2L, folderName, "<b@x.cz>", true);
            stubThread(inbox, own);
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            ThreadResponse result = mailFacade.getThread(ACCOUNT_ID, THREAD_ID, folderName);

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

            ThreadResponse result = mailFacade.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX);

            assertThat(result.messages()).containsExactly(inbox);
        }

        /**
         * unreadCount mirrors the row's, which is folder-scoped in every view: an
         * unread reply sitting in Sent must not make the inbox row report unread mail
         * that marking read from that row cannot clear.
         */
        @Test
        @DisplayName("unreadCount stays folder-scoped even though the member list is cross-folder")
        void unreadCountIsFolderScopedLikeTheRow() {
            MailSummaryResponse inbox = summary(1L, FOLDER_INBOX, "<a@x.cz>", false);
            MailSummaryResponse unreadElsewhere = summary(2L, "[Gmail]/Sent Mail", "<b@x.cz>", false);
            stubRolesResolveToNothing();
            stubThread(inbox, unreadElsewhere);
            when(mapper.withDisplayFallbacks(any(MailSummaryResponse.class))).thenAnswer(inv -> inv.getArgument(0));

            ThreadResponse result = mailFacade.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX);

            // Both members come back...
            assertThat(result.messages()).containsExactly(inbox, unreadElsewhere);
            // ...but only the in-folder one counts as unread.
            assertThat(result.unreadCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when no message in the account belongs to the threadId")
        void shouldThrowWhenThreadHasNoMembers() {
            when(messageRepository.findSummariesByAccountIdAndThreadId(ACCOUNT_ID, THREAD_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> mailFacade.getThread(ACCOUNT_ID, THREAD_ID, FOLDER_INBOX))
                    .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining(THREAD_ID);
        }

        @Test
        @DisplayName("Ownership enforced by accountId — different accountId returns its own (potentially empty) result")
        void shouldScopeQueryByAccountId() {
            // For account 999 the repo returns nothing — the query is correctly
            // narrowed by accountId, so we get a 404 even if account 1 has the
            // thread.
            when(messageRepository.findSummariesByAccountIdAndThreadId(999L, THREAD_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> mailFacade.getThread(999L, THREAD_ID, null))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        private void stubThread(MailSummaryResponse... members) {
            when(messageRepository.findSummariesByAccountIdAndThreadId(ACCOUNT_ID, THREAD_ID))
                    .thenReturn(List.of(members));
            when(messageRepository.findThreadRootMessageIds(eq(ACCOUNT_ID), eq(THREAD_ID),
                    any(org.springframework.data.domain.Pageable.class))).thenReturn(List.of("<root@x.cz>"));
        }

        private void stubRole(FolderRole role, String... folderNames) {
            when(imapFolderService.findFolderNamesByRoleWithoutWaiting(ACCOUNT_ID, role))
                    .thenReturn(Optional.of(List.of(folderNames)));
        }

        /**
         * The account genuinely has no trash, junk or drafts folder — every role
         * resolves, to nothing. Must be spelled out rather than left to the Mockito
         * default: an unstubbed {@code Optional} method returns
         * {@link Optional#empty()}, which the read path reads as "could not resolve"
         * and answers with the folder-scoped fallback. The test would then assert
         * cross-folder behaviour while silently exercising the degraded path.
         */
        private void stubRolesResolveToNothing() {
            for (FolderRole role : List.of(FolderRole.TRASH, FolderRole.JUNK, FolderRole.DRAFTS)) {
                stubRole(role);
            }
        }

        /**
         * Stubs all three excluded roles, giving {@code folderName} to {@code role}.
         * Every role must be stubbed: leaving one out makes the lookup throw a strict
         * stubbing mismatch, which the fail-closed branch swallows — the test would
         * then pass through the degraded path instead of the one it names.
         */
        private void stubAllRoles(FolderRole role, String folderName) {
            for (FolderRole candidate : List.of(FolderRole.TRASH, FolderRole.JUNK, FolderRole.DRAFTS)) {
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

    /**
     * Exercises the {@code withDbWriteRetry} wrapper directly through both wrapped
     * paths: a transient SQLITE_BUSY (modelled as
     * {@link CannotAcquireLockException}, the exact type the SQLiteDialect→Spring
     * chain produces) must be retried until it succeeds, while a non-transient
     * failure must propagate on the first attempt. The {@code @Spy} real
     * {@link RetryTemplate} runs the production policy, so these assertions reflect
     * the actual runtime behaviour, not a stub.
     */
    @Nested
    @DisplayName("withDbWriteRetry (transient SQLite write contention)")
    class DbWriteRetry {

        @Test
        @DisplayName("Transient lock failure on the local delete is retried, then succeeds")
        void retriesTransientLockFailureOnDelete() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.TRASH)).thenReturn(FOLDER_TRASH);
            // First attempt loses the SQLite write lock; the retry wins.
            doThrow(new CannotAcquireLockException("SQLITE_BUSY")).doNothing().when(messageService)
                    .deleteByStableId(STABLE_ID);

            mailFacade.moveToTrash(STABLE_ID);

            verify(messageService, times(2)).deleteByStableId(STABLE_ID);
            // The async provider move is dispatched only once the local write finally
            // lands.
            verify(imapActionService).moveOnServerAsync(ACCOUNT_ID, FOLDER_INBOX, FOLDER_TRASH, UID);
        }

        @Test
        @DisplayName("Non-transient failure on the local delete is not retried and propagates")
        void doesNotRetryNonTransientFailureOnDelete() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.TRASH)).thenReturn(FOLDER_TRASH);
            doThrow(new DataIntegrityViolationException("constraint")).when(messageService).deleteByStableId(STABLE_ID);

            assertThatThrownBy(() -> mailFacade.moveToTrash(STABLE_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);

            verify(messageService, times(1)).deleteByStableId(STABLE_ID);
            // The local write never succeeded, so the provider action must not fire.
            verify(imapActionService, never()).moveOnServerAsync(anyLong(), anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("Transient lock failure on a flag update is retried, then succeeds")
        void retriesTransientLockFailureOnFlagUpdate() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            doThrow(new CannotAcquireLockException("SQLITE_BUSY")).doNothing().when(messageRepository)
                    .updateSeenStatus(STABLE_ID, true);

            mailFacade.updateMessageFlag(STABLE_ID, MessageFlag.SEEN, true);

            verify(messageRepository, times(2)).updateSeenStatus(STABLE_ID, true);
            verify(imapActionService).updateFlagsOnServerAsync(ACCOUNT_ID, FOLDER_INBOX, UID, MessageFlag.SEEN, true);
        }
    }

    private MailRequest dummyMailRequest(String subject) {
        return new MailRequest("to@example.com", "", "", subject, "body", null, null, null);
    }

    private MailDetailResponse dummyDetailResponse(String contentError) {
        return new MailDetailResponse(STABLE_ID, UID, FOLDER_INBOX, "Test subject", "Alice <alice@example.com>", null,
                null, null, "body content", LocalDateTime.of(2026, 1, 15, 10, 0), false, false, false, null, null, null,
                false, List.of(), contentError, null);
    }
}
