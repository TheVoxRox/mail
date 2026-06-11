package org.voxrox.mailbackend.feature.auth.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.voxrox.mailbackend.core.security.CryptoService;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.entity.AccountCredentialEntity;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountCredentialRepository;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Unit tests for {@link GoogleTokenService}.
 *
 * Mocks: - HTTP layer via WireMock (runs on a dynamic port) - Repository via
 * Mockito.
 *
 * Covers the critical paths: cache, refresh happy path, 400 -> requires_reauth,
 * 5xx -> transient, network error, exchangeRefreshToken without accountId, DB
 * failure during mark, EXPIRY_SKEW, parseExpiresIn variants, revoke.
 */
@ExtendWith(MockitoExtension.class)
class GoogleTokenServiceTest {

    private static final String TOKEN_PATH = "/token";
    private static final String REVOKE_PATH = "/revoke";
    private static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String EMAIL = "user@example.com";

    @Mock
    private CryptoService cryptoService;

    @Mock
    private AccountCredentialRepository credentialRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private org.voxrox.mailbackend.core.metrics.MailMetrics mailMetrics;

    private GoogleTokenService service;

    private WireMockServer wireMock;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        service = new GoogleTokenService(cryptoService, credentialRepository, accountRepository, mailMetrics,
                new TokenCache());

        ReflectionTestUtils.setField(service, "clientId", CLIENT_ID);
        ReflectionTestUtils.setField(service, "clientSecret", CLIENT_SECRET);
        ReflectionTestUtils.setField(service, "tokenEndpoint", wireMock.baseUrl() + TOKEN_PATH);
        ReflectionTestUtils.setField(service, "revokeEndpoint", wireMock.baseUrl() + REVOKE_PATH);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private void stubTokenSuccess(String accessToken, long expiresIn) {
        wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"access_token\":\"" + accessToken + "\","
                        + "\"expires_in\":" + expiresIn + "," + "\"token_type\":\"Bearer\"}")));
    }

    @Nested
    @DisplayName("Refresh — happy path")
    class HappyPath {

        @Test
        void shouldReturnAccessTokenFromGoogleResponse() {
            stubTokenSuccess("ya29.fresh-token", 3600);

            String token = service.getAccessToken(1L, "refresh-xyz", EMAIL);

            assertThat(token).isEqualTo("ya29.fresh-token");
        }

        @Test
        void shouldSendCorrectFormBodyToGoogle() {
            stubTokenSuccess("any", 3600);

            service.getAccessToken(1L, "rt-abc", EMAIL);

            wireMock.verify(postRequestedFor(urlEqualTo(TOKEN_PATH))
                    .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                    .withRequestBody(containing("client_id=" + CLIENT_ID))
                    .withRequestBody(containing("client_secret=" + CLIENT_SECRET))
                    .withRequestBody(containing("refresh_token=rt-abc"))
                    .withRequestBody(containing("grant_type=refresh_token")));
        }

        @Test
        void shouldThrowWhenResponseMissingAccessToken() {
            wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json").withBody("{\"token_type\":\"Bearer\"}")));

            assertThatThrownBy(() -> service.getAccessToken(1L, "rt", EMAIL)).isInstanceOf(MailOperationException.class)
                    .extracting("code").isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Nested
    @DisplayName("Cache")
    class Cache {

        @Test
        void secondCallWithinValidityShouldNotHitHttp() {
            stubTokenSuccess("token-1", 3600);

            service.getAccessToken(1L, "rt", EMAIL);
            service.getAccessToken(1L, "rt", EMAIL);

            wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }

        @Test
        void invalidateShouldForceNextCallToHitHttp() {
            stubTokenSuccess("token", 3600);

            service.getAccessToken(1L, "rt", EMAIL);
            service.invalidate(1L);
            service.getAccessToken(1L, "rt", EMAIL);

            wireMock.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }

        @Test
        void differentAccountsHaveIndependentCache() {
            stubTokenSuccess("token", 3600);

            service.getAccessToken(1L, "rt", EMAIL);
            service.getAccessToken(2L, "rt", EMAIL);

            // First call for each account = HTTP. Subsequent ones would be cached.
            wireMock.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }

        @Test
        void concurrentCallsWithStaleCacheShouldRefreshOnlyOnce() throws Exception {
            // IMAP (account lock) and SMTP (userMailExecutor) can both see a stale
            // cache at the same moment. The per-account refresh lock must serialize
            // them: the loser waits, then reuses the winner's token from the cache —
            // exactly one POST to /token. The 300 ms response delay guarantees both
            // threads pass the initial cache check before the first refresh lands.
            wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withFixedDelay(300)
                            .withBody("{\"access_token\":\"shared\",\"expires_in\":3600,\"token_type\":\"Bearer\"}")));

            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var start = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<String> call = () -> {
                    start.await();
                    return service.getAccessToken(1L, "rt", EMAIL);
                };
                var first = executor.submit(call);
                var second = executor.submit(call);
                start.countDown();

                assertThat(first.get()).isEqualTo("shared");
                assertThat(second.get()).isEqualTo("shared");
            } finally {
                executor.shutdownNow();
            }

            wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }

        @Test
        void tokenExpiringWithinSkewShouldNotBeCached() {
            // EXPIRY_SKEW = 60s. Returning expires_in=30 -> token "fresh" for only 30s,
            // but isFresh() subtracts the 60s skew -> the cached token must be stale
            // immediately.
            stubTokenSuccess("token", 30);

            service.getAccessToken(1L, "rt", EMAIL);
            service.getAccessToken(1L, "rt", EMAIL);

            wireMock.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }
    }

    @Nested
    @DisplayName("400 BadRequest — refresh token revoked/expired")
    class BadRequestPath {

        private void stubBadRequest() {
            wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(400)
                    .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_grant\"}")));
        }

        @Test
        void shouldMarkAccountAsRequiresReauth() {
            stubBadRequest();

            assertThatThrownBy(() -> service.getAccessToken(42L, "rt", EMAIL))
                    .isInstanceOf(MailOperationException.class).extracting("code")
                    .isEqualTo(ErrorCode.MAIL_AUTHENTICATION_FAILED);

            verify(accountRepository).updateRequiresReauth(42L, true);
            verify(accountRepository).updateLastError(eq(42L), any(AccountLastError.class), any(LocalDateTime.class));
        }

        @Test
        void shouldEvictCachedTokenAfterReauth() {
            // First we fill the cache with a success.
            stubTokenSuccess("good", 3600);
            service.getAccessToken(7L, "rt", EMAIL);
            wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));

            // Then the server starts returning 400.
            stubBadRequest();
            wireMock.resetRequests();

            // Second call: cache hit, returns the cached "good" — no HTTP request, no
            // marking.
            // This is the correct behavior: while the cache is fresh, we do not hit Google.
            service.getAccessToken(7L, "rt", EMAIL);
            wireMock.verify(0, postRequestedFor(urlEqualTo(TOKEN_PATH)));

            // After a manual cache invalidate -> next call goes to HTTP -> 400 -> mark +
            // evict.
            service.invalidate(7L);
            assertThatThrownBy(() -> service.getAccessToken(7L, "rt", EMAIL))
                    .isInstanceOf(MailOperationException.class);

            verify(accountRepository).updateRequiresReauth(7L, true);
        }

        @Test
        void exchangeRefreshTokenWithNullAccountIdMustNotTouchRepository() {
            // Initial login flow: account does not exist yet, mark would fail with NPE/FK
            // violation.
            stubBadRequest();

            assertThatThrownBy(() -> service.exchangeRefreshToken("rt", EMAIL))
                    .isInstanceOf(MailOperationException.class).extracting("code")
                    .isEqualTo(ErrorCode.MAIL_AUTHENTICATION_FAILED);

            verify(accountRepository, never()).updateRequiresReauth(anyLong(), eq(true));
            verify(accountRepository, never()).updateLastError(anyLong(), any(AccountLastError.class), any());
        }

        @Test
        void dbFailureDuringMarkMustNotMaskOriginalAuthError() {
            // When the DB fails while marking requires_reauth, the original 401 must
            // still propagate.
            // Otherwise the user would see "DB error" instead of "you need to sign in
            // again".
            stubBadRequest();
            doThrow(new RuntimeException("DB connection lost")).when(accountRepository).updateRequiresReauth(anyLong(),
                    eq(true));

            assertThatThrownBy(() -> service.getAccessToken(99L, "rt", EMAIL))
                    .isInstanceOf(MailOperationException.class).extracting("code")
                    .isEqualTo(ErrorCode.MAIL_AUTHENTICATION_FAILED);
        }
    }

    @Nested
    @DisplayName("401 Unauthorized — OAuth client rejected")
    class UnauthorizedPath {

        private void stubUnauthorizedClient() {
            wireMock.stubFor(post(urlEqualTo(TOKEN_PATH))
                    .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"unauthorized_client\",\"error_description\":\"Unauthorized\"}")));
        }

        @Test
        void shouldMarkAccountAsRequiresReauth() {
            stubUnauthorizedClient();

            assertThatThrownBy(() -> service.getAccessToken(42L, "rt", EMAIL))
                    .isInstanceOf(MailOperationException.class).extracting("code")
                    .isEqualTo(ErrorCode.MAIL_AUTHENTICATION_FAILED);

            verify(accountRepository).updateRequiresReauth(42L, true);
            verify(accountRepository).updateLastError(eq(42L), any(AccountLastError.class), any(LocalDateTime.class));
        }

        @Test
        void exchangeRefreshTokenWithNullAccountIdMustNotTouchRepository() {
            stubUnauthorizedClient();

            assertThatThrownBy(() -> service.exchangeRefreshToken("rt", EMAIL))
                    .isInstanceOf(MailOperationException.class).extracting("code")
                    .isEqualTo(ErrorCode.MAIL_AUTHENTICATION_FAILED);

            verify(accountRepository, never()).updateRequiresReauth(anyLong(), eq(true));
            verify(accountRepository, never()).updateLastError(anyLong(), any(AccountLastError.class), any());
        }
    }

    @Nested
    @DisplayName("Transient errors — must NOT mark requires_reauth")
    class TransientErrors {

        @Test
        void serverError500ShouldThrowInternalErrorAndKeepAccountActive() {
            wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(500).withBody("oops")));

            assertThatThrownBy(() -> service.getAccessToken(1L, "rt", EMAIL)).isInstanceOf(MailOperationException.class)
                    .extracting("code").isEqualTo(ErrorCode.INTERNAL_ERROR);

            // CRITICAL: 5xx is transient, the account must NOT be marked as
            // requires_reauth.
            verify(accountRepository, never()).updateRequiresReauth(anyLong(), eq(true));
        }

        @Test
        void serviceUnavailable503ShouldNotMarkRequiresReauth() {
            wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(503)));

            assertThatThrownBy(() -> service.getAccessToken(1L, "rt", EMAIL))
                    .isInstanceOf(MailOperationException.class);

            verify(accountRepository, never()).updateRequiresReauth(anyLong(), eq(true));
        }

        @Test
        void connectionRefusedShouldNotMarkRequiresReauth() {
            // Stop WireMock → connect refused.
            wireMock.stop();

            assertThatThrownBy(() -> service.getAccessToken(1L, "rt", EMAIL))
                    .isInstanceOf(MailOperationException.class);

            verify(accountRepository, never()).updateRequiresReauth(anyLong(), eq(true));
        }
    }

    @Nested
    @DisplayName("parseExpiresIn — defensive parsing")
    class ParseExpiresIn {

        @Test
        void numberValue() {
            stubTokenSuccess("t", 1800);
            service.getAccessToken(1L, "rt", EMAIL);
            // The second call should be cached (1800s >> 60s skew).
            service.getAccessToken(1L, "rt", EMAIL);
            wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }

        @Test
        void missingExpiresInFallsBackTo300sDefault() {
            wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json").withBody("{\"access_token\":\"t\"}")));

            service.getAccessToken(1L, "rt", EMAIL);
            // 300s default > 60s skew -> the second call is cached.
            service.getAccessToken(1L, "rt", EMAIL);
            wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }

        @Test
        void stringExpiresInIsAccepted() {
            wireMock.stubFor(post(urlEqualTo(TOKEN_PATH))
                    .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                            .withBody("{\"access_token\":\"t\",\"expires_in\":\"3600\"}")));

            service.getAccessToken(1L, "rt", EMAIL);
            service.getAccessToken(1L, "rt", EMAIL);
            wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }
    }

    @Nested
    @DisplayName("revokeToken")
    class Revoke {

        private AccountEntity account(Long id, String email) {
            AccountEntity a = new AccountEntity();
            a.setId(id);
            a.setEmail(email);
            return a;
        }

        private AccountCredentialEntity credentials(String encrypted) {
            AccountCredentialEntity c = new AccountCredentialEntity();
            c.setEncryptedPassword(encrypted);
            return c;
        }

        @Test
        void shouldPostRefreshTokenToRevokeEndpointAndEvictCache() {
            wireMock.stubFor(post(urlEqualTo(REVOKE_PATH)).willReturn(aResponse().withStatus(200)));

            // Fill the cache so we can verify eviction.
            stubTokenSuccess("cached", 3600);
            service.getAccessToken(5L, "rt", EMAIL);

            when(credentialRepository.findById(5L)).thenReturn(Optional.of(credentials("enc-token")));
            when(cryptoService.decrypt("enc-token", 5L)).thenReturn("plaintext-rt");

            service.revokeToken(account(5L, EMAIL));

            wireMock.verify(postRequestedFor(urlEqualTo(REVOKE_PATH))
                    .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                    .withRequestBody(containing("token=plaintext-rt")));

            // Cache evicted — the next access-token call goes to HTTP again.
            wireMock.resetRequests();
            stubTokenSuccess("new", 3600);
            service.getAccessToken(5L, "rt", EMAIL);
            wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }

        @Test
        void revokeShouldSwallowHttpErrorsSoUserCanDeleteAccount() {
            // If Google returns 500, the user must still be able to delete the account —
            // revoke je best-effort cleanup, ne load-bearing krok.
            wireMock.stubFor(post(urlEqualTo(REVOKE_PATH)).willReturn(aResponse().withStatus(500)));
            when(credentialRepository.findById(5L)).thenReturn(Optional.of(credentials("enc")));
            when(cryptoService.decrypt(anyString(), eq(5L))).thenReturn("rt");

            // Must not throw.
            service.revokeToken(account(5L, EMAIL));
        }

        @Test
        void revokeWithMissingCredentialsIsNoOp() {
            when(credentialRepository.findById(5L)).thenReturn(Optional.empty());

            service.revokeToken(account(5L, EMAIL));

            wireMock.verify(0, postRequestedFor(urlEqualTo(REVOKE_PATH)));
        }

        @Test
        void revokeWithNullEncryptedPasswordIsNoOp() {
            when(credentialRepository.findById(5L)).thenReturn(Optional.of(credentials(null)));

            service.revokeToken(account(5L, EMAIL));

            wireMock.verify(0, postRequestedFor(urlEqualTo(REVOKE_PATH)));
            verify(cryptoService, never()).decrypt(anyString(), anyLong());
        }
    }

    @Nested
    @DisplayName("exchangeRefreshToken — initial login")
    class ExchangeRefreshToken {

        @Test
        void shouldReturnAccessTokenWithoutCaching() {
            stubTokenSuccess("initial", 3600);

            String token = service.exchangeRefreshToken("rt", EMAIL);

            assertThat(token).isEqualTo("initial");
            // Must not interact with the repository — initial flow.
            verify(accountRepository, never()).updateRequiresReauth(anyLong(), eq(true));
        }

        @Test
        void exchangeShouldNotPopulateCacheBecauseAccountIdIsNull() {
            // ConcurrentHashMap does not allow null keys -> if it were accidentally
            // cached, it would throw NPE. We verify that 2 calls = 2 HTTP requests.
            stubTokenSuccess("t", 3600);

            service.exchangeRefreshToken("rt", EMAIL);
            service.exchangeRefreshToken("rt", EMAIL);

            wireMock.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }
    }
}
