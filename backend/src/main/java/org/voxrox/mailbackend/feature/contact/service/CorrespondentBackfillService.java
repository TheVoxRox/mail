package org.voxrox.mailbackend.feature.contact.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.contact.repository.CorrespondentRepository;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.repository.CorrespondentBackfillRow;
import org.voxrox.mailbackend.feature.mail.repository.FolderSyncStateRepository;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

/**
 * Builds the {@code correspondent} cache from the messages already in the
 * database.
 *
 * <p>
 * Without this the typeahead would only ever know mail that arrived after the
 * feature shipped — which is the empty-address-book problem it exists to solve,
 * moved up one level: a user with a year of synced mail would still be typing
 * every address by hand. The pass exists for exactly the same reason
 * {@code ThreadingBackfillService} does, and follows its shape: fired once at
 * {@link ApplicationReadyEvent}, async on {@code mailEventExecutor} so startup
 * is not delayed, batched by id cursor with a fresh persistence context per
 * batch.
 *
 * <p>
 * One thing it adds to that shape: the sweep is bounded above by the highest
 * message id when it starts. The sync scheduler fires 10s after startup while
 * this pass can run for minutes on a populated mailbox, and
 * {@code MessageDownloader} harvests everything it persists inline — so without
 * a ceiling the id cursor would walk into those same rows and count them twice.
 *
 * <p>
 * <b>Re-entry is guarded differently from the threading passes.</b> Those
 * repair a column on the message row, so the row itself records whether it was
 * processed and the WHERE clause is the guard. Harvesting writes to another
 * table and leaves no mark on the message, so re-running would add a second
 * sighting for every message and double every counter. The guard is therefore
 * the cache being empty for that account: populated means either a previous run
 * finished or the sync has been harvesting inline, and in both cases there is
 * nothing to do.
 *
 * <p>
 * The cost of that choice is that a run interrupted half way leaves a partial
 * cache that the startup pass will never complete — the account is no longer
 * empty. Same for an account that was in {@code requires_reauth} at startup:
 * once the user signs in, the sync starts harvesting new mail inline and the
 * guard then blocks the backfill of everything older. Neither is silently
 * permanent, which is what makes them acceptable: {@link #rebuildAccount} drops
 * the cache and harvests again, exposed as
 * {@code POST /api/internal/correspondent/rebuild}. Without that escape hatch
 * "a droppable cache" would be a claim with no way to act on it.
 */
@Service
public class CorrespondentBackfillService {

    private static final Logger log = LoggerFactory.getLogger(CorrespondentBackfillService.class);

    /**
     * Messages per batch transaction. Matches the threading backfill; the rows here
     * are projections without the body, so the bound is on statement count rather
     * than heap.
     */
    private static final int BATCH_SIZE = 200;

    private final AccountRepository accountRepository;
    private final MessageRepository messageRepository;
    private final FolderSyncStateRepository folderSyncStateRepository;
    private final CorrespondentRepository correspondentRepository;
    private final CorrespondentService correspondentService;
    private final TransactionTemplate transactionTemplate;

    public CorrespondentBackfillService(AccountRepository accountRepository, MessageRepository messageRepository,
            FolderSyncStateRepository folderSyncStateRepository, CorrespondentRepository correspondentRepository,
            CorrespondentService correspondentService, TransactionTemplate transactionTemplate) {
        this.accountRepository = accountRepository;
        this.messageRepository = messageRepository;
        this.folderSyncStateRepository = folderSyncStateRepository;
        this.correspondentRepository = correspondentRepository;
        this.correspondentService = correspondentService;
        this.transactionTemplate = transactionTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    @Async("mailEventExecutor")
    public void backfillCorrespondentsOnStartup() {
        List<AccountEntity> accounts = accountRepository.findByActiveTrueAndRequiresReauthFalse();
        int totalHarvested = 0;
        int accountsTouched = 0;
        for (AccountEntity account : accounts) {
            int n = backfillAccountWithLogging(account);
            if (n > 0) {
                totalHarvested += n;
                accountsTouched++;
            }
        }
        if (totalHarvested > 0) {
            log.info("{} Correspondent backfill done — {} message(s) across {} account(s).", LogCategory.SYNC,
                    totalHarvested, accountsTouched);
            AuditLog.success("correspondent_backfill_completed", "system",
                    "messages=" + totalHarvested + " accounts=" + accountsTouched);
        } else {
            log.debug("{} Correspondent backfill: nothing to do.", LogCategory.SYNC);
        }
    }

    private int backfillAccountWithLogging(AccountEntity account) {
        try {
            return backfillAccount(account);
        } catch (RuntimeException e) {
            log.error("{} Correspondent backfill failed for account {} ({}). Skipping.", LogCategory.SYNC,
                    account.getId(), LogMasker.maskEmail(account.getEmail()), e);
            AuditLog.failure("correspondent_backfill_skipped_account", LogMasker.maskEmail(account.getEmail()),
                    "id=" + account.getId() + " cause=" + e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * Drops the account's cache and harvests it again from scratch.
     *
     * <p>
     * The repair path for the two states the startup guard cannot get out of on its
     * own: a backfill interrupted half way, and an account that was in
     * {@code requires_reauth} when the pass ran. Also the way to apply a changed
     * robot list or ranking to already harvested rows.
     *
     * <p>
     * It deliberately does <em>not</em> go through {@link #backfillAccount}: that
     * method's guard is the startup contract, and re-entering it here would make
     * the rebuild lose a race it must not lose. The DELETE commits, and a sync pass
     * landing in the window before the guard runs would put a row back, close the
     * guard and return 0 — leaving the caller with a cache that was wiped and never
     * refilled, which is worse than the partial cache they asked to repair. Both
     * paths share {@link #harvestAccount}; only the guard differs.
     *
     * @return the number of messages harvested
     */
    public int rebuildAccount(AccountEntity account) {
        int dropped = Objects.requireNonNull(
                transactionTemplate.execute(status -> correspondentRepository.deleteAllForAccount(account.getId())));
        log.info("{} Correspondent rebuild: dropped {} cached address(es) for account {}.", LogCategory.SYNC, dropped,
                account.getId());
        AuditLog.success("correspondent_rebuild", LogMasker.maskEmail(account.getEmail()),
                "id=" + account.getId() + " dropped=" + dropped);
        return harvestAccount(account);
    }

    /**
     * Harvests every message of one account, unless its cache already holds rows.
     *
     * @return the number of messages visited, zero when the guard skipped the
     *         account
     */
    public int backfillAccount(AccountEntity account) {
        if (correspondentRepository.countByAccountId(account.getId()) > 0) {
            return 0;
        }
        return harvestAccount(account);
    }

    /**
     * Walks the account's messages and harvests each one. Unguarded — the caller
     * decides whether running is appropriate.
     *
     * <p>
     * The sweep is bounded above by the highest message id at the moment it starts.
     * Without that ceiling the id cursor would walk straight into messages the sync
     * persists <em>while this runs</em> — on a first launch the pass takes minutes
     * and the scheduler fires 10s in — and
     * {@code MessageDownloader.saveMessagesBatchAtomic} has already harvested those
     * inline, so every one of them would land in the counters twice.
     */
    private int harvestAccount(AccountEntity account) {
        Long maxId = messageRepository.findMaxMessageIdByAccount(account.getId());
        if (maxId == null) {
            return 0;
        }
        Map<String, FolderRole> rolesByFolder = rolesByFolder(account);

        log.info("{} Correspondent backfill: harvesting account {}.", LogCategory.SYNC, account.getId());
        AuditLog.success("correspondent_backfill_started", LogMasker.maskEmail(account.getEmail()),
                "id=" + account.getId());

        int total = 0;
        long afterId = 0;
        while (true) {
            final long cursor = afterId;
            List<Long> processed = Objects.requireNonNull(transactionTemplate.execute(status -> {
                List<CorrespondentBackfillRow> batch = messageRepository.findMessagesForCorrespondentBackfill(
                        account.getId(), cursor, maxId, PageRequest.of(0, BATCH_SIZE));
                List<Long> ids = new ArrayList<>(batch.size());
                for (CorrespondentBackfillRow row : batch) {
                    correspondentService.harvest(account,
                            new CorrespondentService.HarvestInput(roleOf(rolesByFolder, row.folderName()),
                                    row.receivedAt(), row.sender(), row.recipientsTo(), row.recipientsCc(),
                                    row.recipientsBcc()));
                    ids.add(row.id());
                }
                return ids;
            }));
            if (processed.isEmpty()) {
                break;
            }
            afterId = processed.get(processed.size() - 1);
            total += processed.size();
            if (processed.size() < BATCH_SIZE) {
                break;
            }
        }

        if (total > 0) {
            log.info("{} Correspondent backfill: harvested {} message(s) in account {}.", LogCategory.SYNC, total,
                    account.getId());
            AuditLog.success("correspondent_backfill", LogMasker.maskEmail(account.getEmail()),
                    "id=" + account.getId() + " messages=" + total);
        }
        return total;
    }

    /**
     * Folder name to role for one account, read once. Messages carry
     * {@code folder_name} but not the role, and the harvest needs the role for
     * every single row to decide direction — a per-message lookup would be one
     * query per message.
     */
    private Map<String, FolderRole> rolesByFolder(AccountEntity account) {
        Map<String, FolderRole> roles = new HashMap<>();
        for (FolderSyncStateEntity state : folderSyncStateRepository.findByAccountId(account.getId())) {
            roles.put(state.getFolderName(), state.getRole());
        }
        return roles;
    }

    /**
     * Role of the folder a message sits in. Falls back to name-based detection for
     * a folder with no sync state — a message can outlive the row (SyncStateService
     * deletes state for folders that vanish from the server) and the fallback is
     * what decides whether the message counts as sent or received. Getting that
     * wrong on a Sent folder would silently reclassify the user's strongest signal
     * as incoming mail.
     */
    private static FolderRole roleOf(Map<String, FolderRole> rolesByFolder, String folderName) {
        FolderRole known = rolesByFolder.get(folderName);
        return known != null ? known : FolderRole.fromNameFallback(folderName);
    }
}
