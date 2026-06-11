package org.voxrox.mailbackend.feature.auth.service;

import java.time.Duration;
import java.time.Instant;

/**
 * Entry stored in {@link TokenCache}. {@code expiresAt} already reflects the
 * nominal validity returned by the OAuth2 provider; {@link #isFresh()} on top
 * of that subtracts {@link #EXPIRY_SKEW}, so we never return a token that could
 * expire during the fraction of a second between the cache hit and the
 * IMAP/SMTP auth handshake.
 */
record CachedToken(String accessToken, Instant expiresAt) {

    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);

    boolean isFresh() {
        return Instant.now().isBefore(expiresAt.minus(EXPIRY_SKEW));
    }
}
