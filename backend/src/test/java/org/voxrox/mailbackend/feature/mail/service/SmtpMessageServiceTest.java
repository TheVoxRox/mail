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
    @Mock
    private DraftPersistenceService draftPersistenceService;

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
                    connectionDetailsService, mailMetrics, transportFactory, draftPersistenceService);

            // The client still gets an outcome so its pending indicator resolves.
            ArgumentCaptor<SendNotification> sent = ArgumentCaptor.forClass(SendNotification.class);
            verify(sseNotificationService).broadcast(sent.capture());
            assertThat(sent.getValue().type()).isEqualTo(SendNotification.TYPE_FAILED);
            assertThat(sent.getValue().sendId()).isEqualTo(SEND_ID);
        }

        @Test
        @DisplayName("Draft row with null UID -> send_failed broadcast, not a hung client")
        void shouldBroadcastFailureWhenDraftUidIsNull() {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);

            MessageEntity draft = new MessageEntity();
            draft.setStableId(STABLE_ID);
            draft.setAccount(account);
            draft.setFolderName("Drafts");
            draft.setUid(null); // corrupt / partially-synced row

            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(draft));

            // The NPE from unboxing a null UID must be caught and reported, so the client's
            // pending indicator resolves instead of hanging.
            service.sendDraftAsync(ACCOUNT_ID, STABLE_ID, SEND_ID);

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
                    connectionDetailsService, mailMetrics, transportFactory, draftPersistenceService);

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
            service.sendEmailAsync(ACCOUNT_ID, req, SEND_ID, null);

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
            verifyNoInteractions(mailMetrics, transportFactory, appendService, imapActionService,
                    draftPersistenceService);

            ArgumentCaptor<SendNotification> sent = ArgumentCaptor.forClass(SendNotification.class);
            verify(sseNotificationService).broadcast(sent.capture());
            assertThat(sent.getValue().type()).isEqualTo(SendNotification.TYPE_FAILED);
            assertThat(sent.getValue().sendId()).isEqualTo(SEND_ID);
        }
    }

    @Nested
    @DisplayName("sendEmailAsync — superseded draft and recovery draft delegation")
    class SendSupersedeAndRecovery {

        private void stubDeliveredSend() throws Exception {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            account.setEmail("me@example.com");
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(mimeMessageBuilder.build(any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(transportFactory.openTransport(eq(ACCOUNT_ID), any(), any()))
                    .thenReturn(mock(jakarta.mail.Transport.class));
        }

        @Test
        @DisplayName("Delivered with supersedesDraftId -> delegates the post-delivery delete to DraftPersistenceService")
        void deliveredSendDelegatesSupersedeDelete() throws Exception {
            stubDeliveredSend();

            service.sendEmailAsync(ACCOUNT_ID,
                    new MailRequest("to@example.com", null, null, "subj", "body", null, null, null), SEND_ID,
                    STABLE_ID);

            verify(draftPersistenceService).deleteSupersededDraft(ACCOUNT_ID, STABLE_ID);
        }

        @Test
        @DisplayName("Delivered without supersedesDraftId -> no supersede delete")
        void deliveredSendWithoutSupersedeDoesNotDelete() throws Exception {
            stubDeliveredSend();

            service.sendEmailAsync(ACCOUNT_ID,
                    new MailRequest("to@example.com", null, null, "subj", "body", null, null, null), SEND_ID, null);

            verify(draftPersistenceService, never()).deleteSupersededDraft(anyLong(), anyString());
        }

        @Test
        @DisplayName("Failed send of a brand-new message -> parks a recovery draft and carries its id in the notification")
        void failedNewSendParksRecoveryDraft() {
            when(connectionDetailsService.getSmtpConnectionDetails(ACCOUNT_ID))
                    .thenThrow(new RuntimeException("SMTP down"));
            when(draftPersistenceService.saveRecoveryDraft(eq(ACCOUNT_ID), any(MailRequest.class)))
                    .thenReturn("recovery-stable-id");

            service.sendEmailAsync(ACCOUNT_ID,
                    new MailRequest("to@example.com", null, null, "subj", "body", null, null, null), SEND_ID, null);

            verify(draftPersistenceService).saveRecoveryDraft(eq(ACCOUNT_ID), any(MailRequest.class));
            ArgumentCaptor<SendNotification> sent = ArgumentCaptor.forClass(SendNotification.class);
            verify(sseNotificationService).broadcast(sent.capture());
            assertThat(sent.getValue().type()).isEqualTo(SendNotification.TYPE_FAILED);
            assertThat(sent.getValue().recoveryDraftStableId()).isEqualTo("recovery-stable-id");
        }

        @Test
        @DisplayName("Failed send of an edited draft -> no recovery draft (the original draft still exists)")
        void failedEditedSendDoesNotParkRecoveryDraft() {
            when(connectionDetailsService.getSmtpConnectionDetails(ACCOUNT_ID))
                    .thenThrow(new RuntimeException("SMTP down"));

            service.sendEmailAsync(ACCOUNT_ID,
                    new MailRequest("to@example.com", null, null, "subj", "body", null, null, null), SEND_ID,
                    STABLE_ID);

            verify(draftPersistenceService, never()).saveRecoveryDraft(anyLong(), any());
            ArgumentCaptor<SendNotification> sent = ArgumentCaptor.forClass(SendNotification.class);
            verify(sseNotificationService).broadcast(sent.capture());
            assertThat(sent.getValue().recoveryDraftStableId()).isNull();
        }
    }

    // Note: the OAuth2 SSL guard has lived in SmtpTransportFactory since the
    // step-2 refactor — its logic is exercised directly in
    // SmtpTransportFactoryTest. The orchestrator merely relies on it; the
    // behaviour "anything thrown from openTransport -> updateLastError +
    // metric FAILURE" is covered by the BoundaryErrorHandling group.
}
