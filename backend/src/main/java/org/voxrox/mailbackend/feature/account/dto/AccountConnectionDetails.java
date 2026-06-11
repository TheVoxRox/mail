package org.voxrox.mailbackend.feature.account.dto;

import org.voxrox.mailbackend.feature.auth.dto.AuthType;

/**
 * {@code oauth2Provider} is the registrationId of an OAuth2 provider
 * ({@code "google"}, {@code "microsoft"}, ...) or {@code null} for PASSWORD
 * accounts. It is used by consumers (IMAP/SMTP transport) to resolve the
 * correct implementation via
 * {@link org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry}.
 */
public record AccountConnectionDetails(String email, String host, int port, boolean useSsl, String username,
        String passwordOrSecret, AuthType authType, String oauth2Provider) {
}
