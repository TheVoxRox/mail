package org.voxrox.mailbackend.feature.mail.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

import module java.base;

/**
 * Pauses the sync of an account whose sync keeps ending the backend process.
 *
 * <p>
 * A message the server sends can end the process: the packaged JVM exits on its
 * first {@code OutOfMemoryError} ({@code -XX:+ExitOnOutOfMemoryError}), and the
 * IMAP/SMTP audit lists the shapes that got there before their bounds existed.
 * The shell restarts the backend, the next sync meets the same message, and the
 * process ends again — taking every other account and the whole app with it,
 * for as long as the message stays on the server. The bounds close the shapes
 * someone found; this closes the loop for the ones nobody has, by counting how
 * often the process ended during an account's sync.
 *
 * <p>
 * How it counts:
 * <ul>
 * <li>{@link #enter}/{@link #exit} bracket every sync of the account, the
 * scheduled pass and the read path's folder sync alike. While one runs,
 * {@code accounts.sync_started_at} is set; it is cleared, and the crash count
 * reset, when the last one ends inside the process, however it ended.</li>
 * <li>At startup, before the scheduler or the web server runs, a
 * {@code sync_started_at} still set means the previous process ended during
 * that account's sync. It counts as a crash only when the shell says it
 * restarted the backend after an exit it did not ask for
 * ({@value #PREVIOUS_EXIT_VARIABLE}, set by {@code sidecar.ts}). The shell ends
 * the backend with a kill on every quit and reload, so without that the user
 * closing the app three times during a long first sync would read as a crash
 * loop.</li>
 * <li>At {@value #CRASHES_BEFORE_PAUSE} in a row the account's sync is paused
 * and {@code last_error} says why. Three because the shell restarts the backend
 * three times within a minute before it gives up: a loop fast enough to exhaust
 * that budget still reaches the pause on the last restart.</li>
 * </ul>
 * Every account whose sync was running counts the crash, since nothing tells
 * them apart; an innocent one clears its count on its next pass that ends, long
 * before a third.
 *
 * <p>
 * A manual sync resumes a paused account ({@link #resumeIfPaused}) one crash
 * short of the limit, so a message that is still there pauses it again on the
 * next crash instead of after three more.
 */
@Component
public class SyncCrashGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SyncCrashGuard.class);

    /** Crashes in a row during an account's sync before that sync is paused. */
    static final int CRASHES_BEFORE_PAUSE = 3;

    /**
     * Set by the shell when it spawns the backend after an exit it did not ask for;
     * the value is that exit's code, for the log.
     */
    static final String PREVIOUS_EXIT_VARIABLE = "MAIL_SIDECAR_PREVIOUS_EXIT";

    private final AccountRepository accountRepository;
    private final Environment environment;

    /**
     * Syncs of one account in flight in this process. The scheduled pass and a
     * read-path folder sync can overlap, and the marker may only be cleared when
     * the last of them ends.
     */
    private final ConcurrentMap<Long, InFlight> inFlight = new ConcurrentHashMap<>();

    private static final class InFlight {
        private int count;
    }

    public SyncCrashGuard(AccountRepository accountRepository, Environment environment) {
        this.accountRepository = accountRepository;
        this.environment = environment;
    }

    /**
     * Runs once every singleton exists, which is before the scheduler starts and
     * before the web server takes a request, so no sync can begin ahead of it.
     */
    @Override
    public void afterSingletonsInstantiated() {
        reconcileInterruptedSyncs(environment.getProperty(PREVIOUS_EXIT_VARIABLE));
    }

    /**
     * Counts the syncs the previous process left running.
     *
     * @param previousExit
     *            the exit code the shell reports for the previous process, or
     *            {@code null} when it asked for that exit or did not see it
     */
    void reconcileInterruptedSyncs(@Nullable String previousExit) {
        List<AccountEntity> interrupted;
        try {
            interrupted = accountRepository.findBySyncStartedAtIsNotNull();
        } catch (DataAccessException e) {
            log.warn("{} Could not read which syncs the previous backend left running: {}", LogCategory.SYNC,
                    e.getMessage());
            return;
        }
        for (AccountEntity account : interrupted) {
            try {
                reconcile(account, previousExit);
            } catch (DataAccessException e) {
                log.warn("{} Could not record the interrupted sync of account {}: {}", LogCategory.SYNC,
                        account.getId(), e.getMessage());
            }
        }
    }

    private void reconcile(AccountEntity account, @Nullable String previousExit) {
        String maskedEmail = LogMasker.maskEmail(account.getEmail());
        if (previousExit == null || previousExit.isBlank()) {
            log.info("{} The previous backend ended during the sync of {}; not counted, the shell did not restart it.",
                    LogCategory.SYNC, maskedEmail);
            accountRepository.clearSyncStarted(account.getId());
            return;
        }
        int crashes = account.getSyncCrashes() + 1;
        if (crashes < CRASHES_BEFORE_PAUSE) {
            log.warn(
                    "{} The previous backend ended (exit {}) during the sync of {}: crash {} of {} before it is paused.",
                    LogCategory.SYNC, previousExit, maskedEmail, crashes, CRASHES_BEFORE_PAUSE);
            accountRepository.recordSyncCrash(account.getId(), crashes, false);
            return;
        }
        log.error("{} The backend ended (last exit {}) during the sync of {} {} times in a row; pausing its sync.",
                LogCategory.SYNC, previousExit, maskedEmail, crashes);
        AuditLog.failure("sync_paused_after_crashes", maskedEmail, "crashes=" + crashes + " exit=" + previousExit);
        accountRepository.recordSyncCrash(account.getId(), crashes, true);
        accountRepository.updateLastError(account.getId(),
                AccountLastError.of(AccountLastErrorCode.MAIL_SYNC_PAUSED_AFTER_CRASHES, Map.of(),
                        "Sync paused: the backend ended during it " + crashes + " times in a row"),
                LocalDateTime.now());
    }

    /**
     * Marks the start of a sync of the account. Every call must be paired with
     * {@link #exit} in a {@code finally}. A failure to record it is logged and the
     * sync goes on: the guard is a safety net, not a gate.
     */
    public void enter(Long accountId) {
        InFlight running = inFlight.computeIfAbsent(accountId, id -> new InFlight());
        synchronized (running) {
            running.count++;
            if (running.count == 1) {
                write(() -> accountRepository.markSyncStarted(accountId, LocalDateTime.now()), accountId);
            }
        }
    }

    /** Marks the end of a sync {@link #enter} started, however it ended. */
    public void exit(Long accountId) {
        InFlight running = inFlight.get(accountId);
        if (running == null) {
            return;
        }
        synchronized (running) {
            running.count--;
            if (running.count == 0) {
                write(() -> accountRepository.markSyncEnded(accountId), accountId);
            }
        }
    }

    /**
     * Lifts a pause for a sync the user asked for. Leaves the account one crash
     * short of the limit, so the message that caused the pause, if it is still
     * there, pauses it again on the next crash.
     *
     * @return whether the account was paused
     */
    public boolean resumeIfPaused(AccountEntity account) {
        if (!account.isSyncPaused()) {
            return false;
        }
        accountRepository.resumeSync(account.getId(), CRASHES_BEFORE_PAUSE - 1);
        log.info("{} Sync of {} resumed by a manual sync after it was paused.", LogCategory.SYNC,
                LogMasker.maskEmail(account.getEmail()));
        AuditLog.success("sync_resumed", LogMasker.maskEmail(account.getEmail()), "after=pause");
        return true;
    }

    private void write(Runnable update, Long accountId) {
        try {
            update.run();
        } catch (DataAccessException e) {
            log.warn("{} Could not record the sync state of account {} for the crash guard: {}", LogCategory.SYNC,
                    accountId, e.getMessage());
        }
    }
}
