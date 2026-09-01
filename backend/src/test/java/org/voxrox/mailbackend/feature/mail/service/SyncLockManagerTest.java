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
 * acquire/reject/release contract, per-account independence, no-op release, the
 * report-on-finish flag a rejected pass hands to the running one, and the
 * concurrency invariant that exactly one of many racing threads wins.
 */
class SyncLockManagerTest {

    private final SyncLockManager manager = new SyncLockManager();

    @Test
    @DisplayName("First tryLock acquires; a second, overlapping attempt is rejected")
    void firstAcquiresSecondRejected() {
        assertThat(manager.tryLock(1L, false)).isTrue();
        assertThat(manager.tryLock(1L, false)).isFalse();
    }

    @Test
    @DisplayName("After unlock the account can be locked again")
    void unlockAllowsReacquire() {
        assertThat(manager.tryLock(1L, false)).isTrue();
        manager.unlock(1L);
        assertThat(manager.tryLock(1L, false)).isTrue();
    }

    @Test
    @DisplayName("Locks are independent per account")
    void locksAreIndependentPerAccount() {
        assertThat(manager.tryLock(1L, false)).isTrue();
        assertThat(manager.tryLock(2L, false)).isTrue();
        // Releasing one leaves the other held: 1 can be taken again, 2 cannot.
        manager.unlock(1L);
        assertThat(manager.tryLock(1L, false)).isTrue();
        assertThat(manager.tryLock(2L, false)).isFalse();
    }

    @Test
    @DisplayName("Unlocking a lock that was never held is a safe no-op")
    void unlockNonHeldLockIsNoOp() {
        assertThat(manager.unlock(99L)).isFalse(); // must not throw
        assertThat(manager.tryLock(99L, false)).isTrue();
    }

    @Test
    @DisplayName("A rejected pass that must be reported escalates the running one")
    void rejectedReportingPassEscalatesRunningPass() {
        assertThat(manager.tryLock(1L, false)).isTrue();
        // The user pressed Synchronise mid-scheduled-pass: dropped, but its
        // obligation to report is not — the running pass finishes for both.
        assertThat(manager.tryLock(1L, true)).isFalse();
        assertThat(manager.unlock(1L)).isTrue();
    }

    @Test
    @DisplayName("A rejected silent pass does not cancel a pending report")
    void rejectedSilentPassKeepsPendingReport() {
        assertThat(manager.tryLock(1L, true)).isTrue();
        assertThat(manager.tryLock(1L, false)).isFalse();
        assertThat(manager.unlock(1L)).isTrue();
    }

    @Test
    @DisplayName("unlock hands back the flag the pass was locked with, and it does not survive into the next pass")
    void unlockReturnsReportFlagWithoutLeakingIt() {
        assertThat(manager.tryLock(1L, true)).isTrue();
        assertThat(manager.unlock(1L)).isTrue();
        // Nobody is waiting for the pass after it, so nothing is announced.
        assertThat(manager.tryLock(1L, false)).isTrue();
        assertThat(manager.unlock(1L)).isFalse();
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
        assertThat(manager.tryLock(1L, false)).isTrue();
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
                    if (manager.tryLock(7L, false)) {
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
            // The winner still holds it: nobody else can take it after the race.
            assertThat(manager.tryLock(7L, false)).isFalse();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
