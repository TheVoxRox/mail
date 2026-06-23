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
 * Unit tests for {@link OAuth2TokenServiceRegistry} — the provider-keyed lookup
 * over the auto-injected {@link OAuth2TokenService} beans. Mirrors
 * {@link OAuth2ClaimsExtractorRegistryTest}: successful resolution, fail-fast
 * on an unknown provider, and the construction-time guard against two services
 * claiming the same provider. Adds the registry-specific cases this registry
 * carries over the claims one: the documented {@code null}-provider path (an
 * OAUTH2 account without {@code oauth2_provider}) and the
 * {@code totalCachedTokens} aggregation across providers.
 */
class OAuth2TokenServiceRegistryTest {

    private static OAuth2TokenService serviceFor(String providerName) {
        OAuth2TokenService service = mock(OAuth2TokenService.class);
        when(service.providerName()).thenReturn(providerName);
        return service;
    }

    @Test
    @DisplayName("resolve returns the token service registered under the matching provider name")
    void resolveReturnsMatchingService() {
        OAuth2TokenService google = serviceFor("google");
        OAuth2TokenService microsoft = serviceFor("microsoft");
        OAuth2TokenServiceRegistry registry = new OAuth2TokenServiceRegistry(List.of(google, microsoft));

        assertThat(registry.resolve("google")).isSameAs(google);
        assertThat(registry.resolve("microsoft")).isSameAs(microsoft);
    }

    @Test
    @DisplayName("resolve throws INTERNAL_ERROR naming the provider when none is registered")
    void resolveUnknownProviderThrows() {
        OAuth2TokenServiceRegistry registry = new OAuth2TokenServiceRegistry(List.of(serviceFor("google")));

        assertThatThrownBy(() -> registry.resolve("yahoo")).isInstanceOf(MailOperationException.class)
                .hasMessageContaining("yahoo").extracting("code").isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("resolve(null) — an OAUTH2 account with no provider — fails fast instead of an NPE")
    void resolveNullProviderThrows() {
        OAuth2TokenServiceRegistry registry = new OAuth2TokenServiceRegistry(List.of(serviceFor("google")));

        assertThatThrownBy(() -> registry.resolve(null)).isInstanceOf(MailOperationException.class).extracting("code")
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("Two services claiming the same provider fail fast at construction")
    void duplicateProviderNamesFailFast() {
        OAuth2TokenService first = serviceFor("google");
        OAuth2TokenService second = serviceFor("google");

        assertThatThrownBy(() -> new OAuth2TokenServiceRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("totalCachedTokens sums the per-service cache stats across all providers")
    void totalCachedTokensSumsAcrossProviders() {
        OAuth2TokenService google = serviceFor("google");
        OAuth2TokenService microsoft = serviceFor("microsoft");
        when(google.getCacheStats()).thenReturn(new OAuth2TokenService.CacheStats(3));
        when(microsoft.getCacheStats()).thenReturn(new OAuth2TokenService.CacheStats(2));
        OAuth2TokenServiceRegistry registry = new OAuth2TokenServiceRegistry(List.of(google, microsoft));

        assertThat(registry.totalCachedTokens()).isEqualTo(5);
    }
}
