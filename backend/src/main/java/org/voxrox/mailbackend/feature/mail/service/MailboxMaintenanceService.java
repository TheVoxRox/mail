package org.voxrox.mailbackend.feature.mail.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

@Service
public class MailboxMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(MailboxMaintenanceService.class);

    private final MessageRepository messageRepository;
    private final MailClientProperties mailProps;

    public MailboxMaintenanceService(MessageRepository messageRepository, MailClientProperties mailProps) {
        this.messageRepository = messageRepository;
        this.mailProps = mailProps;
    }

    /**
     * Keeps the local DB size within reasonable bounds. Runs asynchronously on
     * {@code mailEventExecutor} so it does not slow down the main sync process
     * <em>and</em> does not contend for permits in {@code mailSyncExecutor} — which
     * is the call site (see {@code MailSyncService.performFullSyncCycle}). Running
     * it on the sync executor would deadlock the pool, because the caller already
     * holds a permit and the IMAP per-account lock.
     */
    @Async("mailEventExecutor")
    @Transactional
    public void enforceLocalWindowLimitAsync(Long accountId, String folderName) {
        try {
            int limit = mailProps.sync().localWindowLimit();

            List<Long> latestUids = messageRepository.findLatestUids(accountId, folderName, limit);

            if (latestUids.size() >= limit) {
                // Last (oldest) UID within our limit
                long thresholdUid = latestUids.getLast();

                // Everything older than this UID gets deleted
                int deletedCount = messageRepository.deleteOlderThan(accountId, folderName, thresholdUid);

                if (deletedCount > 0) {
                    log.info("{} Folder {}: Deleted {} old messages (limit {} exceeded).", LogCategory.SYNC, folderName,
                            deletedCount, limit);
                }
            }
        } catch (Exception e) {
            log.warn("{} Local window maintenance for folder {} account {} failed: {}", LogCategory.SYNC, folderName,
                    accountId, e.getMessage(), e);
            AuditLog.failure("mailbox_maintenance", "account=" + accountId,
                    "folder=" + folderName + " " + e.getClass().getSimpleName());
        }
    }

    @Transactional
    public void clearLocalCache(Long accountId, String folderName) {
        log.info("{} Clearing local cache for account {} and folder {}", LogCategory.SYNC, accountId, folderName);
        messageRepository.deleteByAccountIdAndFolderName(accountId, folderName);
    }
}
