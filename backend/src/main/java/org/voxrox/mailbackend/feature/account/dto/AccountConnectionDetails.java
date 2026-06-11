package org.voxrox.mailbackend.feature.account.dto;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;

/**
 * {@code oauth2Provider} is the registrationId of an OAuth2 provider
 * ({@code "google"}, {@code "microsoft"}, ...) or {@code null} for PASSWORD
 * accounts. It is used by consumers (IMAP/SMTP transport) to resolve the
 * correct implementation via
 * {@link org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry}.
 * {@code passwordOrSecret} is {@code null} when the stored secret is missing —
 * the OAuth2 token layer translates that into a re-login error.
 */
public record AccountConnectionDetails(String email, String host, int port, boolean useSsl, String username,
        @Nullable String passwordOrSecret, AuthType authType, @Nullable String oauth2Provider) {
}
