package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.feature.mail.dto.FolderResponse;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;

class FolderListCacheTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Instant T0 = Instant.parse("2026-05-29T10:00:00Z");

    private static final List<FolderResponse> FOLDERS = List.of(
            new FolderResponse("Inbox", "INBOX", 3, FolderRole.INBOX),
            new FolderResponse("Sent", "Sent", 0, FolderRole.SENT));

    @Test
    @DisplayName("Empty cache returns Optional.empty()")
    void missReturnsEmpty() {
        FolderListCache cache = new FolderListCache(Clock.fixed(T0, ZoneOffset.UTC), TTL);

        assertThat(cache.get(1L)).isEmpty();
    }

    @Test
    @DisplayName("put then get within TTL returns the stored folder list")
    void putThenGetReturnsFolders() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        FolderListCache cache = new FolderListCache(movingClock(now), TTL);

        cache.put(7L, FOLDERS);
        now.set(T0.plus(Duration.ofSeconds(15)));

        assertThat(cache.get(7L)).contains(FOLDERS);
    }

    @Test
    @DisplayName("Entry beyond TTL is reported as miss")
    void expiredEntryIsMiss() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        FolderListCache cache = new FolderListCache(movingClock(now), TTL);

        cache.put(7L, FOLDERS);
        now.set(T0.plus(TTL).plus(Duration.ofSeconds(1)));

        assertThat(cache.get(7L)).isEmpty();
    }

    /**
     * The boundary belongs in its own test because the comparison is {@code > ttl},
     * not {@code >= ttl} — an entry exactly TTL old is still a hit, and flipping
     * that operator would otherwise only show up as a cache that expires one clock
     * tick early.
     */
    @Test
    @DisplayName("Entry exactly TTL old is still a hit")
    void entryAtExactTtlIsHit() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        FolderListCache cache = new FolderListCache(movingClock(now), TTL);

        cache.put(7L, FOLDERS);
        now.set(T0.plus(TTL));

        assertThat(cache.get(7L)).contains(FOLDERS);
    }

    @Test
    @DisplayName("Separate account keys do not collide")
    void keysAreScopedPerAccount() {
        FolderListCache cache = new FolderListCache(Clock.fixed(T0, ZoneOffset.UTC), TTL);
        List<FolderResponse> other = List.of(new FolderResponse("Archive", "Archive", 9, FolderRole.ARCHIVE));

        cache.put(7L, FOLDERS);
        cache.put(8L, other);

        assertThat(cache.get(7L)).contains(FOLDERS);
        assertThat(cache.get(8L)).contains(other);
    }

    @Test
    @DisplayName("invalidate removes the entry")
    void invalidateClearsEntry() {
        FolderListCache cache = new FolderListCache(Clock.fixed(T0, ZoneOffset.UTC), TTL);
        cache.put(7L, FOLDERS);

        cache.invalidate(7L);

        assertThat(cache.get(7L)).isEmpty();
    }

    /**
     * The javadoc on {@code put} promises the snapshot cannot be mutated through
     * the caller's list. Both halves are asserted, because only the second one
     * fails if the defensive copy is dropped: the mutation would then be visible
     * through the cache.
     */
    @Test
    @DisplayName("put copies the caller's list, so a later mutation cannot reach the snapshot")
    void putDefensivelyCopiesTheList() {
        FolderListCache cache = new FolderListCache(Clock.fixed(T0, ZoneOffset.UTC), TTL);
        List<FolderResponse> mutable = new ArrayList<>(FOLDERS);

        cache.put(7L, mutable);
        mutable.add(new FolderResponse("Trash", "Trash", 1, FolderRole.TRASH));

        assertThat(cache.get(7L)).contains(FOLDERS);
        assertThatThrownBy(() -> cache.get(7L).orElseThrow().add(FOLDERS.get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
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
