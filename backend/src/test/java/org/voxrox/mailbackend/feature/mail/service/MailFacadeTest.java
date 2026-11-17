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
import org.voxrox.mailbackend.core.dto.PagedResponse;
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
    @Mock
    private ConversationViewService conversationViewService;

    /*
     * Real (not mocked) so the wrapped write actually runs: withDbWriteRetry
     * delegates to this template, which executes the callback once on success. Uses
     * the production retry policy so only TransientDataAccessException would be
     * retried — non-transient throws from the wrapped write still propagate on the
     * first attempt, keeping the verify(...)-once assertions valid.
     */
    @SuppressWarnings("UnusedVariable") // injected, never read directly; see above
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
    private static final String FOLDER_JUNK = "[Gmail]/Spam";
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

        // The shipped listing depth; lenient because most tests never list a folder.
        lenient().when(mailSyncService.listingDepth()).thenReturn(10_000);
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
                    .thenThrow(new ResourceNotFoundException("Content not found", "error.message.notFound"));
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
        @DisplayName("Returns imapFolderService's folders in display order")
        void shouldReturnFoldersInDisplayOrder() {
            FolderResponse inbox = new FolderResponse("Inbox", "INBOX", 5, FolderRole.INBOX);
            FolderResponse sent = new FolderResponse("Sent", "Sent", 0, FolderRole.SENT);
            FolderResponse projects = new FolderResponse("Projects", "Projects", 0, FolderRole.USER);
            when(imapFolderService.getFolders(ACCOUNT_ID)).thenReturn(List.of(projects, sent, inbox));

            List<FolderResponse> result = mailFacade.getFolders(ACCOUNT_ID);

            assertThat(result).containsExactly(inbox, sent, projects);
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

        /**
         * B1-9. The count says how much mail the folder holds; the pager ends where the
         * lazy fetch stops extending the mirror, so a last-page click no longer asks
         * for every message down to the oldest.
         */
        @Test
        @DisplayName("A folder larger than the listing depth keeps the server's count and pages only that deep")
        void folderLargerThanTheDepthPagesOnlyThatDeep() {
            when(mailSyncService.listingDepth()).thenReturn(300);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(messageRepository.findSummariesByAccountAndFolder(eq(ACCOUNT_ID), eq(FOLDER_INBOX),
                    any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
            when(folderCountCache.get(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(OptionalLong.empty());
            when(mailSyncService.fetchServerCountAndEnsurePageLocally(account, FOLDER_INBOX, 14, 20)).thenReturn(1790L);

            Page<MailSummaryResponse> result = mailFacade.getEmails(ACCOUNT_ID, FOLDER_INBOX, 14, 20);

            assertThat(result.getTotalElements()).isEqualTo(1790L);
            assertThat(result.getTotalPages()).isEqualTo(15);
            assertThat(result.isLast()).isTrue();
            assertThat(PagedResponse.from(result).olderOnServer()).isTrue();
        }

        @Test
        @DisplayName("A folder within the listing depth pages to its end and has nothing only on the server")
        void folderWithinTheDepthPagesToItsEnd() {
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(messageRepository.findSummariesByAccountAndFolder(eq(ACCOUNT_ID), eq(FOLDER_INBOX),
                    any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
            when(folderCountCache.get(ACCOUNT_ID, FOLDER_INBOX)).thenReturn(OptionalLong.empty());
            when(mailSyncService.fetchServerCountAndEnsurePageLocally(account, FOLDER_INBOX, 0, 20)).thenReturn(1790L);

            Page<MailSummaryResponse> result = mailFacade.getEmails(ACCOUNT_ID, FOLDER_INBOX, 0, 20);

            assertThat(result.getTotalPages()).isEqualTo(90);
            assertThat(PagedResponse.from(result).olderOnServer()).isFalse();
        }
    }

    /**
     * The conversation view lives in {@link ConversationViewService}, which has its
     * own tests; the facade only has to hand the arguments over and the answer
     * back.
     */
    @Nested
    @DisplayName("getConversations / getThread (delegated to ConversationViewService)")
    class ConversationView {

        @Test
        @DisplayName("getConversations returns the service's page for the same account, folder and page")
        void getConversationsDelegates() {
            Page<ConversationSummaryResponse> page = new PageImpl<>(List.of());
            when(conversationViewService.getConversations(ACCOUNT_ID, FOLDER_INBOX, 2, 50)).thenReturn(page);

            assertThat(mailFacade.getConversations(ACCOUNT_ID, FOLDER_INBOX, 2, 50)).isSameAs(page);
        }

        @Test
        @DisplayName("getThread returns the service's thread, the folderRef passed through")
        void getThreadDelegates() {
            ThreadResponse thread = new ThreadResponse("thread-1", null, 0, 0, List.of());
            when(conversationViewService.getThread(ACCOUNT_ID, "thread-1", FOLDER_INBOX)).thenReturn(thread);

            assertThat(mailFacade.getThread(ACCOUNT_ID, "thread-1", FOLDER_INBOX)).isSameAs(thread);
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
        @DisplayName("Returns the mapped detail without fetching the body")
        void shouldReturnDetailWithoutFetchingBody() {
            when(messageRepository.findByStableIdWithAttachments(STABLE_ID)).thenReturn(Optional.of(entity));
            MailDetailResponse expectedDetail = dummyDetailResponse();
            when(mapper.toDto(entity)).thenReturn(expectedDetail);

            MailDetailResponse result = mailFacade.getEmailDetailByStableId(STABLE_ID);

            assertThat(result).isSameAs(expectedDetail);
            // The body is the content endpoint's: a client that asks for both at
            // once must not send one uncached body to the mail server twice.
            verifyNoInteractions(mailContentService);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when the message does not exist")
        void shouldThrowWhenMessageNotFound() {
            when(messageRepository.findByStableIdWithAttachments(STABLE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> mailFacade.getEmailDetailByStableId(STABLE_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
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
                    .isInstanceOf(ResourceNotFoundException.class)
                    .extracting(e -> ((ResourceNotFoundException) e).getMessageKey())
                    .isEqualTo("error.message.notFound");
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
                    .isInstanceOf(ResourceNotFoundException.class)
                    .extracting(e -> ((ResourceNotFoundException) e).getMessageKey())
                    .isEqualTo("error.folder.notFoundOnServer");
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

        @Test
        @DisplayName("A trusted sender's message outside junk loads its remote images and may extend the list")
        void shouldApplyTheAllowListOutsideJunk() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(mailContentService.getOrFetchMessageContent(MESSAGE_ID)).thenReturn("<p>Body</p>");
            when(remoteImageAllowlistService.isAllowed(ACCOUNT_ID, "alice@example.com")).thenReturn(true);

            MailContentResponse result = mailFacade.getMessageContentOnly(STABLE_ID);

            assertThat(result.senderEmail()).isEqualTo("alice@example.com");
            assertThat(result.remoteImagesAllowedForSender()).isTrue();
            assertThat(result.remoteImageTrustOffered()).isTrue();
        }

        /*
         * The allow-list is keyed on From, which anyone can write. A message forged in
         * a trusted sender's name is what a server files under junk, and loading its
         * images on open would tell the forger the address is read.
         */
        @Test
        @DisplayName("In a junk folder the allow-list is neither applied nor offered, even for a trusted sender")
        void shouldNotApplyTheAllowListInJunk() {
            entity.setFolderName(FOLDER_JUNK);
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(entity));
            when(mailContentService.getOrFetchMessageContent(MESSAGE_ID)).thenReturn("<p>Body</p>");
            when(imapFolderService.isRecordedWithRole(ACCOUNT_ID, FOLDER_JUNK, FolderRole.JUNK)).thenReturn(true);
            lenient().when(remoteImageAllowlistService.isAllowed(ACCOUNT_ID, "alice@example.com")).thenReturn(true);

            MailContentResponse result = mailFacade.getMessageContentOnly(STABLE_ID);

            assertThat(result.remoteImagesAllowedForSender()).isFalse();
            assertThat(result.remoteImageTrustOffered()).isFalse();
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

    private MailDetailResponse dummyDetailResponse() {
        return new MailDetailResponse(STABLE_ID, FOLDER_INBOX, "Test subject", "Alice <alice@example.com>", null, null,
                null, LocalDateTime.of(2026, 1, 15, 10, 0), false, false, false, null, null, null, false, List.of(),
                null);
    }
}
