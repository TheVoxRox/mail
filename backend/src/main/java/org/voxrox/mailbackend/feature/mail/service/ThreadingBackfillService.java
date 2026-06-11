package org.voxrox.mailbackend.feature.mail.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

/**
 * Backfills {@code thread_id} for messages persisted before the V2 migration
 * applied (every row with {@code thread_id IS NULL}).
 *
 * <p>
 * The service runs once at {@link ApplicationReadyEvent}, asynchronously on the
 * {@code mailEventExecutor} so it does not delay startup. It walks every active
 * account that does not require reauth, fetches unthreaded messages in
 * ascending {@code receivedAt} order, and calls
 * {@link ThreadingService#assignThread} for each. Late-arriving parent
 * reconciliation happens for free as a side effect — by walking chronologically
 * the missing root almost always lands before its children.
 *
 * <p>
 * Re-entry safety: subsequent restarts find no rows with {@code thread_id IS
 * NULL} and skip silently. There is no idempotency token because the WHERE
 * clause itself is the guard.
 *
 * <p>
 * Accounts in {@code requires_reauth=true} state are skipped — their last known
 * data is fine, but new arrivals will not flow until the user signs in again,
 * so wasting work on a stale set is pointless. Once reauth completes the next
 * normal sync seeds new messages through
 * {@link MessageDownloader#saveMessagesBatchAtomic} which assigns
 * {@code thread_id} inline; the backfill is not retried per account.
 *
 * <p>
 * Concurrency: SQLite serializes writes, so the backfill and the incremental
 * sync that may start in parallel never write to the same row twice.
 * Late-arriving parent reconciliation works per account, so cross-account
 * coordination is not needed.
 */
@Service
public class ThreadingBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ThreadingBackfillService.class);

    private final AccountRepository accountRepository;
    private final MessageRepository messageRepository;
    private final ThreadingService threadingService;

    public ThreadingBackfillService(AccountRepository accountRepository, MessageRepository messageRepository,
            ThreadingService threadingService) {
        this.accountRepository = accountRepository;
        this.messageRepository = messageRepository;
        this.threadingService = threadingService;
    }

    /**
     * Hook fired by Spring after the application is fully ready. Ordered
     * {@link Ordered#LOWEST_PRECEDENCE} so HandshakeService writes
     * {@code session.json} and {@code .ready} first — the desktop client is not
     * blocked while the backfill runs.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    @Async("mailEventExecutor")
    public void backfillThreadsOnStartup() {
        List<AccountEntity> accounts = accountRepository.findByActiveTrueAndRequiresReauthFalse();
        int totalBackfilled = 0;
        int accountsTouched = 0;
        for (AccountEntity account : accounts) {
            int n = backfillAccountWithLogging(account);
            if (n > 0) {
                totalBackfilled += n;
                accountsTouched++;
            }
        }
        if (totalBackfilled > 0) {
            log.info("{} Threading backfill done — {} message(s) across {} account(s).", LogCategory.SYNC,
                    totalBackfilled, accountsTouched);
            AuditLog.success("threading_backfill_completed", "system",
                    "messages=" + totalBackfilled + " accounts=" + accountsTouched);
        } else {
            log.debug("{} Threading backfill: nothing to do — all messages already have thread_id.", LogCategory.SYNC);
        }
    }

    private int backfillAccountWithLogging(AccountEntity account) {
        try {
            return backfillAccount(account);
        } catch (RuntimeException e) {
            log.error("{} Threading backfill failed for account {} ({}). Skipping; will retry on next start.",
                    LogCategory.SYNC, account.getId(), LogMasker.maskEmail(account.getEmail()), e);
            AuditLog.failure("threading_backfill_skipped_account", LogMasker.maskEmail(account.getEmail()),
                    "id=" + account.getId() + " cause=" + e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * Per-account backfill. Public so the internal {@code /threading/recompute}
     * endpoint can call it directly without going through the
     * {@code ApplicationReadyEvent} path.
     *
     * @return number of messages whose {@code thread_id} was newly assigned
     */
    @Transactional
    public int backfillAccount(AccountEntity account) {
        List<MessageEntity> messages = messageRepository.findUnthreadedByAccountOrderByReceivedAt(account.getId());
        if (messages.isEmpty()) {
            return 0;
        }
        log.info("{} Threading backfill: assigning thread_id for {} message(s) in account {}.", LogCategory.SYNC,
                messages.size(), account.getId());
        AuditLog.success("threading_backfill_started", LogMasker.maskEmail(account.getEmail()),
                "id=" + account.getId() + " messages=" + messages.size());
        for (MessageEntity msg : messages) {
            threadingService.assignThread(msg, account);
        }
        return messages.size();
    }
}
