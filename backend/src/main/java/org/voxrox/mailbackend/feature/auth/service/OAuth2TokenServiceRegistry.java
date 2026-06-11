package org.voxrox.mailbackend.feature.auth.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    public OAuth2TokenServiceRegistry(List<OAuth2TokenService> services) {
        this.byProvider = services.stream().collect(Collectors.collectingAndThen(
                Collectors.toMap(OAuth2TokenService::providerName, Function.identity()), Map::copyOf));
    }

    /**
     * Returns the token service registered for the given provider name.
     *
     * @throws MailOperationException
     *             when no implementation exists for the given provider — typically
     *             a mismatch between {@code accounts.oauth2_provider} and the
     *             registered {@code @Service} beans.
     */
    public OAuth2TokenService resolve(String providerName) {
        OAuth2TokenService svc = byProvider.get(providerName);
        if (svc == null) {
            throw new MailOperationException(ErrorCode.INTERNAL_ERROR,
                    "No implementation is registered for OAuth2 provider '" + providerName + "'.");
        }
        return svc;
    }

    public int totalCachedTokens() {
        return byProvider.values().stream().mapToInt(svc -> svc.getCacheStats().cachedTokens()).sum();
    }
}
