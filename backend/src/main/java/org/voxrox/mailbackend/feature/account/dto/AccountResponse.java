package org.voxrox.mailbackend.feature.account.dto;

import java.time.LocalDateTime;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;

/**
 * {@code oauth2Provider} is the registrationId of an OAuth2 provider
 * ({@code "google"}, {@code "microsoft"}, ...) or {@code null} for PASSWORD
 * accounts. The frontend uses it on {@code requiresReauth=true} to pick the
 * correct re-login flow ("Sign in again with Microsoft/Google").
 */
public record AccountResponse(Long id, String accountName, String email, @Nullable String displayName,

        @Nullable Long providerId, @Nullable String providerName,

        String imapHost, Integer imapPort, boolean imapUseSsl, String smtpHost, Integer smtpPort, boolean smtpUseSsl,

        @Nullable String username, @Nullable AuthType authType, @Nullable String oauth2Provider, boolean active,
        boolean requiresReauth, @Nullable LocalDateTime lastSyncAt, @Nullable String lastError,
        @Nullable String lastErrorCode, @Nullable Map<String, String> lastErrorArgs, @Nullable String signature) {

    public AccountResponse(Long id, String accountName, String email, @Nullable String displayName,
            @Nullable Long providerId, @Nullable String providerName, String imapHost, Integer imapPort,
            boolean imapUseSsl, String smtpHost, Integer smtpPort, boolean smtpUseSsl, @Nullable String username,
            @Nullable AuthType authType, @Nullable String oauth2Provider, boolean active, boolean requiresReauth,
            @Nullable LocalDateTime lastSyncAt, @Nullable String lastError) {
        this(id, accountName, email, displayName, providerId, providerName, imapHost, imapPort, imapUseSsl, smtpHost,
                smtpPort, smtpUseSsl, username, authType, oauth2Provider, active, requiresReauth, lastSyncAt, lastError,
                null, Map.of(), null);
    }

    public AccountResponse {
        lastErrorArgs = lastErrorArgs == null ? Map.of() : Map.copyOf(lastErrorArgs);
    }
}
