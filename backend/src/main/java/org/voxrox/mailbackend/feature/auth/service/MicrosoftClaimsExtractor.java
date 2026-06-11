package org.voxrox.mailbackend.feature.auth.service;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Maps Microsoft Identity Platform OAuth2 claims onto
 * {@link ExternalUserClaims}.
 * <ul>
 * <li>{@code oid} (object ID) — stable user identifier within a tenant. For the
 * {@code common} tenant {@code oid} is preserved per user/application across
 * all sign-in events. Note: {@code oid} changes between tenants (personal MSA
 * tenant {@code 9188040d-...} vs. an AAD tenant), but the composite index
 * {@code (oauth2_provider, external_id)} in the DB shields us from collisions
 * between Google {@code sub} and Microsoft {@code oid} in different
 * namespaces.</li>
 * <li>{@code email}, {@code name} — from the OIDC {@code email}/{@code profile}
 * scope. If Microsoft does not return {@code email} (typical for some B2B
 * configurations), fall back to {@code preferred_username} (UPN, in most cases
 * an e-mail).</li>
 * <li>refresh token — from {@link OAuth2AuthorizedClient}; the flow uses the
 * {@code offline_access} scope, so Microsoft returns one on every re-login.
 * {@code prompt=select_account} in the authorization URL forces the account
 * picker for multi-account browser sessions (independent of the refresh
 * token).</li>
 * </ul>
 */
@Component
public class MicrosoftClaimsExtractor implements OAuth2ClaimsExtractor {

    @Override
    public String providerName() {
        return MicrosoftTokenService.PROVIDER_NAME;
    }

    @Override
    public ExternalUserClaims extract(OAuth2User oauth2User, OAuth2AuthorizedClient authorizedClient) {
        String email = oauth2User.getAttribute("email");
        if (email == null || email.isBlank()) {
            email = oauth2User.getAttribute("preferred_username");
        }
        String name = oauth2User.getAttribute("name");
        String externalId = oauth2User.getAttribute("oid");

        var refreshTokenObj = authorizedClient.getRefreshToken();
        String refreshToken = (refreshTokenObj != null) ? refreshTokenObj.getTokenValue() : null;

        return new ExternalUserClaims(email, name, externalId, refreshToken);
    }
}
