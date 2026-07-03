package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SyncLockManager} — the per-account in-memory guard that
 * prevents two sync cycles running against the same account at once. Covers the
 * acquire/reject/release contract, per-account independence, no-op release, and
 * the concurrency invariant that exactly one of many racing threads wins.
 */
class SyncLockManagerTest {

    private final SyncLockManager manager = new SyncLockManager();

    @Test
    @DisplayName("First tryLock acquires; a second, overlapping attempt is rejected")
    void firstAcquiresSecondRejected() {
        assertThat(manager.tryLock(1L)).isTrue();
        assertThat(manager.tryLock(1L)).isFalse();
    }

    @Test
    @DisplayName("After unlock the account can be locked again")
    void unlockAllowsReacquire() {
        assertThat(manager.tryLock(1L)).isTrue();
        manager.unlock(1L);
        assertThat(manager.tryLock(1L)).isTrue();
    }

    @Test
    @DisplayName("isSyncing reflects the lock state across acquire and release")
    void isSyncingTracksState() {
        assertThat(manager.isSyncing(1L)).isFalse();
        manager.tryLock(1L);
        assertThat(manager.isSyncing(1L)).isTrue();
        manager.unlock(1L);
        assertThat(manager.isSyncing(1L)).isFalse();
    }

    @Test
    @DisplayName("Locks are independent per account")
    void locksAreIndependentPerAccount() {
        assertThat(manager.tryLock(1L)).isTrue();
        assertThat(manager.tryLock(2L)).isTrue();
        assertThat(manager.isSyncing(1L)).isTrue();
        assertThat(manager.isSyncing(2L)).isTrue();
        // Releasing one leaves the other held.
        manager.unlock(1L);
        assertThat(manager.isSyncing(1L)).isFalse();
        assertThat(manager.isSyncing(2L)).isTrue();
    }

    @Test
    @DisplayName("Unlocking a lock that was never held is a safe no-op")
    void unlockNonHeldLockIsNoOp() {
        manager.unlock(99L); // must not throw
        assertThat(manager.isSyncing(99L)).isFalse();
        assertThat(manager.tryLock(99L)).isTrue();
    }

    @Test
    @DisplayName("Folder locks: first acquire wins, duplicate is rejected, release re-opens")
    void folderLockAcquireRejectRelease() {
        assertThat(manager.tryLockFolder(1L, "INBOX")).isTrue();
        assertThat(manager.tryLockFolder(1L, "INBOX")).isFalse();
        manager.unlockFolder(1L, "INBOX");
        assertThat(manager.tryLockFolder(1L, "INBOX")).isTrue();
    }

    @Test
    @DisplayName("Folder locks are independent per (account, folder) pair and from the account lock")
    void folderLocksIndependent() {
        assertThat(manager.tryLockFolder(1L, "INBOX")).isTrue();
        // A different folder of the same account and the same folder of a
        // different account are both free.
        assertThat(manager.tryLockFolder(1L, "Sent")).isTrue();
        assertThat(manager.tryLockFolder(2L, "INBOX")).isTrue();
        // The account-level lock is a separate namespace.
        assertThat(manager.tryLock(1L)).isTrue();
    }

    @Test
    @DisplayName("Under contention exactly one of many racing threads acquires the lock")
    void concurrentTryLockHasExactlyOneWinner() throws Exception {
        int threadCount = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>(threadCount);

            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await(); // line every thread up before the race
                    if (manager.tryLock(7L)) {
                        winners.incrementAndGet();
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get();
            }

            assertThat(winners.get()).isEqualTo(1);
            assertThat(manager.isSyncing(7L)).isTrue();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
