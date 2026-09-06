package org.voxrox.mailbackend.feature.mail.service;

import java.time.LocalDateTime;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.mail.*;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.metrics.MailMetrics;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailAuthenticationException;
import org.voxrox.mailbackend.exception.MailConnectionException;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionDetails;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountConnectionDetailsService;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenService;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

import module java.base;

@Component
public class ImapConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ImapConnectionManager.class);
    /*
     * Text is stored in account.last_error (DB) and also sent as the detail of
     * MailOperationException. The frontend resolves user-facing copy from the
     * errorCode (MAIL_OAUTH2_IMAP_ACCESS_DENIED) via i18n; this fallback shows up
     * only when localization fails on the client side. Kept in English to match the
     * other English fallbacks used across the service layer.
     */
    private static final String OAUTH2_IMAP_ACCESS_DENIED_DETAIL = "The mail provider (OAuth2) denied IMAP access. Open Settings -> Accounts and sign in again.";

    /**
     * Which of an account's two connections a piece of work runs on.
     *
     * <p>
     * One pooled {@link Store} per account used to mean one queue: a sync holds the
     * connection lock for a whole folder cycle — download, flag sweep, cleanup,
     * tens of seconds on a large mailbox — and everything the user asked for waited
     * behind it. Measured on 2026-08-31 during a screen-reader pass, the "page X of
     * Y" announcement after switching folders arrived in 5.0 s, 7.0 s and once 59.3
     * s. That it was queuing rather than transfer size is what a control sample
     * showed: a 12-message folder was slower than a 1801-message one.
     *
     * <p>
     * Two lanes, two {@link Store}s, two locks. Work the user is waiting for goes
     * to {@link #INTERACTIVE} and no longer queues behind a sync at all. Everything
     * that can finish late stays on {@link #BACKGROUND}. Within one lane the
     * serialization contract is unchanged — a JavaMail {@code Store} is still not
     * thread-safe, and the fair lock per (account, lane) is still the only thing
     * protecting the protocol stream.
     */
    public enum Lane {
        /**
         * Work a user is blocked on: message body and attachment fetches, a cold folder
         * listing, lazily paging further back. Short, bounded operations — anything
         * long-running here would rebuild the queue this lane exists to avoid.
         */
        INTERACTIVE,
        /**
         * Sync cycles, backfill, maintenance, and the server-side half of actions whose
         * local write already happened (move, flag propagation, draft append). May hold
         * its connection for as long as it needs.
         */
        BACKGROUND
    }

    /**
     * Pool and lock key. A record rather than a nested map because the two maps are
     * flat everywhere else — {@code purgeAccount}, {@code shutdown} and the pool
     * gauge all want to iterate connections, not accounts.
     */
    record ConnectionKey(Long accountId, Lane lane) {
    }

    private final Map<ConnectionKey, Store> connectionPool = new ConcurrentHashMap<>();
    private final Map<ConnectionKey, ReentrantLock> accountLocks = new ConcurrentHashMap<>();
    /**
     * Accounts whose interactive lane recently failed to connect, and the instant
     * it may be tried again. Keyed by account, not by {@link ConnectionKey} — only
     * the interactive lane is ever skipped, and the background lane has nothing to
     * fall back to. Entries are removed on expiry and on account purge, so this map
     * stays empty in the normal case rather than growing like the lock map.
     */
    private final Map<Long, Instant> interactiveLaneCooldown = new ConcurrentHashMap<>();

    private final AccountConnectionDetailsService connectionDetailsService;
    private final AccountRepository accountRepository;
    private final OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;
    private final MailClientProperties mailProps;
    private final RetryTemplate imapRetryTemplate;
    private final MailMetrics metrics;

    public ImapConnectionManager(AccountConnectionDetailsService connectionDetailsService,
            AccountRepository accountRepository, OAuth2TokenServiceRegistry oauth2TokenServiceRegistry,
            MailClientProperties mailProps, RetryTemplate imapRetryTemplate, MailMetrics metrics) {
        this.connectionDetailsService = connectionDetailsService;
        this.accountRepository = accountRepository;
        this.oauth2TokenServiceRegistry = oauth2TokenServiceRegistry;
        this.mailProps = mailProps;
        this.imapRetryTemplate = imapRetryTemplate;
        this.metrics = metrics;
    }

    @PostConstruct
    void registerPoolGauge() {
        metrics.registerImapPoolGauge(this, m -> m.connectionPool.size());
    }

    @FunctionalInterface
    public interface StoreAction<R> {
        /** Nullable by contract — side-effect-only actions return {@code null}. */
        @Nullable
        R execute(Store store) throws MessagingException, IOException;
    }

    /**
     * Runs an action over a connected Store under the lane's account lock.
     *
     * <p>
     * This is the single entry point through which all IMAP {@link Store} /
     * {@link Folder} work must flow. The fair {@link ReentrantLock} per (account,
     * lane) serializes every operation on that connection — a JavaMail
     * {@code Store}/{@code Folder} is not thread-safe, so two threads touching the
     * same connection concurrently would corrupt the protocol stream. The lock-free
     * fast path in {@link #getConnectedStore(Long, Lane)} is safe only because its
     * callers already hold this lock.
     *
     * <p>
     * Locks are per lane, so a {@link Lane#INTERACTIVE} action does not wait for a
     * {@link Lane#BACKGROUND} sync. Never hold both lanes' locks at once — see the
     * lock-order rule in {@code backend/docs/CONCURRENCY.md}.
     *
     * <p>
     * On {@link AuthenticationFailedException} the pool entry and the cached OAuth
     * access token are invalidated once and the whole action is retried with a
     * fresh connection. Reason: a cached store is technically alive
     * (NOOP/getDefaultFolder succeeds), but Gmail rejects only a follow-up IMAP
     * command such as {@code SELECT} when the token expires. Without the retry the
     * current sync iteration would fail and the user would see a 401 even when a
     * background token refresh would suffice. The retry is strictly single-shot —
     * if auth still fails with a fresh token, the problem is persistent (revoked
     * refresh token, wrong scopes) and propagates outwards.
     */
    public <R> @Nullable R executeWithLock(Long accountId, Lane lane, StoreAction<R> action) {
        requireUsableAccount(accountId);

        Lane effective = laneAfterCooldown(accountId, lane);
        ReentrantLock lock = lockFor(accountId, effective);
        lock.lock();
        try {
            return executeLocked(accountId, effective, action);
        } catch (InteractiveLaneUnavailable e) {
            // Handled below, deliberately outside the lock — see the type's javadoc.
        } finally {
            lock.unlock();
        }
        return executeWithLock(accountId, Lane.BACKGROUND, action);
    }

    /**
     * Resolves the lane to actually use, so a lane known to be unavailable is never
     * locked.
     *
     * <p>
     * Checked here rather than inside the connect step for a reason worth keeping:
     * while an account sits in the cooldown, every interactive request would
     * otherwise take the interactive lock, discover the cooldown, throw and release
     * — serializing all of them on a lock that guards no connection, on top of the
     * background lock they then queue on. Degraded mode has to be no slower than
     * the single-connection design it degrades to.
     *
     * <p>
     * The cooldown can still be set while a call is in flight, so
     * {@link #connectOrDegrade} keeps its own check; this one removes the common
     * case, not the race.
     */
    private Lane laneAfterCooldown(Long accountId, Lane lane) {
        if (lane != Lane.INTERACTIVE) {
            return lane;
        }
        Instant cooldownUntil = interactiveLaneCooldown.get(accountId);
        if (cooldownUntil == null) {
            return Lane.INTERACTIVE;
        }
        if (Instant.now().isBefore(cooldownUntil)) {
            return Lane.BACKGROUND;
        }
        interactiveLaneCooldown.remove(accountId, cooldownUntil);
        return Lane.INTERACTIVE;
    }

    /**
     * Signals that an interactive-lane connection could not be established, so the
     * action has not run and can still be re-run elsewhere.
     *
     * <p>
     * The retry happens in {@link #executeWithLock} <i>after</i> the interactive
     * lock is released, never from inside it. Taking the background lock while
     * holding the interactive one would put two locks in one thread's hands and
     * make the lock order in {@code CONCURRENCY.md} a two-way graph; releasing
     * first keeps it a line. Safe to re-run because it is thrown from the connect
     * step, before the action executes — there is no partial effect to undo.
     */
    private static final class InteractiveLaneUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /*
         * No cause and no stack trace: this never reaches a log or a user — the reason
         * is already logged where it is thrown, and the only handler turns it back into
         * a normal call on the other lane. Carrying a stack trace would be pure cost on
         * a path that exists to make things faster.
         */
        InteractiveLaneUnavailable() {
            super("interactive lane unavailable", null, false, false);
        }
    }

    /**
     * Same as {@link #executeWithLock} but gives up instead of queuing when the
     * lane's connection is busy.
     *
     * <p>
     * Still needed after the lane split, for a narrower reason. Waiting on a lock
     * throws nothing and reports nothing, so a read request that queues reaches the
     * user as a UI that silently hangs; a caller that can answer from the DB should
     * do that rather than wait. What changed is the expected wait: on
     * {@link Lane#INTERACTIVE} the contended case is another interactive request,
     * not a whole sync cycle, so timeouts here now bound a short queue rather than
     * a long one.
     *
     * @return the action's result, or empty when the lock was not acquired within
     *         {@code timeout}. Actions passed here must not return {@code null} —
     *         an empty result means "did not run".
     */
    public <R> Optional<R> executeWithLockOrSkip(Long accountId, Lane lane, Duration timeout, StoreAction<R> action) {
        requireUsableAccount(accountId);

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Lane effective = laneAfterCooldown(accountId, lane);
        ReentrantLock lock = lockFor(accountId, effective);
        boolean acquired;
        try {
            acquired = lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (!acquired) {
            log.debug("{} Connection of account {} ({} lane) is busy; skipping the non-blocking IMAP lookup.",
                    LogCategory.IMAP, accountId, effective);
            metrics.incrementImapLockSkipped();
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(executeLocked(accountId, effective, action));
        } catch (InteractiveLaneUnavailable e) {
            // Handled below, outside the lock — same reasoning as in executeWithLock.
        } finally {
            lock.unlock();
        }
        /*
         * What is left of the caller's budget, not a fresh copy of it. The timeout is
         * the promise this method makes — the folder-role lookup passes 1 s precisely
         * so a read cannot sit longer than that — and handing the same Duration to the
         * retry would let one call wait twice.
         */
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            metrics.incrementImapLockSkipped();
            return Optional.empty();
        }
        return executeWithLockOrSkip(accountId, Lane.BACKGROUND, Duration.ofNanos(remainingNanos), action);
    }

    private ReentrantLock lockFor(Long accountId, Lane lane) {
        return accountLocks.computeIfAbsent(new ConnectionKey(accountId, lane), k -> new ReentrantLock(true));
    }

    /**
     * Connect step of {@link #executeLocked}, with the interactive lane's one
     * degradation path.
     *
     * <p>
     * A second connection is a request the server can refuse — providers cap
     * simultaneous sessions (Gmail 15, others lower), and an account already
     * running a sync may be at the cap. Refusing the user's work over that would be
     * a failure mode the single-connection design never had, so the interactive
     * lane degrades to the background one instead: slower, exactly as slow as
     * before this class had lanes, and correct.
     *
     * <p>
     * The cooldown is what keeps the fallback from costing more than it saves. A
     * connect that fails does so after {@code connectionTimeout} and the retry
     * template's attempts; paying that on every message the user opens would make a
     * degraded lane worse than no lane. After one failure the lane is skipped
     * outright until {@code interactiveLaneRetryAfter} passes.
     */
    private Store connectOrDegrade(Long accountId, Lane lane) throws MessagingException {
        if (lane != Lane.INTERACTIVE) {
            return getConnectedStore(accountId, lane);
        }
        Instant cooldownUntil = interactiveLaneCooldown.get(accountId);
        if (cooldownUntil != null) {
            if (Instant.now().isBefore(cooldownUntil)) {
                throw new InteractiveLaneUnavailable();
            }
            interactiveLaneCooldown.remove(accountId, cooldownUntil);
        }
        try {
            return getConnectedStore(accountId, Lane.INTERACTIVE);
        } catch (AuthenticationFailedException e) {
            /*
             * Not a lane problem — the credentials are the account's, so the background
             * lane would fail identically. Hand it to the refresh-and-retry path in
             * executeLocked instead of degrading.
             */
            throw e;
        } catch (MessagingException e) {
            interactiveLaneCooldown.put(accountId, Instant.now().plus(mailProps.imap().interactiveLaneRetryAfter()));
            log.warn(
                    "{} Could not open an interactive connection for account {} ({}); "
                            + "running on the background lane and not retrying the interactive one for {}.",
                    LogCategory.IMAP, accountId, e.getMessage(), mailProps.imap().interactiveLaneRetryAfter());
            metrics.incrementImapLaneFallback();
            throw new InteractiveLaneUnavailable();
        }
    }

    /*
     * Fail-fast for accounts after a rejected OAuth refresh token or a permanent
     * IMAP auth failure. Without this guard every FE click would run through the
     * whole IMAP connect cycle (token refresh + double XOAUTH2 attempt), generate
     * traffic against the Google API and return a confusing "Invalid credentials"
     * instead of a clear "sign in again" instruction. The flag is cleared only by a
     * successful re-login in
     * ExternalProviderLoginService#processExternalProviderLogin.
     */
    private void requireUsableAccount(Long accountId) {
        if (accountRepository.isRequiresReauth(accountId).orElse(false)) {
            throw new MailOperationException(ErrorCode.MAIL_ACCOUNT_REQUIRES_REAUTH,
                    "The account requires sign-in again.", HttpStatus.UNAUTHORIZED, "error.mail.accountRequiresReauth");
        }
    }

    /**
     * Connection acquisition plus the single-shot auth retry. Lock must be held.
     */
    private <R> @Nullable R executeLocked(Long accountId, Lane lane, StoreAction<R> action) {
        try {
            try {
                Store store = connectOrDegrade(accountId, lane);
                return action.execute(store);
            } catch (AuthenticationFailedException firstAuthFail) {
                log.warn("{} IMAP auth failed for account {} ({} lane), trying to refresh the token and reconnect.",
                        LogCategory.IMAP, accountId, lane);
                metrics.incrementImapAuthRefresh();
                invalidateOauthTokenIfPresent(accountId);
                removeConnectionLocked(accountId, lane);
                try {
                    /*
                     * Through connectOrDegrade, not straight to getConnectedStore. A second auth
                     * failure must NOT degrade — credentials belong to the account, so the other
                     * lane fails identically and the right answer is the persistent classification
                     * below. But this reconnect can also fail for the ordinary reason the
                     * degradation exists for: the pooled connection was just closed, and re-opening
                     * it can hit the provider's simultaneous-session cap. Calling getConnectedStore
                     * directly turned that into an error for the user with no cooldown recorded and
                     * no fallback metric.
                     */
                    Store fresh = connectOrDegrade(accountId, lane);
                    return action.execute(fresh);
                } catch (AuthenticationFailedException secondAuthFail) {
                    log.error("{} IMAP auth failed even after token refresh for account {}", LogCategory.IMAP,
                            accountId, secondAuthFail);
                    throw persistentAuthenticationFailure(accountId, secondAuthFail);
                }
            }
        } catch (MessagingException | IOException e) {
            throw new MailConnectionException("Critical IMAP error for account " + accountId + ": " + e.getMessage(),
                    e);
        }
    }

    private RuntimeException persistentAuthenticationFailure(Long accountId, AuthenticationFailedException cause) {
        AuditLog.failure("imap_auth", "account=" + accountId, "persistent_auth_failure_after_token_refresh");
        try {
            AccountConnectionDetails details = connectionDetailsService.getImapConnectionDetails(accountId);
            if (details.authType() == AuthType.OAUTH2) {
                accountRepository.updateRequiresReauth(accountId, true);
                accountRepository.updateLastError(accountId, AccountLastError
                        .of(AccountLastErrorCode.OAUTH2_IMAP_ACCESS_DENIED, OAUTH2_IMAP_ACCESS_DENIED_DETAIL),
                        LocalDateTime.now());
                return new MailOperationException(ErrorCode.MAIL_OAUTH2_IMAP_ACCESS_DENIED,
                        OAUTH2_IMAP_ACCESS_DENIED_DETAIL, HttpStatus.UNAUTHORIZED, "error.mail.oauth2ImapAccessDenied");
            }
        } catch (RuntimeException markEx) {
            log.warn("{} Failed to mark account {} as requires_reauth: {}", LogCategory.IMAP, accountId,
                    markEx.getMessage());
        }
        /*
         * PASSWORD account (Seznam, custom IMAP) — return a localized
         * MailAuthenticationException. The raw IMAP server response (typically the
         * English "[AUTHENTICATIONFAILED] Incorrect authentication data") does not
         * belong in ProblemDetail.detail, which is rendered to the user in their chosen
         * language — it travels as the exception cause instead, so the original stack
         * stays available wherever the exception ends up logged. The internal accountId
         * is also dropped from the user-facing text — the user knows which account is
         * involved from UI context.
         */
        return new MailAuthenticationException(cause);
    }

    /**
     * Opens a named folder on an <i>already held</i> Store and returns it
     * <b>open</b>. The caller owns the returned folder and MUST close it (typically
     * in a {@code finally}); this method intentionally does not, because the folder
     * outlives the call.
     *
     * <p>
     * <b>Concurrency contract:</b> this does not take a lock. It is package-private
     * precisely so that its only caller (opening a move destination from
     * {@code ImapActionService}) invokes it from <i>inside</i> an
     * {@link #executeWithLock} action that already holds one.
     *
     * <p>
     * It takes the {@link Store} rather than an account id on purpose. Since an
     * account has a connection per {@link Lane}, looking one up here could hand
     * back the <i>other</i> lane's Store — a second folder opened on a connection
     * whose lock this thread does not hold, which is the exact protocol-stream
     * corruption the locking is there to prevent, and silent when it happens. The
     * caller already has the right Store (its folder's own, via
     * {@code Folder.getStore()}), so the parameter makes the wrong one unreachable.
     */
    Folder openFolder(Store store, String folderName, int mode) throws MessagingException {
        Folder folder = store.getFolder(folderName);
        if (!folder.isOpen()) {
            folder.open(mode);
        }
        return folder;
    }

    /**
     * Returns a live IMAP connection from the pool (or establishes a new one).
     *
     * <p>
     * {@code Store.isConnected()} returns {@code true} even for dead TCP
     * connections until the keep-alive timeout — without an active NOOP test a
     * subsequent {@code folder.open()} would throw and the application would not
     * recover without a restart.
     *
     * <p>
     * <b>Concurrency contract:</b> the fast path returns a pooled {@link Store} and
     * runs a liveness probe (an IMAP command) <i>without</i> taking the lane's
     * lock. That is safe only because its caller ({@link #executeWithLock}, via
     * {@link #connectOrDegrade}) already holds it. Kept package-private so no code
     * outside this package can obtain the shared, non-thread-safe Store without
     * that lock.
     */
    Store getConnectedStore(Long accountId, Lane lane) throws MessagingException {
        ConnectionKey key = new ConnectionKey(accountId, lane);
        Store store = connectionPool.get(key);

        if (store != null && isStoreAlive(store)) {
            return store;
        }

        ReentrantLock lock = lockFor(accountId, lane);
        lock.lock();
        try {
            store = connectionPool.get(key);
            if (store == null || !isStoreAlive(store)) {
                if (store != null) {
                    try {
                        store.close();
                    } catch (Exception e) {
                        log.debug("{} Closing a dead pooled store for account {} ({} lane) failed: {}",
                                LogCategory.IMAP, accountId, lane, e.getMessage());
                    }
                    connectionPool.remove(key);
                }
                store = createNewConnectedStore(accountId);
                connectionPool.put(key, store);
            }
            return store;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks whether the store is actually alive — first via the cheap
     * isConnected() check, then via an active NOOP-like command when the store is
     * "connected" according to JavaMail.
     *
     * NOOP sends an empty command to the server and waits for a response — this
     * detects dead TCP connections that isConnected() misses.
     */
    private boolean isStoreAlive(Store store) {
        if (!store.isConnected()) {
            return false;
        }
        try {
            /*
             * Use getDefaultFolder() — it does not create a named Folder object and is the
             * cheapest operation that verifies the liveness of the TCP connection.
             */
            store.getDefaultFolder();
            return true;
        } catch (Exception e) {
            log.warn("{} Store failed the liveness check, it will be reconnected: {}", LogCategory.IMAP,
                    e.getMessage());
            return false;
        }
    }

    /**
     * Closes and removes the pooled connection for a deleted account. The
     * per-account lock deliberately STAYS in {@code accountLocks}: removing it
     * while another thread is still blocked on it would let a third thread create a
     * fresh lock object via {@code computeIfAbsent} and enter the critical section
     * concurrently with the parked owner of the old lock — two threads sharing one
     * non-thread-safe connection. A {@link ReentrantLock} per deleted account is a
     * few dozen bytes, irrelevant for a desktop app with a handful of accounts;
     * everything is released in {@link #shutdown()}.
     */
    public void purgeAccount(Long accountId) {
        removeConnection(accountId);
        interactiveLaneCooldown.remove(accountId);
    }

    public PoolStats getPoolStats() {
        return new PoolStats(connectionPool.size(), accountLocks.size());
    }

    /**
     * Counts are per <b>connection</b>, not per account — an account with both
     * lanes open contributes two to each. Named for that since the lane split:
     * "accountLocks" in a support dump would read as a leak on a two-account
     * install rather than as the two lanes doing their job.
     */
    public record PoolStats(int activeConnections, int trackedConnectionLocks) {
    }

    /**
     * Closes and removes <b>both lanes'</b> IMAP connections for the given account.
     * Called when an account is deleted from AccountService, after a re-login, and
     * by the sync retry path after a transient failure, so that no dead TCP
     * connection is left in memory.
     *
     * <p>
     * Both lanes on purpose: every caller is reacting to something that invalidates
     * the account's sessions as a whole (deleted, re-authenticated, network
     * dropped), and leaving the other lane's socket behind would keep exactly the
     * kind of half-open connection this method exists to clear. The lanes are
     * locked and cleared one after another, never together — see the lock-order
     * rule.
     */
    public void removeConnection(Long accountId) {
        for (Lane lane : Lane.values()) {
            ReentrantLock lock = lockFor(accountId, lane);
            lock.lock();
            try {
                removeConnectionLocked(accountId, lane);
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Resolves the account's OAuth2 provider from the DB and invalidates its cached
     * access token. Called from the auth-retry path in {@link #executeWithLock};
     * no-op for PASSWORD accounts (no token to cache). Resolve errors are tolerated
     * silently — the auth-retry is a best-effort path and a second failure would
     * end up in {@code MailConnectionException} anyway.
     */
    private void invalidateOauthTokenIfPresent(Long accountId) {
        try {
            AccountConnectionDetails details = connectionDetailsService.getImapConnectionDetails(accountId);
            if (details.authType() == AuthType.OAUTH2 && details.oauth2Provider() != null) {
                oauth2TokenServiceRegistry.resolve(details.oauth2Provider()).invalidate(accountId);
            }
        } catch (RuntimeException e) {
            log.warn("{} Failed to invalidate OAuth2 token for account {} (continuing retry): {}", LogCategory.IMAP,
                    accountId, e.getMessage());
        }
    }

    /**
     * Variant of {@link #removeConnection(Long)} for callers that already hold the
     * lock of the lane being cleared (typically the retry path in
     * {@link #executeWithLock}). {@link ReentrantLock} is reentrant, but we want to
     * avoid the repeated lock/unlock bookkeeping.
     *
     * <p>
     * Clears the named lane only. Touching the other lane's Store from here would
     * mean closing a connection this thread does not hold the lock for, while
     * another thread may be mid-command on it.
     */
    private void removeConnectionLocked(Long accountId, Lane lane) {
        Store store = connectionPool.remove(new ConnectionKey(accountId, lane));
        if (store != null) {
            try {
                store.close();
                log.info("{} IMAP connection for account {} ({} lane) closed and removed from the pool.",
                        LogCategory.IMAP, accountId, lane);
            } catch (Exception e) {
                log.debug("{} Closing the removed IMAP connection for account {} ({} lane) failed: {}",
                        LogCategory.IMAP, accountId, lane, e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("{} Closing all IMAP connections ({} active)...", LogCategory.IMAP, connectionPool.size());
        connectionPool.forEach((key, store) -> {
            try {
                if (store.isConnected()) {
                    store.close();
                }
            } catch (Exception e) {
                log.warn("{} Error closing IMAP connection for account {} ({} lane): {}", LogCategory.IMAP,
                        key.accountId(), key.lane(), e.getMessage());
            }
        });
        connectionPool.clear();
        accountLocks.clear();
        interactiveLaneCooldown.clear();
    }

    private Store createNewConnectedStore(Long accountId) throws MessagingException {
        AccountConnectionDetails details = connectionDetailsService.getImapConnectionDetails(accountId);

        /*
         * Defense-in-depth: the OAuth2 access token travels in the SASL XOAUTH2 payload
         * as base64(user=\0auth=Bearer <token>\0\0). Without TLS it would be sniffable
         * on the wire. The provider template should have useSsl=true, but if someone
         * changes the port/flag in the DB, fail-fast is safer than a token leak on the
         * wire.
         */
        if (details.authType() == AuthType.OAUTH2 && !details.useSsl()) {
            AuditLog.critical("imap_oauth2_plaintext_blocked", LogMasker.maskEmail(details.email()),
                    "account=" + accountId);
            throw new MailConnectionException(
                    "OAuth2 account requires an SSL/TLS connection for IMAP (account " + accountId + ")");
        }

        Properties props = new Properties();
        String protocol = details.useSsl() ? mailProps.imap().protocolSsl() : mailProps.imap().protocolStandard();
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", details.host());

        int port = (details.port() > 0) ? details.port() : mailProps.imap().defaultPort();
        props.put("mail." + protocol + ".port", String.valueOf(port));

        props.put("mail." + protocol + ".ssl.enable", String.valueOf(details.useSsl()));
        // Explicit server-identity (hostname) check on the TLS handshake; a no-op on
        // the
        // plaintext protocol but present so the implicit-SSL path cannot regress.
        props.put("mail." + protocol + ".ssl.checkserveridentity", "true");

        /*
         * Pin partial fetch ON (the Angus default) rather than trusting it: the B1-1
         * bounded body read (MimePartExtractor.readBounded) only bounds the heap
         * because getInputStream() streams the part in fetchsize chunks — with
         * partialfetch off, Angus buffers the entire (attacker-sized) part before the
         * cap ever runs.
         */
        props.put("mail." + protocol + ".partialfetch", "true");

        /*
         * JavaMail's PropUtil only reads String/Integer values from Properties — a raw
         * Duration object is silently ignored, leaving the connection with no effective
         * timeout. Always pass String.valueOf(...).toMillis(), matching what
         * MailConnectionProbe does for the credential test path.
         */
        props.put("mail." + protocol + ".timeout", String.valueOf(mailProps.imap().readTimeout().toMillis()));
        props.put("mail." + protocol + ".connectiontimeout",
                String.valueOf(mailProps.imap().connectionTimeout().toMillis()));

        if (details.authType() == AuthType.OAUTH2) {
            MailAuthMechanisms.configureOAuth2(props, protocol);
        }

        Session session = Session.getInstance(props);
        Store store = session.getStore(protocol);

        /*
         * Connect wrapped in RetryTemplate — retries only on transient network errors
         * (SocketTimeoutException, ConnectException, SSLException, IOException).
         * AuthenticationFailedException is explicitly false in the retry policy => it
         * propagates immediately to executeWithLock, which has its own refresh-token
         * path (retry with backoff would not help here — the token will not fix
         * itself).
         */
        final int finalPort = port;
        var sample = metrics.startImapConnect();
        String outcome = MailMetrics.OUTCOME_SUCCESS;
        try {
            imapRetryTemplate.execute(ctx -> {
                int attempt = ctx.getRetryCount() + 1;
                if (attempt > 1) {
                    Throwable lastError = ctx.getLastThrowable();
                    log.warn("{} IMAP connect retry #{} for account {}: {}", LogCategory.IMAP, attempt, accountId,
                            lastError != null ? lastError.getMessage() : "n/a");
                }
                if (details.authType() == AuthType.OAUTH2) {
                    log.info("{} Connecting (OAuth2 / {}): {}", LogCategory.IMAP, details.oauth2Provider(),
                            LogMasker.maskEmail(details.email()));
                    OAuth2TokenService tokenService = oauth2TokenServiceRegistry.resolve(details.oauth2Provider());
                    String accessToken = tokenService.getAccessToken(accountId, details.passwordOrSecret(),
                            details.email());
                    store.connect(details.host(), finalPort, details.email(), accessToken);
                } else {
                    log.debug("{} Connecting (Password): {}", LogCategory.IMAP, LogMasker.lazyEmail(details.email()));
                    store.connect(details.host(), finalPort, details.username(), details.passwordOrSecret());
                }
                return null;
            });
            return store;
        } catch (RuntimeException | MessagingException e) {
            outcome = MailMetrics.OUTCOME_FAILURE;
            throw e;
        } finally {
            metrics.recordImapConnect(sample, outcome);
        }
    }
}
