package org.voxrox.mailbackend.feature.mail.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;

/**
 * Unit tests for {@link MailSyncScheduler}. The value of this class is its
 * scheduling contract: it syncs only active, non-reauth accounts, and isolates
 * per-account failures so one broken mailbox cannot abort the whole cycle.
 */
@ExtendWith(MockitoExtension.class)
class MailSyncSchedulerTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private MailSyncService mailSyncService;
    @InjectMocks
    private MailSyncScheduler scheduler;

    private static AccountEntity accountWithEmail(String email) {
        AccountEntity account = mock(AccountEntity.class);
        when(account.getEmail()).thenReturn(email);
        return account;
    }

    @Test
    @DisplayName("No active accounts -> the sync service is never invoked")
    void noActiveAccountsSkipsSync() {
        when(accountRepository.findByActiveTrueAndRequiresReauthFalse()).thenReturn(List.of());

        scheduler.syncAccounts();

        verifyNoInteractions(mailSyncService);
    }

    @Test
    @DisplayName("Every active (non-reauth) account is synced exactly once")
    void syncsEachActiveAccount() {
        AccountEntity first = accountWithEmail("first@example.com");
        AccountEntity second = accountWithEmail("second@example.com");
        when(accountRepository.findByActiveTrueAndRequiresReauthFalse()).thenReturn(List.of(first, second));

        scheduler.syncAccounts();

        verify(mailSyncService).syncAllFolders(first);
        verify(mailSyncService).syncAllFolders(second);
        verifyNoMoreInteractions(mailSyncService);
    }

    @Test
    @DisplayName("A failure on one account does not stop the others (per-account isolation)")
    void oneAccountFailureDoesNotStopOthers() {
        AccountEntity failing = accountWithEmail("failing@example.com");
        AccountEntity healthy = accountWithEmail("healthy@example.com");
        when(accountRepository.findByActiveTrueAndRequiresReauthFalse()).thenReturn(List.of(failing, healthy));
        doThrow(new RuntimeException("IMAP unreachable")).when(mailSyncService).syncAllFolders(failing);

        scheduler.syncAccounts();

        verify(mailSyncService).syncAllFolders(failing);
        verify(mailSyncService).syncAllFolders(healthy);
    }
}
