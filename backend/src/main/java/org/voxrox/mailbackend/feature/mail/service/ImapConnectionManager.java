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

    private final Map<Long, Store> connectionPool = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

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
     * Runs an action over a connected Store under the account lock.
     *
     * <p>
     * This is the single entry point through which all IMAP {@link Store} /
     * {@link Folder} work must flow. The per-account fair {@link ReentrantLock}
     * serializes every operation on one account's connection — a JavaMail
     * {@code Store}/{@code Folder} is not thread-safe, so two threads touching the
     * same account's connection concurrently would corrupt the protocol stream. The
     * lock-free fast paths in {@link #getConnectedStore(Long)} and
     * {@link #openFolder(Long, String, int)} are safe only because their callers
     * already hold this lock.
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
    public <R> @Nullable R executeWithLock(Long accountId, StoreAction<R> action) {
        /*
         * Fail-fast for accounts after a rejected OAuth refresh token or a permanent
         * IMAP auth failure. Without this guard every FE click would run through the
         * whole IMAP connect cycle (token refresh + double XOAUTH2 attempt), generate
         * traffic against the Google API and return a confusing "Invalid credentials"
         * instead of a clear "sign in again" instruction. The flag is cleared only by a
         * successful re-login in
         * ExternalProviderLoginService#processExternalProviderLogin.
         */
        if (accountRepository.isRequiresReauth(accountId).orElse(false)) {
            throw new MailOperationException(ErrorCode.MAIL_ACCOUNT_REQUIRES_REAUTH,
                    "The account requires sign-in again.", HttpStatus.UNAUTHORIZED, "error.mail.accountRequiresReauth");
        }

        ReentrantLock lock = accountLocks.computeIfAbsent(accountId, k -> new ReentrantLock(true));
        lock.lock();
        try {
            try {
                Store store = getConnectedStore(accountId);
                return action.execute(store);
            } catch (AuthenticationFailedException firstAuthFail) {
                log.warn("{} IMAP auth failed for account {}, trying to refresh the token and reconnect.",
                        LogCategory.IMAP, accountId);
                metrics.incrementImapAuthRefresh();
                invalidateOauthTokenIfPresent(accountId);
                removeConnectionLocked(accountId);
                try {
                    Store fresh = getConnectedStore(accountId);
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
        } finally {
            lock.unlock();
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
     * Opens a named folder on the account's pooled Store and returns it
     * <b>open</b>. The caller owns the returned folder and MUST close it (typically
     * in a {@code finally}); this method intentionally does not, because the folder
     * outlives the call.
     *
     * <p>
     * <b>Concurrency contract:</b> like {@link #getConnectedStore(Long)} this does
     * not take the per-account lock itself. It is package-private precisely so that
     * its only caller (opening a move destination from {@code ImapActionService})
     * invokes it from <i>inside</i> an {@link #executeWithLock} action that already
     * holds the lock. Calling it without the lock held would let a concurrent
     * operation corrupt the shared, non-thread-safe connection.
     */
    Folder openFolder(Long accountId, String folderName, int mode) throws MessagingException {
        Store store = getConnectedStore(accountId);
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
     * runs a liveness probe (an IMAP command) <i>without</i> taking the per-account
     * lock. That is safe only because every caller — {@link #executeWithLock} and
     * the package-private {@link #openFolder} reached from inside an action —
     * already holds the account lock. Kept package-private so no code outside this
     * package can obtain the shared, non-thread-safe Store without that lock.
     */
    Store getConnectedStore(Long accountId) throws MessagingException {
        Store store = connectionPool.get(accountId);

        if (store != null && isStoreAlive(store)) {
            return store;
        }

        ReentrantLock lock = accountLocks.computeIfAbsent(accountId, k -> new ReentrantLock(true));
        lock.lock();
        try {
            store = connectionPool.get(accountId);
            if (store == null || !isStoreAlive(store)) {
                if (store != null) {
                    try {
                        store.close();
                    } catch (Exception e) {
                        log.debug("{} Closing a dead pooled store for account {} failed: {}", LogCategory.IMAP,
                                accountId, e.getMessage());
                    }
                    connectionPool.remove(accountId);
                }
                store = createNewConnectedStore(accountId);
                connectionPool.put(accountId, store);
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
    }

    public PoolStats getPoolStats() {
        return new PoolStats(connectionPool.size(), accountLocks.size());
    }

    public record PoolStats(int activeConnections, int trackedAccountLocks) {
    }

    /**
     * Closes and removes the IMAP connection for the given account. Called when an
     * account is deleted from AccountService so that no dead TCP connection is left
     * in memory.
     */
    public void removeConnection(Long accountId) {
        ReentrantLock lock = accountLocks.computeIfAbsent(accountId, k -> new ReentrantLock(true));
        lock.lock();
        try {
            removeConnectionLocked(accountId);
        } finally {
            lock.unlock();
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
     * Variant of {@link #removeConnection(Long)} for callers that already hold
     * {@code accountLocks.get(accountId)} (typically the retry path in
     * {@link #executeWithLock}). {@link ReentrantLock} is reentrant, but we want to
     * avoid the repeated lock/unlock bookkeeping.
     */
    private void removeConnectionLocked(Long accountId) {
        Store store = connectionPool.remove(accountId);
        if (store != null) {
            try {
                store.close();
                log.info("{} IMAP connection for account {} closed and removed from the pool.", LogCategory.IMAP,
                        accountId);
            } catch (Exception e) {
                log.debug("{} Closing the removed IMAP connection for account {} failed: {}", LogCategory.IMAP,
                        accountId, e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("{} Closing all IMAP connections ({} active)...", LogCategory.IMAP, connectionPool.size());
        connectionPool.forEach((accountId, store) -> {
            try {
                if (store.isConnected()) {
                    store.close();
                }
            } catch (Exception e) {
                log.warn("{} Error closing IMAP connection for account {}: {}", LogCategory.IMAP, accountId,
                        e.getMessage());
            }
        });
        connectionPool.clear();
        accountLocks.clear();
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
