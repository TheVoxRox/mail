package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.repository.FolderSyncStateRepository;

/**
 * Unit tests for {@link SyncStateService}. Focus is the
 * {@code getOrCreateState} role-detection branching; the targeted
 * {@code update*} methods are covered as thin delegations that bypass JPA merge
 * (and the {@code @Version} guard) by design — verifying the forward proves the
 * hot path never round-trips a managed entity.
 */
@ExtendWith(MockitoExtension.class)
class SyncStateServiceTest {

    private static final Long ACCOUNT_ID = 7L;
    private static final Long STATE_ID = 555L;
    private static final String FOLDER = "INBOX";

    @Mock
    private FolderSyncStateRepository syncStateRepository;

    @Mock
    private AccountRepository accountRepository;

    private SyncStateService service;

    @BeforeEach
    void setUp() {
        service = new SyncStateService(syncStateRepository, accountRepository);
    }

    @Nested
    @DisplayName("getOrCreateState")
    class GetOrCreateState {

        @Test
        @DisplayName("Creates and persists a new state with the detected role when none exists")
        void createsWhenAbsent() {
            when(syncStateRepository.findByAccountIdAndFolderName(ACCOUNT_ID, FOLDER)).thenReturn(Optional.empty());
            when(accountRepository.getReferenceById(ACCOUNT_ID)).thenReturn(new AccountEntity());
            when(syncStateRepository.save(any(FolderSyncStateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            FolderSyncStateEntity result = service.getOrCreateState(ACCOUNT_ID, FOLDER, FolderRole.INBOX);

            assertThat(result.getRole()).isEqualTo(FolderRole.INBOX);
            verify(syncStateRepository).save(any(FolderSyncStateEntity.class));
        }

        @Test
        @DisplayName("Upgrades a USER role when a better role is now detected, and persists it")
        void upgradesUserRoleOnBetterDetection() {
            FolderSyncStateEntity existing = new FolderSyncStateEntity();
            existing.setRole(FolderRole.USER);
            when(syncStateRepository.findByAccountIdAndFolderName(ACCOUNT_ID, FOLDER))
                    .thenReturn(Optional.of(existing));
            when(syncStateRepository.save(existing)).thenReturn(existing);

            FolderSyncStateEntity result = service.getOrCreateState(ACCOUNT_ID, FOLDER, FolderRole.INBOX);

            assertThat(result.getRole()).isEqualTo(FolderRole.INBOX);
            verify(syncStateRepository).save(existing);
        }

        @Test
        @DisplayName("Keeps an already-classified non-USER role untouched — no write")
        void keepsExistingNonUserRole() {
            FolderSyncStateEntity existing = new FolderSyncStateEntity();
            existing.setRole(FolderRole.SENT);
            when(syncStateRepository.findByAccountIdAndFolderName(ACCOUNT_ID, FOLDER))
                    .thenReturn(Optional.of(existing));

            FolderSyncStateEntity result = service.getOrCreateState(ACCOUNT_ID, FOLDER, FolderRole.INBOX);

            assertThat(result.getRole()).isEqualTo(FolderRole.SENT);
            verify(syncStateRepository, never()).save(any());
        }

        @Test
        @DisplayName("Leaves USER as-is when detection is also USER — no write")
        void keepsUserWhenDetectionAlsoUser() {
            FolderSyncStateEntity existing = new FolderSyncStateEntity();
            existing.setRole(FolderRole.USER);
            when(syncStateRepository.findByAccountIdAndFolderName(ACCOUNT_ID, FOLDER))
                    .thenReturn(Optional.of(existing));

            FolderSyncStateEntity result = service.getOrCreateState(ACCOUNT_ID, FOLDER, FolderRole.USER);

            assertThat(result.getRole()).isEqualTo(FolderRole.USER);
            verify(syncStateRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Targeted updates delegate to the repository")
    class TargetedUpdates {

        @Test
        void saveSyncStateDelegates() {
            FolderSyncStateEntity state = new FolderSyncStateEntity();
            service.saveSyncState(state);
            verify(syncStateRepository).save(state);
        }

        @Test
        void updateLastKnownUidDelegates() {
            service.updateLastKnownUid(STATE_ID, 42L);
            verify(syncStateRepository).updateLastKnownUid(STATE_ID, 42L);
        }

        @Test
        void updateUidValidityDelegates() {
            service.updateUidValidity(STATE_ID, 123L);
            verify(syncStateRepository).updateUidValidity(STATE_ID, 123L);
        }

        @Test
        void updateLastKnownModseqDelegates() {
            service.updateLastKnownModseq(STATE_ID, 999L);
            verify(syncStateRepository).updateLastKnownModseq(STATE_ID, 999L);
        }

        @Test
        void touchLastSyncAtDelegates() {
            LocalDateTime when = LocalDateTime.of(2026, 6, 15, 8, 0);
            service.touchLastSyncAt(STATE_ID, when);
            verify(syncStateRepository).updateLastSyncAt(STATE_ID, when);
        }

        @Test
        void resetForUidValidityChangeDelegates() {
            service.resetForUidValidityChange(STATE_ID, 456L);
            verify(syncStateRepository).resetForUidValidityChange(STATE_ID, 456L);
        }
    }
}
