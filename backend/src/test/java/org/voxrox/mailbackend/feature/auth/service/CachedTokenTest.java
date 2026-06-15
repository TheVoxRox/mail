package org.voxrox.mailbackend.feature.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CachedToken#isFresh()} — the freshness check applies a
 * 60-second skew so a token that would lapse during the handshake after a cache
 * hit is treated as stale.
 */
class CachedTokenTest {

    @Test
    @DisplayName("Fresh when expiry is comfortably in the future")
    void freshWhenWellBeforeExpiry() {
        CachedToken token = new CachedToken("access", Instant.now().plusSeconds(3600));
        assertThat(token.isFresh()).isTrue();
    }

    @Test
    @DisplayName("Stale when already expired")
    void staleWhenExpired() {
        CachedToken token = new CachedToken("access", Instant.now().minusSeconds(1));
        assertThat(token.isFresh()).isFalse();
    }

    @Test
    @DisplayName("Stale inside the 60s skew window even though the nominal expiry is still ahead")
    void staleWithinSkewWindow() {
        // Expires in 30s, but the 60s skew pulls the effective deadline into the past.
        CachedToken token = new CachedToken("access", Instant.now().plusSeconds(30));
        assertThat(token.isFresh()).isFalse();
    }
}
