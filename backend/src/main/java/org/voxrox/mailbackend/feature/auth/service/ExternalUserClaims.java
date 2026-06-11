package org.voxrox.mailbackend.feature.auth.service;

import org.jspecify.annotations.Nullable;

/**
 * Provider-agnostic claims obtained from the OAuth2 callback flow. The mapping
 * to provider-specific attributes (Google {@code sub} vs. Microsoft {@code oid}
 * etc.) is handled by {@link OAuth2ClaimsExtractor}.
 *
 * <p>
 * All components are nullable on purpose: the record mirrors whatever the
 * provider returned, and {@code OAuth2LoginService.processLogin} is the single
 * place that fail-fasts on the mandatory ones (email, externalId,
 * refreshToken).
 *
 * @param email
 *            user's primary e-mail address
 * @param name
 *            display name (may be {@code null})
 * @param externalId
 *            stable user identifier with the provider — unique per provider but
 *            not globally (hence the composite index in the DB)
 * @param refreshToken
 *            refresh token returned by the provider
 */
public record ExternalUserClaims(@Nullable String email, @Nullable String name, @Nullable String externalId,
        @Nullable String refreshToken) {
}
