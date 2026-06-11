package org.voxrox.mailbackend.feature.auth.service;

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

    @Override
    public String providerName() {
        return GoogleTokenService.PROVIDER_NAME;
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
