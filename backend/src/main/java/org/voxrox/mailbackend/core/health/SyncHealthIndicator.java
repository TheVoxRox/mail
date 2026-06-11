package org.voxrox.mailbackend.core.health;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.mail.repository.FolderSyncStateRepository;

/**
 * Health indicator for the mail synchronization state.
 *
 * Accounts waiting for re-auth are excluded from the sync rotation and reported
 * separately as {@code requiresReauth}. They must not push the health to DOWN:
 * they require user action, not a server restart.
 */
@Component
public class SyncHealthIndicator implements HealthIndicator {

    private final AccountRepository accountRepository;
    private final FolderSyncStateRepository syncStateRepository;
    private final Duration staleThreshold;

    public SyncHealthIndicator(AccountRepository accountRepository, FolderSyncStateRepository syncStateRepository,
            MailClientProperties mailProps) {
        this.accountRepository = accountRepository;
        this.syncStateRepository = syncStateRepository;
        // 2x sync interval = tolerance for delay + one missed cycle.
        this.staleThreshold = mailProps.sync().interval().multipliedBy(2);
    }

    /**
     * Maximum number of stale account IDs in details — more would clutter the
     * /health response.
     */
    private static final int STALE_IDS_LIMIT = 10;

    @Override
    public Health health() {
        List<AccountEntity> activeAccounts = accountRepository.findByActiveTrue();
        int active = activeAccounts.size();

        if (active == 0) {
            return Health.up().withDetail("activeAccounts", 0)
                    .withDetail("note", "No active accounts — sync is not relevant.").build();
        }

        // Re-auth accounts are not stale; the scheduler intentionally skips them.
        List<AccountEntity> syncable = activeAccounts.stream().filter(a -> !a.isRequiresReauth()).toList();
        int requiresReauth = active - syncable.size();

        if (syncable.isEmpty()) {
            return Health.up().withDetail("activeAccounts", active).withDetail("requiresReauth", requiresReauth)
                    .withDetail("note", "All active accounts are waiting for re-auth — sync is not relevant.").build();
        }

        // A single aggregated query keeps the number of SQL round-trips constant.
        List<Long> syncableIds = syncable.stream().map(AccountEntity::getId).toList();
        Map<Long, LocalDateTime> lastSyncByAccount = syncStateRepository.findMaxLastSyncAtByAccountIds(syncableIds)
                .stream().collect(Collectors.toMap(row -> (Long) row[0], row -> (LocalDateTime) row[1]));

        LocalDateTime cutoff = LocalDateTime.now().minus(staleThreshold);

        int recent = 0;
        int stale = 0;
        int neverSynced = 0;
        List<Long> staleIds = new ArrayList<>();

        for (AccountEntity account : syncable) {
            LocalDateTime lastSync = lastSyncByAccount.get(account.getId());
            if (lastSync == null) {
                neverSynced++;
            } else if (lastSync.isAfter(cutoff)) {
                recent++;
            } else {
                stale++;
                if (staleIds.size() < STALE_IDS_LIMIT) {
                    staleIds.add(account.getId());
                }
            }
        }

        // Startup grace: when all accounts are neverSynced, health stays UP until the
        // first sync runs.
        Health.Builder builder = (recent > 0 || neverSynced == syncable.size()) ? Health.up() : Health.down();

        return builder.withDetail("activeAccounts", active).withDetail("requiresReauth", requiresReauth)
                .withDetail("recentlySynced", recent).withDetail("staleSync", stale)
                .withDetail("staleAccountIds", staleIds).withDetail("neverSynced", neverSynced)
                .withDetail("staleThresholdSeconds", staleThreshold.toSeconds()).build();
    }
}
