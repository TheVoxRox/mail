package org.voxrox.mailbackend.feature.mail.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Skip-if-running guards for sync work, at two granularities:
 *
 * <ul>
 * <li><b>Account level</b> ({@link #tryLock}/{@link #unlock}) — serializes the
 * whole-account scheduled pass ({@code MailSyncService.syncAllFolders}); a
 * second account pass is dropped, not queued.</li>
 * <li><b>Folder level</b> ({@link #tryLockFolder}/{@link #unlockFolder}) —
 * deduplicates per-folder cycles. Every {@code GET /emails} dispatches a
 * background folder cycle; without this guard rapid page/folder navigation
 * stacks identical cycles that then serialize on the per-account IMAP
 * connection lock and burn executor permits doing duplicate work.</li>
 * </ul>
 *
 * Both are non-blocking {@code tryLock} sets — a raced cycle is skipped, never
 * queued. The running cycle publishes the same {@code sync_completed} event the
 * skipped one would have, so the client still refreshes.
 */
@Component
public class SyncLockManager {
    private static final Logger log = LoggerFactory.getLogger(SyncLockManager.class);

    private final Set<Long> activeSyncs = ConcurrentHashMap.newKeySet();
    private final Set<FolderKey> activeFolderSyncs = ConcurrentHashMap.newKeySet();

    /**
     * Attempts to acquire the lock for an account.
     *
     * @return true if the lock was acquired, false if a sync is already in
     *         progress.
     */
    public boolean tryLock(Long accountId) {
        boolean acquired = activeSyncs.add(accountId);
        if (acquired) {
            log.debug("{} Acquired lock for account id={}", LogCategory.SYNC, accountId);
        } else {
            log.warn("{} Duplicate lock attempt for account id={} rejected", LogCategory.SYNC, accountId);
        }
        return acquired;
    }

    public void unlock(Long accountId) {
        if (activeSyncs.remove(accountId)) {
            log.debug("{} Released lock for account id={}", LogCategory.SYNC, accountId);
        } else {
            log.trace("{} Attempted to release a non-existent lock for account id={}", LogCategory.SYNC, accountId);
        }
    }

    public boolean isSyncing(Long accountId) {
        return activeSyncs.contains(accountId);
    }

    /**
     * Attempts to acquire the per-folder cycle lock. Callers skip the cycle when
     * this returns {@code false} — an identical cycle is already running and will
     * deliver the same result.
     */
    public boolean tryLockFolder(Long accountId, String folderName) {
        boolean acquired = activeFolderSyncs.add(new FolderKey(accountId, folderName));
        if (!acquired) {
            log.debug("{} Folder cycle for account id={} folder {} already running; skipping.", LogCategory.SYNC,
                    accountId, folderName);
        }
        return acquired;
    }

    public void unlockFolder(Long accountId, String folderName) {
        activeFolderSyncs.remove(new FolderKey(accountId, folderName));
    }

    private record FolderKey(Long accountId, String folderName) {
    }
}
