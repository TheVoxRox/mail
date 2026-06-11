package org.voxrox.mailbackend.feature.auth.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Unit tests for {@link MicrosoftTokenService}.
 *
 * Covers the critical paths: cache, refresh happy path, scope payload
 * (Microsoft requires scopes to be sent on refresh, otherwise it returns a
 * token without IMAP/SMTP), 400 -> requires_reauth (AADSTS), 5xx -> transient,
 * network error, exchangeRefreshToken without accountId, DB failure during
 * mark, EXPIRY_SKEW, parseExpiresIn variants, revoke (no-op for Microsoft, only
 * local cache eviction).
 */
@ExtendWith(MockitoExtension.class)
class MicrosoftTokenServiceTest {

    private static final String TOKEN_PATH = "/token";
    private static final String CLIENT_ID = "test-client-id";
    private static final String EMAIL = "user@outlook.com";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private org.voxrox.mailbackend.core.metrics.MailMetrics mailMetrics;

    private MicrosoftTokenService service;

    private WireMockServer wireMock;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        service = new MicrosoftTokenService(accountRepository, mailMetrics, new TokenCache());

        ReflectionTestUtils.setField(service, "clientId", CLIENT_ID);
        ReflectionTestUtils.setField(service, "tokenEndpoint", wireMock.baseUrl() + TOKEN_PATH);
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
        void shouldReturnAccessTokenFromMicrosoftResponse() {
            stubTokenSuccess("ms.fresh-token", 3600);

            String token = service.getAccessToken(1L, "refresh-xyz", EMAIL);

            assertThat(token).isEqualTo("ms.fresh-token");
        }

        @Test
        void shouldSendCorrectFormBodyToMicrosoft() {
            stubTokenSuccess("any", 3600);

            service.getAccessToken(1L, "rt-abc", EMAIL);

            // Microsoft requires a scope parameter on token refresh — without it
            // it would return a token with default scopes only (no IMAP/SMTP).
            // The test freezes this dependency on the Outlook IMAP/SMTP scope string.
            // Microsoft is a public client: client_secret must NOT appear in the
            // body, otherwise the endpoint rejects the request with AADSTS700025.
            wireMock.verify(postRequestedFor(urlEqualTo(TOKEN_PATH))
                    .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                    .withRequestBody(containing("client_id=" + CLIENT_ID))
                    .withRequestBody(notContaining("client_secret")).withRequestBody(containing("refresh_token=rt-abc"))
                    .withRequestBody(containing("grant_type=refresh_token"))
                    .withRequestBody(containing("IMAP.AccessAsUser.All")).withRequestBody(containing("SMTP.Send"))
                    .withRequestBody(containing("offline_access")));
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

            wireMock.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }

        @Test
        void tokenExpiringWithinSkewShouldNotBeCached() {
            // EXPIRY_SKEW = 60s; expires_in=30 -> cached token must be stale immediately.
            stubTokenSuccess("token", 30);

            service.getAccessToken(1L, "rt", EMAIL);
            service.getAccessToken(1L, "rt", EMAIL);

            wireMock.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }
    }

    @Nested
    @DisplayName("400 BadRequest — refresh token revoked/expired (AADSTS)")
    class BadRequestPath {

        private void stubBadRequest() {
            // Real Microsoft response: AADSTS50173 (token expired), AADSTS65001
            // (consent revoked), AADSTS70008 (refresh token expired). From the
            // client's perspective all are equivalent -> requires_reauth.
            wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(400)
                    .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_grant\","
                            + "\"error_description\":\"AADSTS70008: The refresh token has expired.\"}")));
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
            stubTokenSuccess("good", 3600);
            service.getAccessToken(7L, "rt", EMAIL);
            wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));

            stubBadRequest();
            wireMock.resetRequests();

            // Cache is fresh, the second call returns from it without HTTP (no marking).
            service.getAccessToken(7L, "rt", EMAIL);
            wireMock.verify(0, postRequestedFor(urlEqualTo(TOKEN_PATH)));

            // Manual invalidate -> the next call goes to HTTP -> 400 -> mark + evict.
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
                    .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json").withBody(
                            "{\"error\":\"invalid_client\",\"error_description\":\"Client authentication failed\"}")));
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
    @DisplayName("revokeToken — no-op for Microsoft, only local cache eviction")
    class Revoke {

        private AccountEntity account(Long id, String email) {
            AccountEntity a = new AccountEntity();
            a.setId(id);
            a.setEmail(email);
            return a;
        }

        @Test
        void revokeShouldEvictCacheButNotCallAnyHttpEndpoint() {
            // Fill the cache so we can verify revoke clears it.
            stubTokenSuccess("cached", 3600);
            service.getAccessToken(5L, "rt", EMAIL);
            wireMock.resetRequests();

            service.revokeToken(account(5L, EMAIL));

            // Microsoft Identity Platform does not implement RFC 7009 — no HTTP
            // call may happen. The account repository is also not touched.
            wireMock.verify(0, postRequestedFor(urlEqualTo(TOKEN_PATH)));

            // Cache evicted — the next access-token call goes to HTTP again.
            stubTokenSuccess("new", 3600);
            service.getAccessToken(5L, "rt", EMAIL);
            wireMock.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }

        @Test
        void revokeShouldNeverThrow() {
            // Best-effort cleanup — even if cache eviction failed, deleteAccount
            // must proceed. The test only verifies that calling against an empty cache does
            // not throw.
            service.revokeToken(account(99L, EMAIL));
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
            verify(accountRepository, never()).updateRequiresReauth(anyLong(), eq(true));
        }

        @Test
        void exchangeShouldNotPopulateCacheBecauseAccountIdIsNull() {
            stubTokenSuccess("t", 3600);

            service.exchangeRefreshToken("rt", EMAIL);
            service.exchangeRefreshToken("rt", EMAIL);

            wireMock.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        }
    }
}
