package org.voxrox.mailbackend.feature.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.voxrox.mailbackend.core.metrics.MailMetrics;
import org.voxrox.mailbackend.core.security.CryptoService;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountCredentialRepository;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

/**
 * Google-specific OAuth2 token service. Refresh / cache / error handling lives
 * in {@link OAuth2TokenService}; this class only provides Google identity
 * (provider name, endpoints, OAuth client) and the two places where Google
 * differs meaningfully from Microsoft: the RFC 7009 token revoke and the
 * installed-app client_secret in the refresh body.
 */
@Service
public final class GoogleTokenService extends OAuth2TokenService {

    public static final String PROVIDER_NAME = "google";

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenService.class);

    private final CryptoService cryptoService;
    private final AccountCredentialRepository credentialRepository;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    private String clientSecret;

    /**
     * Endpoint for exchanging a refresh token for an access token. A field (not a
     * constant) so it can be redirected to WireMock in tests via
     * ReflectionTestUtils. Never changes in production.
     */
    private String tokenEndpoint = "https://oauth2.googleapis.com/token";

    /**
     * Endpoint for refresh token revocation (RFC 7009). Same reason as above.
     */
    private String revokeEndpoint = "https://oauth2.googleapis.com/revoke";

    public GoogleTokenService(CryptoService cryptoService, AccountCredentialRepository credentialRepository,
            AccountRepository accountRepository, MailMetrics metrics, TokenCache tokenCache) {
        super(accountRepository, metrics, tokenCache);
        this.cryptoService = cryptoService;
        this.credentialRepository = credentialRepository;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    protected String providerDisplayName() {
        return "Google";
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
     * Google "Desktop app" clients are public clients protected by PKCE, yet
     * Google's token endpoint still requires the client_secret in the refresh
     * request (per Google's installed-app guidance the secret "is not treated as a
     * secret" — see application.properties). Provider-specific because Microsoft,
     * as a true public client, must not send one (AADSTS700025).
     */
    @Override
    protected void customizeRefreshBody(MultiValueMap<String, String> body) {
        body.add("client_secret", clientSecret);
    }

    @Override
    public void revokeToken(AccountEntity account) {
        credentialRepository.findById(account.getId()).ifPresent(credentials -> {
            String encryptedToken = credentials.getEncryptedPassword();
            if (encryptedToken == null)
                return;

            String maskedEmail = LogMasker.maskEmail(account.getEmail());
            try {
                String refreshToken = cryptoService.decrypt(encryptedToken, account.getId());

                /*
                 * The token must travel in the POST body (RFC 7009), not in the query string —
                 * otherwise it would end up in Google's access logs and any intervening
                 * proxies.
                 */
                MultiValueMap<String, String> revokeBody = new LinkedMultiValueMap<>();
                revokeBody.add("token", refreshToken);

                restClient.post().uri(revokeEndpoint).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(revokeBody).retrieve().toBodilessEntity();

                tokenCache.invalidate(account.getId());
                log.info("{} Google access successfully revoked for: {}", LogCategory.AUTH, maskedEmail);
                AuditLog.success("token_revoke", maskedEmail, "provider=google");
            } catch (Exception e) {
                log.warn("{} Failed to revoke Google token for {}", LogCategory.AUTH, maskedEmail, e);
                AuditLog.failure("token_revoke", maskedEmail, e.getClass().getSimpleName());
            }
        });
    }
}
