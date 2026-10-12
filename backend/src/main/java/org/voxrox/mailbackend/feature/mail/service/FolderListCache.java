package org.voxrox.mailbackend.feature.mail.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.feature.mail.dto.FolderResponse;

/**
 * In-memory TTL cache of the folder list (structure + unread counts) per
 * account. Companion of {@link FolderCountCache}, same design.
 *
 * <p>
 * A cold {@code getFolders} costs one IMAP LIST plus one STATUS round-trip per
 * folder ({@code getUnreadMessageCount} on a closed folder), all serialized
 * behind the per-account connection lock — seconds on accounts with many
 * folders. The list is requested often: the sidebar refreshes after every
 * {@code sync_completed} and after every delete/move, and
 * {@code MailFacade.moveToFolder} re-lists folders to validate the target — a
 * bulk move fires that validation once per message. The cache collapses those
 * bursts into a single IMAP round-trip per TTL window.
 *
 * <p>
 * Freshness: {@code MailSyncEventListener} invalidates the account's entry on
 * every completed folder cycle <em>before</em> broadcasting the SSE event, so
 * the client-triggered refresh always re-reads from IMAP after a sync.
 * {@code ImapActionService} invalidates after server-side moves and flag
 * changes land, so the next natural refresh reflects them. Within the TTL a
 * badge can lag behind by at most {@code DEFAULT_TTL} — the pre-cache behavior
 * was already racy there, because the STATUS read raced the asynchronous
 * server-side action.
 *
 * <p>
 * Lost on restart by design; repopulated by the first folder listing.
 */
@Component
public class FolderListCache {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    private static final int MAX_SEEN_FOLDER_REFS_PER_ACCOUNT = 10_000;

    private final ConcurrentHashMap<Long, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> seenFolderRefs = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public FolderListCache() {
        this(Clock.systemUTC(), DEFAULT_TTL);
    }

    // Package-private constructor for tests with injectable Clock + TTL.
    FolderListCache(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * Returns the cached folder list if a recent snapshot exists, otherwise empty.
     * Expired entries are not removed eagerly — they are overwritten on the next
     * {@link #put} or simply ignored on read.
     */
    public Optional<List<FolderResponse>> get(long accountId) {
        Snapshot snap = snapshots.get(accountId);
        if (snap == null) {
            return Optional.empty();
        }
        if (Duration.between(snap.timestamp(), clock.instant()).compareTo(ttl) > 0) {
            return Optional.empty();
        }
        return Optional.of(snap.folders());
    }

    /**
     * Stores or refreshes the folder list snapshot. The list is defensively copied
     * to an immutable view so a later caller cannot mutate a shared snapshot.
     */
    public void put(long accountId, List<FolderResponse> folders) {
        snapshots.put(accountId, new Snapshot(List.copyOf(folders), clock.instant()));
        Set<String> seen = seenFolderRefs.computeIfAbsent(accountId, id -> ConcurrentHashMap.newKeySet());
        for (FolderResponse folder : folders) {
            if (seen.size() >= MAX_SEEN_FOLDER_REFS_PER_ACCOUNT) {
                break;
            }
            seen.add(folder.folderRef());
        }
    }

    /**
     * Every folder reference this cache has been given for each account since the
     * process started, kept past expiry and {@link #invalidate}. The diagnostic
     * bundle replaces these names in its copy of the logs: a folder the user only
     * ever moved mail into is named there without ever having been synchronised, so
     * the sync state alone does not know it.
     *
     * <p>
     * At most {@value #MAX_SEEN_FOLDER_REFS_PER_ACCOUNT} an account. Nothing ever
     * removes a name, so without a cap a server listing different names on every
     * LIST would grow this for as long as the process runs; a real mailbox has a
     * few hundred folders at the most.
     */
    public Map<Long, Set<String>> seenFolderRefs() {
        Map<Long, Set<String>> copy = new HashMap<>();
        seenFolderRefs.forEach((accountId, refs) -> copy.put(accountId, Set.copyOf(refs)));
        return copy;
    }

    /**
     * Drops the cached entry, e.g. after a sync cycle or a server-side action that
     * changed folder contents, or when the account is deleted.
     */
    public void invalidate(long accountId) {
        snapshots.remove(accountId);
    }

    private record Snapshot(List<FolderResponse> folders, Instant timestamp) {
    }
}
