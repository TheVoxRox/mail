package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import org.voxrox.mailbackend.core.config.mail.RetryProperties;
import org.voxrox.mailbackend.core.config.mail.SyncProperties;
import org.voxrox.mailbackend.core.metrics.MailMetrics;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.mail.dto.FolderResponse;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.event.MailSyncCycleCompletedEvent;
import org.voxrox.mailbackend.feature.mail.event.MailSyncErrorStateChangedEvent;
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

        // performFullSyncCycle reads mail.client.retry.* up front for its bounded
        // transient-retry loop. Tiny backoff keeps the retry tests fast; lenient
        // because not every test drives a full cycle.
        lenient().when(mailProps.retry()).thenReturn(
                new RetryProperties(3, java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(2), 2.0));

        // Folder-level skip-if-running guard defaults to "granted" so existing
        // tests exercise the cycle; the skip behavior has its own tests below.
        lenient().when(lockManager.tryLockFolder(anyLong(), any(String.class))).thenReturn(true);
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
            when(lockManager.tryLock(eq(ACCOUNT_ID), anyBoolean())).thenReturn(false);

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            verify(imapFolderService, never()).getFolders(anyLong());
            verify(lockManager, never()).unlock(anyLong());
        }

        @Test
        @DisplayName("Synchronizes only the 5 role-matched folders (INBOX/SENT/DRAFTS/JUNK/TRASH)")
        void syncsOnlyRoleMatchedFolders() {
            when(lockManager.tryLock(eq(ACCOUNT_ID), anyBoolean())).thenReturn(true);
            when(imapFolderService.getFolders(ACCOUNT_ID))
                    .thenReturn(List.of(new FolderResponse("INBOX", "INBOX", FolderRole.INBOX),
                            new FolderResponse("Sent", "[Gmail]/Sent", FolderRole.SENT),
                            new FolderResponse("Drafts", "[Gmail]/Drafts", FolderRole.DRAFTS),
                            new FolderResponse("Spam", "[Gmail]/Spam", FolderRole.JUNK),
                            new FolderResponse("Trash", "[Gmail]/Trash", FolderRole.TRASH),
                            new FolderResponse("MyCustom", "MyCustom", FolderRole.USER),
                            new FolderResponse("Archive", "All", FolderRole.ARCHIVE)));

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            // runFolderCycle -> executeInFolder once per role-matched folder.
            verify(imapFolderService, times(5)).executeInFolder(eq(ACCOUNT_ID), any(),
                    eq(jakarta.mail.Folder.READ_ONLY), any());
            verify(lockManager).unlock(ACCOUNT_ID);
        }

        @Test
        @DisplayName("Exception while listing folders writes last_error and releases the lock")
        void recordsLastErrorAndReleasesLockOnException() {
            when(lockManager.tryLock(eq(ACCOUNT_ID), anyBoolean())).thenReturn(true);
            when(imapFolderService.getFolders(ACCOUNT_ID)).thenThrow(new RuntimeException("boom"));

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

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
            when(lockManager.tryLock(eq(ACCOUNT_ID), anyBoolean())).thenReturn(true);
            when(imapFolderService.getFolders(ACCOUNT_ID))
                    .thenReturn(List.of(new FolderResponse("INBOX", "INBOX", FolderRole.INBOX),
                            new FolderResponse("Sent", "[Gmail]/Sent", FolderRole.SENT)));
            stubExecuteInFolderRunCallback(mock(Folder.class));
            stubTransactionTemplateExecuteRunCallback();
            when(syncStateService.getOrCreateState(eq(ACCOUNT_ID), any(), any()))
                    .thenReturn(new FolderSyncStateEntity());
            when(flagSyncService.handleUidValidity(any())).thenReturn(true);

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            verify(accountRepository).clearLastError(eq(ACCOUNT_ID), any(LocalDateTime.class));
            verify(accountRepository, never()).updateLastError(anyLong(), any(AccountLastError.class),
                    any(LocalDateTime.class));
        }

        @Test
        @DisplayName("A failed folder blocks clearing last_error even when later folders succeed")
        void failedFolderBlocksClearingLastError() throws Exception {
            when(lockManager.tryLock(eq(ACCOUNT_ID), anyBoolean())).thenReturn(true);
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

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            // The INBOX failure is recorded...
            ArgumentCaptor<AccountLastError> captor = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), captor.capture(), any(LocalDateTime.class));
            assertThat(captor.getValue().code()).isEqualTo(AccountLastErrorCode.MAIL_SYNC_FOLDER_FAILED);
            // ...and the later successful SENT cycle must not erase it.
            verify(accountRepository, never()).clearLastError(anyLong(), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("A folder whose cycle already runs elsewhere is skipped and blocks clearing last_error")
        void skipsFolderLockedElsewhereAndDoesNotClearLastError() throws Exception {
            when(lockManager.tryLock(eq(ACCOUNT_ID), anyBoolean())).thenReturn(true);
            when(imapFolderService.getFolders(ACCOUNT_ID))
                    .thenReturn(List.of(new FolderResponse("INBOX", "INBOX", FolderRole.INBOX),
                            new FolderResponse("Sent", "[Gmail]/Sent", FolderRole.SENT)));
            // INBOX is being synced by a user-triggered cycle; SENT is free.
            when(lockManager.tryLockFolder(ACCOUNT_ID, "INBOX")).thenReturn(false);
            when(lockManager.tryLockFolder(ACCOUNT_ID, "[Gmail]/Sent")).thenReturn(true);
            stubExecuteInFolderRunCallback(mock(Folder.class));
            stubTransactionTemplateExecuteRunCallback();
            when(syncStateService.getOrCreateState(eq(ACCOUNT_ID), any(), any()))
                    .thenReturn(new FolderSyncStateEntity());
            when(flagSyncService.handleUidValidity(any())).thenReturn(true);

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            // Only SENT ran; the skipped INBOX released nothing it never acquired.
            verify(imapFolderService, times(1)).executeInFolder(eq(ACCOUNT_ID), eq("[Gmail]/Sent"),
                    eq(jakarta.mail.Folder.READ_ONLY), any());
            verify(imapFolderService, never()).executeInFolder(eq(ACCOUNT_ID), eq("INBOX"),
                    eq(jakarta.mail.Folder.READ_ONLY), any());
            verify(lockManager, never()).unlockFolder(ACCOUNT_ID, "INBOX");
            verify(lockManager).unlockFolder(ACCOUNT_ID, "[Gmail]/Sent");
            // An incomplete pass must not clear a last_error the concurrent
            // cycle may be about to record.
            verify(accountRepository, never()).clearLastError(anyLong(), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("Duplicate roles are reduced to one (first match)")
        void duplicateRolesLimitedToFirst() {
            when(lockManager.tryLock(eq(ACCOUNT_ID), anyBoolean())).thenReturn(true);
            when(imapFolderService.getFolders(ACCOUNT_ID))
                    .thenReturn(List.of(new FolderResponse("INBOX", "INBOX", FolderRole.INBOX),
                            new FolderResponse("Inbox2", "[Gmail]/Inbox2", FolderRole.INBOX)));

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            verify(imapFolderService, times(1)).executeInFolder(eq(ACCOUNT_ID), eq("INBOX"),
                    eq(jakarta.mail.Folder.READ_ONLY), any());
        }
    }

    /**
     * Reporting a finished pass back to the waiting user. The per-folder
     * {@code sync_completed} event cannot serve this: it is suppressed when the
     * folder downloaded nothing, which is the ordinary outcome of pressing
     * Synchronise, so the pass has to say so itself.
     */
    @Nested
    @DisplayName("completion of a user-triggered pass")
    class CycleCompletionReporting {

        @Test
        @DisplayName("A manual trigger asks the lock to have the pass reported")
        void manualTriggerRequestsReport() {
            when(lockManager.tryLock(eq(ACCOUNT_ID), anyBoolean())).thenReturn(true);
            when(imapFolderService.getFolders(ACCOUNT_ID)).thenReturn(List.of());

            service.syncAllFolders(account, SyncTrigger.MANUAL);

            verify(lockManager).tryLock(ACCOUNT_ID, true);
        }

        @Test
        @DisplayName("The scheduled pass stays silent — it would announce itself every five minutes")
        void scheduledTriggerDoesNotRequestReport() {
            when(lockManager.tryLock(eq(ACCOUNT_ID), anyBoolean())).thenReturn(true);
            when(imapFolderService.getFolders(ACCOUNT_ID)).thenReturn(List.of());

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            verify(lockManager).tryLock(ACCOUNT_ID, false);
        }

        @Test
        @DisplayName("A pass someone waits on publishes its completion, zero new messages included")
        void publishesCompletionWithZeroCount() {
            when(lockManager.tryLock(eq(ACCOUNT_ID), anyBoolean())).thenReturn(true);
            when(lockManager.unlock(ACCOUNT_ID)).thenReturn(true);
            when(imapFolderService.getFolders(ACCOUNT_ID)).thenReturn(List.of());

            service.syncAllFolders(account, SyncTrigger.MANUAL);

            MailSyncCycleCompletedEvent event = capturePublished(MailSyncCycleCompletedEvent.class);
            assertThat(event.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(event.newMessagesCount()).isZero();
            // Nothing was skipped, so the client may read the zero as "no mail came".
            assertThat(event.allFoldersSynced()).isTrue();
        }

        @Test
        @DisplayName("A pass nobody waits on publishes nothing")
        void publishesNothingWhenNobodyIsWaiting() {
            when(lockManager.tryLock(eq(ACCOUNT_ID), anyBoolean())).thenReturn(true);
            when(lockManager.unlock(ACCOUNT_ID)).thenReturn(false);
            when(imapFolderService.getFolders(ACCOUNT_ID)).thenReturn(List.of());

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            verify(eventPublisher, never()).publishEvent(any(MailSyncCycleCompletedEvent.class));
        }

        @Test
        @DisplayName("The count sums the folders that deliver new mail, and only those")
        void countSumsFoldersThatDeliverNewMail() throws Exception {
            grantLockAndReport();
            // Sent is mirrored, not delivered: counting it would report three
            // messages the user wrote on their phone as three new ones, and a first
            // pass over a fresh account as hundreds.
            stubFolderCycle(new FolderResponse("INBOX", "INBOX", FolderRole.INBOX),
                    new FolderResponse("Newsletters", "Bulk", FolderRole.NEWSLETTERS),
                    new FolderResponse("Sent", "[Gmail]/Sent", FolderRole.SENT));
            // The pass walks rolesToSync order (INBOX, SENT, …, NEWSLETTERS), not the
            // order the folder list came in: 2 inbox, 300 sent, 3 newsletters.
            when(messageDownloader.syncNewMessages(any())).thenReturn(2).thenReturn(300).thenReturn(3);

            service.syncAllFolders(account, SyncTrigger.MANUAL);

            assertThat(capturePublished(MailSyncCycleCompletedEvent.class).newMessagesCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("A skipped folder marks the pass incomplete, so its zero cannot be read as \"nothing arrived\"")
        void skippedFolderMarksThePassIncomplete() {
            grantLockAndReport();
            when(imapFolderService.getFolders(ACCOUNT_ID))
                    .thenReturn(List.of(new FolderResponse("INBOX", "INBOX", FolderRole.INBOX)));
            // INBOX is being synced by the cycle every GET /emails dispatches; it
            // downloads into the same mailbox without this pass seeing the count.
            when(lockManager.tryLockFolder(ACCOUNT_ID, "INBOX")).thenReturn(false);

            service.syncAllFolders(account, SyncTrigger.MANUAL);

            MailSyncCycleCompletedEvent event = capturePublished(MailSyncCycleCompletedEvent.class);
            assertThat(event.newMessagesCount()).isZero();
            assertThat(event.allFoldersSynced()).isFalse();
        }

        @Test
        @DisplayName("Messages a transient blip left behind are still counted after the retry")
        void countSurvivesATransientRetry() throws Exception {
            grantLockAndReport();
            stubFolderCycle(new FolderResponse("INBOX", "INBOX", FolderRole.INBOX));
            /*
             * The first attempt persists 4 messages and then hits a blip. Those batches
             * committed and advanced lastKnownUid, so the retry starts above them and
             * legitimately downloads none — without carrying the 4 out of the abandoned
             * attempt the pass would report zero for mail that landed.
             */
            when(messageDownloader.syncNewMessages(any())).thenReturn(4).thenReturn(0);
            when(flagSyncService.cleanupDeletedInWindow(any()))
                    .thenThrow(new MessagingException("failed to create new store connection")).thenReturn(List.of());

            service.syncAllFolders(account, SyncTrigger.MANUAL);

            MailSyncCycleCompletedEvent event = capturePublished(MailSyncCycleCompletedEvent.class);
            assertThat(event.newMessagesCount()).isEqualTo(4);
            assertThat(event.allFoldersSynced()).isTrue();
        }

        private void grantLockAndReport() {
            when(lockManager.tryLock(eq(ACCOUNT_ID), anyBoolean())).thenReturn(true);
            when(lockManager.unlock(ACCOUNT_ID)).thenReturn(true);
        }

        private void stubFolderCycle(FolderResponse... folders) throws MessagingException {
            when(imapFolderService.getFolders(ACCOUNT_ID)).thenReturn(List.of(folders));
            stubExecuteInFolderRunCallback(mock(Folder.class));
            stubTransactionTemplateExecuteRunCallback();
            when(syncStateService.getOrCreateState(eq(ACCOUNT_ID), any(), any()))
                    .thenReturn(new FolderSyncStateEntity());
            when(flagSyncService.handleUidValidity(any())).thenReturn(true);
        }

        @Test
        @DisplayName("A pass that failed outright still reports — that is when the user most needs an answer")
        void reportsCompletionAfterFailure() {
            when(lockManager.tryLock(eq(ACCOUNT_ID), anyBoolean())).thenReturn(true);
            when(lockManager.unlock(ACCOUNT_ID)).thenReturn(true);
            when(imapFolderService.getFolders(ACCOUNT_ID)).thenThrow(new IllegalStateException("IMAP down"));

            service.syncAllFolders(account, SyncTrigger.MANUAL);

            assertThat(capturePublished(MailSyncCycleCompletedEvent.class).newMessagesCount()).isZero();
        }

        /**
         * The pass publishes more than one kind of event (the error-state transition
         * rides along), so the assertions pick the one they are about instead of
         * assuming a single publish.
         */
        private <T> T capturePublished(Class<T> eventType) {
            ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(events.capture());
            return events.getAllValues().stream().filter(eventType::isInstance).map(eventType::cast).findFirst()
                    .orElseThrow(() -> new AssertionError("No " + eventType.getSimpleName() + " was published"));
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

            service.syncAndBackfill(account, "INBOX", 0);

            // 1) performFullSyncCycle → executeInFolder
            // 2) downloadRange → executeInFolder
            verify(imapFolderService, times(2)).executeInFolder(eq(ACCOUNT_ID), eq("INBOX"),
                    eq(jakarta.mail.Folder.READ_ONLY), any());
        }

        @Test
        @DisplayName("page>0 only sync, no backfill")
        void noBackfillOnLaterPages() {
            service.syncAndBackfill(account, "INBOX", 1);

            verify(imapFolderService, times(1)).executeInFolder(eq(ACCOUNT_ID), eq("INBOX"),
                    eq(jakarta.mail.Folder.READ_ONLY), any());
            verify(messageRepository, never()).findMinUidByAccountIdAndFolderName(anyLong(), any());
        }

        @Test
        @DisplayName("minUid=1 does not trigger backfill (nothing older can exist)")
        void noBackfillWhenMinUidIsOne() {
            when(messageRepository.findMinUidByAccountIdAndFolderName(ACCOUNT_ID, "INBOX")).thenReturn(Optional.of(1L));

            service.syncAndBackfill(account, "INBOX", 0);

            verify(imapFolderService, times(1)).executeInFolder(eq(ACCOUNT_ID), eq("INBOX"),
                    eq(jakarta.mail.Folder.READ_ONLY), any());
        }

        @Test
        @DisplayName("No messages in DB -> no backfill")
        void noBackfillWhenEmpty() {
            when(messageRepository.findMinUidByAccountIdAndFolderName(ACCOUNT_ID, "INBOX"))
                    .thenReturn(Optional.empty());

            service.syncAndBackfill(account, "INBOX", 0);

            verify(imapFolderService, times(1)).executeInFolder(eq(ACCOUNT_ID), eq("INBOX"),
                    eq(jakarta.mail.Folder.READ_ONLY), any());
        }

        @Test
        @DisplayName("Skips the whole cycle when the folder lock is held (duplicate dispatch dedup)")
        void skipsWhenFolderLockHeld() {
            when(lockManager.tryLockFolder(ACCOUNT_ID, "INBOX")).thenReturn(false);

            service.syncAndBackfill(account, "INBOX", 0);

            verify(imapFolderService, never()).executeInFolder(anyLong(), any(), anyInt(), any());
            verify(messageRepository, never()).findMinUidByAccountIdAndFolderName(anyLong(), any());
            verify(lockManager, never()).unlockFolder(anyLong(), any());
        }

        @Test
        @DisplayName("Releases the folder lock even when the cycle throws")
        void releasesFolderLockOnFailure() {
            when(imapFolderService.executeInFolder(eq(ACCOUNT_ID), eq("INBOX"), eq(jakarta.mail.Folder.READ_ONLY),
                    any())).thenThrow(new RuntimeException("folder open failed"));

            service.syncAndBackfillAsync(account, "INBOX", 1);

            verify(lockManager).unlockFolder(ACCOUNT_ID, "INBOX");
        }

        @Test
        @DisplayName("syncAndBackfillAsync catches outermost exception and writes last_error")
        void asyncBoundaryRecordsLastError() {
            when(imapFolderService.executeInFolder(eq(ACCOUNT_ID), eq("INBOX"), eq(jakarta.mail.Folder.READ_ONLY),
                    any())).thenThrow(new RuntimeException("folder open failed"));

            service.syncAndBackfillAsync(account, "INBOX", 0);

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
    @DisplayName("performFullSyncCycle — transient retry (bug D)")
    class TransientRetry {

        @Test
        @DisplayName("A transient blip is retried after reconnecting and the cycle then succeeds (no last_error)")
        void transientBlipRetriedThenSucceeds() throws Exception {
            stubExecuteInFolderRunCallback(mock(Folder.class));
            stubTransactionTemplateExecuteRunCallback();
            when(syncStateService.getOrCreateState(eq(ACCOUNT_ID), eq("INBOX"), eq(FolderRole.INBOX)))
                    .thenReturn(new FolderSyncStateEntity());
            when(flagSyncService.handleUidValidity(any())).thenReturn(true);
            // First cycle hits the Angus "failed to create new store connection" blip;
            // after reconnecting, the retried cycle succeeds.
            when(messageDownloader.syncNewMessages(any()))
                    .thenThrow(new MessagingException("failed to create new store connection")).thenReturn(0);

            boolean result = service.performFullSyncCycle(account, "INBOX", FolderRole.INBOX);

            assertThat(result).isTrue();
            verify(imapFolderService).invalidateConnection(ACCOUNT_ID);
            verify(accountRepository, never()).updateLastError(anyLong(), any(AccountLastError.class),
                    any(LocalDateTime.class));
        }

        @Test
        @DisplayName("A transient blip that never recovers is recorded once after the bounded retries are exhausted")
        void transientBlipExhaustsRetriesThenRecorded() throws Exception {
            stubExecuteInFolderRunCallback(mock(Folder.class));
            stubTransactionTemplateExecuteRunCallback();
            when(syncStateService.getOrCreateState(eq(ACCOUNT_ID), eq("INBOX"), eq(FolderRole.INBOX)))
                    .thenReturn(new FolderSyncStateEntity());
            when(flagSyncService.handleUidValidity(any())).thenReturn(true);
            when(messageDownloader.syncNewMessages(any()))
                    .thenThrow(new MessagingException("failed to create new store connection"));

            boolean result = service.performFullSyncCycle(account, "INBOX", FolderRole.INBOX);

            assertThat(result).isFalse();
            // maxAttempts=3 -> two reconnect-and-retry rounds before giving up.
            verify(imapFolderService, times(2)).invalidateConnection(ACCOUNT_ID);
            ArgumentCaptor<AccountLastError> captor = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), captor.capture(), any(LocalDateTime.class));
            assertThat(captor.getValue().code()).isEqualTo(AccountLastErrorCode.MAIL_SYNC_FOLDER_FAILED);
        }
    }

    /**
     * The sync error state is pushed to the client edge-triggered: the scheduler
     * runs a pass every five minutes, so anything per-pass would notify the user
     * that often for as long as a mail server stays unreachable.
     */
    @Nested
    @DisplayName("sync error state transitions")
    class ErrorStateTransitions {

        @BeforeEach
        void grantAccountLock() {
            when(lockManager.tryLock(eq(ACCOUNT_ID), anyBoolean())).thenReturn(true);
            lenient().when(imapFolderService.getFolders(ACCOUNT_ID)).thenReturn(List.of());
        }

        @Test
        @DisplayName("First failure emits sync_failed; the pass after it, still failing the same way, emits nothing")
        void emitsOnceWhileTheFailurePersists() {
            when(imapFolderService.getFolders(ACCOUNT_ID)).thenThrow(new IllegalStateException("IMAP down"));
            when(accountRepository.findLastErrorCode(ACCOUNT_ID))
                    .thenReturn(Optional.of(AccountLastErrorCode.MAIL_SYNC_ACCOUNT_FAILED.name()));

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            // The second pass starts from the state the first one left behind — which is
            // what the loaded entity carries in production.
            account.setLastErrorCode(AccountLastErrorCode.MAIL_SYNC_ACCOUNT_FAILED.name());
            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, times(1)).publishEvent(events.capture());
            MailSyncErrorStateChangedEvent event = (MailSyncErrorStateChangedEvent) events.getValue();
            assertThat(event.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(event.errorCode()).isEqualTo(AccountLastErrorCode.MAIL_SYNC_ACCOUNT_FAILED.name());
        }

        @Test
        @DisplayName("A clean pass after a standing failure emits the recovery")
        void emitsRecoveryWhenTheErrorClears() {
            account.setLastErrorCode(AccountLastErrorCode.MAIL_SYNC_FOLDER_FAILED.name());
            when(accountRepository.findLastErrorCode(ACCOUNT_ID)).thenReturn(Optional.empty());

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(events.capture());
            assertThat(((MailSyncErrorStateChangedEvent) events.getValue()).errorCode()).isNull();
        }

        @Test
        @DisplayName("A clean pass on a healthy account emits nothing")
        void staysSilentWhenNothingChanged() {
            when(accountRepository.findLastErrorCode(ACCOUNT_ID)).thenReturn(Optional.empty());

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            verify(eventPublisher, never()).publishEvent(any(MailSyncErrorStateChangedEvent.class));
        }

        @Test
        @DisplayName("A failure that swaps one code for another is not a transition")
        void staysSilentWhenOneFailureCodeReplacesAnother() {
            /*
             * The shape that made the client announce the same outage twice. last_error is
             * one account-scoped slot and syncAndBackfill writes MAIL_SYNC_FOLDER_FAILED
             * into it without publishing, so while a mail server is down the code flips
             * between the folder and account variants and every scheduled cycle looked like
             * a fresh failure. The client cannot even tell them apart — it reads only
             * whether errorCode is null.
             */
            account.setLastErrorCode(AccountLastErrorCode.MAIL_SYNC_FOLDER_FAILED.name());
            when(imapFolderService.getFolders(ACCOUNT_ID)).thenThrow(new IllegalStateException("IMAP down"));
            when(accountRepository.findLastErrorCode(ACCOUNT_ID))
                    .thenReturn(Optional.of(AccountLastErrorCode.MAIL_SYNC_ACCOUNT_FAILED.name()));

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            verify(eventPublisher, never()).publishEvent(any(MailSyncErrorStateChangedEvent.class));
        }

        @Test
        @DisplayName("An account that starts failing still emits, whichever code it lands on")
        void emitsWhenAHealthyAccountStartsFailing() {
            when(imapFolderService.getFolders(ACCOUNT_ID)).thenThrow(new IllegalStateException("IMAP down"));
            when(accountRepository.findLastErrorCode(ACCOUNT_ID))
                    .thenReturn(Optional.of(AccountLastErrorCode.MAIL_SYNC_FOLDER_FAILED.name()));

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(events.capture());
            assertThat(((MailSyncErrorStateChangedEvent) events.getValue()).errorCode())
                    .isEqualTo(AccountLastErrorCode.MAIL_SYNC_FOLDER_FAILED.name());
        }

        @Test
        @DisplayName("A failing state read never turns a completed sync into a failed one")
        void readFailureIsSwallowed() {
            when(accountRepository.findLastErrorCode(ACCOUNT_ID)).thenThrow(new IllegalStateException("DB busy"));

            service.syncAllFolders(account, SyncTrigger.SCHEDULED);

            verify(lockManager).unlock(ACCOUNT_ID);
        }
    }
}
