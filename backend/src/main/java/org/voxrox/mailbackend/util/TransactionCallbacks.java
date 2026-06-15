package org.voxrox.mailbackend.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Helpers for deferring side effects until a transaction has committed.
 *
 * <p>
 * Side effects that must only become visible once the data is durable — SSE
 * broadcasts (a client would otherwise refetch pre-commit and read stale rows),
 * blocking cleanup that should not hold the single-writer SQLite transaction
 * open, cache invalidation — belong in {@code afterCommit}, not inline in the
 * service method.
 *
 * <p>
 * Centralises the
 * {@code isSynchronizationActive() ? inline : registerSynchronization}
 * boilerplate that previously lived hand-copied across services. When no
 * transaction is active (a plain unit-test invocation or a non-transactional
 * caller) the action runs inline, so behaviour is unchanged off the transaction
 * path. Each caller's action owns its own error handling — a throwing action is
 * the caller's concern, not this helper's.
 */
public final class TransactionCallbacks {

    private TransactionCallbacks() {
    }

    /**
     * Runs {@code action} after the current transaction commits, or immediately if
     * no transaction synchronization is active.
     *
     * @param action
     *            the side effect to defer until after commit
     */
    public static void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
