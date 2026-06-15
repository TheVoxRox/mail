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

class GoogleClaimsExtractorTest {

    private final GoogleClaimsExtractor extractor = new GoogleClaimsExtractor();

    @Test
    @DisplayName("providerName returns 'google' (key in OAuth2TokenServiceRegistry)")
    void providerName() {
        assertThat(extractor.providerName()).isEqualTo("google");
    }

    @Test
    @DisplayName("Maps email/name/sub + refresh token from the OAuth2 callback payload")
    void extractsPrimaryClaims() {
        OAuth2User user = mock(OAuth2User.class);
        when(user.getAttribute("email")).thenReturn("user@gmail.com");
        when(user.getAttribute("name")).thenReturn("Jan Novák");
        when(user.getAttribute("sub")).thenReturn("106839701234567890123");

        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        when(client.getRefreshToken()).thenReturn(new OAuth2RefreshToken("rt-google", null));

        ExternalUserClaims claims = extractor.extract(user, client);

        assertThat(claims.email()).isEqualTo("user@gmail.com");
        assertThat(claims.name()).isEqualTo("Jan Novák");
        assertThat(claims.externalId()).isEqualTo("106839701234567890123");
        assertThat(claims.refreshToken()).isEqualTo("rt-google");
    }

    @Test
    @DisplayName("Unlike Microsoft, Google does not fall back to preferred_username — the email scope always returns email")
    void doesNotFallBackToPreferredUsername() {
        OAuth2User user = mock(OAuth2User.class);
        when(user.getAttribute("email")).thenReturn(null);
        when(user.getAttribute("preferred_username")).thenReturn("ignored@example.com");
        when(user.getAttribute("name")).thenReturn("Jan");
        when(user.getAttribute("sub")).thenReturn("sub-123");

        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        when(client.getRefreshToken()).thenReturn(new OAuth2RefreshToken("rt", null));

        ExternalUserClaims claims = extractor.extract(user, client);

        assertThat(claims.email()).isNull();
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
