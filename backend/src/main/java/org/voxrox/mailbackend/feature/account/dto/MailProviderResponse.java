package org.voxrox.mailbackend.feature.account.dto;

/**
 * {@code supportsOauth2 = true} tells the frontend that the provider has a
 * backend OAuth2 implementation and that, instead of the password form, it
 * should show a "Sign in with ..." button. {@code oauth2RegistrationId} is then
 * the value sent back to the backend (e.g.
 * {@code GET /api/v1/auth/oauth2/start?provider=google}).
 */
public record MailProviderResponse(Long id, String name, String imapHost, Integer imapPort, boolean imapSsl,
        String smtpHost, Integer smtpPort, boolean smtpSsl, String domains, boolean supportsOauth2,
        String oauth2RegistrationId) {
}
