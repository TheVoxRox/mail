package org.voxrox.mailbackend.feature.mail.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
 * Both are non-blocking {@code tryLock} guards — a raced cycle is skipped,
 * never queued. What the skip costs differs by level, so read the method you
 * are calling rather than this paragraph: the running <em>folder</em> cycle
 * publishes the same {@code sync_completed} event the skipped one would have,
 * so the client still refreshes; a dropped <em>account</em> pass is not
 * discarded but absorbed, see {@link #tryLock}.
 */
@Component
public class SyncLockManager {
    private static final Logger log = LoggerFactory.getLogger(SyncLockManager.class);

    /**
     * Accounts with a pass in flight, mapped to whether that pass has to report its
     * completion to the client. The flag rides along with the lock instead of
     * living in {@code MailSyncService} so that absorbing a dropped request (see
     * {@link #tryLock}) is atomic against the release — held anywhere else, the
     * running pass could finish in between and the request would be left with
     * nothing to carry it.
     */
    private final ConcurrentMap<Long, Boolean> activeSyncs = new ConcurrentHashMap<>();
    private final Set<FolderKey> activeFolderSyncs = ConcurrentHashMap.newKeySet();

    /**
     * Attempts to acquire the lock for an account.
     *
     * <p>
     * A dropped pass is not simply discarded when it was asked to report: the pass
     * already running <em>absorbs</em> the obligation and reports for both. The
     * user pressed Synchronise and a sync is indeed running, so its completion
     * answers them truthfully; without this the request would vanish and the client
     * would sit on "Synchronising…" until its own fallback timer gave up.
     *
     * @param reportOnFinish
     *            whether the caller needs the finished pass reported to the client
     * @return true if the lock was acquired, false if a sync is already in
     *         progress.
     */
    public boolean tryLock(Long accountId, boolean reportOnFinish) {
        /*
         * compute() hands back the new value, not whether the entry was created, so the
         * answer is smuggled out of the remapping function. It has to be one operation:
         * a putIfAbsent followed by a separate escalation loses the flag when the
         * running pass releases the lock between the two.
         */
        boolean[] acquired = {false};
        activeSyncs.compute(accountId, (id, running) -> {
            if (running == null) {
                acquired[0] = true;
                return reportOnFinish;
            }
            return running || reportOnFinish;
        });

        if (acquired[0]) {
            log.debug("{} Acquired lock for account id={}", LogCategory.SYNC, accountId);
        } else {
            log.warn("{} Duplicate lock attempt for account id={} rejected{}", LogCategory.SYNC, accountId,
                    reportOnFinish ? "; the running pass will report its completion" : "");
        }
        return acquired[0];
    }

    /**
     * Releases the account lock.
     *
     * @return true when the released pass was asked to report its completion — by
     *         its own caller, or by a request it absorbed while running.
     */
    public boolean unlock(Long accountId) {
        Boolean reportOnFinish = activeSyncs.remove(accountId);
        if (reportOnFinish == null) {
            log.trace("{} Attempted to release a non-existent lock for account id={}", LogCategory.SYNC, accountId);
            return false;
        }
        log.debug("{} Released lock for account id={}", LogCategory.SYNC, accountId);
        return reportOnFinish;
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
