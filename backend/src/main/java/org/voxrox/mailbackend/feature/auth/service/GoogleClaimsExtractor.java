package org.voxrox.mailbackend.feature.auth.service;

import java.util.Set;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Maps Google OAuth2 claims onto {@link ExternalUserClaims}.
 * <ul>
 * <li>{@code sub} — stable user identifier (Google subject ID, unchanged even
 * when the primary e-mail changes).</li>
 * <li>{@code email}, {@code name} — from the {@code openid email profile}
 * scope.</li>
 * <li>refresh token — from {@link OAuth2AuthorizedClient}; the flow uses
 * {@code prompt=consent}, so Google should return one on every re-login.</li>
 * </ul>
 */
@Component
public class GoogleClaimsExtractor implements OAuth2ClaimsExtractor {

    /**
     * Full Gmail access (IMAP + SMTP). A restricted scope, which Google presents on
     * its own consent screen separate from the sign-in — declining it still yields
     * a usable OIDC token, so the grant has to be verified rather than assumed.
     */
    static final String GMAIL_SCOPE = "https://mail.google.com/";

    @Override
    public String providerName() {
        return GoogleTokenService.PROVIDER_NAME;
    }

    @Override
    public Set<String> requiredMailScopes() {
        return Set.of(GMAIL_SCOPE);
    }

    @Override
    public ExternalUserClaims extract(OAuth2User oauth2User, OAuth2AuthorizedClient authorizedClient) {
        String email = oauth2User.getAttribute("email");
        String name = oauth2User.getAttribute("name");
        String externalId = oauth2User.getAttribute("sub");

        var refreshTokenObj = authorizedClient.getRefreshToken();
        String refreshToken = (refreshTokenObj != null) ? refreshTokenObj.getTokenValue() : null;

        return new ExternalUserClaims(email, name, externalId, refreshToken);
    }
}
