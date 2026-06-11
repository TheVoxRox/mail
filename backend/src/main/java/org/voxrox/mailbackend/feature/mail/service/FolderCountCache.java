package org.voxrox.mailbackend.feature.mail.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * In-memory TTL cache of the server-side message count per (account, folder).
 *
 * <p>
 * The read path ({@code MailFacade.getEmails}) needs this count for the
 * paginator total so the user sees the real folder size, not just what is in
 * the local cache. Without a cache every page navigation would open the IMAP
 * folder just to read {@code getMessageCount()} — a noticeable per-click
 * latency hit (~100–500 ms over wifi). The cache is refreshed by the periodic
 * sync cycle (which opens the folder anyway) and by any IMAP fetch the read
 * path itself performs, so stale entries clear out within a single sync
 * interval.
 *
 * <p>
 * Lost on app restart by design — the cache is recovered organically by the
 * first periodic sync or first page navigation. No persistence layer needed.
 */
@Component
public class FolderCountCache {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final ConcurrentHashMap<Key, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public FolderCountCache() {
        this(Clock.systemUTC(), DEFAULT_TTL);
    }

    // Package-private constructor for tests with injectable Clock + TTL.
    FolderCountCache(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * Returns the cached server count for the folder if a recent snapshot exists,
     * otherwise empty. Expired entries are not removed eagerly — they are
     * overwritten on the next {@link #put} or simply ignored on read.
     */
    public OptionalLong get(long accountId, String folderName) {
        Snapshot snap = snapshots.get(new Key(accountId, folderName));
        if (snap == null) {
            return OptionalLong.empty();
        }
        if (Duration.between(snap.timestamp(), clock.instant()).compareTo(ttl) > 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(snap.count());
    }

    /**
     * Stores or refreshes the server count snapshot for the folder.
     */
    public void put(long accountId, String folderName, long count) {
        snapshots.put(new Key(accountId, folderName), new Snapshot(count, clock.instant()));
    }

    /**
     * Drops the cached entry, e.g. after a move/delete that we know changed the
     * folder size and we want the next reader to refresh from IMAP.
     */
    public void invalidate(long accountId, String folderName) {
        snapshots.remove(new Key(accountId, folderName));
    }

    private record Key(long accountId, String folderName) {
    }

    private record Snapshot(long count, Instant timestamp) {
    }
}
