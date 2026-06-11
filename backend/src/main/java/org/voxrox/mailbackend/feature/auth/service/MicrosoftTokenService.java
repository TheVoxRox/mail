package org.voxrox.mailbackend.feature.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.voxrox.mailbackend.core.metrics.MailMetrics;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

/**
 * Microsoft Identity Platform OAuth2 token service for Outlook.com (MSA) and
 * Exchange Online (AAD) under the 'common' tenant. Refresh / cache / error
 * handling lives in {@link OAuth2TokenService}; this class only handles the two
 * places where Microsoft differs from Google:
 *
 * <ul>
 * <li>The refresh body must explicitly include {@code scope} — without it the
 * v2.0 endpoint downgrades to default scopes (openid/profile only) and the
 * resulting access token fails IMAP/SMTP XOAUTH2 auth.</li>
 * <li>Microsoft does not implement RFC 7009 token revoke. Refresh tokens can be
 * invalidated only via Graph API {@code POST /me/revokeSignInSessions}
 * (requires elevated scopes), or by the user via account.microsoft.com /
 * myapps.microsoft.com. The override below therefore does local cleanup
 * only.</li>
 * </ul>
 */
@Service
public final class MicrosoftTokenService extends OAuth2TokenService {

    public static final String PROVIDER_NAME = "microsoft";

    private static final Logger log = LoggerFactory.getLogger(MicrosoftTokenService.class);

    /**
     * Same scopes as at authorize time. Microsoft's refresh token grant honors
     * scope downgrades — if we send nothing, it returns a token with default scopes
     * (typically just openid/profile) that IMAP/SMTP reject. Always specify the
     * full required set.
     */
    private static final String REFRESH_SCOPE = "openid email profile offline_access "
            + "https://outlook.office.com/IMAP.AccessAsUser.All " + "https://outlook.office.com/SMTP.Send";

    @Value("${spring.security.oauth2.client.registration.microsoft.client-id}")
    private String clientId;

    /**
     * Endpoint for exchanging a refresh token for an access token. A field (not a
     * constant) so it can be redirected to WireMock in tests via
     * ReflectionTestUtils.
     */
    private String tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token";

    public MicrosoftTokenService(AccountRepository accountRepository, MailMetrics metrics, TokenCache tokenCache) {
        super(accountRepository, metrics, tokenCache);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    protected String providerDisplayName() {
        return "Microsoft";
    }

    @Override
    protected String tokenEndpoint() {
        return tokenEndpoint;
    }

    @Override
    protected String clientId() {
        return clientId;
    }

    /**
     * Microsoft is a public client (RFC 8252) — only {@code scope} is added; a
     * {@code client_secret} must never appear in the body (AADSTS700025).
     */
    @Override
    protected void customizeRefreshBody(MultiValueMap<String, String> body) {
        body.add("scope", REFRESH_SCOPE);
    }

    /**
     * Microsoft Identity Platform does not implement RFC 7009 token revoke (see
     * class Javadoc). Local cleanup only — cache eviction + audit log with reason;
     * the operator finds instructions in {@code OPERATIONS.md} for how to revoke
     * access manually on the user's behalf. Best-effort, no throwing — so that
     * {@code deleteAccount} completes for Microsoft accounts as well.
     */
    @Override
    public void revokeToken(AccountEntity account) {
        String maskedEmail = LogMasker.maskEmail(account.getEmail());
        tokenCache.invalidate(account.getId());
        log.info("{} Microsoft refresh token cannot be revoked programmatically (provider does not support RFC 7009). "
                + "The user must revoke access manually via account.microsoft.com / myapps.microsoft.com. "
                + "Cache for account {} has been cleared locally.", LogCategory.AUTH, maskedEmail);
        AuditLog.success("token_revoke", maskedEmail, "provider=microsoft revoke=local_cache_only");
    }
}
