package org.voxrox.mailbackend.feature.auth.service;

/**
 * Provider-agnostic claims obtained from the OAuth2 callback flow. The mapping
 * to provider-specific attributes (Google {@code sub} vs. Microsoft {@code oid}
 * etc.) is handled by {@link OAuth2ClaimsExtractor}.
 *
 * @param email
 *            user's primary e-mail address
 * @param name
 *            display name (may be {@code null})
 * @param externalId
 *            stable user identifier with the provider — unique per provider but
 *            not globally (hence the composite index in the DB)
 * @param refreshToken
 *            refresh token returned by the provider (must be non-null; without
 *            it we cannot continue — see fail-fast in the login flow)
 */
public record ExternalUserClaims(String email, String name, String externalId, String refreshToken) {
}
