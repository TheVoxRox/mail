package org.voxrox.mailbackend.feature.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.account.service.ExternalProviderLoginService;

/**
 * Guard tests for {@link OAuth2LoginService}. The identity claims (e-mail,
 * external id) and the refresh token are mandatory — a malformed provider
 * response must be rejected up front, never passed into account persistence
 * (where a null e-mail would die on the NOT NULL constraint as an opaque 500
 * and a null external id would silently break the (provider, external_id)
 * account identity).
 */
@ExtendWith(MockitoExtension.class)
class OAuth2LoginServiceTest {

    private static final String PROVIDER = "google";
    private static final String EMAIL = "user@example.com";
    private static final String EXTERNAL_ID = "sub-123";
    private static final String REFRESH_TOKEN = "rt-abc";

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

    @Test
    @DisplayName("Happy path -> claims are passed to account persistence")
    void happyPathPersistsAccount() {
        stubClaims(EMAIL, EXTERNAL_ID, REFRESH_TOKEN);

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
}
