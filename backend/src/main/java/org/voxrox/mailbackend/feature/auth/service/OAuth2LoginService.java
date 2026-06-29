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
        String email = claims.email();
        String externalId = claims.externalId();
        String refreshToken = claims.refreshToken();

        /*
         * Identity claims are mandatory. Without an e-mail we can neither match nor
         * create an account (the column is NOT NULL — the failure would otherwise
         * surface as an opaque 500 from the DB constraint). Without the provider's
         * stable id (Google sub / Microsoft oid) the (oauth2_provider, external_id)
         * identity degrades to e-mail matching and silently breaks the re-login
         * routing. Both are standard OIDC claims; their absence means a malformed
         * provider response — reject and let the user repeat the flow.
         */
        if (email == null || email.isBlank()) {
            log.warn("{} Provider {} did not return an e-mail claim — login rejected.", LogCategory.AUTH, providerName);
            AuditLog.failure("oauth2_login", "<missing-email>", "provider=" + providerName + " missing_email");
            throw new MailOperationException(ErrorCode.MAIL_AUTHENTICATION_FAILED,
                    "The provider did not return an e-mail address. Repeat the sign-in.", HttpStatus.UNAUTHORIZED);
        }

        String maskedEmail = LogMasker.maskEmail(email);

        if (externalId == null || externalId.isBlank()) {
            log.warn("{} Provider {} did not return a stable user id for {} — login rejected.", LogCategory.AUTH,
                    providerName, maskedEmail);
            AuditLog.failure("oauth2_login", maskedEmail, "provider=" + providerName + " missing_external_id");
            throw new MailOperationException(ErrorCode.MAIL_AUTHENTICATION_FAILED,
                    "The provider did not return a stable user identifier. Repeat the sign-in.",
                    HttpStatus.UNAUTHORIZED);
        }

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
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("{} Provider {} did not return a refresh token for {} — login rejected.", LogCategory.AUTH,
                    providerName, maskedEmail);
            AuditLog.failure("oauth2_login", maskedEmail, "provider=" + providerName + " missing_refresh_token");
            accountService.markRequiresReauthIfExists(email);
            throw new MailOperationException(ErrorCode.MAIL_AUTHENTICATION_FAILED,
                    "The provider did not return a refresh token. Repeat the sign-in and confirm access.",
                    HttpStatus.UNAUTHORIZED);
        }

        try {
            accountService.processExternalProviderLogin(providerName, email, claims.name(),
                    externalId, refreshToken);
            AuditLog.success("oauth2_login", maskedEmail, "provider=" + providerName);
        } catch (RuntimeException e) {
            AuditLog.failure("oauth2_login", maskedEmail,
                    "provider=" + providerName + " " + e.getClass().getSimpleName());
            throw e;
        }
    }
}
