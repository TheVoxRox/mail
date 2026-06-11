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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
            when(mailContentService.getOrFetchMessageContent(MESSAGE_ID)).thenReturn("<p>Hello</p>");
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
            when(mailContentService.getOrFetchMessageContent(MESSAGE_ID)).thenReturn("content");
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
            when(mailContentService.getOrFetchMessageContent(MESSAGE_ID))
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
            when(mailContentService.getOrFetchMessageContent(MESSAGE_ID))
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
            when(mailContentService.getOrFetchMessageContent(MESSAGE_ID)).thenReturn("<p>Original</p>");
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
            when(mailContentService.getOrFetchMessageContent(MESSAGE_ID))
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
            verify(mailSyncService).syncAndBackfillAsync(account, FOLDER_INBOX, 0, 20);
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
    @DisplayName("searchEmails")
    class SearchEmails {

        @Test
        @DisplayName("Delegates to messageService.search and maps via the mapper")
        void shouldDelegateToMessageServiceAndMap() {
            MessageEntity msg = new MessageEntity();
            msg.setId(1L);
            msg.setStableId("s1");
            msg.setFolderName("INBOX");
            msg.setSubject("Test");
            msg.setSender("a@b.com");
            msg.setReceivedAt(LocalDateTime.now());
            msg.setUid(1L);
            msg.setUidValidity(1L);
            Page<MessageEntity> entityPage = new PageImpl<>(List.of(msg));
            when(messageService.search(ACCOUNT_ID, "query", 0, 20)).thenReturn(entityPage);

            MailSummaryResponse summaryDto = new MailSummaryResponse(1L, "s1", "INBOX", "Test", "a@b.com", "c@d.com",
                    LocalDateTime.now(), false, false, false, false, null, 1L);
            when(mapper.toSummaryDto(msg)).thenReturn(summaryDto);

            Page<MailSummaryResponse> result = mailFacade.searchEmails(ACCOUNT_ID, "query", 0, 20);

            assertThat(result.getContent()).containsExactly(summaryDto);
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
            InputStream expectedStream = new ByteArrayInputStream("data".getBytes());
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
        @DisplayName("Returns the full thread with summaries ordered by threadPosition and unreadCount populated")
        void shouldReturnThreadWithMembersAndUnreadCount() {
            MessageEntity m1 = new MessageEntity();
            m1.setId(1L);
            m1.setThreadId(THREAD_ID);
            m1.setThreadRootMessageId("<root@x.cz>");
            m1.setSeen(true);
            MessageEntity m2 = new MessageEntity();
            m2.setId(2L);
            m2.setThreadId(THREAD_ID);
            m2.setThreadRootMessageId("<root@x.cz>");
            m2.setSeen(false);
            MessageEntity m3 = new MessageEntity();
            m3.setId(3L);
            m3.setThreadId(THREAD_ID);
            m3.setThreadRootMessageId("<root@x.cz>");
            m3.setSeen(false);

            when(messageRepository.findByAccountIdAndThreadId(ACCOUNT_ID, THREAD_ID)).thenReturn(List.of(m1, m2, m3));
            MailSummaryResponse s1 = dummySummary(1L);
            MailSummaryResponse s2 = dummySummary(2L);
            MailSummaryResponse s3 = dummySummary(3L);
            when(mapper.toSummaryDto(m1)).thenReturn(s1);
            when(mapper.toSummaryDto(m2)).thenReturn(s2);
            when(mapper.toSummaryDto(m3)).thenReturn(s3);

            ThreadResponse result = mailFacade.getThread(ACCOUNT_ID, THREAD_ID);

            assertThat(result.threadId()).isEqualTo(THREAD_ID);
            assertThat(result.rootMessageId()).isEqualTo("<root@x.cz>");
            assertThat(result.participantsTotal()).isEqualTo(3);
            assertThat(result.unreadCount()).isEqualTo(2);
            assertThat(result.messages()).containsExactly(s1, s2, s3);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException when no message in the account belongs to the threadId")
        void shouldThrowWhenThreadHasNoMembers() {
            when(messageRepository.findByAccountIdAndThreadId(ACCOUNT_ID, THREAD_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> mailFacade.getThread(ACCOUNT_ID, THREAD_ID))
                    .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining(THREAD_ID);
        }

        @Test
        @DisplayName("Ownership enforced by accountId — different accountId returns its own (potentially empty) result")
        void shouldScopeQueryByAccountId() {
            // For account 999 the repo returns nothing — the query is correctly
            // narrowed by accountId, so we get a 404 even if account 1 has the
            // thread.
            when(messageRepository.findByAccountIdAndThreadId(999L, THREAD_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> mailFacade.getThread(999L, THREAD_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    private MailSummaryResponse dummySummary(long id) {
        return new MailSummaryResponse(id, "s" + id, "INBOX", "Subject " + id, "from@x.cz", "to@x.cz",
                LocalDateTime.of(2026, 1, 1, 10, 0), false, false, false, false, "8b4abcde-uuid", 100L);
    }

    private MailRequest dummyMailRequest(String subject) {
        return new MailRequest("to@example.com", "", "", subject, "body", null, null, null);
    }

    private MailDetailResponse dummyDetailResponse(String contentError) {
        return new MailDetailResponse(STABLE_ID, UID, "Test subject", "Alice <alice@example.com>", null, null,
                "body content", LocalDateTime.of(2026, 1, 15, 10, 0), false, false, false, null, null, null, false,
                List.of(), contentError, null);
    }
}
