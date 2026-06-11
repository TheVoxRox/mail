package org.voxrox.mailbackend.feature.account.dto;

import java.time.LocalDateTime;
import java.util.Map;

import org.voxrox.mailbackend.feature.auth.dto.AuthType;

/**
 * {@code oauth2Provider} is the registrationId of an OAuth2 provider
 * ({@code "google"}, {@code "microsoft"}, ...) or {@code null} for PASSWORD
 * accounts. The frontend uses it on {@code requiresReauth=true} to pick the
 * correct re-login flow ("Sign in again with Microsoft/Google").
 */
public record AccountResponse(Long id, String accountName, String email, String displayName,

        Long providerId, String providerName,

        String imapHost, Integer imapPort, boolean imapUseSsl, String smtpHost, Integer smtpPort, boolean smtpUseSsl,

        String username, AuthType authType, String oauth2Provider, boolean active, boolean requiresReauth,
        LocalDateTime lastSyncAt, String lastError, String lastErrorCode, Map<String, String> lastErrorArgs) {

    public AccountResponse(Long id, String accountName, String email, String displayName, Long providerId,
            String providerName, String imapHost, Integer imapPort, boolean imapUseSsl, String smtpHost,
            Integer smtpPort, boolean smtpUseSsl, String username, AuthType authType, String oauth2Provider,
            boolean active, boolean requiresReauth, LocalDateTime lastSyncAt, String lastError) {
        this(id, accountName, email, displayName, providerId, providerName, imapHost, imapPort, imapUseSsl, smtpHost,
                smtpPort, smtpUseSsl, username, authType, oauth2Provider, active, requiresReauth, lastSyncAt, lastError,
                null, Map.of());
    }

    public AccountResponse {
        lastErrorArgs = lastErrorArgs == null ? Map.of() : Map.copyOf(lastErrorArgs);
    }
}
