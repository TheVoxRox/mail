package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.core.metrics.MailMetrics;
import org.voxrox.mailbackend.exception.AccountNotFoundException;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountConnectionDetailsService;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.DraftRequest;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.feature.mail.dto.SendNotification;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;

/**
 * Unit tests for {@link SmtpMessageService}.
 *
 * We do not exercise actual SMTP sending — the whole pipeline is tightly
 * coupled with {@code jakarta.mail.Session.getInstance/getTransport} and a unit
 * test would require static mocking. Instead we verify:
 *
 * - Early-exit paths in {@code sendDraftAsync} (draft not in DB, draft belongs
 * to a different account). - Boundary handling: any exception inside async
 * methods lands in {@code accountRepository.updateLastError(...)} and is not
 * propagated outward (the methods run in the {@code @Async} executor, so an
 * uncaught exception would have no handler).
 */
@ExtendWith(MockitoExtension.class)
class SmtpMessageServiceTest {

    private static final Long ACCOUNT_ID = 11L;
    private static final Long OTHER_ACCOUNT_ID = 22L;
    private static final String STABLE_ID = "draft-stable-id";
    private static final String SEND_ID = "send-id-123";

    @Mock
    private AccountService accountService;
    @Mock
    private AccountConnectionDetailsService connectionDetailsService;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private MessageService messageService;
    @Mock
    private ImapActionService imapActionService;
    @Mock
    private ImapAppendService appendService;
    @Mock
    private MailMetrics mailMetrics;
    @Mock
    private MimeMessageBuilder mimeMessageBuilder;
    @Mock
    private SmtpTransportFactory transportFactory;
    @Mock
    private SseNotificationService sseNotificationService;

    @InjectMocks
    private SmtpMessageService service;

    @Nested
    @DisplayName("sendDraftAsync — early exits")
    class SendDraftEarlyExits {

        @Test
        @DisplayName("Draft with stableId does not exist -> no-op, no SMTP/DB write")
        void shouldNoopWhenDraftNotFound() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.empty());

            service.sendDraftAsync(ACCOUNT_ID, STABLE_ID, SEND_ID);

            // No IMAP contact, no repository update — the method silently returns.
            // mailMetrics is not touched either: startSmtpSend runs AFTER the early-exit
            // check, so "draft disappeared between request and async" is not counted as a
            // send attempt.
            verifyNoInteractions(appendService, imapActionService, accountRepository, accountService,
                    connectionDetailsService, mailMetrics, transportFactory);

            // The client still gets an outcome so its pending indicator resolves.
            ArgumentCaptor<SendNotification> sent = ArgumentCaptor.forClass(SendNotification.class);
            verify(sseNotificationService).broadcast(sent.capture());
            assertThat(sent.getValue().type()).isEqualTo(SendNotification.TYPE_FAILED);
            assertThat(sent.getValue().sendId()).isEqualTo(SEND_ID);
        }

        @Test
        @DisplayName("Draft belongs to a different account -> no-op (protection against cross-account operation)")
        void shouldNoopWhenDraftBelongsToOtherAccount() {
            AccountEntity otherAccount = new AccountEntity();
            otherAccount.setId(OTHER_ACCOUNT_ID);

            MessageEntity draft = new MessageEntity();
            draft.setStableId(STABLE_ID);
            draft.setAccount(otherAccount);
            draft.setFolderName("Drafts");
            draft.setUid(42L);

            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(draft));

            service.sendDraftAsync(ACCOUNT_ID, STABLE_ID, SEND_ID);

            verifyNoInteractions(appendService, imapActionService, accountRepository, accountService,
                    connectionDetailsService, mailMetrics, transportFactory);

            ArgumentCaptor<SendNotification> sent = ArgumentCaptor.forClass(SendNotification.class);
            verify(sseNotificationService).broadcast(sent.capture());
            assertThat(sent.getValue().type()).isEqualTo(SendNotification.TYPE_FAILED);
            assertThat(sent.getValue().sendId()).isEqualTo(SEND_ID);
        }
    }

    @Nested
    @DisplayName("Boundary handling — exceptions are not swallowed silently; they land in lastError")
    class BoundaryErrorHandling {

        @Test
        @DisplayName("sendEmailAsync: failure resolving connection details -> updateLastError")
        void sendEmailShouldRecordLastErrorOnFailure() {
            when(connectionDetailsService.getSmtpConnectionDetails(ACCOUNT_ID))
                    .thenThrow(new AccountNotFoundException(ACCOUNT_ID));

            MailRequest req = new MailRequest("to@example.com", null, null, "subj", "body", null, null, null);

            // Act — must not propagate the exception (runs inside @Async).
            service.sendEmailAsync(ACCOUNT_ID, req, SEND_ID);

            // Assert: the last error is persisted to the DB.
            ArgumentCaptor<AccountLastError> err = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), err.capture(), any(LocalDateTime.class));
            assertThat(err.getValue().code()).isEqualTo(AccountLastErrorCode.SMTP_SEND_FAILED);
            assertThat(err.getValue().fallbackMessage()).startsWith("Send failed:");

            // Metric is recorded with outcome=FAILURE (timer sample is started
            // before the try-block and closed in the finally block).
            verify(mailMetrics).startSmtpSend();
            verify(mailMetrics).recordSmtpSend(any(), eq(MailMetrics.OUTCOME_FAILURE));

            // The client is notified of the failure via the SSE stream.
            ArgumentCaptor<SendNotification> sent = ArgumentCaptor.forClass(SendNotification.class);
            verify(sseNotificationService).broadcast(sent.capture());
            assertThat(sent.getValue().type()).isEqualTo(SendNotification.TYPE_FAILED);
            assertThat(sent.getValue().sendId()).isEqualTo(SEND_ID);
            assertThat(sent.getValue().errorCode()).isEqualTo(AccountLastErrorCode.SMTP_SEND_FAILED.name());
        }

        @Test
        @DisplayName("sendDraftAsync: getSmtpConnectionDetails failure -> updateLastError with prefix \"Draft send failed\"")
        void sendDraftShouldRecordLastErrorOnFailure() {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);

            MessageEntity draft = new MessageEntity();
            draft.setStableId(STABLE_ID);
            draft.setAccount(account);
            draft.setFolderName("Drafts");
            draft.setUid(100L);

            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(draft));
            when(connectionDetailsService.getSmtpConnectionDetails(ACCOUNT_ID))
                    .thenThrow(new RuntimeException("IMAP unavailable"));

            service.sendDraftAsync(ACCOUNT_ID, STABLE_ID, SEND_ID);

            ArgumentCaptor<AccountLastError> err = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), err.capture(), any(LocalDateTime.class));
            assertThat(err.getValue().code()).isEqualTo(AccountLastErrorCode.DRAFT_SEND_FAILED);
            assertThat(err.getValue().fallbackMessage()).startsWith("Draft send failed:");

            // Nothing was sent or deleted.
            verify(imapActionService, never()).hardDelete(anyLong(), anyString(), anyLong());
            verify(messageService, never()).deleteByStableId(anyString());

            // Parity with sendEmailAsync: metric is recorded as FAILURE for draft-send too.
            verify(mailMetrics).startSmtpSend();
            verify(mailMetrics).recordSmtpSend(any(), eq(MailMetrics.OUTCOME_FAILURE));

            ArgumentCaptor<SendNotification> sent = ArgumentCaptor.forClass(SendNotification.class);
            verify(sseNotificationService).broadcast(sent.capture());
            assertThat(sent.getValue().type()).isEqualTo(SendNotification.TYPE_FAILED);
            assertThat(sent.getValue().sendId()).isEqualTo(SEND_ID);
            assertThat(sent.getValue().errorCode()).isEqualTo(AccountLastErrorCode.DRAFT_SEND_FAILED.name());
        }

        @Test
        @DisplayName("sendDraftAsync: exception while loading the draft -> updateLastError")
        void sendDraftLookupFailureShouldRecordLastError() {
            when(messageService.getByStableId(STABLE_ID)).thenThrow(new RuntimeException("DB unavailable"));

            service.sendDraftAsync(ACCOUNT_ID, STABLE_ID, SEND_ID);

            ArgumentCaptor<AccountLastError> err = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), err.capture(), any(LocalDateTime.class));
            assertThat(err.getValue().code()).isEqualTo(AccountLastErrorCode.DRAFT_SEND_FAILED);
            assertThat(err.getValue().fallbackMessage()).startsWith("Draft send failed:").contains("DB unavailable");
            verifyNoInteractions(mailMetrics, transportFactory, appendService, imapActionService);

            ArgumentCaptor<SendNotification> sent = ArgumentCaptor.forClass(SendNotification.class);
            verify(sseNotificationService).broadcast(sent.capture());
            assertThat(sent.getValue().type()).isEqualTo(SendNotification.TYPE_FAILED);
            assertThat(sent.getValue().sendId()).isEqualTo(SEND_ID);
        }

        @Test
        @DisplayName("saveDraftAsync: exception while loading the draft being replaced -> updateLastError")
        void saveDraftReplaceLookupFailureShouldRecordLastError() {
            when(messageService.getByStableId(STABLE_ID)).thenThrow(new RuntimeException("DB unavailable"));

            service.saveDraftAsync(ACCOUNT_ID, new org.voxrox.mailbackend.feature.mail.dto.DraftRequest(
                    "to@example.com", null, null, "subj", "body", null, null, null), STABLE_ID);

            ArgumentCaptor<AccountLastError> err = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), err.capture(), any(LocalDateTime.class));
            assertThat(err.getValue().code()).isEqualTo(AccountLastErrorCode.DRAFT_SAVE_FAILED);
            assertThat(err.getValue().fallbackMessage()).startsWith("Draft save failed:").contains("DB unavailable");
            verifyNoInteractions(mailMetrics, transportFactory, appendService, imapActionService);
        }
    }

    @Nested
    @DisplayName("saveDraftAsync — replace guard (data-loss prevention)")
    class SaveDraftReplaceGuard {

        private MessageEntity oldDraft() {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            MessageEntity old = new MessageEntity();
            old.setStableId(STABLE_ID);
            old.setAccount(account);
            old.setFolderName("Drafts");
            old.setUid(100L);
            return old;
        }

        private DraftRequest draftRequest() {
            return new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null);
        }

        @Test
        @DisplayName("Append of the new revision fails -> old draft kept, DRAFT_SAVE_FAILED, lastError not cleared")
        void appendFailureKeepsOldDraft() throws Exception {
            MessageEntity old = oldDraft();
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(old));
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(old.getAccount());
            when(mimeMessageBuilder.build(any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendByRole(eq(ACCOUNT_ID), eq(FolderRole.DRAFTS), any(), eq(false))).thenReturn(false);

            service.saveDraftAsync(ACCOUNT_ID, draftRequest(), STABLE_ID);

            // The only surviving copy must NOT be deleted.
            verify(imapActionService, never()).hardDelete(anyLong(), anyString(), anyLong());
            verify(messageService, never()).deleteByStableId(anyString());
            // Failure is surfaced; success is not falsely signalled.
            ArgumentCaptor<AccountLastError> err = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), err.capture(), any(LocalDateTime.class));
            assertThat(err.getValue().code()).isEqualTo(AccountLastErrorCode.DRAFT_SAVE_FAILED);
            verify(accountRepository, never()).clearLastErrorIfCodeIn(anyLong(), any());
        }

        @Test
        @DisplayName("Append of the new revision succeeds -> old draft deleted, lastError cleared")
        void appendSuccessDeletesOldDraft() throws Exception {
            MessageEntity old = oldDraft();
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(old));
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(old.getAccount());
            when(mimeMessageBuilder.build(any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendByRole(eq(ACCOUNT_ID), eq(FolderRole.DRAFTS), any(), eq(false))).thenReturn(true);

            service.saveDraftAsync(ACCOUNT_ID, draftRequest(), STABLE_ID);

            verify(imapActionService).hardDelete(ACCOUNT_ID, "Drafts", 100L);
            verify(messageService).deleteByStableId(STABLE_ID);
            // Conditional clear scoped to send-pipeline codes — a successful draft
            // save must not wipe a standing sync error (shared last_error slot).
            verify(accountRepository).clearLastErrorIfCodeIn(eq(ACCOUNT_ID), any());
            verify(accountRepository, never()).updateLastError(anyLong(), any(AccountLastError.class),
                    any(LocalDateTime.class));
        }
    }

    // Note: the OAuth2 SSL guard has lived in SmtpTransportFactory since the
    // step-2 refactor — its logic is exercised directly in
    // SmtpTransportFactoryTest. The orchestrator merely relies on it; the
    // behaviour "anything thrown from openTransport -> updateLastError +
    // metric FAILURE" is covered by the BoundaryErrorHandling group.
}
