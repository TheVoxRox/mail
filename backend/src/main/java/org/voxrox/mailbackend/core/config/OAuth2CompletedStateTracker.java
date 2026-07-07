package org.voxrox.mailbackend.core.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Session-independent record of OAuth2 {@code state} values whose login
 * completed, so {@code SecurityConfig.oauth2FailureHandler()} can recognise a
 * benign duplicate callback by its {@code state} alone.
 *
 * <p>
 * Keying on {@code state} — a query parameter present on every callback —
 * rather than on the HTTP session is deliberate. Spring rotates the session id
 * on successful login (session-fixation protection), so a retried or prefetched
 * duplicate callback that still carries the pre-login cookie may not resolve to
 * the session that completed the login; a session-based check would then miss
 * the duplicate. The {@code state} is an unguessable, single-use value that
 * uniquely identifies one authorization flow, so an application-scoped set is
 * both correct (states never collide across flows) and safe to keep briefly.
 *
 * <p>
 * The set is bounded (LRU eviction): a completed {@code state} stays known
 * until pushed out by {@link #MAX_ENTRIES} newer logins, which is far more than
 * a single-user desktop app produces, so a real duplicate is always recognised
 * while memory stays bounded.
 */
@Component
class OAuth2CompletedStateTracker {

    private static final int MAX_ENTRIES = 64;

    private final Set<String> completedStates = Collections
            .synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<>(MAX_ENTRIES + 1, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_ENTRIES;
                }
            }));

    /** Record that the OAuth2 login identified by {@code state} completed. */
    void markCompleted(@Nullable String state) {
        if (state != null && !state.isBlank()) {
            completedStates.add(state);
        }
    }

    /**
     * True when {@code state} names a login that already completed — i.e. a
     * callback carrying it is a benign duplicate of a finished flow, not a fresh
     * failure.
     */
    boolean wasCompleted(@Nullable String state) {
        return state != null && !state.isBlank() && completedStates.contains(state);
    }
}
