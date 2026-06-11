package org.voxrox.mailbackend.feature.auth.dto;

/**
 * Generic authentication type used by an account against the mail server.
 *
 * <p>
 * The concrete OAuth2 provider (Google / Microsoft / ...) is read from
 * {@code accounts.oauth2_provider}. This enum deliberately does not contain
 * per-provider values (no {@code GOOGLE_OAUTH2}, {@code MICROSOFT_OAUTH2}), so
 * that adding a new provider does not require an enum-grow + per-provider
 * switch scattered across the codebase. Dispatch is instead handled by
 * {@link org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry}.
 */
public enum AuthType {
    PASSWORD, OAUTH2
}
