package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FolderCountCacheTest {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Instant T0 = Instant.parse("2026-05-29T10:00:00Z");

    @Test
    @DisplayName("Empty cache returns OptionalLong.empty()")
    void missReturnsEmpty() {
        FolderCountCache cache = new FolderCountCache(Clock.fixed(T0, ZoneOffset.UTC), TTL);

        assertThat(cache.get(1L, "INBOX")).isEmpty();
    }

    @Test
    @DisplayName("put then get within TTL returns the stored count")
    void putThenGetReturnsCount() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        FolderCountCache cache = new FolderCountCache(movingClock(now), TTL);

        cache.put(7L, "INBOX", 1790L);
        now.set(T0.plus(Duration.ofSeconds(30)));

        assertThat(cache.get(7L, "INBOX")).isEqualTo(OptionalLong.of(1790L));
    }

    @Test
    @DisplayName("Entry beyond TTL is reported as miss")
    void expiredEntryIsMiss() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        FolderCountCache cache = new FolderCountCache(movingClock(now), TTL);

        cache.put(7L, "INBOX", 1790L);
        now.set(T0.plus(TTL).plus(Duration.ofSeconds(1)));

        assertThat(cache.get(7L, "INBOX")).isEmpty();
    }

    @Test
    @DisplayName("Separate (account, folder) keys do not collide")
    void keysAreScopedPerAccountAndFolder() {
        FolderCountCache cache = new FolderCountCache(Clock.fixed(T0, ZoneOffset.UTC), TTL);

        cache.put(7L, "INBOX", 100L);
        cache.put(7L, "Sent", 5L);
        cache.put(8L, "INBOX", 42L);

        assertThat(cache.get(7L, "INBOX")).isEqualTo(OptionalLong.of(100L));
        assertThat(cache.get(7L, "Sent")).isEqualTo(OptionalLong.of(5L));
        assertThat(cache.get(8L, "INBOX")).isEqualTo(OptionalLong.of(42L));
    }

    @Test
    @DisplayName("invalidate removes the entry")
    void invalidateClearsEntry() {
        FolderCountCache cache = new FolderCountCache(Clock.fixed(T0, ZoneOffset.UTC), TTL);
        cache.put(7L, "INBOX", 100L);

        cache.invalidate(7L, "INBOX");

        assertThat(cache.get(7L, "INBOX")).isEmpty();
    }

    private static Clock movingClock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public Instant instant() {
                return now.get();
            }

            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }
        };
    }
}
