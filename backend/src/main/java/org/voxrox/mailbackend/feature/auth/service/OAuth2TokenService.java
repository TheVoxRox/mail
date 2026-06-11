package org.voxrox.mailbackend.feature.auth.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.voxrox.mailbackend.core.metrics.MailMetrics;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

/**
 * Provider-agnostic base for OAuth2 access token management.
 *
 * <p>
 * The token refresh protocol against Google and Microsoft Identity Platform is
 * nearly identical (RFC 6749 §6, form-encoded {@code grant_type=refresh_token},
 * 200 with {@code access_token} + {@code expires_in}, 400/401 on permanent
 * rejection). Provider-specific differences are isolated to small abstract
 * hooks:
 * <ul>
 * <li>{@link #providerName()} / {@link #providerDisplayName()} — identity in
 * logs, audit and the registry lookup key.</li>
 * <li>{@link #tokenEndpoint()} / {@link #clientId()} — provider HTTP target and
 * OAuth client.</li>
 * <li>{@link #customizeRefreshBody(MultiValueMap)} — extra body params
 * (Microsoft requires the same scope at refresh time; Google adds its
 * installed-app client_secret).</li>
 * <li>{@link #revokeToken(AccountEntity)} — RFC 7009 revoke (Google) vs. local
 * cleanup only (Microsoft does not implement RFC 7009).</li>
 * </ul>
 *
 * <p>
 * Adding another provider = a new {@code @Service} subclass implementing those
 * hooks + the matching Spring Security {@code ClientRegistration} in
 * {@code application.properties} + a row in {@code mail_providers}. No changes
 * required in the shared refresh / cache / error-handling code below.
 */
public abstract class OAuth2TokenService {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TokenService.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final ParameterizedTypeReference<Map<String, Object>> TOKEN_RESPONSE_TYPE = new ParameterizedTypeReference<>() {
    };

    /**
     * RestClient with explicit timeouts (connect 5 s, read 10 s). Without them a
     * hung provider endpoint would block the sync thread forever — and under
     * {@code ImapConnectionManager.executeWithLock} would trigger cascading lock
     * contention across all accounts.
     */
    protected final RestClient restClient = buildRestClient();

    protected final AccountRepository accountRepository;
    protected final MailMetrics metrics;
    protected final TokenCache tokenCache;

    /*
     * Serializes the HTTP refresh per account. IMAP (under the account connection
     * lock) and SMTP (userMailExecutor) can both see a stale cache entry at the
     * same moment and would fire two parallel POSTs to the provider's /token
     * endpoint. Providers tolerate multiple live access tokens, so this is purely
     * wasted traffic — but it doubles the chance of tripping provider throttling.
     * The loser of the race now waits on the lock and picks up the winner's token
     * from the cache (double-check in getAccessToken). Entries deliberately stay in
     * the map after account deletion — a lock per account is a few dozen bytes,
     * same policy as ImapConnectionManager.accountLocks.
     */
    private final ConcurrentMap<Long, ReentrantLock> refreshLocks = new ConcurrentHashMap<>();

    protected OAuth2TokenService(AccountRepository accountRepository, MailMetrics metrics, TokenCache tokenCache) {
        this.accountRepository = accountRepository;
        this.metrics = metrics;
        this.tokenCache = tokenCache;
    }

    /** Stable registration key. Matches {@code accounts.oauth2_provider}. */
    public abstract String providerName();

    /**
     * Human-readable provider name used in log messages, audit and user-facing
     * errors.
     */
    protected abstract String providerDisplayName();

    /**
     * OAuth token endpoint URL. Mutable field in subclasses so tests can redirect
     * to WireMock.
     */
    protected abstract String tokenEndpoint();

    /** OAuth client id (injected via {@code @Value} in the subclass). */
    protected abstract String clientId();

    /**
     * Hook to append provider-specific body params before the refresh POST. Default
     * no-op. The base body is public-client-only ({@code client_id},
     * {@code refresh_token}, {@code grant_type}); anything else is a provider
     * difference. Microsoft uses this to send {@code scope} (the v2.0 endpoint
     * downgrades to default scopes otherwise, breaking IMAP/SMTP) — and must not
     * receive a {@code client_secret} (a public client sending one fails with
     * AADSTS700025). Google uses it to add its installed-app client_secret.
     */
    protected void customizeRefreshBody(MultiValueMap<String, String> body) {
        // no-op by default
    }

    /**
     * Best-effort revoke of the refresh token at the provider. A failure must not
     * crash the caller — implementations handle errors with try/catch + audit log.
     */
    public abstract void revokeToken(AccountEntity account);

    /**
     * Returns a fresh access token for the given account — from the cache if valid,
     * otherwise performs an HTTP refresh against the provider and stores it in the
     * cache.
     *
     * <p>
     * Entry point for both IMAP and SMTP. Called per connection; the cache ensures
     * that the actual HTTP call happens only ~once per hour.
     */
    public final String getAccessToken(Long accountId, String refreshToken, String email) {
        Optional<CachedToken> cached = tokenCache.get(accountId);
        if (cached.isPresent() && cached.get().isFresh()) {
            return cached.get().accessToken();
        }

        ReentrantLock refreshLock = refreshLocks.computeIfAbsent(accountId, id -> new ReentrantLock());
        refreshLock.lock();
        try {
            // Double-check under the lock: a concurrent caller may have refreshed
            // while this thread was waiting — reuse its token instead of firing a
            // second HTTP request.
            cached = tokenCache.get(accountId);
            if (cached.isPresent() && cached.get().isFresh()) {
                return cached.get().accessToken();
            }

            CachedToken refreshed = doRefresh(accountId, refreshToken, email);
            tokenCache.put(accountId, refreshed);
            return refreshed.accessToken();
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * Invalidates the cached access token for the account. Called from the
     * auth-retry path in {@code ImapConnectionManager} and
     * {@code SmtpMessageService} after the server rejected XOAUTH2 — the cached
     * token may have expired sooner than expected (clock skew, server-side revoke).
     */
    public final void invalidate(Long accountId) {
        tokenCache.invalidate(accountId);
    }

    public final CacheStats getCacheStats() {
        return new CacheStats(tokenCache.size());
    }

    /**
     * Used during the initial OAuth2 login flow, where we do not yet have an
     * accountId — the login handler receives the refresh token from the OAuth2
     * redirect and needs to exchange it for an access token straight away, without
     * caching anything.
     */
    public final String exchangeRefreshToken(String refreshToken, String email) {
        return doRefresh(null, refreshToken, email).accessToken();
    }

    /**
     * @param accountId
     *            when {@code null}, this is the initial login flow (the account may
     *            not yet be persisted / there is no point marking it)
     */
    private CachedToken doRefresh(Long accountId, String refreshToken, String email) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId());
        body.add("refresh_token", refreshToken);
        body.add("grant_type", "refresh_token");
        customizeRefreshBody(body);

        try {
            Map<String, Object> response = restClient.post().uri(tokenEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body).retrieve().body(TOKEN_RESPONSE_TYPE);

            if (response == null || !response.containsKey("access_token")) {
                throw new MailOperationException(ErrorCode.INTERNAL_ERROR,
                        providerDisplayName() + " OAuth provider did not return an access_token.");
            }

            String accessToken = (String) response.get("access_token");
            long expiresInSeconds = parseExpiresIn(response.get("expires_in"));
            Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds);

            String maskedEmail = LogMasker.maskEmail(email);
            /*
             * Log granted scopes to diagnose the scenario where the provider returns an
             * access token but IMAP/SMTP rejects it (typically because a sensitive scope is
             * missing from the grant — Gmail consent screen in Testing mode, Microsoft
             * default-scope downgrade, ...). Without this log the root cause cannot be
             * distinguished from other XOAUTH2 failures.
             */
            Object grantedScope = response.get("scope");
            log.info("{} {} access token refreshed successfully for: {} (valid {}s, scope: {})", LogCategory.AUTH,
                    providerDisplayName(), maskedEmail, expiresInSeconds,
                    grantedScope != null ? grantedScope : "<missing>");
            AuditLog.success("token_refresh", maskedEmail, "provider=" + providerName());
            metrics.recordOauthRefresh(MailMetrics.OUTCOME_SUCCESS);
            return new CachedToken(accessToken, expiresAt);

        } catch (HttpClientErrorException e) {
            if (!isPermanentRefreshRejection(e)) {
                throw tokenRefreshFailure(email, e);
            }

            String maskedEmail = LogMasker.maskEmail(email);
            log.error(
                    "{} {} rejected the OAuth refresh for {} (status {}, likely revoke, expiration "
                            + "or OAuth client mismatch)",
                    LogCategory.AUTH, providerDisplayName(), maskedEmail, e.getStatusCode(), e);
            AuditLog.failure("token_refresh", maskedEmail, providerName() + "_rejected_" + e.getStatusCode().value());

            /*
             * Permanent failure: the refresh token will never work again until the user
             * goes through the OAuth login flow once more. Mark the account as
             * requires_reauth so MailSyncScheduler stops picking it up — otherwise we would
             * hammer the provider's /token endpoint forever and earn a warning ban / AADSTS
             * throttle.
             */
            if (accountId != null) {
                tokenCache.invalidate(accountId);
                try {
                    accountRepository.updateRequiresReauth(accountId, true);
                    accountRepository.updateLastError(accountId,
                            AccountLastError.of(AccountLastErrorCode.OAUTH2_REFRESH_REJECTED,
                                    Map.of("provider", providerDisplayName()),
                                    providerDisplayName() + " OAuth: the refresh token or OAuth client was rejected; "
                                            + "verify the configuration and sign in again."),
                            LocalDateTime.now());
                    log.warn("{} Account {} marked as requires_reauth.", LogCategory.AUTH, maskedEmail);
                } catch (Exception markEx) {
                    log.error("{} Failed to mark account {} as requires_reauth", LogCategory.AUTH, maskedEmail, markEx);
                    AuditLog.critical("account_reauth_marking_failed", maskedEmail, markEx.getClass().getSimpleName());
                }
            }

            metrics.recordOauthRefresh(MailMetrics.OUTCOME_AUTH_REVOKED);
            throw new MailOperationException(ErrorCode.MAIL_AUTHENTICATION_FAILED,
                    providerDisplayName() + " authorization has expired or been revoked.", HttpStatus.UNAUTHORIZED);
        } catch (MailOperationException e) {
            metrics.recordOauthRefresh(MailMetrics.OUTCOME_FAILURE);
            throw e;
        } catch (Exception e) {
            throw tokenRefreshFailure(email, e);
        }
    }

    private static boolean isPermanentRefreshRejection(HttpClientErrorException e) {
        return e.getStatusCode() == HttpStatus.BAD_REQUEST || e.getStatusCode() == HttpStatus.UNAUTHORIZED;
    }

    private MailOperationException tokenRefreshFailure(String email, Exception e) {
        String maskedEmail = LogMasker.maskEmail(email);
        log.error("{} Critical error while communicating with the {} OAuth provider for {}", LogCategory.AUTH,
                providerDisplayName(), maskedEmail, e);
        AuditLog.critical("token_refresh", maskedEmail, e.getClass().getSimpleName());
        metrics.recordOauthRefresh(MailMetrics.OUTCOME_FAILURE);
        return new MailOperationException(ErrorCode.INTERNAL_ERROR, "Access token refresh failed: " + e.getMessage());
    }

    private static long parseExpiresIn(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        /*
         * Providers normally return 3599 s (Google) or 3599–4499 s (Microsoft). If the
         * field is missing or has an unexpected shape, fall back to a conservative
         * 5-minute TTL — better to be fresher.
         */
        return 300L;
    }

    private static RestClient buildRestClient() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(factory).build();
    }

    public record CacheStats(int cachedTokens) {
    }
}
