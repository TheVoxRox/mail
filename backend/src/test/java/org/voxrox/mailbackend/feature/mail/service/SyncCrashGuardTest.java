package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;

@ExtendWith(MockitoExtension.class)
class SyncCrashGuardTest {

    private static final Long ACCOUNT_ID = 7L;

    @Mock
    private AccountRepository accountRepository;

    private final MockEnvironment environment = new MockEnvironment();

    private SyncCrashGuard guard;

    @BeforeEach
    void setUp() {
        guard = new SyncCrashGuard(accountRepository, environment);
    }

    private static AccountEntity account(int crashes) {
        AccountEntity account = new AccountEntity();
        account.setId(ACCOUNT_ID);
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
    }

    @Nested
    @DisplayName("while syncing")
    class WhileSyncing {

        @Test
        @DisplayName("The marker is set by the first sync of an account and cleared by the last")
        void overlappingSyncsShareOneMarker() {
            guard.enter(ACCOUNT_ID);
            guard.enter(ACCOUNT_ID);
            guard.exit(ACCOUNT_ID);

            verify(accountRepository, times(1)).markSyncStarted(eq(ACCOUNT_ID), any(LocalDateTime.class));
            verify(accountRepository, never()).markSyncEnded(anyLong());

            guard.exit(ACCOUNT_ID);

            verify(accountRepository).markSyncEnded(ACCOUNT_ID);
        }

        @Test
        @DisplayName("A failure to record the marker does not stop the sync")
        void toleratesAFailedWrite() {
            doThrow(new DataAccessResourceFailureException("locked")).when(accountRepository)
                    .markSyncStarted(eq(ACCOUNT_ID), any(LocalDateTime.class));

            guard.enter(ACCOUNT_ID);
            guard.exit(ACCOUNT_ID);

            verify(accountRepository).markSyncEnded(ACCOUNT_ID);
        }

        @Test
        @DisplayName("An exit without an enter writes nothing")
        void exitWithoutEnterIsANoOp() {
            guard.exit(ACCOUNT_ID);

            verify(accountRepository, never()).markSyncEnded(anyLong());
        }
    }

    @Nested
    @DisplayName("resuming")
    class Resuming {

        @Test
        @DisplayName("A manual sync lifts the pause one crash short of the limit")
        void resumesOneCrashShortOfTheLimit() {
            AccountEntity paused = account(SyncCrashGuard.CRASHES_BEFORE_PAUSE);
            paused.setSyncPaused(true);

            assertThat(guard.resumeIfPaused(paused)).isTrue();

            verify(accountRepository).resumeSync(ACCOUNT_ID, SyncCrashGuard.CRASHES_BEFORE_PAUSE - 1);
        }

        @Test
        @DisplayName("An account that is not paused is left as it is")
        void leavesAnAccountThatIsNotPaused() {
            assertThat(guard.resumeIfPaused(account(1))).isFalse();

            verify(accountRepository, never()).resumeSync(anyLong(), anyInt());
        }
    }
}
