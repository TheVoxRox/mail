package org.voxrox.mailbackend.feature.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

/**
 * Provider-agnostic handler for the OAuth2 login flow. Selection of the
 * concrete provider (Google, Microsoft, ...) is based on the
 * {@code providerName} parameter, which the controller reads from
 * {@code OAuth2AuthenticationToken.getAuthorizedClientRegistrationId()}.
 *
 * <p>
 * Per-provider claim mapping is handled by {@link OAuth2ClaimsExtractor};
 * account and credential persistence is handled by {@link AccountService}. This
 * class orchestrates the fail-fast guard on a missing refresh token and the
 * audit trail.
 */
@Service
public class OAuth2LoginService {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginService.class);

    private final AccountService accountService;
    private final OAuth2ClaimsExtractorRegistry claimsExtractorRegistry;

    public OAuth2LoginService(AccountService accountService, OAuth2ClaimsExtractorRegistry claimsExtractorRegistry) {
        this.accountService = accountService;
        this.claimsExtractorRegistry = claimsExtractorRegistry;
    }

    public void processLogin(String providerName, OAuth2User oauth2User, OAuth2AuthorizedClient authorizedClient) {
        OAuth2ClaimsExtractor extractor = claimsExtractorRegistry.resolve(providerName);
        ExternalUserClaims claims = extractor.extract(oauth2User, authorizedClient);

        String maskedEmail = LogMasker.maskEmail(claims.email());
        log.info("{} Processing OAuth2 login (provider={}) for: {}", LogCategory.AUTH, providerName, maskedEmail);

        /*
         * Without a refresh token we cannot continue: downstream saveCredentials would
         * overwrite any existing password with null and accountService would
         * incorrectly clear the requires_reauth flag — the result would be an account
         * in a "seemingly OK" state but with broken credentials. The OAuth flow should
         * use prompt=consent (Google) / offline_access scope (Microsoft), so the
         * provider should always return one; if it did not, it is an anomaly and the
         * user must repeat the flow.
         */
        if (claims.refreshToken() == null || claims.refreshToken().isBlank()) {
            log.warn("{} Provider {} did not return a refresh token for {} — login rejected.", LogCategory.AUTH,
                    providerName, maskedEmail);
            AuditLog.failure("oauth2_login", maskedEmail, "provider=" + providerName + " missing_refresh_token");
            accountService.markRequiresReauthIfExists(claims.email());
            throw new MailOperationException(ErrorCode.MAIL_AUTHENTICATION_FAILED,
                    "The provider did not return a refresh token. Repeat the sign-in and confirm access.",
                    HttpStatus.UNAUTHORIZED);
        }

        try {
            accountService.processExternalProviderLogin(providerName, claims.email(), claims.name(),
                    claims.externalId(), claims.refreshToken());
            AuditLog.success("oauth2_login", maskedEmail, "provider=" + providerName);
        } catch (RuntimeException e) {
            AuditLog.failure("oauth2_login", maskedEmail,
                    "provider=" + providerName + " " + e.getClass().getSimpleName());
            throw e;
        }
    }
}
