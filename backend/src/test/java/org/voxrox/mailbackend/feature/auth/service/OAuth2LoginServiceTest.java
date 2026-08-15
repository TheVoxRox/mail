package org.voxrox.mailbackend.feature.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.account.service.ExternalProviderLoginService;

/**
 * Guard tests for {@link OAuth2LoginService}. The identity claims (e-mail,
 * external id) and the refresh token are mandatory — a malformed provider
 * response must be rejected up front, never passed into account persistence
 * (where a null e-mail would die on the NOT NULL constraint as an opaque 500
 * and a null external id would silently break the (provider, external_id)
 * account identity).
 *
 * <p>
 * The granted mail scopes are checked for the same reason: a token that is
 * valid but carries the OIDC scopes alone (Google asks for the Gmail scope on a
 * separate consent screen) would otherwise create a working-looking account
 * whose every IMAP connection is refused.
 */
@ExtendWith(MockitoExtension.class)
class OAuth2LoginServiceTest {

    private static final String PROVIDER = "google";
    private static final String EMAIL = "user@example.com";
    private static final String EXTERNAL_ID = "sub-123";
    private static final String REFRESH_TOKEN = "rt-abc";
    private static final String GMAIL_SCOPE = "https://mail.google.com/";
    private static final String SMTP_SCOPE = "https://outlook.office.com/SMTP.Send";
    private static final Set<String> OIDC_SCOPES = Set.of("openid", "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile");

    @Mock
    private ExternalProviderLoginService externalProviderLoginService;
    @Mock
    private OAuth2ClaimsExtractorRegistry registry;
    @Mock
    private OAuth2ClaimsExtractor extractor;

    private final OAuth2User oauth2User = mock(OAuth2User.class);
    private final OAuth2AuthorizedClient authorizedClient = mock(OAuth2AuthorizedClient.class);

    private OAuth2LoginService service;

    @BeforeEach
    void setUp() {
        service = new OAuth2LoginService(externalProviderLoginService, registry);
        when(registry.resolve(PROVIDER)).thenReturn(extractor);
    }

    private void stubClaims(String email, String externalId, String refreshToken) {
        when(extractor.extract(oauth2User, authorizedClient))
                .thenReturn(new ExternalUserClaims(email, "User Name", externalId, refreshToken));
    }

    /** Access token as returned by the provider, carrying exactly these scopes. */
    private void stubGrantedScopes(Set<String> granted) {
        when(authorizedClient.getAccessToken()).thenReturn(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "at-token", Instant.now(), Instant.now().plusSeconds(3600), granted));
    }

    private static Set<String> plus(Set<String> scopes, String extra) {
        return Stream.concat(scopes.stream(), Stream.of(extra)).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Happy path -> claims are passed to account persistence")
    void happyPathPersistsAccount() {
        stubClaims(EMAIL, EXTERNAL_ID, REFRESH_TOKEN);
        stubGrantedScopes(plus(OIDC_SCOPES, GMAIL_SCOPE));
        when(extractor.requiredMailScopes()).thenReturn(Set.of(GMAIL_SCOPE));

        service.processLogin(PROVIDER, oauth2User, authorizedClient);

        verify(externalProviderLoginService).processExternalProviderLogin(PROVIDER, EMAIL, "User Name", EXTERNAL_ID,
                REFRESH_TOKEN);
    }

    @Test
    @DisplayName("Missing e-mail claim -> rejected before any persistence")
    void missingEmailIsRejected() {
        stubClaims(null, EXTERNAL_ID, REFRESH_TOKEN);

        assertThatThrownBy(() -> service.processLogin(PROVIDER, oauth2User, authorizedClient))
                .isInstanceOf(MailOperationException.class).hasMessageContaining("e-mail");

        verify(externalProviderLoginService, never()).processExternalProviderLogin(anyString(), any(), any(), any(),
                any());
        verify(externalProviderLoginService, never()).markRequiresReauthIfExists(any());
    }

    @Test
    @DisplayName("Missing external id (sub/oid) -> rejected before any persistence")
    void missingExternalIdIsRejected() {
        stubClaims(EMAIL, "  ", REFRESH_TOKEN);

        assertThatThrownBy(() -> service.processLogin(PROVIDER, oauth2User, authorizedClient))
                .isInstanceOf(MailOperationException.class).hasMessageContaining("identifier");

        verify(externalProviderLoginService, never()).processExternalProviderLogin(anyString(), any(), any(), any(),
                any());
    }

    @Test
    @DisplayName("Missing refresh token -> rejected + existing account flagged requires_reauth")
    void missingRefreshTokenIsRejectedAndAccountFlagged() {
        stubClaims(EMAIL, EXTERNAL_ID, null);

        assertThatThrownBy(() -> service.processLogin(PROVIDER, oauth2User, authorizedClient))
                .isInstanceOf(MailOperationException.class).hasMessageContaining("refresh token");

        verify(externalProviderLoginService).markRequiresReauthIfExists(EMAIL);
        verify(externalProviderLoginService, never()).processExternalProviderLogin(anyString(), any(), any(), any(),
                any());
    }

    /**
     * The real Google failure: sign-in and token exchange both succeed, the token
     * is valid, only the mail scope was never granted. No account may be created
     * from it — otherwise the user gets one that looks fine in the list and fails
     * on every sync.
     */
    @Test
    @DisplayName("Mail scope not granted -> rejected + existing account flagged requires_reauth")
    void missingMailScopeIsRejectedAndAccountFlagged() {
        stubClaims(EMAIL, EXTERNAL_ID, REFRESH_TOKEN);
        stubGrantedScopes(OIDC_SCOPES);
        when(extractor.requiredMailScopes()).thenReturn(Set.of(GMAIL_SCOPE));

        assertThatThrownBy(() -> service.processLogin(PROVIDER, oauth2User, authorizedClient))
                .isInstanceOf(MailOperationException.class).hasMessageContaining("grant access to the mailbox")
                .satisfies(ex -> assertThat(((MailOperationException) ex).getCode())
                        .isEqualTo(ErrorCode.MAIL_OAUTH2_SCOPE_NOT_GRANTED));

        verify(externalProviderLoginService).markRequiresReauthIfExists(EMAIL);
        verify(externalProviderLoginService, never()).processExternalProviderLogin(anyString(), any(), any(), any(),
                any());
    }

    @Test
    @DisplayName("Partial grant (one of two required scopes) -> rejected")
    void partiallyGrantedMailScopesAreRejected() {
        stubClaims(EMAIL, EXTERNAL_ID, REFRESH_TOKEN);
        stubGrantedScopes(plus(OIDC_SCOPES, GMAIL_SCOPE));
        when(extractor.requiredMailScopes()).thenReturn(Set.of(GMAIL_SCOPE, SMTP_SCOPE));

        assertThatThrownBy(() -> service.processLogin(PROVIDER, oauth2User, authorizedClient))
                .isInstanceOf(MailOperationException.class);

        verify(externalProviderLoginService, never()).processExternalProviderLogin(anyString(), any(), any(), any(),
                any());
    }

    /**
     * A token response without scope information says nothing about the grant, so
     * the guard stands down and lets the IMAP connection be the judge — rejecting
     * the login on no evidence would lock out a provider that simply omits the
     * field.
     */
    @Test
    @DisplayName("No scope information in the token -> guard stands down, login proceeds")
    void unknownScopesDoNotBlockLogin() {
        stubClaims(EMAIL, EXTERNAL_ID, REFRESH_TOKEN);
        stubGrantedScopes(Set.of());

        service.processLogin(PROVIDER, oauth2User, authorizedClient);

        verify(externalProviderLoginService).processExternalProviderLogin(PROVIDER, EMAIL, "User Name", EXTERNAL_ID,
                REFRESH_TOKEN);
    }
}
