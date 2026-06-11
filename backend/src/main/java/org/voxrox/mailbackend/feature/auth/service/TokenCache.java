package org.voxrox.mailbackend.feature.auth.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * In-memory access token cache. The key is {@code accountId} (Long), globally
 * unique within the application — an account belongs to exactly one provider,
 * so a Google/Microsoft token collision under the same key cannot occur.
 *
 * <p>
 * Single concrete class — no interface. There is no second cache implementation
 * (this is a single-user desktop app, not a scale-out service).
 */
@Component
public class TokenCache {

    /**
     * Cap kept as defense-in-depth against a memory leak. In practice users have a
     * handful of accounts and entries are invalidated on deleteAccount / refresh
     * failure. The bound holds the invariant "in-memory caches always have an upper
     * limit", consistent with the LRU in {@code CryptoService}.
     */
    private static final int MAX_CACHED_TOKENS = 256;

    private final Map<Long, CachedToken> tokens;

    public TokenCache() {
        LinkedHashMap<Long, CachedToken> lru = new LinkedHashMap<>(MAX_CACHED_TOKENS + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, CachedToken> eldest) {
                // Explicit super.size() — TokenCache.this also exposes size(),
                // so an unqualified call would be ambiguous (SpotBugs IA_*).
                return super.size() > MAX_CACHED_TOKENS;
            }
        };
        this.tokens = Collections.synchronizedMap(lru);
    }

    public Optional<CachedToken> get(Long accountId) {
        return Optional.ofNullable(tokens.get(accountId));
    }

    public void put(Long accountId, CachedToken token) {
        tokens.put(accountId, token);
    }

    public void invalidate(Long accountId) {
        tokens.remove(accountId);
    }

    public int size() {
        return tokens.size();
    }
}
