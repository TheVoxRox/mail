package org.voxrox.mailbackend.feature.mail.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
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
 * scheduled pass and the read path's folder sync alike, and
 * {@link #whileFetching} the download of a body the user opened. While one
 * runs, {@code accounts.sync_started_at} is set; it is cleared when the last
 * one ends inside the process, however it ended.</li>
 * <li>At startup, before the scheduler or the web server runs, a
 * {@code sync_started_at} still set means the previous process ended during
 * that account's sync. It counts as a crash only when the shell says it
 * restarted the backend after an exit it did not ask for
 * ({@value #PREVIOUS_EXIT_VARIABLE}, set by {@code sidecar.ts}). The shell ends
 * the backend with a kill on every quit and reload, so without that the user
 * closing the app three times during a long first sync would read as a crash
 * loop.</li>
 * <li>The count starts over only when a whole pass over the account's folders
 * ends inside the process ({@link #passCompleted}). A read-path sync covers one
 * folder, and the folder the restarted app opens first would otherwise clear
 * the count before the scheduled pass reached the message that ends the process
 * in another folder, so the pause would never come.</li>
 * <li>At {@value #CRASHES_BEFORE_PAUSE} in a row the account's sync is paused
 * and {@code last_error} says why. Three because the shell restarts the backend
 * three times within a minute before it gives up: a loop fast enough to exhaust
 * that budget still reaches the pause on the last restart.</li>
 * </ul>
 *
 * <p>
 * Every account whose sync was running counts the crash, since nothing tells
 * them apart then. From that point on an account with a crash on record syncs
 * alone among the accounts that have one: {@link #enter} turns its sync away
 * while another of them is syncing, and a later pass runs it. The next crash
 * then names one of them, and an innocent one clears its count with its next
 * whole pass instead of being counted beside the account that ends the process
 * until both are paused. An account with no crash on record syncs as before, so
 * a crash costs an innocent account one count at most.
 *
 * <p>
 * A paused account is not synced (MailSyncService) and its bodies are not
 * downloaded (MailContentService): a restarted app opens the message it showed
 * again, so a crash in a body would otherwise come back the same way. A manual
 * sync resumes it ({@link #resumeIfPaused}) one crash short of the limit, so a
 * message that is still there pauses it again on the next crash instead of
 * after three more.
 *
 * <p>
 * The guard is a safety net, not a gate: a write it cannot make is logged and
 * the sync goes on, whatever the exception, so a database that refuses it never
 * ends a startup or leaves a sync's lock held.
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
     * Syncs of one account in flight in this process. The scheduled pass, a
     * read-path folder sync and a body download can overlap, and the marker may
     * only be cleared when the last of them ends.
     */
    private final ConcurrentMap<Long, InFlight> inFlight = new ConcurrentHashMap<>();

    private static final class InFlight {
        private int count;
    }

    /**
     * Guards {@link #isolated}. Taken inside an account's {@link InFlight} monitor,
     * never the other way round.
     */
    private final Object isolationLock = new Object();

    /** The account with a crash on record that is syncing now, if any. */
    private @Nullable Long isolated;

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
     * Counts the syncs the previous process left running. Catches every runtime
     * exception, not only a data-access one: it runs inside the context's startup,
     * which an exception would end, and a transaction that cannot begin is a
     * {@code TransactionException}.
     *
     * @param previousExit
     *            the exit code the shell reports for the previous process, or
     *            {@code null} when it asked for that exit or did not see it
     */
    void reconcileInterruptedSyncs(@Nullable String previousExit) {
        List<AccountEntity> interrupted;
        try {
            interrupted = accountRepository.findBySyncStartedAtIsNotNull();
        } catch (RuntimeException e) {
            log.warn("{} Could not read which syncs the previous backend left running: {}", LogCategory.SYNC,
                    e.getMessage());
            return;
        }
        for (AccountEntity account : interrupted) {
            try {
                reconcile(account, previousExit);
            } catch (RuntimeException e) {
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
     * Marks the start of a sync of the account, the scheduled pass or a read-path
     * folder sync.
     *
     * @return whether the sync may run, and then it must be paired with
     *         {@link #exit} in a {@code finally}. {@code false} when the account
     *         has a crash on record and another account with one is syncing: the
     *         caller skips this sync, nothing has been marked, and there is no
     *         {@link #exit} to make.
     */
    public boolean enter(AccountEntity account) {
        return begin(account.getId(), account.getSyncCrashes() > 0);
    }

    /**
     * Runs the download of a body the user opened as a sync of its account, so a
     * process that ends in it counts as one that ended during the account's sync.
     * Never turned away: the user is waiting for it, and the crash it may take part
     * in is counted for every account syncing beside it, as at any other time.
     */
    public <T> T whileFetching(Long accountId, Supplier<T> fetch) {
        begin(accountId, false);
        try {
            return fetch.get();
        } finally {
            exit(accountId);
        }
    }

    private boolean begin(Long accountId, boolean crashOnRecord) {
        InFlight running = inFlight.computeIfAbsent(accountId, id -> new InFlight());
        synchronized (running) {
            if (crashOnRecord && !claimIsolation(accountId)) {
                return false;
            }
            running.count++;
            if (running.count == 1) {
                write(() -> accountRepository.markSyncStarted(accountId, LocalDateTime.now()), accountId);
            }
            return true;
        }
    }

    /** Marks the end of a sync {@link #enter} let run, however it ended. */
    public void exit(Long accountId) {
        InFlight running = inFlight.get(accountId);
        if (running == null) {
            return;
        }
        synchronized (running) {
            if (running.count == 0) {
                return;
            }
            running.count--;
            if (running.count == 0) {
                write(() -> accountRepository.clearSyncStarted(accountId), accountId);
                releaseIsolation(accountId);
            }
        }
    }

    /**
     * A pass over all of the account's folders reached its end inside the process,
     * each folder synced by the pass itself, whether it succeeded or failed: the
     * account's mail as it stands did not end the process, so its crash count
     * starts over. A pass that skipped a folder another sync held does not count,
     * since that sync may still be the one that ends it.
     */
    public void passCompleted(AccountEntity account) {
        if (account.getSyncCrashes() > 0) {
            write(() -> accountRepository.clearSyncCrashes(account.getId()), account.getId());
        }
    }

    /**
     * Whether the account's sync is paused. Read from the database, not from a
     * loaded entity, for a caller that holds none; a read that fails answers
     * {@code false}, as every other failure of the guard leaves the sync alone.
     */
    public boolean isPaused(Long accountId) {
        try {
            return accountRepository.isSyncPaused(accountId).orElse(false);
        } catch (RuntimeException e) {
            log.warn("{} Could not read whether the sync of account {} is paused: {}", LogCategory.SYNC, accountId,
                    e.getMessage());
            return false;
        }
    }

    /**
     * Lifts a pause for a sync the user asked for. Leaves the account one crash
     * short of the limit, so the message that caused the pause, if it is still
     * there, pauses it again on the next crash, and clears the standing error that
     * said it was paused: the sync runs again from here, whatever the pass that
     * follows records.
     *
     * @return whether the account was paused
     */
    public boolean resumeIfPaused(AccountEntity account) {
        if (!account.isSyncPaused()) {
            return false;
        }
        Long accountId = account.getId();
        write(() -> accountRepository.resumeSync(accountId, CRASHES_BEFORE_PAUSE - 1), accountId);
        write(() -> accountRepository.clearLastErrorIfCodeIn(accountId,
                List.of(AccountLastErrorCode.MAIL_SYNC_PAUSED_AFTER_CRASHES.name())), accountId);
        log.info("{} Sync of {} resumed by a manual sync after it was paused.", LogCategory.SYNC,
                LogMasker.maskEmail(account.getEmail()));
        AuditLog.success("sync_resumed", LogMasker.maskEmail(account.getEmail()), "after=pause");
        return true;
    }

    private boolean claimIsolation(Long accountId) {
        synchronized (isolationLock) {
            if (isolated == null || isolated.equals(accountId)) {
                isolated = accountId;
                return true;
            }
            return false;
        }
    }

    private void releaseIsolation(Long accountId) {
        synchronized (isolationLock) {
            if (accountId.equals(isolated)) {
                isolated = null;
            }
        }
    }

    /**
     * Every runtime exception, not only a data-access one: a transaction that
     * cannot begin is a {@code TransactionException}, and one escaping here would
     * leave the caller's sync lock held.
     */
    private void write(Runnable update, Long accountId) {
        try {
            update.run();
        } catch (RuntimeException e) {
            log.warn("{} Could not record the sync state of account {} for the crash guard: {}", LogCategory.SYNC,
                    accountId, e.getMessage());
        }
    }
}
