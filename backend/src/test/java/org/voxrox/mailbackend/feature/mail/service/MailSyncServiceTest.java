package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.config.mail.SyncProperties;
import org.voxrox.mailbackend.core.metrics.MailMetrics;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.mail.dto.FolderResponse;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * Unit tests for {@link MailSyncService}.
 *
 * Orchestration logic: lock gating, iteration over role-matched folders,
 * backfill guards. The callback inside
 * {@code imapFolderService.executeInFolder} is intentionally not invoked
 * (returns default null) — this keeps the unit tests focused on "what sync
 * triggers" rather than "how sync steps through details".
 */
@ExtendWith(MockitoExtension.class)
class MailSyncServiceTest {

    @Mock
    private ImapFolderService imapFolderService;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private SyncStateService syncStateService;
    @Mock
    private SyncLockManager lockManager;
    @Mock
    private MailboxMaintenanceService maintenanceService;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private MailClientProperties mailProps;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private MessageDownloader messageDownloader;
    @Mock
    private FlagSyncService flagSyncService;
    @Mock
    private MailMetrics mailMetrics;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private FolderCountCache folderCountCache;

    private MailSyncService service;

    private static final Long ACCOUNT_ID = 7L;
    private static final String EMAIL = "test@example.com";

    private AccountEntity account;

    @BeforeEach
    void setUp() {
        service = new MailSyncService(imapFolderService, messageRepository, syncStateService, lockManager,
                maintenanceService, transactionTemplate, mailProps, eventPublisher, messageDownloader, flagSyncService,
                mailMetrics, accountRepository, folderCountCache);

        account = new AccountEntity();
        account.setId(ACCOUNT_ID);
        account.setEmail(EMAIL);
    }

    /**
     * Stubs {@code imapFolderService.executeInFolder} so the action lambda runs
     * with the given folder and a fresh UIDFolder mock. Shared by the nested test
     * classes that exercise behavior inside the IMAP open path.
     */
    private void stubExecuteInFolderRunCallback(Folder folder) {
        when(imapFolderService.executeInFolder(eq(ACCOUNT_ID), any(String.class), anyInt(), any())).thenAnswer(inv -> {
            org.voxrox.mailbackend.feature.mail.service.ImapFolderAction<?> action = inv.getArgument(3);
            return action.apply(folder, mock(UIDFolder.class));
        });
    }

    private void stubTransactionTemplateExecuteRunCallback() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
    }

    @Nested
    @DisplayName("syncAllFolders")
    class SyncAllFolders {

        @Test
        @DisplayName("Skips synchronization when one already runs for the account (lock not granted)")
        void skipsWhenLocked() {
            when(lockManager.tryLock(ACCOUNT_ID)).thenReturn(false);

            service.syncAllFolders(account);

            verify(imapFolderService, never()).getFolders(anyLong());
            verify(lockManager, never()).unlock(anyLong());
        }

        @Test
        @DisplayName("Synchronizes only the 5 role-matched folders (INBOX/SENT/DRAFTS/JUNK/TRASH)")
        void syncsOnlyRoleMatchedFolders() {
            when(lockManager.tryLock(ACCOUNT_ID)).thenReturn(true);
            when(imapFolderService.getFolders(ACCOUNT_ID))
                    .thenReturn(List.of(new FolderResponse("INBOX", "INBOX", FolderRole.INBOX),
                            new FolderResponse("Sent", "[Gmail]/Sent", FolderRole.SENT),
                            new FolderResponse("Drafts", "[Gmail]/Drafts", FolderRole.DRAFTS),
                            new FolderResponse("Spam", "[Gmail]/Spam", FolderRole.JUNK),
                            new FolderResponse("Trash", "[Gmail]/Trash", FolderRole.TRASH),
                            new FolderResponse("MyCustom", "MyCustom", FolderRole.USER),
                            new FolderResponse("Archive", "All", FolderRole.ARCHIVE)));

            service.syncAllFolders(account);

            // performFullSyncCycle -> executeInFolder once per role-matched folder.
            verify(imapFolderService, times(5)).executeInFolder(eq(ACCOUNT_ID), any(),
                    eq(jakarta.mail.Folder.READ_ONLY), any());
            verify(lockManager).unlock(ACCOUNT_ID);
        }

        @Test
        @DisplayName("Exception while listing folders writes last_error and releases the lock")
        void recordsLastErrorAndReleasesLockOnException() {
            when(lockManager.tryLock(ACCOUNT_ID)).thenReturn(true);
            when(imapFolderService.getFolders(ACCOUNT_ID)).thenThrow(new RuntimeException("boom"));

            service.syncAllFolders(account);

            verify(lockManager).unlock(ACCOUNT_ID);
            ArgumentCaptor<AccountLastError> captor = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), captor.capture(), any(LocalDateTime.class));
            assertThat(captor.getValue().code()).isEqualTo(AccountLastErrorCode.MAIL_SYNC_ACCOUNT_FAILED);
            assertThat(captor.getValue().fallbackMessage()).contains("Account sync failed").contains("RuntimeException")
                    .contains("boom");
        }

        @Test
        @DisplayName("Clears last_error only after a fully clean pass over all role-matched folders")
        void clearsLastErrorAfterFullyCleanPass() throws Exception {
            when(lockManager.tryLock(ACCOUNT_ID)).thenReturn(true);
            when(imapFolderService.getFolders(ACCOUNT_ID))
                    .thenReturn(List.of(new FolderResponse("INBOX", "INBOX", FolderRole.INBOX),
                            new FolderResponse("Sent", "[Gmail]/Sent", FolderRole.SENT)));
            stubExecuteInFolderRunCallback(mock(Folder.class));
            stubTransactionTemplateExecuteRunCallback();
            when(syncStateService.getOrCreateState(eq(ACCOUNT_ID), any(), any()))
                    .thenReturn(new FolderSyncStateEntity());
            when(flagSyncService.handleUidValidity(any())).thenReturn(true);

            service.syncAllFolders(account);

            verify(accountRepository).clearLastError(eq(ACCOUNT_ID), any(LocalDateTime.class));
            verify(accountRepository, never()).updateLastError(anyLong(), any(AccountLastError.class),
                    any(LocalDateTime.class));
        }

        @Test
        @DisplayName("A failed folder blocks clearing last_error even when later folders succeed")
        void failedFolderBlocksClearingLastError() throws Exception {
            when(lockManager.tryLock(ACCOUNT_ID)).thenReturn(true);
            when(imapFolderService.getFolders(ACCOUNT_ID))
                    .thenReturn(List.of(new FolderResponse("INBOX", "INBOX", FolderRole.INBOX),
                            new FolderResponse("Sent", "[Gmail]/Sent", FolderRole.SENT)));
            stubExecuteInFolderRunCallback(mock(Folder.class));
            stubTransactionTemplateExecuteRunCallback();
            when(syncStateService.getOrCreateState(eq(ACCOUNT_ID), any(), any()))
                    .thenReturn(new FolderSyncStateEntity());
            // INBOX fails, SENT succeeds afterwards.
            when(flagSyncService.handleUidValidity(any())).thenThrow(new RuntimeException("IMAP timeout"))
                    .thenReturn(true);

            service.syncAllFolders(account);

            // The INBOX failure is recorded...
            ArgumentCaptor<AccountLastError> captor = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), captor.capture(), any(LocalDateTime.class));
            assertThat(captor.getValue().code()).isEqualTo(AccountLastErrorCode.MAIL_SYNC_FOLDER_FAILED);
            // ...and the later successful SENT cycle must not erase it.
            verify(accountRepository, never()).clearLastError(anyLong(), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("Duplicate roles are reduced to one (first match)")
        void duplicateRolesLimitedToFirst() {
            when(lockManager.tryLock(ACCOUNT_ID)).thenReturn(true);
            when(imapFolderService.getFolders(ACCOUNT_ID))
                    .thenReturn(List.of(new FolderResponse("INBOX", "INBOX", FolderRole.INBOX),
                            new FolderResponse("Inbox2", "[Gmail]/Inbox2", FolderRole.INBOX)));

            service.syncAllFolders(account);

            verify(imapFolderService, times(1)).executeInFolder(eq(ACCOUNT_ID), eq("INBOX"),
                    eq(jakarta.mail.Folder.READ_ONLY), any());
        }
    }

    @Nested
    @DisplayName("syncAndBackfill")
    class SyncAndBackfill {

        @Test
        @DisplayName("page=0 + minUid>1 triggers backfill (downloadRange via executeInFolder)")
        void triggersBackfillOnFirstPage() {
            SyncProperties sync = new SyncProperties(100, 200, java.time.Duration.ofMinutes(5),
                    java.time.Duration.ofSeconds(10), 50, 30, 300, 4, 256, 200, java.time.Duration.ofMinutes(30),
                    java.time.Duration.ofSeconds(30));
            when(mailProps.sync()).thenReturn(sync);

            when(messageRepository.findMinUidByAccountIdAndFolderName(ACCOUNT_ID, "INBOX"))
                    .thenReturn(Optional.of(100L));

            service.syncAndBackfill(account, "INBOX", 0, 20);

            // 1) performFullSyncCycle → executeInFolder
            // 2) downloadRange → executeInFolder
            verify(imapFolderService, times(2)).executeInFolder(eq(ACCOUNT_ID), eq("INBOX"),
                    eq(jakarta.mail.Folder.READ_ONLY), any());
        }

        @Test
        @DisplayName("page>0 only sync, no backfill")
        void noBackfillOnLaterPages() {
            service.syncAndBackfill(account, "INBOX", 1, 20);

            verify(imapFolderService, times(1)).executeInFolder(eq(ACCOUNT_ID), eq("INBOX"),
                    eq(jakarta.mail.Folder.READ_ONLY), any());
            verify(messageRepository, never()).findMinUidByAccountIdAndFolderName(anyLong(), any());
        }

        @Test
        @DisplayName("minUid=1 does not trigger backfill (nothing older can exist)")
        void noBackfillWhenMinUidIsOne() {
            when(messageRepository.findMinUidByAccountIdAndFolderName(ACCOUNT_ID, "INBOX")).thenReturn(Optional.of(1L));

            service.syncAndBackfill(account, "INBOX", 0, 20);

            verify(imapFolderService, times(1)).executeInFolder(eq(ACCOUNT_ID), eq("INBOX"),
                    eq(jakarta.mail.Folder.READ_ONLY), any());
        }

        @Test
        @DisplayName("No messages in DB -> no backfill")
        void noBackfillWhenEmpty() {
            when(messageRepository.findMinUidByAccountIdAndFolderName(ACCOUNT_ID, "INBOX"))
                    .thenReturn(Optional.empty());

            service.syncAndBackfill(account, "INBOX", 0, 20);

            verify(imapFolderService, times(1)).executeInFolder(eq(ACCOUNT_ID), eq("INBOX"),
                    eq(jakarta.mail.Folder.READ_ONLY), any());
        }

        @Test
        @DisplayName("syncAndBackfillAsync catches outermost exception and writes last_error")
        void asyncBoundaryRecordsLastError() {
            when(imapFolderService.executeInFolder(eq(ACCOUNT_ID), eq("INBOX"), eq(jakarta.mail.Folder.READ_ONLY),
                    any())).thenThrow(new RuntimeException("folder open failed"));

            service.syncAndBackfillAsync(account, "INBOX", 0, 20);

            ArgumentCaptor<AccountLastError> captor = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), captor.capture(), any(LocalDateTime.class));
            assertThat(captor.getValue().code()).isEqualTo(AccountLastErrorCode.MAIL_SYNC_FOLDER_FAILED);
            assertThat(captor.getValue().fallbackMessage()).contains("Folder sync INBOX failed")
                    .contains("RuntimeException").contains("folder open failed");
        }
    }

    @Nested
    @DisplayName("fetchServerCountAndEnsurePageLocally")
    class FetchServerCountAndEnsurePageLocally {

        @Test
        @DisplayName("local cache covers the page -> no lazy fetch, returns server count and refreshes cache")
        void noLazyFetchWhenLocalCovers() throws Exception {
            Folder folder = mock(Folder.class);
            when(messageRepository.countByAccountIdAndFolderName(ACCOUNT_ID, "INBOX")).thenReturn(50L);
            when(folder.getMessageCount()).thenReturn(1790);
            stubExecuteInFolderRunCallback(folder);

            long total = service.fetchServerCountAndEnsurePageLocally(account, "INBOX", 0, 20);

            assertThat(total).isEqualTo(1790L);
            verify(messageDownloader, never()).downloadSequenceRange(any(), anyInt(), anyInt());
            verify(folderCountCache).put(ACCOUNT_ID, "INBOX", 1790L);
        }

        @Test
        @DisplayName("page beyond local cache -> lazy-fetches the correct sequence range, returns server count")
        void lazyFetchesWhenPageBeyondLocal() throws Exception {
            Folder folder = mock(Folder.class);
            when(messageRepository.countByAccountIdAndFolderName(ACCOUNT_ID, "INBOX")).thenReturn(100L);
            when(folder.getMessageCount()).thenReturn(1790);
            stubExecuteInFolderRunCallback(folder);
            stubTransactionTemplateExecuteRunCallback();
            when(syncStateService.getOrCreateState(eq(ACCOUNT_ID), eq("INBOX"), eq(FolderRole.USER)))
                    .thenReturn(new FolderSyncStateEntity());
            // page 5, size 50 -> needed = 300; endSeq = 1790 - 100 = 1690; startSeq = 1790
            // - 300 + 1 = 1491
            when(messageDownloader.downloadSequenceRange(any(), eq(1491), eq(1690))).thenReturn(200);

            long total = service.fetchServerCountAndEnsurePageLocally(account, "INBOX", 5, 50);

            assertThat(total).isEqualTo(1790L);
            verify(messageDownloader).downloadSequenceRange(any(), eq(1491), eq(1690));
            verify(folderCountCache).put(ACCOUNT_ID, "INBOX", 1790L);
        }

        @Test
        @DisplayName("MessagingException during fetch -> falls back to local count, cache untouched")
        void fallsBackToLocalCountOnMessagingException() throws Exception {
            Folder folder = mock(Folder.class);
            when(messageRepository.countByAccountIdAndFolderName(ACCOUNT_ID, "INBOX")).thenReturn(123L);
            when(folder.getMessageCount()).thenThrow(new MessagingException("server down"));
            stubExecuteInFolderRunCallback(folder);

            long total = service.fetchServerCountAndEnsurePageLocally(account, "INBOX", 0, 20);

            assertThat(total).isEqualTo(123L);
            verify(messageDownloader, never()).downloadSequenceRange(any(), anyInt(), anyInt());
            verify(folderCountCache, never()).put(anyLong(), any(), anyLong());
        }
    }

    @Nested
    @DisplayName("performFullSyncCycle — last_error propagation")
    class LastErrorPropagation {

        @Test
        @DisplayName("Successful cycle returns true, writes lastSyncAt via UPDATE and does not touch last_error")
        void successfulCycleDoesNotClearLastError() throws Exception {
            stubExecuteInFolderRunCallback(mock(Folder.class));
            stubTransactionTemplateExecuteRunCallback();
            when(syncStateService.getOrCreateState(eq(ACCOUNT_ID), eq("INBOX"), eq(FolderRole.INBOX)))
                    .thenReturn(new FolderSyncStateEntity());
            when(flagSyncService.handleUidValidity(any())).thenReturn(true);

            boolean result = service.performFullSyncCycle(account, "INBOX", FolderRole.INBOX);

            assertThat(result).isTrue();
            // last_error clearing is owned by syncAllFolders (only after a fully
            // clean pass over all folders) — a single folder cycle must not clear
            // it, otherwise this folder's success would erase another folder's
            // standing error.
            verify(accountRepository, never()).clearLastError(anyLong(), any(LocalDateTime.class));
            // Regression for the optimistic-lock fix: lastSyncAt must go through
            // a dedicated UPDATE, not through save(detached entity).
            verify(syncStateService).touchLastSyncAt(any(), any(LocalDateTime.class));
            verify(syncStateService, never()).saveSyncState(any());
        }

        @Test
        @DisplayName("Sync failure returns false and writes last_error with the folder name and exception class")
        void setsLastErrorOnFailure() throws Exception {
            stubExecuteInFolderRunCallback(mock(Folder.class));
            stubTransactionTemplateExecuteRunCallback();
            when(syncStateService.getOrCreateState(eq(ACCOUNT_ID), eq("INBOX"), eq(FolderRole.INBOX)))
                    .thenReturn(new FolderSyncStateEntity());
            when(flagSyncService.handleUidValidity(any())).thenThrow(new RuntimeException("IMAP timeout"));

            boolean result = service.performFullSyncCycle(account, "INBOX", FolderRole.INBOX);

            assertThat(result).isFalse();
            ArgumentCaptor<AccountLastError> captor = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), captor.capture(), any(LocalDateTime.class));
            assertThat(captor.getValue().code()).isEqualTo(AccountLastErrorCode.MAIL_SYNC_FOLDER_FAILED);
            assertThat(captor.getValue().fallbackMessage()).contains("Folder sync INBOX failed")
                    .contains("RuntimeException").contains("IMAP timeout");
        }

        @Test
        @DisplayName("OptimisticLockingFailureException is caught and writes last_error (audit has its own action)")
        void handlesOptimisticLockConflict() throws Exception {
            // After fix A this state should not occur, but we verify the catch
            // and forward-safety audit action via regression — if the optimistic
            // lock ever returns, the operator sees 'sync_optimistic_lock_conflict'
            // instead of the generic 'sync_folder' and recognizes this as a known issue.
            stubExecuteInFolderRunCallback(mock(Folder.class));
            stubTransactionTemplateExecuteRunCallback();
            when(syncStateService.getOrCreateState(eq(ACCOUNT_ID), eq("INBOX"), eq(FolderRole.INBOX)))
                    .thenReturn(new FolderSyncStateEntity());
            when(flagSyncService.handleUidValidity(any()))
                    .thenThrow(new OptimisticLockingFailureException("Row was already updated"));

            service.performFullSyncCycle(account, "INBOX", FolderRole.INBOX);

            ArgumentCaptor<AccountLastError> captor = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), captor.capture(), any(LocalDateTime.class));
            assertThat(captor.getValue().code()).isEqualTo(AccountLastErrorCode.MAIL_SYNC_FOLDER_FAILED);
            assertThat(captor.getValue().fallbackMessage()).contains("Folder sync INBOX failed")
                    .contains("OptimisticLockingFailureException");
        }

    }

    @Nested
    @DisplayName("getMessageOrThrow")
    class GetMessageOrThrow {

        @Test
        @DisplayName("Returns the message when it exists in the repository")
        void returnsEntity() {
            MessageEntity entity = new MessageEntity();
            when(messageRepository.findByStableId("stable-123")).thenReturn(Optional.of(entity));

            MessageEntity found = service.getMessageOrThrow("stable-123");

            assertThat(found).isSameAs(entity);
        }

        @Test
        @DisplayName("Throws ResourceNotFoundException for non-existing stableId")
        void throwsWhenMissing() {
            when(messageRepository.findByStableId("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getMessageOrThrow("missing")).isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("missing");
        }
    }
}
