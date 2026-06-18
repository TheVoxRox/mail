package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.*;

import org.hibernate.StaleObjectStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.metrics.MailMetrics;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.mail.dto.FolderResponse;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.event.MailSyncCompletedEvent;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

import module java.base;

/**
 * E-mail synchronization orchestrator. Coordinates MessageDownloader (message
 * download) and FlagSyncService (flags and deletions).
 */
@Service
public class MailSyncService {

    private static final Logger log = LoggerFactory.getLogger(MailSyncService.class);
    private static final long UID_INCREMENT = 1L;

    private final ImapFolderService imapFolderService;
    private final MessageRepository messageRepository;
    private final SyncStateService syncStateService;
    private final SyncLockManager lockManager;
    private final MailboxMaintenanceService maintenanceService;
    private final TransactionTemplate transactionTemplate;
    private final MailClientProperties mailProps;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageDownloader messageDownloader;
    private final FlagSyncService flagSyncService;
    private final MailMetrics metrics;
    private final AccountRepository accountRepository;
    private final FolderCountCache folderCountCache;

    public MailSyncService(ImapFolderService imapFolderService, MessageRepository messageRepository,
            SyncStateService syncStateService, SyncLockManager lockManager,
            MailboxMaintenanceService maintenanceService, TransactionTemplate transactionTemplate,
            MailClientProperties mailProps, ApplicationEventPublisher eventPublisher,
            MessageDownloader messageDownloader, FlagSyncService flagSyncService, MailMetrics metrics,
            AccountRepository accountRepository, FolderCountCache folderCountCache) {
        this.imapFolderService = imapFolderService;
        this.messageRepository = messageRepository;
        this.syncStateService = syncStateService;
        this.lockManager = lockManager;
        this.maintenanceService = maintenanceService;
        this.transactionTemplate = transactionTemplate;
        this.mailProps = mailProps;
        this.eventPublisher = eventPublisher;
        this.messageDownloader = messageDownloader;
        this.flagSyncService = flagSyncService;
        this.metrics = metrics;
        this.accountRepository = accountRepository;
        this.folderCountCache = folderCountCache;
    }

    private static final int LAST_ERROR_MAX_LENGTH = 950;

    @Async("mailSyncExecutor")
    public void syncAllFolders(AccountEntity account) {
        if (!lockManager.tryLock(account.getId())) {
            log.info("{} Skipping sync for {}, already running.", LogCategory.SYNC,
                    LogMasker.maskEmail(account.getEmail()));
            return;
        }

        try {
            log.info("{} Starting account sync: {}", LogCategory.SYNC, LogMasker.maskEmail(account.getEmail()));
            List<FolderResponse> folders = imapFolderService.getFolders(account.getId());

            List<FolderRole> rolesToSync = List.of(FolderRole.INBOX, FolderRole.SENT, FolderRole.DRAFTS,
                    FolderRole.JUNK, FolderRole.TRASH, FolderRole.NEWSLETTERS);

            /*
             * Forward the FolderRole carried by FolderResponse — detected by
             * ImapFolderService via RFC 6154 attributes or a name fallback. No com.sun.mail
             * dependency.
             */
            List<FolderResponse> toSync = rolesToSync.stream()
                    .flatMap(role -> folders.stream().filter(f -> f.role() == role).limit(1)).toList();

            boolean allSucceeded = true;
            for (FolderResponse f : toSync) {
                allSucceeded &= performFullSyncCycle(account, f.folderRef(), f.role());
            }

            /*
             * Clear last_error only after a fully clean pass over all role-matched folders.
             * The folders sync sequentially and last_error is a single account-scoped slot
             * — clearing after each successful folder cycle (the previous behavior) meant a
             * later folder's success silently erased the error an earlier folder had just
             * recorded (INBOX fails, TRASH succeeds → the user never learns INBOX is
             * broken). Recovery still works: the next scheduler pass where every folder
             * succeeds clears the slot.
             */
            if (allSucceeded) {
                accountRepository.clearLastError(account.getId(), LocalDateTime.now());
            }
        } catch (Exception e) {
            log.error("{} Account sync {} failed: {}", LogCategory.SYNC, LogMasker.maskEmail(account.getEmail()),
                    e.getMessage(), e);
            AuditLog.failure("sync_account", LogMasker.maskEmail(account.getEmail()), e.getClass().getSimpleName());
            accountRepository.updateLastError(account.getId(), buildAccountSyncError(e), LocalDateTime.now());
        } finally {
            lockManager.unlock(account.getId());
        }
    }

    public boolean performFullSyncCycle(AccountEntity account, String folderName) {
        return performFullSyncCycle(account, folderName, FolderRole.USER);
    }

    /**
     * Main sync cycle with persistence of the detected role into the DB. The role
     * comes from RFC 6154 IMAP attributes — no com.sun.mail dependency.
     *
     * @return {@code true} when the cycle completed without errors, {@code false}
     *         when it failed and last_error was recorded. The cycle itself never
     *         clears last_error — only a fully clean {@link #syncAllFolders} pass
     *         does, so one folder's success cannot mask another folder's standing
     *         failure.
     */
    public boolean performFullSyncCycle(AccountEntity account, String folderName, FolderRole detectedRole) {
        Boolean succeeded = imapFolderService.executeInFolder(account.getId(), folderName, Folder.READ_ONLY,
                (folder, uidFolder) -> {
                    final FolderRole role = detectedRole;
                    FolderSyncStateEntity syncState = transactionTemplate
                            .execute(status -> syncStateService.getOrCreateState(account.getId(), folderName, role));

                    FolderSyncContext ctx = new FolderSyncContext(account, folderName, folder, uidFolder, syncState);
                    String maskedEmail = LogMasker.maskEmail(account.getEmail());

                    var sample = metrics.startSync();
                    int totalDownloaded = 0;
                    String outcome = MailMetrics.OUTCOME_SUCCESS;
                    try {
                        boolean uidValidityOk = flagSyncService.handleUidValidity(ctx);
                        if (uidValidityOk) {
                            totalDownloaded = messageDownloader.syncNewMessages(ctx);
                            ImapCapabilities caps = ImapCapabilities.probe(folder.getStore());
                            if (caps.hasCondstore()) {
                                /*
                                 * RFC 7162 CONDSTORE — O(changes) instead of O(folder size) for flag sync. The
                                 * cleanup of deletions still requires UID enumeration (full QRESYNC SELECT with
                                 * VANISHED is deferred because it requires raw IMAPProtocol access), but it
                                 * runs over a lightweight UID-only fetch instead of metadata.
                                 */
                                flagSyncService.syncMessageFlagsCondstore(ctx);
                                flagSyncService.cleanupDeletedViaUidEnumeration(ctx);
                            } else {
                                flagSyncService.syncMessageFlagsBatched(ctx);
                                flagSyncService.cleanupDeletedInWindow(ctx);
                            }
                        } else {
                            /*
                             * UID validity has changed — the IMAP server has reset the mailbox state. That
                             * means local UIDs no longer match the server and the sync state will be
                             * rebuilt from scratch. Data integrity is at risk → CRITICAL.
                             */
                            AuditLog.critical("sync_uid_validity_reset", maskedEmail, "folder=" + folderName);
                        }

                        /*
                         * Targeted UPDATE — last_sync_at is purely informational and sync is serialized
                         * per (account, folder) via SyncLockManager. Calling save(detached) would fail
                         * on the optimistic lock because the version was already bumped in
                         * handleUidValidity / saveMessagesBatchAtomic earlier in the cycle.
                         */
                        LocalDateTime now = LocalDateTime.now();
                        syncStateService.touchLastSyncAt(ctx.syncState().getId(), now);
                        ctx.syncState().setLastSyncAt(now);

                        maintenanceService.enforceLocalWindowLimitAsync(ctx.getAccountId(), ctx.folderName());

                        eventPublisher.publishEvent(new MailSyncCompletedEvent(account.getId(), folderName,
                                totalDownloaded, Instant.now()));

                        // Refresh the cached server count — folder is already open, getMessageCount
                        // is cheap, and this lets the read path serve paginator totals without an
                        // extra IMAP roundtrip for at least the cache TTL. Wrapped so a failure to
                        // read the count does not pollute last_error after an otherwise successful
                        // cycle.
                        try {
                            folderCountCache.put(account.getId(), folderName, folder.getMessageCount());
                        } catch (MessagingException e) {
                            log.debug(
                                    "{} Failed to refresh folder count cache for {} ({}); read path will refresh next time.",
                                    LogCategory.SYNC, folderName, e.getMessage());
                        }

                        return true;
                    } catch (Exception e) {
                        outcome = MailMetrics.OUTCOME_FAILURE;
                        /*
                         * Log with the full stack trace — this catch is the last place the exception is
                         * seen (it is converted into last_error and swallowed), so without it the root
                         * cause would be unrecoverable from the logs.
                         */
                        log.error("{} Critical error during folder sync {}: {}", LogCategory.SYNC, folderName,
                                e.getMessage(), e);
                        String auditAction = isOptimisticLockConflict(e)
                                ? "sync_optimistic_lock_conflict"
                                : "sync_folder";
                        AuditLog.failure(auditAction, maskedEmail,
                                "folder=" + folderName + " " + e.getClass().getSimpleName());
                        accountRepository.updateLastError(account.getId(), buildFolderSyncError(folderName, e),
                                LocalDateTime.now());
                        return false;
                    } finally {
                        metrics.recordSync(sample, outcome, totalDownloaded);
                    }
                });
        return Boolean.TRUE.equals(succeeded);
    }

    /**
     * Detects an optimistic-lock conflict in the exception cause chain. Spring
     * usually wraps Hibernate {@link StaleObjectStateException} into
     * {@link OptimisticLockingFailureException}, but the raw Hibernate version may
     * propagate too.
     */
    private static boolean isOptimisticLockConflict(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof OptimisticLockingFailureException || cur instanceof StaleObjectStateException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static AccountLastError buildFolderSyncError(String folderName, Exception e) {
        String base = "Folder sync " + folderName + " failed: " + e.getClass().getSimpleName();
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            base = base + ": " + e.getMessage();
        }
        if (base.length() > LAST_ERROR_MAX_LENGTH) {
            base = base.substring(0, LAST_ERROR_MAX_LENGTH);
        }
        return AccountLastError.of(AccountLastErrorCode.MAIL_SYNC_FOLDER_FAILED,
                Map.of("folder", folderName, "errorClass", e.getClass().getSimpleName(), "detail", safeDetail(e)),
                base);
    }

    private static AccountLastError buildAccountSyncError(Exception e) {
        String base = "Account sync failed: " + e.getClass().getSimpleName();
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            base = base + ": " + e.getMessage();
        }
        if (base.length() > LAST_ERROR_MAX_LENGTH) {
            base = base.substring(0, LAST_ERROR_MAX_LENGTH);
        }
        return AccountLastError.of(AccountLastErrorCode.MAIL_SYNC_ACCOUNT_FAILED,
                Map.of("errorClass", e.getClass().getSimpleName(), "detail", safeDetail(e)), base);
    }

    private static String safeDetail(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "" : e.getMessage();
    }

    /**
     * Synchronous helper for the read path: opens the folder, reads the server's
     * message count (used as {@code totalElements} so the paginator reflects the
     * real folder size, not just what we hold locally), and lazy-fetches the IMAP
     * sequence range that covers the requested page when it falls below the local
     * cache. With this in place the user can navigate to any page without us
     * mirroring the whole folder up front.
     * <p>
     * The "local = contiguous newest" invariant holds because lazy fetch only fires
     * when {@code (page+1)*size > localCount} and fills the range immediately below
     * the current local edge, so the local DB always matches the latest
     * {@code localCount} server positions. Initial sync, periodic sync (which only
     * adds newer UIDs at the top) and the existing page-0 backfill also extend the
     * cache contiguously.
     * <p>
     * On IMAP failure (network, auth, MessagingException) the method falls back to
     * the local count so a transient outage does not break the read path — the user
     * keeps seeing whatever is cached.
     */
    public long fetchServerCountAndEnsurePageLocally(AccountEntity account, String folderName, int page, int size) {
        long needed = ((long) page + 1) * size;

        Long serverCount = imapFolderService.executeInFolder(account.getId(), folderName, Folder.READ_ONLY,
                (folder, uidFolder) -> {
                    // Read the local count INSIDE the per-account lock so a concurrent
                    // periodic sync cannot add rows between our count read and the
                    // sequence-range computation — that would let lazy fetch overlap
                    // with already-saved UIDs and hit the unique constraint.
                    long localCount = messageRepository.countByAccountIdAndFolderName(account.getId(), folderName);
                    try {
                        int count = folder.getMessageCount();
                        if (needed > localCount && (long) count > localCount) {
                            FolderSyncStateEntity syncState = transactionTemplate.execute(status -> syncStateService
                                    .getOrCreateState(account.getId(), folderName, FolderRole.USER));
                            FolderSyncContext ctx = new FolderSyncContext(account, folderName, folder, uidFolder,
                                    syncState);
                            int endSeq = (int) ((long) count - localCount);
                            long target = Math.min(needed, (long) count);
                            int startSeq = (int) Math.max(1L, (long) count - target + 1L);
                            int fetched = messageDownloader.downloadSequenceRange(ctx, startSeq, endSeq);
                            log.info("{} Lazy page fetch {}: page {} (seq {}-{}), {} messages added.", LogCategory.SYNC,
                                    folderName, page, startSeq, endSeq, fetched);
                        }
                        folderCountCache.put(account.getId(), folderName, count);
                        return (long) count;
                    } catch (MessagingException e) {
                        log.warn("{} Lazy page fetch failed for account {} folder {}: {}", LogCategory.SYNC,
                                account.getId(), folderName, e.getMessage(), e);
                        AuditLog.failure("lazy_page_fetch", LogMasker.maskEmail(account.getEmail()),
                                "folder=" + folderName + " page=" + page + " " + e.getClass().getSimpleName());
                        return localCount;
                    }
                });
        return serverCount != null
                ? serverCount
                : messageRepository.countByAccountIdAndFolderName(account.getId(), folderName);
    }

    @Async("mailSyncExecutor")
    public void syncAndBackfillAsync(AccountEntity account, String folderName, int page) {
        try {
            syncAndBackfill(account, folderName, page);
        } catch (Exception e) {
            log.error("{} Async sync/backfill of folder {} for account {} failed: {}", LogCategory.SYNC, folderName,
                    LogMasker.maskEmail(account.getEmail()), e.getMessage(), e);
            AuditLog.failure("sync_backfill_async", LogMasker.maskEmail(account.getEmail()),
                    "folder=" + folderName + " " + e.getClass().getSimpleName());
            accountRepository.updateLastError(account.getId(), buildFolderSyncError(folderName, e),
                    LocalDateTime.now());
        }
    }

    public void syncAndBackfill(AccountEntity account, String folderName, int page) {
        performFullSyncCycle(account, folderName);

        if (page == 0) {
            messageRepository.findMinUidByAccountIdAndFolderName(account.getId(), folderName).ifPresent(minUid -> {
                if (minUid > UID_INCREMENT) {
                    int backfillCount = mailProps.sync().backfillBatchSize();
                    long startUid = Math.max(UID_INCREMENT, minUid - backfillCount);
                    long endUid = minUid - 1;
                    log.info("{} Folder backfill {}: UID {}-{}", LogCategory.SYNC, folderName, startUid, endUid);
                    downloadRange(account, folderName, startUid, endUid);
                }
            });
        }
    }

    public void downloadRange(AccountEntity account, String folderName, long startUid, long endUid) {
        log.info("{} History backfill for {}: UID {}-{}", LogCategory.SYNC, folderName, startUid, endUid);

        imapFolderService.executeInFolder(account.getId(), folderName, Folder.READ_ONLY, (folder, uidFolder) -> {
            // USER = "do not change the existing role". Backfill targets an
            // already-synced folder whose role was set by performFullSyncCycle.
            FolderSyncStateEntity syncState = transactionTemplate
                    .execute(status -> syncStateService.getOrCreateState(account.getId(), folderName, FolderRole.USER));

            FolderSyncContext ctx = new FolderSyncContext(account, folderName, folder, uidFolder, syncState);

            try {
                messageDownloader.downloadRange(ctx, startUid, endUid);
            } catch (MessagingException e) {
                log.error("{} Error during folder backfill {}: {}", LogCategory.SYNC, folderName, e.getMessage());
                AuditLog.failure("sync_backfill", LogMasker.maskEmail(account.getEmail()), "folder=" + folderName
                        + " range=" + startUid + "-" + endUid + " " + e.getClass().getSimpleName());
            }
            return null;
        });
    }

    @Transactional(readOnly = true)
    public MessageEntity getMessageOrThrow(String stableId) {
        return messageRepository.findByStableId(stableId)
                .orElseThrow(() -> new ResourceNotFoundException("Message does not exist: " + stableId));
    }
}
