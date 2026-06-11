package org.voxrox.mailbackend.feature.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

class MicrosoftClaimsExtractorTest {

    private final MicrosoftClaimsExtractor extractor = new MicrosoftClaimsExtractor();

    @Test
    @DisplayName("providerName returns 'microsoft' (key in OAuth2TokenServiceRegistry)")
    void providerName() {
        assertThat(extractor.providerName()).isEqualTo("microsoft");
    }

    @Test
    @DisplayName("Maps email/name/oid + refresh token from the OAuth2 callback payload")
    void extractsPrimaryClaims() {
        OAuth2User user = mock(OAuth2User.class);
        when(user.getAttribute("email")).thenReturn("user@outlook.com");
        when(user.getAttribute("name")).thenReturn("Jan Novák");
        when(user.getAttribute("oid")).thenReturn("00000000-0000-0000-0000-000000000123");

        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        when(client.getRefreshToken()).thenReturn(new OAuth2RefreshToken("rt-microsoft", null));

        ExternalUserClaims claims = extractor.extract(user, client);

        assertThat(claims.email()).isEqualTo("user@outlook.com");
        assertThat(claims.name()).isEqualTo("Jan Novák");
        assertThat(claims.externalId()).isEqualTo("00000000-0000-0000-0000-000000000123");
        assertThat(claims.refreshToken()).isEqualTo("rt-microsoft");
    }

    @Test
    @DisplayName("When the email claim is missing, fall back to preferred_username (Microsoft B2B)")
    void fallsBackToPreferredUsernameWhenEmailMissing() {
        OAuth2User user = mock(OAuth2User.class);
        when(user.getAttribute("email")).thenReturn(null);
        when(user.getAttribute("preferred_username")).thenReturn("user@contoso.onmicrosoft.com");
        when(user.getAttribute("name")).thenReturn("Jan");
        when(user.getAttribute("oid")).thenReturn("oid-123");

        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        when(client.getRefreshToken()).thenReturn(new OAuth2RefreshToken("rt", null));

        ExternalUserClaims claims = extractor.extract(user, client);

        assertThat(claims.email()).isEqualTo("user@contoso.onmicrosoft.com");
    }

    @Test
    @DisplayName("Empty email claim also triggers fallback to preferred_username")
    void blankEmailFallsBackToPreferredUsername() {
        OAuth2User user = mock(OAuth2User.class);
        when(user.getAttribute("email")).thenReturn("");
        when(user.getAttribute("preferred_username")).thenReturn("user@contoso.onmicrosoft.com");
        when(user.getAttribute("name")).thenReturn("Jan");
        when(user.getAttribute("oid")).thenReturn("oid-123");

        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        when(client.getRefreshToken()).thenReturn(new OAuth2RefreshToken("rt", null));

        ExternalUserClaims claims = extractor.extract(user, client);

        assertThat(claims.email()).isEqualTo("user@contoso.onmicrosoft.com");
    }

    @Test
    @DisplayName("Missing refresh token returns null (fail-fast guard handled by OAuth2LoginService)")
    void missingRefreshTokenYieldsNull() {
        OAuth2User user = mock(OAuth2User.class);
        when(user.getAttribute(anyString())).thenReturn("dummy");

        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        when(client.getRefreshToken()).thenReturn(null);

        ExternalUserClaims claims = extractor.extract(user, client);

        assertThat(claims.refreshToken()).isNull();
    }
}
