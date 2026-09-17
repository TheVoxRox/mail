package org.voxrox.mailbackend.feature.auth.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;

/**
 * Registry of {@link OAuth2TokenService} implementations keyed by
 * {@code providerName()}. Spring auto-injects every bean implementing the
 * interface — adding a new {@code @Service} extends the registry automatically;
 * no changes required in consumers.
 */
@Component
public class OAuth2TokenServiceRegistry {

    private final Map<String, OAuth2TokenService> byProvider;
    private final TokenCache tokenCache;

    public OAuth2TokenServiceRegistry(List<OAuth2TokenService> services, TokenCache tokenCache) {
        this.byProvider = services.stream().collect(Collectors.collectingAndThen(
                Collectors.toMap(OAuth2TokenService::providerName, Function.identity()), Map::copyOf));
        this.tokenCache = tokenCache;
    }

    /**
     * Returns the token service registered for the given provider name.
     *
     * @throws MailOperationException
     *             when no implementation exists for the given provider — typically
     *             a mismatch between {@code accounts.oauth2_provider} and the
     *             registered {@code @Service} beans. A {@code null} provider (an
     *             OAUTH2 account without {@code oauth2_provider} set) is the same
     *             data mismatch, just reported here instead of as an NPE — the
     *             backing immutable map rejects null keys.
     */
    public OAuth2TokenService resolve(@Nullable String providerName) {
        OAuth2TokenService svc = providerName != null ? byProvider.get(providerName) : null;
        if (svc == null) {
            throw new MailOperationException(ErrorCode.INTERNAL_ERROR,
                    "No implementation is registered for OAuth2 provider '" + providerName + "'.");
        }
        return svc;
    }

    /**
     * Access tokens cached across all providers. Every service holds the same
     * {@link TokenCache} bean, keyed by account id, so the count is that one cache
     * read once; summing it per service counted each token once per provider.
     */
    public int totalCachedTokens() {
        return tokenCache.size();
    }
}
