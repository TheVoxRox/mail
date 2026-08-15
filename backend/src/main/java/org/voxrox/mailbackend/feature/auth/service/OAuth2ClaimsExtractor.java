package org.voxrox.mailbackend.feature.auth.service;

import java.util.Set;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Provider-specific mapping between the OAuth2 callback payload and the
 * internal {@link ExternalUserClaims} representation. Each OAuth2 provider has
 * its own claim names (Google {@code sub}, Microsoft {@code oid}, ...); this
 * layer unifies them for {@link OAuth2LoginService}.
 *
 * <p>
 * Implementations are registered as Spring {@code @Component} beans and
 * auto-injected into {@link OAuth2ClaimsExtractorRegistry} keyed by
 * {@link #providerName()}.
 */
public interface OAuth2ClaimsExtractor {

    /** RegistrationId Spring Security ClientRegistration. */
    String providerName();

    ExternalUserClaims extract(OAuth2User oauth2User, OAuth2AuthorizedClient authorizedClient);

    /**
     * Scopes the account cannot work without — the ones granting IMAP/SMTP access
     * to the mailbox. Requested in {@code application.properties}, but a provider
     * may hand back a token without them (see the scope guard in
     * {@link OAuth2LoginService#processLogin}), so the values are repeated here to
     * be checked against what was actually granted.
     *
     * <p>
     * Intentionally abstract rather than a defaulted empty set: a new provider must
     * state its mail scopes, otherwise it would silently opt out of the guard.
     */
    Set<String> requiredMailScopes();
}
