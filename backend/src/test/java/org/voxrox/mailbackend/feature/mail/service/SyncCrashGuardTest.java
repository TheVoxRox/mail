package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;

@ExtendWith(MockitoExtension.class)
class SyncCrashGuardTest {

    private static final Long ACCOUNT_ID = 7L;
    private static final Long OTHER_ACCOUNT_ID = 8L;

    @Mock
    private AccountRepository accountRepository;

    private final MockEnvironment environment = new MockEnvironment();

    private SyncCrashGuard guard;

    @BeforeEach
    void setUp() {
        guard = new SyncCrashGuard(accountRepository, environment);
    }

    private static AccountEntity account(int crashes) {
        return account(ACCOUNT_ID, crashes);
    }

    private static AccountEntity account(Long id, int crashes) {
        AccountEntity account = new AccountEntity();
        account.setId(id);
        account.setEmail("test@example.com");
        account.setSyncCrashes(crashes);
        return account;
    }

    @Nested
    @DisplayName("at startup")
    class AtStartup {

        @Test
        @DisplayName("A sync the shell restarted the backend out of counts as a crash")
        void countsASyncTheShellRestartedOutOf() {
            when(accountRepository.findBySyncStartedAtIsNotNull()).thenReturn(List.of(account(0)));

            guard.reconcileInterruptedSyncs("3");

            verify(accountRepository).recordSyncCrash(ACCOUNT_ID, 1, false);
            verify(accountRepository, never()).updateLastError(anyLong(), any(), any());
        }

        @Test
        @DisplayName("The third crash in a row pauses the sync and says why in last_error")
        void pausesAtTheLimit() {
            when(accountRepository.findBySyncStartedAtIsNotNull())
                    .thenReturn(List.of(account(SyncCrashGuard.CRASHES_BEFORE_PAUSE - 1)));

            guard.reconcileInterruptedSyncs("3");

            verify(accountRepository).recordSyncCrash(ACCOUNT_ID, SyncCrashGuard.CRASHES_BEFORE_PAUSE, true);
            ArgumentCaptor<AccountLastError> error = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), error.capture(), any(LocalDateTime.class));
            assertThat(error.getValue().code()).isEqualTo(AccountLastErrorCode.MAIL_SYNC_PAUSED_AFTER_CRASHES);
            assertThat(error.getValue().args()).isEmpty();
        }

        @Test
        @DisplayName("An end the shell asked for, such as quitting the app, is not counted")
        void doesNotCountAnEndTheShellAskedFor() {
            /*
             * The shell kills the backend on every quit and reload, so a first sync long
             * enough to be cut short three times would otherwise pause an account nothing
             * is wrong with.
             */
            when(accountRepository.findBySyncStartedAtIsNotNull())
                    .thenReturn(List.of(account(SyncCrashGuard.CRASHES_BEFORE_PAUSE - 1)));

            guard.reconcileInterruptedSyncs(null);

            verify(accountRepository).clearSyncStarted(ACCOUNT_ID);
            verify(accountRepository, never()).recordSyncCrash(anyLong(), anyInt(), anyBoolean());
            verify(accountRepository, never()).updateLastError(anyLong(), any(), any());
        }

        @Test
        @DisplayName("Reads the previous exit from the variable the shell sets")
        void readsThePreviousExitFromTheShell() {
            environment.setProperty(SyncCrashGuard.PREVIOUS_EXIT_VARIABLE, "3");
            when(accountRepository.findBySyncStartedAtIsNotNull()).thenReturn(List.of(account(0)));

            guard.afterSingletonsInstantiated();

            verify(accountRepository).recordSyncCrash(ACCOUNT_ID, 1, false);
        }

        @Test
        @DisplayName("A database that cannot be read leaves the startup alone")
        void toleratesAnUnreadableDatabase() {
            when(accountRepository.findBySyncStartedAtIsNotNull())
                    .thenThrow(new DataAccessResourceFailureException("locked"));

            guard.reconcileInterruptedSyncs("3");

            verify(accountRepository, never()).recordSyncCrash(anyLong(), anyInt(), anyBoolean());
        }

        @Test
        @DisplayName("A transaction that cannot begin does not end the startup either")
        void toleratesATransactionThatCannotBegin() {
            /*
             * A TransactionException is not a DataAccessException. Escaping
             * afterSingletonsInstantiated it would end the context's startup, so the guard
             * meant to stop a crash loop would start one.
             */
            when(accountRepository.findBySyncStartedAtIsNotNull()).thenReturn(List.of(account(0)));
            doThrow(new CannotCreateTransactionException("busy")).when(accountRepository).recordSyncCrash(ACCOUNT_ID, 1,
                    false);

            guard.reconcileInterruptedSyncs("3");

            when(accountRepository.findBySyncStartedAtIsNotNull())
                    .thenThrow(new CannotCreateTransactionException("busy"));

            guard.reconcileInterruptedSyncs("3");
        }
    }

    @Nested
    @DisplayName("while syncing")
    class WhileSyncing {

        @Test
        @DisplayName("The marker is set by the first sync of an account and cleared by the last")
        void overlappingSyncsShareOneMarker() {
            AccountEntity account = account(0);
            assertThat(guard.enter(account)).isTrue();
            assertThat(guard.enter(account)).isTrue();
            guard.exit(ACCOUNT_ID);

            verify(accountRepository, times(1)).markSyncStarted(eq(ACCOUNT_ID), any(LocalDateTime.class));
            verify(accountRepository, never()).clearSyncStarted(anyLong());

            guard.exit(ACCOUNT_ID);

            verify(accountRepository).clearSyncStarted(ACCOUNT_ID);
        }

        @Test
        @DisplayName("A sync that ends clears the marker but not the crash count: only a whole pass does that")
        void endOfASyncLeavesTheCrashCount() {
            guard.enter(account(2));
            guard.exit(ACCOUNT_ID);

            verify(accountRepository).clearSyncStarted(ACCOUNT_ID);
            verify(accountRepository, never()).clearSyncCrashes(anyLong());
        }

        @Test
        @DisplayName("A whole pass starts the crash count over, and writes nothing when there is none")
        void wholePassStartsTheCountOver() {
            guard.passCompleted(account(0));

            verify(accountRepository, never()).clearSyncCrashes(anyLong());

            guard.passCompleted(account(2));

            verify(accountRepository).clearSyncCrashes(ACCOUNT_ID);
        }

        @Test
        @DisplayName("A failure to record the marker does not stop the sync")
        void toleratesAFailedWrite() {
            doThrow(new DataAccessResourceFailureException("locked")).when(accountRepository)
                    .markSyncStarted(eq(ACCOUNT_ID), any(LocalDateTime.class));

            assertThat(guard.enter(account(0))).isTrue();
            guard.exit(ACCOUNT_ID);

            verify(accountRepository).clearSyncStarted(ACCOUNT_ID);
        }

        @Test
        @DisplayName("A transaction that cannot begin or commit does not escape either, so no sync lock is left held")
        void toleratesATransactionFailure() {
            doThrow(new CannotCreateTransactionException("busy")).when(accountRepository)
                    .markSyncStarted(eq(ACCOUNT_ID), any(LocalDateTime.class));
            doThrow(new TransactionSystemException("commit failed")).when(accountRepository)
                    .clearSyncStarted(ACCOUNT_ID);
            doThrow(new CannotCreateTransactionException("busy")).when(accountRepository).clearSyncCrashes(ACCOUNT_ID);

            assertThat(guard.enter(account(1))).isTrue();
            guard.passCompleted(account(1));
            guard.exit(ACCOUNT_ID);
        }

        @Test
        @DisplayName("An exit without an enter writes nothing, and an extra exit cannot unbalance the count")
        void unpairedExitIsANoOp() {
            guard.exit(ACCOUNT_ID);

            verify(accountRepository, never()).clearSyncStarted(anyLong());

            guard.enter(account(0));
            guard.exit(ACCOUNT_ID);
            guard.exit(ACCOUNT_ID);
            guard.enter(account(0));

            verify(accountRepository, times(2)).markSyncStarted(eq(ACCOUNT_ID), any(LocalDateTime.class));
        }
    }

    @Nested
    @DisplayName("isolating accounts with a crash on record")
    class Isolating {

        @Test
        @DisplayName("Two accounts with a crash on record do not sync at once, so the next crash names one")
        void accountsWithACrashOnRecordSyncOneAtATime() {
            assertThat(guard.enter(account(ACCOUNT_ID, 1))).isTrue();

            assertThat(guard.enter(account(OTHER_ACCOUNT_ID, 1))).isFalse();
            verify(accountRepository, never()).markSyncStarted(eq(OTHER_ACCOUNT_ID), any(LocalDateTime.class));

            guard.exit(ACCOUNT_ID);

            assertThat(guard.enter(account(OTHER_ACCOUNT_ID, 1))).isTrue();
            verify(accountRepository).markSyncStarted(eq(OTHER_ACCOUNT_ID), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("The account that holds the turn can run its pass and its read path together")
        void holderReentersFreely() {
            AccountEntity suspect = account(1);
            assertThat(guard.enter(suspect)).isTrue();
            assertThat(guard.enter(suspect)).isTrue();
            guard.exit(ACCOUNT_ID);

            // Still in flight once, so still holding the turn.
            assertThat(guard.enter(account(OTHER_ACCOUNT_ID, 1))).isFalse();

            guard.exit(ACCOUNT_ID);

            assertThat(guard.enter(account(OTHER_ACCOUNT_ID, 1))).isTrue();
        }

        @Test
        @DisplayName("An account with no crash on record syncs beside one that has")
        void accountWithoutACrashIsNotHeld() {
            assertThat(guard.enter(account(ACCOUNT_ID, 1))).isTrue();

            assertThat(guard.enter(account(OTHER_ACCOUNT_ID, 0))).isTrue();
        }

        @Test
        @DisplayName("A body download is never turned away, and does not take the turn")
        void bodyDownloadIsNotIsolated() {
            assertThat(guard.enter(account(ACCOUNT_ID, 1))).isTrue();

            assertThat(guard.whileFetching(OTHER_ACCOUNT_ID, () -> "body")).isEqualTo("body");

            guard.exit(ACCOUNT_ID);
            assertThat(guard.enter(account(OTHER_ACCOUNT_ID, 1))).isTrue();
        }
    }

    @Nested
    @DisplayName("downloading a body")
    class DownloadingABody {

        @Test
        @DisplayName("The download is marked as a running sync and unmarked after it, also when it fails")
        void marksTheDownload() {
            assertThatThrownBy(() -> guard.whileFetching(ACCOUNT_ID, () -> {
                throw new IllegalStateException("IMAP down");
            })).isInstanceOf(IllegalStateException.class);

            verify(accountRepository).markSyncStarted(eq(ACCOUNT_ID), any(LocalDateTime.class));
            verify(accountRepository).clearSyncStarted(ACCOUNT_ID);
        }

        @Test
        @DisplayName("Whether an account is paused is read from the database; a failed read answers no")
        void readsThePause() {
            when(accountRepository.isSyncPaused(ACCOUNT_ID)).thenReturn(Optional.of(true));
            when(accountRepository.isSyncPaused(OTHER_ACCOUNT_ID))
                    .thenThrow(new CannotCreateTransactionException("busy"));

            assertThat(guard.isPaused(ACCOUNT_ID)).isTrue();
            assertThat(guard.isPaused(OTHER_ACCOUNT_ID)).isFalse();
        }
    }

    @Nested
    @DisplayName("resuming")
    class Resuming {

        @Test
        @DisplayName("A manual sync lifts the pause one crash short of the limit and clears the pause's error")
        void resumesOneCrashShortOfTheLimit() {
            AccountEntity paused = account(SyncCrashGuard.CRASHES_BEFORE_PAUSE);
            paused.setSyncPaused(true);

            assertThat(guard.resumeIfPaused(paused)).isTrue();

            verify(accountRepository).resumeSync(ACCOUNT_ID, SyncCrashGuard.CRASHES_BEFORE_PAUSE - 1);
            verify(accountRepository).clearLastErrorIfCodeIn(ACCOUNT_ID,
                    List.of(AccountLastErrorCode.MAIL_SYNC_PAUSED_AFTER_CRASHES.name()));
        }

        @Test
        @DisplayName("An account that is not paused is left as it is")
        void leavesAnAccountThatIsNotPaused() {
            assertThat(guard.resumeIfPaused(account(1))).isFalse();

            verify(accountRepository, never()).resumeSync(anyLong(), anyInt());
            verify(accountRepository, never()).clearLastErrorIfCodeIn(anyLong(), any());
        }
    }
}
