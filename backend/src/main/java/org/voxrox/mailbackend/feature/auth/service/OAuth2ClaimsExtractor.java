package org.voxrox.mailbackend.feature.auth.service;

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
}
