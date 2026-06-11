package org.voxrox.mailbackend.feature.mail.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.util.LogCategory;

@Component
public class SyncLockManager {
    private static final Logger log = LoggerFactory.getLogger(SyncLockManager.class);

    private final Set<Long> activeSyncs = ConcurrentHashMap.newKeySet();

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
}
