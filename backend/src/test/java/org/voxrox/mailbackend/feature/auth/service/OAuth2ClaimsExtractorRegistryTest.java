package org.voxrox.mailbackend.feature.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;

/**
 * Unit tests for {@link OAuth2ClaimsExtractorRegistry} — the provider-keyed
 * lookup over the auto-injected {@link OAuth2ClaimsExtractor} beans. Covers
 * successful resolution, the fail-fast on an unknown provider, and the
 * construction-time guard against two extractors claiming the same provider.
 */
class OAuth2ClaimsExtractorRegistryTest {

    private static OAuth2ClaimsExtractor extractorFor(String providerName) {
        OAuth2ClaimsExtractor extractor = mock(OAuth2ClaimsExtractor.class);
        when(extractor.providerName()).thenReturn(providerName);
        return extractor;
    }

    @Test
    @DisplayName("resolve returns the extractor registered under the matching provider name")
    void resolveReturnsMatchingExtractor() {
        OAuth2ClaimsExtractor google = extractorFor("google");
        OAuth2ClaimsExtractor microsoft = extractorFor("microsoft");
        OAuth2ClaimsExtractorRegistry registry = new OAuth2ClaimsExtractorRegistry(List.of(google, microsoft));

        assertThat(registry.resolve("google")).isSameAs(google);
        assertThat(registry.resolve("microsoft")).isSameAs(microsoft);
    }

    @Test
    @DisplayName("resolve throws INTERNAL_ERROR naming the provider when none is registered")
    void resolveUnknownProviderThrows() {
        OAuth2ClaimsExtractorRegistry registry = new OAuth2ClaimsExtractorRegistry(List.of(extractorFor("google")));

        assertThatThrownBy(() -> registry.resolve("yahoo")).isInstanceOf(MailOperationException.class)
                .hasMessageContaining("yahoo").extracting("code").isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("Two extractors claiming the same provider fail fast at construction")
    void duplicateProviderNamesFailFast() {
        OAuth2ClaimsExtractor first = extractorFor("google");
        OAuth2ClaimsExtractor second = extractorFor("google");

        assertThatThrownBy(() -> new OAuth2ClaimsExtractorRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class);
    }
}
