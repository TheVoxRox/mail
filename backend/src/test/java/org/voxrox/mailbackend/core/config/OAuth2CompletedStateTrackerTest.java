package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OAuth2CompletedStateTracker}: a state is recognised
 * only after it is marked, distinct states stay independent, null/blank are
 * ignored, and the set stays bounded (the oldest entries are evicted).
 */
class OAuth2CompletedStateTrackerTest {

    private final OAuth2CompletedStateTracker tracker = new OAuth2CompletedStateTracker();

    @Test
    @DisplayName("A marked state is reported as completed")
    void marksAndRecognisesState() {
        tracker.markCompleted("state-1");

        assertThat(tracker.wasCompleted("state-1")).isTrue();
    }

    @Test
    @DisplayName("An unmarked state is not completed")
    void unknownStateIsNotCompleted() {
        tracker.markCompleted("state-1");

        assertThat(tracker.wasCompleted("state-2")).isFalse();
    }

    @Test
    @DisplayName("null and blank states are ignored on both mark and query")
    void nullAndBlankAreIgnored() {
        tracker.markCompleted(null);
        tracker.markCompleted("   ");

        assertThat(tracker.wasCompleted(null)).isFalse();
        assertThat(tracker.wasCompleted("")).isFalse();
        assertThat(tracker.wasCompleted("   ")).isFalse();
    }

    @Test
    @DisplayName("The set is bounded — the oldest states are evicted, the newest survive")
    void boundedByEviction() {
        // Far exceeds the internal cap, so the earliest entries must be gone.
        for (int i = 0; i < 500; i++) {
            tracker.markCompleted("state-" + i);
        }

        assertThat(tracker.wasCompleted("state-499")).isTrue();
        assertThat(tracker.wasCompleted("state-0")).isFalse();
    }
}
