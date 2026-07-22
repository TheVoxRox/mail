package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.*;

import org.eclipse.angus.mail.imap.IMAPFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

@Service
public class FlagSyncService {

    private static final Logger log = LoggerFactory.getLogger(FlagSyncService.class);

    private final MessageRepository messageRepository;
    private final SyncStateService syncStateService;
    private final MailboxMaintenanceService maintenanceService;
    private final TransactionTemplate transactionTemplate;
    private final MailClientProperties mailProps;

    public FlagSyncService(MessageRepository messageRepository, SyncStateService syncStateService,
            MailboxMaintenanceService maintenanceService, TransactionTemplate transactionTemplate,
            MailClientProperties mailProps) {
        this.messageRepository = messageRepository;
        this.syncStateService = syncStateService;
        this.maintenanceService = maintenanceService;
        this.transactionTemplate = transactionTemplate;
        this.mailProps = mailProps;
    }

    /**
     * Verifies the server UIDValidity against the local value. If they differ,
     * resets the local cache (the whole folder is invalidated). Returns false when
     * a reset was performed — the sync should be aborted.
     * <p>
     * Persists via targeted UPDATE queries instead of {@code save(detached)} — the
     * detached entity is written several times within a single cycle in independent
     * transactions and a merge would raise {@code StaleObjectStateException}.
     */
    public boolean handleUidValidity(FolderSyncContext ctx) throws MessagingException {
        long serverUidValidity = ctx.uidFolder().getUIDValidity();
        Long localValidity = ctx.syncState().getUidValidity();
        Long syncStateId = ctx.syncState().getId();

        if (localValidity != null && localValidity != serverUidValidity) {
            log.warn("{} UIDValidity changed for folder {}. Resetting local cache.", LogCategory.SYNC,
                    ctx.folderName());
            transactionTemplate.executeWithoutResult(status -> {
                maintenanceService.clearLocalCache(ctx.getAccountId(), ctx.folderName());
                syncStateService.resetForUidValidityChange(syncStateId, serverUidValidity);
            });
            ctx.syncState().setLastKnownUid(0L);
            ctx.syncState().setUidValidity(serverUidValidity);
            ctx.syncState().setLastKnownModseq(null);
            return false;
        }

        if (localValidity != null && localValidity == serverUidValidity) {
            // No change — avoid an unnecessary DB write and version bump.
            return true;
        }

        // localValidity == null → first time we persist the server value.
        transactionTemplate
                .executeWithoutResult(status -> syncStateService.updateUidValidity(syncStateId, serverUidValidity));
        ctx.syncState().setUidValidity(serverUidValidity);
        return true;
    }

    /**
     * CONDSTORE-aware flag sync. Instead of enumerating every local UID and
     * comparing it with the server, fetch only messages whose MODSEQ advanced
     * beyond {@code ctx.syncState().getLastKnownModseq()} — see RFC 7162 §3.1.5.
     * After completion, store the new HIGHESTMODSEQ of the folder.
     * <p>
     * The caller (MailSyncService) must check the capability — this method assumes
     * the server supports CONDSTORE and that IMAPFolder exposes a valid
     * {@link IMAPFolder#getHighestModSeq()} value.
     */
    public void syncMessageFlagsCondstore(FolderSyncContext ctx) throws MessagingException {
        if (!(ctx.folder() instanceof IMAPFolder imapFolder)) {
            log.warn("{} CONDSTORE sync requires IMAPFolder, falling back to full sweep ({}).", LogCategory.SYNC,
                    ctx.folderName());
            syncMessageFlagsBatched(ctx);
            return;
        }

        Long since = ctx.syncState().getLastKnownModseq();
        long serverHighestModseq = imapFolder.getHighestModSeq();

        if (since != null && since == serverHighestModseq) {
            log.debug("{} No flag changes in folder {} (modseq unchanged: {}).", LogCategory.SYNC, ctx.folderName(),
                    serverHighestModseq);
            return;
        }

        Long syncStateId = ctx.syncState().getId();

        if (since == null) {
            /*
             * First CONDSTORE cycle for this folder — no MODSEQ baseline yet. CHANGEDSINCE
             * 0 would make the server stream the flags of EVERY message in the folder,
             * while we mirror only a recency window of it (on a 50k mailbox that is
             * megabytes of FETCH responses plus a no-op UPDATE storm, all under the
             * per-account connection lock). Run one sweep bounded to the local UIDs instead
             * and start the incremental protocol from the HIGHESTMODSEQ observed BEFORE the
             * sweep — a change racing the sweep is simply re-fetched (idempotently) in the
             * next cycle.
             */
            syncMessageFlagsBatched(ctx);
            transactionTemplate.executeWithoutResult(
                    status -> syncStateService.updateLastKnownModseq(syncStateId, serverHighestModseq));
            ctx.syncState().setLastKnownModseq(serverHighestModseq);
            return;
        }

        long sinceForFetch = since;
        log.debug("{} CONDSTORE flag sync in folder {}: CHANGEDSINCE {} -> HIGHESTMODSEQ {}", LogCategory.SYNC,
                ctx.folderName(), sinceForFetch, serverHighestModseq);

        List<ImapCondstoreCommands.FlagChange> changes = ImapCondstoreCommands.fetchFlagChangesSince(imapFolder,
                sinceForFetch);

        if (!changes.isEmpty()) {
            log.debug("{} {} flags changed since MODSEQ {} in folder {}.", LogCategory.SYNC, changes.size(),
                    sinceForFetch, ctx.folderName());
            transactionTemplate.executeWithoutResult(status -> applyFlagChanges(ctx, changes));
        }

        transactionTemplate.executeWithoutResult(
                status -> syncStateService.updateLastKnownModseq(syncStateId, serverHighestModseq));
        ctx.syncState().setLastKnownModseq(serverHighestModseq);
    }

    /**
     * Fallback path for servers without CONDSTORE — enumerate local UIDs and fetch
     * flags in batched ranges. O(folder size) every cycle, but works on any IMAP
     * server.
     */
    public void syncMessageFlagsBatched(FolderSyncContext ctx) throws MessagingException {
        int batchSize = mailProps.sync().batchSize();
        List<Long> localUids = messageRepository.findUidsByAccountAndFolder(ctx.getAccountId(), ctx.folderName());
        if (localUids.isEmpty())
            return;

        List<List<Long>> batches = localUids.stream().gather(Gatherers.windowFixed(batchSize)).toList();

        for (List<Long> batch : batches) {
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.FLAGS);

            Message[] serverMessages = ctx.uidFolder().getMessagesByUID(batch.getFirst(), batch.getLast());
            ctx.folder().fetch(serverMessages, fp);

            Map<Long, Message> serverMap = new HashMap<>();
            for (Message m : serverMessages) {
                try {
                    serverMap.put(ctx.uidFolder().getUID(m), m);
                } catch (MessagingException e) {
                    /*
                     * Benign: a missing UID in the map = skipped flag update for this message. The
                     * next sync cycle will pick it up, no data loss.
                     */
                    log.warn("{} Unable to obtain UID of a server message in folder {}, flag update skipped.",
                            LogCategory.SYNC, ctx.folderName(), e);
                }
            }
            transactionTemplate.executeWithoutResult(status -> processFlagUpdates(ctx, batch, serverMap));
        }
    }

    private void applyFlagChanges(FolderSyncContext ctx, List<ImapCondstoreCommands.FlagChange> changes) {
        for (ImapCondstoreCommands.FlagChange c : changes) {
            messageRepository.updateFlagsIfChanged(ctx.getAccountId(), ctx.folderName(), c.uid(), c.seen(), c.flagged(),
                    c.answered());
        }
    }

    private void processFlagUpdates(FolderSyncContext ctx, List<Long> uids, Map<Long, Message> serverMap) {
        for (Long uid : uids) {
            Message serverMsg = serverMap.get(uid);
            if (serverMsg != null) {
                try {
                    boolean sSeen = serverMsg.isSet(Flags.Flag.SEEN);
                    boolean sFlagged = serverMsg.isSet(Flags.Flag.FLAGGED);
                    boolean sAnswered = serverMsg.isSet(Flags.Flag.ANSWERED);
                    messageRepository.updateFlagsIfChanged(ctx.getAccountId(), ctx.folderName(), uid, sSeen, sFlagged,
                            sAnswered);
                } catch (MessagingException e) {
                    log.warn("{} Error reading flags for UID {}: {}", LogCategory.SYNC, uid, e.getMessage());
                }
            }
        }
    }

    /**
     * Detects deleted messages with a lightweight {@code UID FETCH 1:* (UID)}. The
     * server returns only UIDs (no metadata); the client compares them against the
     * local DB and removes anything that is missing on the server.
     * <p>
     * Substitute for full QRESYNC SELECT VANISHED — that would be slightly more
     * efficient (the server sends only deleted UIDs instead of all of them), but
     * requires raw access to the SELECT command outside the {@code folder.open()}
     * flow, which is out of scope today.
     * <p>
     * The same UID set that detects server-side deletions also reveals the reverse:
     * server-only UIDs sitting in a hole inside the mirrored window. Those are
     * returned (never fetched here) so the caller can re-download them — see
     * {@link #detectServerOnlyHolesInWindow} and
     * {@code MessageDownloader.reconcileServerOnlyUids}.
     *
     * @return server-only UIDs inside the mirrored window that the caller should
     *         re-download; empty when the mirror is contiguous
     */
    public List<Long> cleanupDeletedViaUidEnumeration(FolderSyncContext ctx) throws MessagingException {
        if (!(ctx.folder() instanceof IMAPFolder imapFolder)) {
            log.warn("{} Cleanup requires IMAPFolder, falling back to legacy path ({}).", LogCategory.SYNC,
                    ctx.folderName());
            return cleanupDeletedInWindow(ctx);
        }

        List<Long> localUids = messageRepository.findUidsByAccountAndFolder(ctx.getAccountId(), ctx.folderName());
        if (localUids.isEmpty())
            return List.of();

        Set<Long> serverUids = ImapCondstoreCommands.fetchAllServerUids(imapFolder);

        List<Long> toDelete = localUids.stream().filter(uid -> !serverUids.contains(uid)).toList();
        if (!toDelete.isEmpty()) {
            log.debug("{} Deleting {} messages no longer on the server in folder {} (UID enumeration).",
                    LogCategory.SYNC, toDelete.size(), ctx.folderName());
            transactionTemplate.executeWithoutResult(status -> messageRepository
                    .deleteAllByAccountIdAndFolderNameAndUidIn(ctx.getAccountId(), ctx.folderName(), toDelete));
        }

        return detectServerOnlyHolesInWindow(localUids, serverUids);
    }

    /**
     * Legacy fallback for servers where UID enumeration via raw command is not
     * available either (e.g. non-Angus IMAPFolder implementations). Compares local
     * UIDs with the server range-fetched metadata.
     * <p>
     * Here the server UID set is fetched over exactly the local window range
     * {@code [min, max]}, so every server-only UID it reveals is inside the window
     * by construction — same reconciliation contract as
     * {@link #cleanupDeletedViaUidEnumeration}.
     *
     * @return server-only UIDs inside the mirrored window that the caller should
     *         re-download; empty when the mirror is contiguous (or when the fail-safe
     *         aborts this cycle)
     */
    public List<Long> cleanupDeletedInWindow(FolderSyncContext ctx) throws MessagingException {
        List<Long> localUids = messageRepository.findUidsByAccountAndFolder(ctx.getAccountId(), ctx.folderName());
        if (localUids.isEmpty())
            return List.of();

        Message[] serverMessages = ctx.uidFolder().getMessagesByUID(localUids.getFirst(), localUids.getLast());

        Set<Long> serverUids = new HashSet<>();
        for (Message m : serverMessages) {
            try {
                serverUids.add(ctx.uidFolder().getUID(m));
            } catch (MessagingException e) {
                /*
                 * Fail-safe: if we cannot obtain the UID of even a single server message we
                 * cannot safely determine what is missing on the server — a UID absent from
                 * serverUids would incorrectly mark the local message as deleted and the
                 * cleanup would drop it even though it still exists on the server. The same
                 * unreliable set would also fabricate phantom "holes" and trigger needless
                 * re-downloads. Skip the whole window in this cycle; the next cycle will retry.
                 */
                log.warn(
                        "{} Unable to obtain UID of a server message in folder {} — "
                                + "skipping cleanup deletion in this cycle (fail-safe).",
                        LogCategory.SYNC, ctx.folderName(), e);
                return List.of();
            }
        }

        List<Long> toDelete = localUids.stream().filter(uid -> !serverUids.contains(uid)).toList();

        if (!toDelete.isEmpty()) {
            log.debug("{} Deleting {} messages no longer on the server in folder {}", LogCategory.SYNC, toDelete.size(),
                    ctx.folderName());
            transactionTemplate.executeWithoutResult(status -> messageRepository
                    .deleteAllByAccountIdAndFolderNameAndUidIn(ctx.getAccountId(), ctx.folderName(), toDelete));
        }

        return detectServerOnlyHolesInWindow(localUids, serverUids);
    }

    /**
     * Server-only UIDs that fall strictly inside the locally mirrored window
     * {@code (min(localUids), max(localUids))} yet have no local row — structural
     * holes the caller re-downloads (see
     * {@code MessageDownloader.reconcileServerOnlyUids}). A message stuck in such a
     * hole is otherwise invisible forever: forward sync only fetches UIDs above
     * {@code lastKnownUid}, this cleanup only deletes, and page-0 backfill assumes
     * the missing run is the oldest tail — but the folder unread badge still counts
     * it (it reads the server STATUS UNSEEN), so the folder can read "1 unread" with
     * nothing to show.
     * <p>
     * Restricted to the window interior on purpose: server UIDs BELOW the window are
     * the not-yet-backfilled older tail, served on demand by lazy page fetch —
     * treating them as holes would re-download the whole mailbox every sync cycle.
     * The rare top-edge case (a hole newer than {@code max(localUids)} but at or
     * below {@code lastKnownUid}) stays out of scope here; healing it would mean
     * rewinding the forward cursor, a larger change than this reconcile.
     * <p>
     * {@code localUids} is ascending ({@code findUidsByAccountAndFolder} orders by
     * uid), so first = min and last = max. The endpoints are always present locally,
     * so the {@code !localSet.contains} filter already excludes them — the strict
     * bounds just document that only interior holes qualify.
     */
    private static List<Long> detectServerOnlyHolesInWindow(List<Long> localUids, Set<Long> serverUids) {
        long minLocal = localUids.getFirst();
        long maxLocal = localUids.getLast();
        Set<Long> localSet = Set.copyOf(localUids);
        return serverUids.stream().filter(uid -> uid > minLocal && uid < maxLocal && !localSet.contains(uid)).sorted()
                .toList();
    }
}
