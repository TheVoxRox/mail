package org.voxrox.mailbackend.core.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.config.mail.SyncProperties;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.mail.repository.FolderSyncStateRepository;

/**
 * Unit tests for {@link SyncHealthIndicator}.
 *
 * Key branching: UP / DOWN based on (recent vs stale vs neverSynced) and the
 * exclusion of {@code requiresReauth} accounts from the evaluation (they do not
 * count as stale).
 */
class SyncHealthIndicatorTest {

    private static final Duration SYNC_INTERVAL = Duration.ofMinutes(5);
    // staleThreshold = 2 x interval = 10 min. Instance fields, not static —
    // a static initialiser would capture the class-load time and silently age
    // across a long test run.
    private final LocalDateTime RECENT = LocalDateTime.now().minusMinutes(2);
    private final LocalDateTime STALE = LocalDateTime.now().minusHours(1);

    private AccountRepository accountRepo;
    private FolderSyncStateRepository syncStateRepo;
    private SyncHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        accountRepo = mock(AccountRepository.class);
        syncStateRepo = mock(FolderSyncStateRepository.class);

        SyncProperties sync = new SyncProperties(100, 200, SYNC_INTERVAL, Duration.ofSeconds(10), 50, 30, 300, 4, 256,
                200, Duration.ofMinutes(30), Duration.ofSeconds(30));
        MailClientProperties props = new MailClientProperties(null, null, sync, null);

        indicator = new SyncHealthIndicator(accountRepo, syncStateRepo, props);
    }

    private AccountEntity account(long id, boolean requiresReauth) {
        AccountEntity a = new AccountEntity();
        a.setId(id);
        a.setActive(true);
        a.setRequiresReauth(requiresReauth);
        return a;
    }

    private Object[] row(long accountId, LocalDateTime lastSync) {
        return new Object[]{accountId, lastSync};
    }

    /**
     * {@code List.of(Object[])} expands as varargs — we must type it explicitly.
     */
    @SafeVarargs
    private static List<Object[]> rows(Object[]... rs) {
        return java.util.Arrays.asList(rs);
    }

    @Nested
    @DisplayName("No active accounts")
    class NoActive {

        @Test
        void noActiveAccount_isUP_withNote() {
            when(accountRepo.findByActiveTrue()).thenReturn(List.of());

            Health h = indicator.health();

            assertThat(h.getStatus()).isEqualTo(Status.UP);
            assertThat(h.getDetails()).containsEntry("activeAccounts", 0);
            assertThat(h.getDetails()).containsKey("note");
        }
    }

    @Nested
    @DisplayName("Classification recent / stale / neverSynced")
    class Classification {

        @Test
        void allRecentSync_isUP() {
            when(accountRepo.findByActiveTrue()).thenReturn(List.of(account(1, false), account(2, false)));
            doReturn(rows(row(1, RECENT), row(2, RECENT))).when(syncStateRepo)
                    .findMaxLastSyncAtByAccountIds(anyCollection());

            Health h = indicator.health();

            assertThat(h.getStatus()).isEqualTo(Status.UP);
            assertThat(h.getDetails()).containsEntry("recentlySynced", 2);
            assertThat(h.getDetails()).containsEntry("staleSync", 0);
        }

        @Test
        void allStale_isDOWN_withIdsInDetails() {
            when(accountRepo.findByActiveTrue()).thenReturn(List.of(account(1, false), account(2, false)));
            doReturn(rows(row(1, STALE), row(2, STALE))).when(syncStateRepo)
                    .findMaxLastSyncAtByAccountIds(anyCollection());

            Health h = indicator.health();

            assertThat(h.getStatus()).isEqualTo(Status.DOWN);
            assertThat(h.getDetails()).containsEntry("staleSync", 2);
            assertThat(h.getDetails()).containsEntry("recentlySynced", 0);
            @SuppressWarnings("unchecked")
            List<Long> staleIds = (List<Long>) h.getDetails().get("staleAccountIds");
            assertThat(staleIds).containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        void partialFailure_atLeast1Recent_isUP() {
            // 1 recent + 4 stale -> UP (graceful degradation, per-account log handles the
            // rest)
            when(accountRepo.findByActiveTrue()).thenReturn(List.of(account(1, false), account(2, false),
                    account(3, false), account(4, false), account(5, false)));
            doReturn(rows(row(1, RECENT), row(2, STALE), row(3, STALE), row(4, STALE), row(5, STALE)))
                    .when(syncStateRepo).findMaxLastSyncAtByAccountIds(anyCollection());

            Health h = indicator.health();

            assertThat(h.getStatus()).isEqualTo(Status.UP);
            assertThat(h.getDetails()).containsEntry("recentlySynced", 1);
            assertThat(h.getDetails()).containsEntry("staleSync", 4);
        }

        @Test
        void allNeverSynced_startupGrace_isUP() {
            when(accountRepo.findByActiveTrue()).thenReturn(List.of(account(1, false), account(2, false)));
            doReturn(rows()).when(syncStateRepo).findMaxLastSyncAtByAccountIds(anyCollection()); // no rows

            Health h = indicator.health();

            assertThat(h.getStatus()).isEqualTo(Status.UP);
            assertThat(h.getDetails()).containsEntry("neverSynced", 2);
            assertThat(h.getDetails()).containsEntry("recentlySynced", 0);
            assertThat(h.getDetails()).containsEntry("staleSync", 0);
        }
    }

    @Nested
    @DisplayName("requiresReauth accounts do not count as stale")
    class RequiresReauth {

        @Test
        void singleAccountRequiresReauth_isUP_notDOWN() {
            // Without the new logic this account would land in staleSync (lastSync is null
            // -> no, it would be neverSynced; but after the first attempt lastSync would be
            // frozen).
            // Key: it is not counted among syncable, so it does not push health to DOWN.
            when(accountRepo.findByActiveTrue()).thenReturn(List.of(account(1, true)));

            Health h = indicator.health();

            assertThat(h.getStatus()).isEqualTo(Status.UP);
            assertThat(h.getDetails()).containsEntry("activeAccounts", 1);
            assertThat(h.getDetails()).containsEntry("requiresReauth", 1);
        }

        @Test
        void mixRequiresReauthAndStale_DOWN_dueToSyncable() {
            // 1 requires_reauth + 2 stale syncable -> DOWN (due to stale, not reauth)
            when(accountRepo.findByActiveTrue())
                    .thenReturn(List.of(account(1, true), account(2, false), account(3, false)));
            doReturn(rows(row(2, STALE), row(3, STALE))).when(syncStateRepo)
                    .findMaxLastSyncAtByAccountIds(anyCollection());

            Health h = indicator.health();

            assertThat(h.getStatus()).isEqualTo(Status.DOWN);
            assertThat(h.getDetails()).containsEntry("requiresReauth", 1);
            assertThat(h.getDetails()).containsEntry("staleSync", 2);
        }

        @Test
        void mixRequiresReauthAndStale_butOneRecent_isUP() {
            when(accountRepo.findByActiveTrue())
                    .thenReturn(List.of(account(1, true), account(2, false), account(3, false)));
            doReturn(rows(row(2, RECENT), row(3, STALE))).when(syncStateRepo)
                    .findMaxLastSyncAtByAccountIds(anyCollection());

            Health h = indicator.health();

            assertThat(h.getStatus()).isEqualTo(Status.UP);
            assertThat(h.getDetails()).containsEntry("requiresReauth", 1);
            assertThat(h.getDetails()).containsEntry("recentlySynced", 1);
        }
    }

    @Nested
    @DisplayName("Details in the response")
    class Details {

        @Test
        void staleAccountIds_cappedAt10() {
            // 12 stale accounts -> staleAccountIds has only 10
            java.util.List<AccountEntity> many = new java.util.ArrayList<>();
            java.util.List<Object[]> rows = new java.util.ArrayList<Object[]>();
            for (long i = 1; i <= 12; i++) {
                many.add(account(i, false));
                rows.add(row(i, STALE));
            }
            when(accountRepo.findByActiveTrue()).thenReturn(many);
            doReturn(rows).when(syncStateRepo).findMaxLastSyncAtByAccountIds(anyCollection());

            Health h = indicator.health();

            assertThat(h.getStatus()).isEqualTo(Status.DOWN);
            assertThat((List<?>) h.getDetails().get("staleAccountIds")).hasSize(10);
            assertThat(h.getDetails()).containsEntry("staleSync", 12);
        }

        @Test
        void staleThresholdSeconds_is2xInterval() {
            when(accountRepo.findByActiveTrue()).thenReturn(List.of(account(1, false)));
            doReturn(rows(row(1, RECENT))).when(syncStateRepo).findMaxLastSyncAtByAccountIds(anyCollection());

            Health h = indicator.health();

            // 5 min x 2 = 600 s
            assertThat(h.getDetails()).containsEntry("staleThresholdSeconds", 600L);
        }
    }
}
