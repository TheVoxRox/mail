package org.voxrox.mailbackend.feature.mail.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * How often a folder still pays for the full {@code UID FETCH 1:* (UID)}
 * enumeration once QRESYNC covers the same ground more cheaply.
 *
 * <p>
 * On a QRESYNC server the SELECT reports expunged messages itself, so the
 * enumeration is no longer how deletions are found. What it is still the only
 * source of is a <em>server-only hole</em>: a UID that exists on the server,
 * sits inside the locally mirrored window, and has no local row. Nothing else
 * sees one — forward sync only fetches above {@code lastKnownUid}, the QRESYNC
 * response only speaks about messages the client already knows, and page-0
 * backfill assumes the missing run is the oldest tail — yet the folder's unread
 * badge counts it. So the enumeration is kept as a slow safety net rather than
 * deleted, and this class is the throttle: hourly instead of every five-minute
 * cycle, which is a twelfth of the cost of the O(server folder) scan this whole
 * path exists to remove.
 *
 * <p>
 * State is process-local and lost on restart, deliberately: a fresh start
 * re-verifies every folder once, which is when a mirror is most likely to have
 * been left inconsistent by whatever ended the previous run. The map grows by
 * one entry per folder actually synced in this process — the same bound as
 * {@link FolderListCache}'s snapshots, and for the same reason not worth
 * evicting.
 */
@Component
public class UidEnumerationSchedule {

    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(1);

    private final ConcurrentHashMap<FolderKey, Instant> lastRun = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration interval;

    public UidEnumerationSchedule() {
        this(Clock.systemUTC(), DEFAULT_INTERVAL);
    }

    // Package-private constructor for tests with an injectable Clock + interval.
    UidEnumerationSchedule(Clock clock, Duration interval) {
        this.clock = clock;
        this.interval = interval;
    }

    /**
     * Whether the folder's enumeration is due again. A folder that has never run
     * one in this process is always due.
     */
    public boolean isDue(Long accountId, String folderName) {
        Instant last = lastRun.get(new FolderKey(accountId, folderName));
        return last == null || Duration.between(last, clock.instant()).compareTo(interval) >= 0;
    }

    /**
     * Records that the enumeration has just run. Called from the enumeration
     * itself, so a cycle that ran it for another reason — a server without QRESYNC,
     * or the first cycle of a folder, where it runs unconditionally — also resets
     * the clock instead of leaving a redundant scan queued right behind it.
     */
    public void markRan(Long accountId, String folderName) {
        lastRun.put(new FolderKey(accountId, folderName), clock.instant());
    }

    private record FolderKey(Long accountId, String folderName) {
    }
}
