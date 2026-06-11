package org.voxrox.mailbackend.feature.auth.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;

/**
 * Registry of {@link OAuth2ClaimsExtractor} implementations keyed by
 * {@code providerName()}. Spring auto-injects every bean implementing the
 * interface — adding a new provider = a new {@code @Component} and the registry
 * picks it up seamlessly.
 */
@Component
public class OAuth2ClaimsExtractorRegistry {

    private final Map<String, OAuth2ClaimsExtractor> byProvider;

    public OAuth2ClaimsExtractorRegistry(List<OAuth2ClaimsExtractor> extractors) {
        this.byProvider = extractors.stream().collect(Collectors.collectingAndThen(
                Collectors.toMap(OAuth2ClaimsExtractor::providerName, Function.identity()), Map::copyOf));
    }

    public OAuth2ClaimsExtractor resolve(String providerName) {
        OAuth2ClaimsExtractor extractor = byProvider.get(providerName);
        if (extractor == null) {
            throw new MailOperationException(ErrorCode.INTERNAL_ERROR,
                    "No claims extractor is registered for OAuth2 provider '" + providerName + "'.");
        }
        return extractor;
    }
}
