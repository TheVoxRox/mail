package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.feature.mail.dto.MailDetailResponse;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.mapper.MessageMapper;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

/**
 * Responsible for downloading messages from the server and persisting them to
 * the DB.
 */
@Service
public class MessageDownloader {

    private static final Logger log = LoggerFactory.getLogger(MessageDownloader.class);
    private static final long UID_INCREMENT = 1L;
    private static final long UID_INITIAL = 0L;

    private final MessageRepository messageRepository;
    private final MessageFetcher messageFetcher;
    private final SyncStateService syncStateService;
    private final TransactionTemplate transactionTemplate;
    private final MailClientProperties mailProps;
    private final MessageMapper messageMapper;
    private final ThreadingService threadingService;

    public MessageDownloader(MessageRepository messageRepository, MessageFetcher messageFetcher,
            SyncStateService syncStateService, TransactionTemplate transactionTemplate, MailClientProperties mailProps,
            MessageMapper messageMapper, ThreadingService threadingService) {
        this.messageRepository = messageRepository;
        this.messageFetcher = messageFetcher;
        this.syncStateService = syncStateService;
        this.transactionTemplate = transactionTemplate;
        this.mailProps = mailProps;
        this.messageMapper = messageMapper;
        this.threadingService = threadingService;
    }

    /**
     * Downloads new messages and stores them in the DB. Returns the count of
     * downloaded messages.
     *
     * <p>
     * Two paths:
     * <ul>
     * <li><b>Initial sync</b> ({@code lastKnownUid <= 0}) — the window is selected
     * via message sequence numbers ({@code 1..messageCount}), not via the UID
     * range. The UID space can have holes (historically moved/deleted messages
     * leave a high {@code UIDNEXT} above the actual UIDs in the mailbox), so a
     * window of {@code UIDNEXT - N .. UIDNEXT-1} could miss everything and we would
     * download nothing on the first run — see the Seznam.cz INBOX scenario where
     * {@code UIDNEXT=58871} but real messages have UIDs under 1000.</li>
     * <li><b>Incremental sync</b> ({@code lastKnownUid > 0}) — continues via a UID
     * range above {@code lastKnownUid}. After every cycle (even an empty one) we
     * advance {@code lastKnownUid} to the observed {@code UIDNEXT-1} so empty gaps
     * do not loop forever when messages get moved on the server in the
     * meantime.</li>
     * </ul>
     */
    public int syncNewMessages(FolderSyncContext ctx) throws MessagingException {
        long lastUid = ctx.syncState().getLastKnownUid();
        long maxUid = getLatestUidFromServer(ctx.folder(), ctx.uidFolder());

        if (maxUid <= lastUid) {
            return 0;
        }

        int downloaded;
        if (lastUid <= UID_INITIAL) {
            downloaded = downloadInitialWindowBySequence(ctx);
        } else {
            long startUid = lastUid + UID_INCREMENT;
            log.info("{} Detected new messages in {}: UID {} -> {}", LogCategory.SYNC, ctx.folderName(), startUid,
                    maxUid);
            downloaded = downloadRangeInternal(ctx, startUid, maxUid);
        }

        advanceLastKnownUidIfNeeded(ctx, maxUid);
        return downloaded;
    }

    /**
     * Downloads the most recent {@code windowSize} messages by their order in the
     * folder (RFC 3501 sequence numbers {@code 1..MESSAGES}). Regardless of where
     * their UIDs sit in the UID space — sequence numbers are, unlike UIDs, always
     * dense and contiguous.
     */
    private int downloadInitialWindowBySequence(FolderSyncContext ctx) throws MessagingException {
        int count = ctx.folder().getMessageCount();
        if (count <= 0) {
            log.info("{} Initial sync of folder {}: folder is empty, nothing to download.", LogCategory.SYNC,
                    ctx.folderName());
            return 0;
        }

        int windowSize = mailProps.sync().windowSize();
        int startSeq = Math.max(1, count - windowSize + 1);
        log.info("{} Initial sync of folder {}: downloading the last {} messages (seq {} -> {}, total in folder {}).",
                LogCategory.SYNC, ctx.folderName(), count - startSeq + 1, startSeq, count, count);

        Message[] messages = ctx.folder().getMessages(startSeq, count);
        if (messages == null || messages.length == 0) {
            return 0;
        }

        List<MailDetailResponse> dtos = messageFetcher.fetchBatch(messages, ctx.uidFolder(), ctx.folderName());
        saveMessagesBatchAtomic(dtos, ctx, messages);
        return dtos.size();
    }

    /**
     * Advances {@code lastKnownUid} to {@code observedMaxUid} when the downloaded
     * batch has not already moved it higher.
     *
     * <p>
     * Without this step sync would loop when {@code UIDNEXT - 1 > lastKnownUid}
     * (the server reports new UIDs) but {@code getMessagesByUID(...)} returns an
     * empty array (messages in that range have been deleted/moved in the meantime).
     * Without the advance the same empty range would be retried forever and every
     * sync tick would end with "0 new messages" even though we have evidently seen
     * the UID space move.
     */
    private void advanceLastKnownUidIfNeeded(FolderSyncContext ctx, long observedMaxUid) {
        if (observedMaxUid > ctx.syncState().getLastKnownUid()) {
            ctx.syncState().setLastKnownUid(observedMaxUid);
            syncStateService.updateLastKnownUid(ctx.syncState().getId(), observedMaxUid);
        }
    }

    /**
     * Downloads messages within the given UID range (used for historical backfill).
     */
    public int downloadRange(FolderSyncContext ctx, long startUid, long endUid) throws MessagingException {
        return downloadRangeInternal(ctx, startUid, endUid);
    }

    /**
     * Lazy-fetch path: downloads the messages at the given IMAP sequence positions
     * (1-indexed; sequence 1 = oldest, {@code folder.getMessageCount()} = newest).
     * Used by the read path when the user navigates to a page that falls below the
     * locally cached recency window, so we can serve any page of any folder without
     * mirroring the whole mailbox up front.
     * <p>
     * Saves through the same batched-atomic write as the initial sync, which
     * advances {@code lastKnownUid} only upward — backfilled / lazy-fetched older
     * UIDs never regress the forward sync cursor.
     */
    public int downloadSequenceRange(FolderSyncContext ctx, int startSeq, int endSeq) throws MessagingException {
        if (endSeq < startSeq) {
            return 0;
        }
        log.info("{} Lazy page fetch in {}: sequence {} -> {}.", LogCategory.SYNC, ctx.folderName(), startSeq, endSeq);
        Message[] messages = ctx.folder().getMessages(startSeq, endSeq);
        if (messages == null || messages.length == 0) {
            return 0;
        }
        List<MailDetailResponse> dtos = messageFetcher.fetchBatch(messages, ctx.uidFolder(), ctx.folderName());
        saveMessagesBatchAtomic(dtos, ctx, messages);
        return dtos.size();
    }

    /**
     * Re-downloads server messages whose UID sits inside the locally mirrored UID
     * window but has no local row — structural holes detected by the deletion
     * cleanup ({@link FlagSyncService#cleanupDeletedViaUidEnumeration}). Without this
     * such a message stays invisible forever: {@link #syncNewMessages} only fetches
     * UIDs above {@code lastKnownUid}, cleanup only deletes local rows, and page-0
     * backfill assumes the missing run is the oldest tail — yet the folder's unread
     * badge still counts it (it reads the server STATUS UNSEEN), so a folder can
     * report "1 unread" with nothing to show. Filling the hole makes the local
     * mirror contiguous again.
     * <p>
     * Only the UIDs the caller passes are fetched — a targeted {@code UID FETCH} of
     * exactly those holes, not a range — so the cost stays proportional to the
     * number of holes, which is normally zero. Persisted through the same
     * batched-atomic write as every other download, so {@link #dropAlreadyPersisted}
     * keeps it idempotent and {@code lastKnownUid} only ever advances upward (an
     * interior hole never regresses the forward cursor).
     *
     * @return the number of messages actually re-downloaded
     */
    public int reconcileServerOnlyUids(FolderSyncContext ctx, List<Long> holeUids) throws MessagingException {
        if (holeUids.isEmpty()) {
            return 0;
        }
        int batchSize = mailProps.sync().batchSize();
        int total = 0;
        for (List<Long> batch : holeUids.stream().gather(Gatherers.windowFixed(batchSize)).toList()) {
            long[] uids = batch.stream().mapToLong(Long::longValue).toArray();
            Message[] fetched = ctx.uidFolder().getMessagesByUID(uids);
            if (fetched == null) {
                continue;
            }
            /*
             * getMessagesByUID(long[]) can return null entries for UIDs the server no
             * longer has (a message expunged between the enumeration and this fetch); drop
             * them so the envelope fetch / mapping never dereferences a null.
             */
            Message[] messages = Arrays.stream(fetched).filter(Objects::nonNull).toArray(Message[]::new);
            if (messages.length == 0) {
                continue;
            }
            List<MailDetailResponse> dtos = messageFetcher.fetchBatch(messages, ctx.uidFolder(), ctx.folderName());
            saveMessagesBatchAtomic(dtos, ctx, messages);
            total += dtos.size();
        }
        if (total > 0) {
            log.info("{} Reconciled {} server-only message(s) missing from the local mirror in {}.", LogCategory.SYNC,
                    total, ctx.folderName());
        }
        return total;
    }

    private int downloadRangeInternal(FolderSyncContext ctx, long startUid, long endUid) throws MessagingException {
        int windowSize = mailProps.sync().windowSize();
        int totalDownloaded = 0;

        for (long currentEnd = endUid; currentEnd >= startUid; currentEnd -= windowSize) {
            long currentStart = Math.max(currentEnd - windowSize + UID_INCREMENT, startUid);
            Message[] messages = ctx.uidFolder().getMessagesByUID(currentStart, currentEnd);

            if (messages != null && messages.length > 0) {
                List<MailDetailResponse> dtos = messageFetcher.fetchBatch(messages, ctx.uidFolder(), ctx.folderName());
                saveMessagesBatchAtomic(dtos, ctx, messages);
                totalDownloaded += dtos.size();
            }
        }
        return totalDownloaded;
    }

    private void saveMessagesBatchAtomic(List<MailDetailResponse> dtos, FolderSyncContext ctx, Message[] messages) {
        /*
         * FlagSyncService.handleUidValidity runs before any download and resolves the
         * folder's UIDVALIDITY (persisting the server value on the first pass), so it
         * is non-null by the time we map messages. The column messages.uid_validity is
         * NOT NULL and the value feeds MessageStableId — fail loudly here rather than
         * write a broken identity if that call order is ever changed.
         */
        Long uidValidity = Objects.requireNonNull(ctx.syncState().getUidValidity(),
                "UIDValidity must be resolved before messages are downloaded");
        transactionTemplate.executeWithoutResult(status -> {
            List<MessageEntity> entities = dtos.stream()
                    .map(dto -> messageMapper.toEntity(dto, ctx.account(), ctx.folderName(), uidValidity)).toList();
            List<MessageEntity> toInsert = dropAlreadyPersisted(entities, ctx);
            if (!toInsert.isEmpty()) {
                List<MessageEntity> saved = messageRepository.saveAll(toInsert);
                /*
                 * Threading is assigned AFTER persistence so each entity has a DB-generated id
                 * and the JPA persistence context can see this message via
                 * findByAccountIdAndMessageId for any sibling that arrives later in the same
                 * batch. The batch order is by IMAP UID (descending in the fetch loop,
                 * ascending within a single getMessagesByUID call) — neither order is
                 * guaranteed to match receivedAt, so a later message of the thread can
                 * occasionally land before its parent in the same batch. That is exactly what
                 * ThreadingService.reconcileLateArrivingParent handles: when the parent finally
                 * lands it merges the orphan chain.
                 *
                 * Per design (THREADING_DESIGN.md) the call has zero effect on lastKnownUid
                 * updates — it operates purely on the threading columns.
                 */
                for (MessageEntity entity : saved) {
                    threadingService.assignThread(entity, ctx.account());
                }
            }

            long highestUid = getHighestUid(messages, ctx.uidFolder());
            if (highestUid > ctx.syncState().getLastKnownUid()) {
                ctx.syncState().setLastKnownUid(highestUid);
                /*
                 * Targeted UPDATE instead of save() on a detached entity — sync persists state
                 * across several independent transactions in a row, merge would end with
                 * StaleObjectStateException (the DB version advances while the detached entity
                 * holds the old one).
                 */
                syncStateService.updateLastKnownUid(ctx.syncState().getId(), highestUid);
            }
        });
    }

    /**
     * Drops messages a concurrent sync already persisted, keeping the batch insert
     * idempotent. Two overlapping syncs (the scheduled cycle and a send-triggered
     * refresh) each derive their "new" uids from a {@code lastKnownUid} snapshot
     * taken before the per-account lock, so the one that runs second can re-fetch a
     * uid the first already inserted. This runs inside the per-account lock and the
     * same transaction as the insert, so the first sync's committed rows are
     * visible here and the {@code (account, folder, uid)} unique constraint is
     * never tripped.
     */
    private List<MessageEntity> dropAlreadyPersisted(List<MessageEntity> entities, FolderSyncContext ctx) {
        if (entities.isEmpty()) {
            return entities;
        }
        List<Long> candidateUids = entities.stream().map(MessageEntity::getUid).toList();
        Set<Long> existing = Set
                .copyOf(messageRepository.findExistingUids(ctx.getAccountId(), ctx.folderName(), candidateUids));
        if (existing.isEmpty()) {
            return entities;
        }
        List<MessageEntity> kept = entities.stream().filter(e -> !existing.contains(e.getUid())).toList();
        log.debug("{} Folder {}: skipped {} message(s) a concurrent sync already persisted.", LogCategory.SYNC,
                ctx.folderName(), entities.size() - kept.size());
        return kept;
    }

    public long getLatestUidFromServer(Folder folder, UIDFolder uidFolder) throws MessagingException {
        long nextUid = uidFolder.getUIDNext();
        if (nextUid > UID_INCREMENT)
            return nextUid - UID_INCREMENT;
        int count = folder.getMessageCount();
        return (count > 0) ? uidFolder.getUID(folder.getMessage(count)) : UID_INITIAL;
    }

    private long getHighestUid(Message[] messages, UIDFolder uidFolder) {
        if (messages == null || messages.length == 0) {
            return UID_INITIAL;
        }
        try {
            return uidFolder.getUID(messages[messages.length - 1]);
        } catch (Exception e) {
            log.warn("{} Failed to read the highest UID from the message batch: {}", LogCategory.SYNC, e.getMessage());
            return UID_INITIAL;
        }
    }
}
