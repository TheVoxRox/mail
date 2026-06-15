package org.voxrox.mailbackend.feature.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenCache} — the in-memory, access-ordered LRU keyed
 * by {@code accountId}. Covers the get/put/invalidate/size contract and the
 * 256-entry bound, including that a recent {@code get} spares an entry from
 * eviction (access order, not insertion order).
 */
class TokenCacheTest {

    private static final int MAX_CACHED_TOKENS = 256;

    private final TokenCache cache = new TokenCache();

    private static CachedToken token(String value) {
        return new CachedToken(value, Instant.now().plusSeconds(3600));
    }

    @Test
    @DisplayName("get on an empty cache returns empty")
    void getMissReturnsEmpty() {
        assertThat(cache.get(1L)).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("put then get returns the stored token")
    void putThenGet() {
        CachedToken stored = token("access-1");
        cache.put(1L, stored);

        assertThat(cache.get(1L)).contains(stored);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("invalidate removes the entry")
    void invalidateRemovesEntry() {
        cache.put(1L, token("access-1"));
        cache.invalidate(1L);

        assertThat(cache.get(1L)).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("invalidating an absent key is a safe no-op")
    void invalidateAbsentKeyIsNoOp() {
        cache.invalidate(42L); // must not throw
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("The cache is bounded — the 257th entry evicts the eldest, size stays at the cap")
    void evictsEldestBeyondCap() {
        for (long id = 0; id < MAX_CACHED_TOKENS; id++) {
            cache.put(id, token("t" + id));
        }
        assertThat(cache.size()).isEqualTo(MAX_CACHED_TOKENS);

        cache.put(999L, token("overflow"));

        assertThat(cache.size()).isEqualTo(MAX_CACHED_TOKENS);
        assertThat(cache.get(0L)).as("eldest entry evicted").isEmpty();
        assertThat(cache.get(999L)).as("newest entry kept").isPresent();
    }

    @Test
    @DisplayName("Access order: a recent get spares an entry; the next-eldest is evicted instead")
    void recentGetSparesEntryFromEviction() {
        for (long id = 0; id < MAX_CACHED_TOKENS; id++) {
            cache.put(id, token("t" + id));
        }

        // Touch the eldest (id 0) — it becomes the most-recently-used entry.
        assertThat(cache.get(0L)).isPresent();

        // One more put forces a single eviction.
        cache.put(999L, token("overflow"));

        assertThat(cache.get(0L)).as("recently accessed entry survives").isPresent();
        assertThat(cache.get(1L)).as("now-eldest entry evicted instead").isEmpty();
    }
}
