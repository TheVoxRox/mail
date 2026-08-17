package org.voxrox.mailbackend.feature.mail.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.mail.dto.MailSummaryResponse;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    String DTO_PATH = "org.voxrox.mailbackend.feature.mail.dto.MailSummaryResponse";

    /**
     * Shared constructor-projection prefix for {@code MailSummaryResponse} — the
     * single place that must match the record's constructor parameter order. Every
     * summary query appends only its own WHERE/ORDER BY clause.
     */
    String SUMMARY_SELECT = "SELECT new " + DTO_PATH + "("
            + "m.id, m.stableId, m.folderName, m.subject, m.sender, m.recipientsTo, m.receivedAt, "
            + "m.seen, m.flagged, m.answered, m.hasAttachments, m.threadId, m.messageId, m.uid) FROM MessageEntity m ";

    Optional<MessageEntity> findByStableId(String stableId);

    /**
     * Loads a message with its owning account in a single SQL query (JOIN FETCH).
     * MailFacade hands the entity out of the loading transaction (IMAP round-trips
     * deliberately run with no transaction open), so the lazy account association
     * must be populated up front — a detached proxy would throw on first access.
     */
    @Query("SELECT m FROM MessageEntity m JOIN FETCH m.account WHERE m.stableId = :stableId")
    Optional<MessageEntity> findByStableIdWithAccount(@Param("stableId") String stableId);

    /**
     * Loads a message including attachments in a single SQL query (JOIN FETCH).
     */
    @Query("SELECT m FROM MessageEntity m LEFT JOIN FETCH m.attachments WHERE m.stableId = :stableId")
    Optional<MessageEntity> findByStableIdWithAttachments(@Param("stableId") String stableId);

    /**
     * Returns paginated message summaries (DTO) for the given folder. Built from
     * the SUMMARY_SELECT constant, which is a valid constant expression for the
     * annotation.
     */
    @Query(SUMMARY_SELECT + "WHERE m.account.id = :accId AND m.folderName = :folder ORDER BY m.receivedAt DESC")
    Page<MailSummaryResponse> findSummariesByAccountAndFolder(@Param("accId") Long accId,
            @Param("folder") String folderName, Pageable pageable);

    /**
     * SQLite FTS5 search via the 'message_search' virtual table. Returns only the
     * matching row ids (newest first) — the FTS MATCH must stay native SQL, but
     * hydrating full entities here would drag every {@code @Lob} body of the page
     * into the 384m heap. The caller loads the display columns for these ids via
     * {@link #findSummariesByIds} and keeps this order.
     * <p>
     * Declared as {@code Number}, not {@code Long}: the SQLite JDBC driver sizes
     * the boxed type to the value (small ids arrive as {@code Integer}) and Spring
     * Data does not coerce native scalar results. A {@code Page<Long>} declaration
     * would lie at runtime and a {@code Map<Long, …>.get(Integer)} lookup would
     * silently return null — callers must normalize via {@code longValue()}.
     */
    @Query(value = """
            SELECT id FROM messages
            WHERE account_id = :accountId
              AND id IN (SELECT rowid FROM message_search WHERE message_search MATCH :query)
            ORDER BY received_at DESC
            """, countQuery = """
            SELECT count(*) FROM messages
            WHERE account_id = :accountId
              AND id IN (SELECT rowid FROM message_search WHERE message_search MATCH :query)
            """, nativeQuery = true)
    Page<Number> fullTextSearchIds(@Param("query") String query, @Param("accountId") Long accountId, Pageable pageable);

    /**
     * Summary projection (no {@code @Lob} body) for the given ids. The IN clause
     * returns rows in undefined order — callers re-sort by their own id order (e.g.
     * the FTS relevance/recency order from {@link #fullTextSearchIds}).
     */
    @Query(SUMMARY_SELECT + "WHERE m.id IN :ids")
    List<MailSummaryResponse> findSummariesByIds(@Param("ids") List<Long> ids);

    Page<MessageEntity> findByAccountIdAndFolderName(Long accountId, String folderName, Pageable pageable);

    long countByAccountIdAndFolderName(Long accountId, String folderName);

    /**
     * One page of the conversation-grouped folder listing (Threading Phase 2).
     * Collapses the folder's messages into conversations keyed by
     * {@code COALESCE(thread_id, stable_id)} — a message the backfill has not
     * threaded yet ({@code thread_id IS NULL}) falls back to its own stable id, so
     * it forms a singleton group instead of merging with every other unthreaded
     * row. For each conversation it returns the newest message as the
     * representative plus whole-partition counts, all in one pass via window
     * aggregates.
     * <p>
     * Each row is {@code [representativeId, messageCount, unreadCount]}; SQLite
     * sizes the boxed numerics to the value, so callers must normalize via
     * {@link Number}. Ordered newest-conversation first (by the representative's
     * {@code received_at}, id as the deterministic tie-break) to match the flat
     * listing's ordering.
     * <p>
     * Drives the page in <em>both</em> listing modes. Everything it returns is
     * folder-scoped, which is exactly right for the row identity, the ordering and
     * {@code unreadCount}; the Outlook-style cross-folder mode keeps all of that
     * and only replaces {@code messageCount}, via
     * {@link #countCrossFolderConversationSizes}.
     * <p>
     * Window functions require SQLite &gt;= 3.25 (2018); the bundled driver is well
     * past that.
     */
    @Query(value = """
            SELECT id, cnt, unread FROM (
              SELECT id, received_at,
                     ROW_NUMBER() OVER (PARTITION BY COALESCE(thread_id, stable_id)
                                        ORDER BY received_at DESC, id DESC) AS rn,
                     COUNT(*) OVER (PARTITION BY COALESCE(thread_id, stable_id)) AS cnt,
                     SUM(CASE WHEN seen = 0 THEN 1 ELSE 0 END)
                       OVER (PARTITION BY COALESCE(thread_id, stable_id)) AS unread
              FROM messages
              WHERE account_id = :accId AND folder_name = :folder
            )
            WHERE rn = 1
            ORDER BY received_at DESC, id DESC
            LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<Object[]> findConversationRepresentatives(@Param("accId") Long accountId, @Param("folder") String folderName,
            @Param("size") int size, @Param("offset") long offset);

    /**
     * Number of distinct conversations in the folder — the paginator total for the
     * conversation-grouped listing. Same grouping key as
     * {@link #findConversationRepresentatives}.
     */
    @Query(value = """
            SELECT COUNT(*) FROM (
              SELECT 1 FROM messages
              WHERE account_id = :accId AND folder_name = :folder
              GROUP BY COALESCE(thread_id, stable_id)
            )
            """, nativeQuery = true)
    long countConversationsByAccountAndFolder(@Param("accId") Long accountId, @Param("folder") String folderName);

    /**
     * Number of messages in the folder that do not yet have {@code seen=true}. Used
     * for {@code unreadCount} in the folder listing; reflects the state in the
     * local DB, so it matches what the user sees in the UI (IMAP flags are
     * propagated after sync_completed).
     */
    long countByAccountIdAndFolderNameAndSeenFalse(Long accountId, String folderName);

    @Query("SELECT m.uid FROM MessageEntity m WHERE m.account.id = :accId AND m.folderName = :folder ORDER BY m.uid ASC")
    List<Long> findUidsByAccountAndFolder(@Param("accId") Long accId, @Param("folder") String folderName);

    /**
     * Of the given candidate uids, the subset already present in the folder. Used
     * by the sync to keep a batch insert idempotent: two overlapping syncs (e.g.
     * the scheduled cycle and a send-triggered refresh) each compute their "new"
     * uids from a {@code lastKnownUid} snapshot taken before the per-account lock,
     * so the second can re-fetch a uid the first already persisted — inserting it
     * again would trip the {@code (account, folder, uid)} unique constraint and
     * abort the whole batch.
     */
    @Query("SELECT m.uid FROM MessageEntity m WHERE m.account.id = :accId AND m.folderName = :folder AND m.uid IN :uids")
    List<Long> findExistingUids(@Param("accId") Long accId, @Param("folder") String folderName,
            @Param("uids") List<Long> uids);

    @Modifying
    @Transactional
    @Query("UPDATE MessageEntity m SET m.seen = :seen, m.flagged = :flagged, m.answered = :answered "
            + "WHERE m.account.id = :accId AND m.folderName = :folder AND m.uid = :uid "
            + "AND (m.seen != :seen OR m.flagged != :flagged OR m.answered != :answered)")
    void updateFlagsIfChanged(@Param("accId") Long accountId, @Param("folder") String folderName,
            @Param("uid") Long uid, @Param("seen") boolean seen, @Param("flagged") boolean flagged,
            @Param("answered") boolean answered);

    @Modifying
    @Transactional
    @Query("UPDATE MessageEntity m SET m.seen = :seen WHERE m.stableId = :stableId AND m.seen != :seen")
    void updateSeenStatus(@Param("stableId") String stableId, @Param("seen") boolean seen);

    @Modifying
    @Transactional
    @Query("UPDATE MessageEntity m SET m.flagged = :flagged WHERE m.stableId = :stableId AND m.flagged != :flagged")
    void updateFlaggedStatus(@Param("stableId") String stableId, @Param("flagged") boolean flagged);

    @Modifying
    @Transactional
    @Query("UPDATE MessageEntity m SET m.answered = :answered WHERE m.stableId = :stableId AND m.answered != :answered")
    void updateAnsweredStatus(@Param("stableId") String stableId, @Param("answered") boolean answered);

    @Query("SELECT MAX(m.uid) FROM MessageEntity m WHERE m.account.id = :accId AND m.folderName = :folder")
    Optional<Long> findMaxUidByAccountIdAndFolderName(@Param("accId") Long accountId,
            @Param("folder") String folderName);

    @Query("SELECT MIN(m.uid) FROM MessageEntity m WHERE m.account.id = :accId AND m.folderName = :folder")
    Optional<Long> findMinUidByAccountIdAndFolderName(@Param("accId") Long accountId,
            @Param("folder") String folderName);

    @Query(value = "SELECT uid FROM messages WHERE account_id = :accId AND folder_name = :folder ORDER BY uid DESC LIMIT :limit", nativeQuery = true)
    List<Long> findLatestUids(@Param("accId") Long accountId, @Param("folder") String folderName,
            @Param("limit") int limit);

    @Modifying
    @Transactional
    void deleteByAccountIdAndFolderName(Long accountId, String folderName);

    @Modifying
    @Transactional
    @Query("DELETE FROM MessageEntity m WHERE m.account.id = :accId AND m.folderName = :folder AND m.uid IN :uids")
    void deleteAllByAccountIdAndFolderNameAndUidIn(@Param("accId") Long accountId, @Param("folder") String folderName,
            @Param("uids") List<Long> uids);

    @Modifying
    @Transactional
    @Query("DELETE FROM MessageEntity m WHERE m.account.id = :accId AND m.folderName = :folder AND m.uid < :thresholdUid")
    int deleteOlderThan(@Param("accId") Long accountId, @Param("folder") String folderName,
            @Param("thresholdUid") long thresholdUid);

    @Modifying
    @Transactional
    void deleteByStableId(String stableId);

    /**
     * Threading lookup — find a message by RFC 5322 {@code Message-ID} within an
     * account. Cross-folder duplicates of the same Message-ID (e.g. Gmail INBOX +
     * All Mail) are possible; callers MUST treat the first hit as authoritative —
     * they all share the same {@code threadId} by construction.
     */
    @Query("SELECT m FROM MessageEntity m " + "WHERE m.account.id = :accId AND m.messageId = :messageId "
            + "ORDER BY m.id ASC")
    List<MessageEntity> findByAccountIdAndMessageId(@Param("accId") Long accountId,
            @Param("messageId") String messageId);

    /**
     * All messages of a thread within an account, ordered by position. Used by
     * {@link org.voxrox.mailbackend.feature.mail.service.ThreadingService} for
     * position renumbering, which mutates the managed entities. The read-only
     * thread detail endpoint uses {@link #findSummariesByAccountIdAndThreadId}
     * instead — entities carry the {@code @Lob} body.
     */
    @Query("SELECT m FROM MessageEntity m " + "WHERE m.account.id = :accId AND m.threadId = :threadId "
            + "ORDER BY m.threadPosition ASC, m.id ASC")
    List<MessageEntity> findByAccountIdAndThreadId(@Param("accId") Long accountId, @Param("threadId") String threadId);

    /**
     * Summary projection of a thread's members (no {@code @Lob} body), ordered by
     * position. Drives the thread detail endpoint.
     */
    @Query(SUMMARY_SELECT + "WHERE m.account.id = :accId AND m.threadId = :threadId "
            + "ORDER BY m.threadPosition ASC, m.id ASC")
    List<MailSummaryResponse> findSummariesByAccountIdAndThreadId(@Param("accId") Long accountId,
            @Param("threadId") String threadId);

    /**
     * {@code thread_root_message_id} of the thread's first member (position order).
     * Every member shares the root by construction; the ordered LIMIT-1 read (via
     * {@code Pageable}) just keeps the pick deterministic while an orphan merge is
     * mid-flight. Returns an empty list when the thread does not exist; the single
     * element may be {@code null} for a root without a Message-ID.
     */
    @Query("SELECT m.threadRootMessageId FROM MessageEntity m "
            + "WHERE m.account.id = :accId AND m.threadId = :threadId " + "ORDER BY m.threadPosition ASC, m.id ASC")
    List<String> findThreadRootMessageIds(@Param("accId") Long accountId, @Param("threadId") String threadId,
            Pageable pageable);

    /**
     * Max thread_position within a thread — used by
     * {@link org.voxrox.mailbackend.feature.mail.service.ThreadingService} when
     * appending a new message to the end of an existing thread.
     */
    @Query("SELECT COALESCE(MAX(m.threadPosition), 0) FROM MessageEntity m "
            + "WHERE m.account.id = :accId AND m.threadId = :threadId")
    int findMaxThreadPosition(@Param("accId") Long accountId, @Param("threadId") String threadId);

    /**
     * How many members of {@code threadId} sort after the key
     * {@code (receivedAt, id)} in the thread's canonical order — the same
     * {@code (receivedAt, id)} order
     * {@link org.voxrox.mailbackend.feature.mail.service.ThreadingService}
     * renumbers by. Zero means a message with that key belongs at the end of the
     * thread, so the cheap append is correct and no renumbering pass is needed.
     *
     * <p>
     * The {@code id} tiebreak is what makes this exact rather than merely close:
     * two messages of a thread can share a {@code receivedAt} to the second, and
     * without it the answer would depend on which of them the comparison happened
     * to see.
     */
    @Query("SELECT COUNT(m) FROM MessageEntity m " + "WHERE m.account.id = :accId AND m.threadId = :threadId "
            + "AND (m.receivedAt > :receivedAt OR (m.receivedAt = :receivedAt AND m.id > :id))")
    long countThreadMembersAfter(@Param("accId") Long accountId, @Param("threadId") String threadId,
            @Param("receivedAt") LocalDateTime receivedAt, @Param("id") Long id);

    /**
     * Subject-fallback lookup — the newest already-threaded message of the account
     * whose normalized subject equals {@code norm}, within a time window around the
     * new arrival. Callers pass a page of size 1; the window plus the reply-prefix
     * gate in {@code ThreadingService.resolveSubjectFallbackParent} are the
     * false-positive guards. {@code selfId} excludes the arriving row itself (it is
     * already persisted when threading runs).
     */
    @Query("SELECT m FROM MessageEntity m "
            + "WHERE m.account.id = :accId AND m.subjectNorm = :norm AND m.threadId IS NOT NULL "
            + "AND m.id <> :selfId AND m.receivedAt BETWEEN :from AND :to " + "ORDER BY m.receivedAt DESC, m.id DESC")
    List<MessageEntity> findNewestThreadedBySubjectNorm(@Param("accId") Long accountId, @Param("norm") String norm,
            @Param("selfId") Long selfId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            Pageable pageable);

    /**
     * Reconciliation lookup — find orphan thread ids that should merge into the
     * thread of a freshly-arrived message identified by its own {@code messageId}.
     * A thread qualifies when one of its rows either is rooted at {@code messageId}
     * (a cross-folder duplicate of the same root, e.g. Gmail INBOX + All Mail) or
     * directly replies to it via {@code In-Reply-To} (a child that arrived before
     * its parent — the canonical late-arriving-parent case). Both predicate columns
     * are indexed ({@code idx_messages_account_thread_root},
     * {@code idx_messages_account_in_reply_to}) so the lookup stays cheap on every
     * arrival during a bulk sync.
     * <p>
     * A child that links to {@code messageId} only through {@code References} (no
     * {@code In-Reply-To}) is <em>not</em> matched here — a token match inside the
     * free-text references column is unindexable and would turn bulk sync into an
     * O(n^2) scan. That case is covered separately by
     * {@link MessageReferenceRepository#findOrphanThreadIdsReferencing} against the
     * normalized {@code message_reference} index;
     * {@code ThreadingService.reconcileLateArrivingParent} merges the two result
     * sets.
     */
    @Query("SELECT DISTINCT m.threadId FROM MessageEntity m " + "WHERE m.account.id = :accId "
            + "AND (m.threadRootMessageId = :messageId OR m.inReplyTo = :messageId) " + "AND m.threadId IS NOT NULL "
            + "AND m.threadId <> :excludeThreadId")
    List<String> findMergeableOrphanThreadIds(@Param("accId") Long accountId, @Param("messageId") String messageId,
            @Param("excludeThreadId") String excludeThreadId);

    /**
     * Reverse subject-fallback lookup — every member of the threads a
     * freshly-arrived message might absorb by subject, so
     * {@code ThreadingService.reconcileLateArrivingParent} can decide per thread.
     *
     * <p>
     * The inner select finds the threads: they hold a headerless message with the
     * same normalized subject inside the fallback window and are not the arriving
     * message's own thread. The outer select then returns <em>all</em> their
     * members — the decision is "may this whole thread be absorbed", and that can
     * only be answered by looking at every member, not just the one that matched
     * the subject.
     *
     * <p>
     * Each row is {@code [threadId, subject, inReplyTo, references]}. The
     * reply-prefix test that guards the absorption lives in
     * {@code SubjectNormalizer.hasConversationMarker}, which is Java-side, hence
     * the raw subject rather than a SQL predicate.
     */
    @Query("SELECT m.threadId, m.subject, m.inReplyTo, m.references FROM MessageEntity m "
            + "WHERE m.account.id = :accId AND m.threadId IN (" + "  SELECT DISTINCT c.threadId FROM MessageEntity c "
            + "  WHERE c.account.id = :accId AND c.subjectNorm = :norm AND c.threadId IS NOT NULL "
            + "  AND c.threadId <> :excludeThreadId AND c.inReplyTo IS NULL AND c.references IS NULL "
            + "  AND c.receivedAt BETWEEN :from AND :to)")
    List<Object[]> findSubjectFallbackOrphanCandidates(@Param("accId") Long accountId, @Param("norm") String norm,
            @Param("excludeThreadId") String excludeThreadId, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Bulk-update thread membership for orphan reconciliation. Used when an
     * incoming message is discovered to be the missing root of one or more orphan
     * threads — every member of those threads moves under the new
     * {@code newThreadId} and is re-rooted at {@code newRootMessageId}.
     */
    @Modifying
    @Transactional
    @Query("UPDATE MessageEntity m " + "SET m.threadId = :newThreadId, m.threadRootMessageId = :newRootMessageId "
            + "WHERE m.account.id = :accId AND m.threadId IN :oldThreadIds")
    int reassignThreads(@Param("accId") Long accountId, @Param("oldThreadIds") List<String> oldThreadIds,
            @Param("newThreadId") String newThreadId, @Param("newRootMessageId") @Nullable String newRootMessageId);

    /**
     * One backfill batch — the oldest unthreaded messages of an account in
     * ascending {@code receivedAt} order, used by
     * {@link org.voxrox.mailbackend.feature.mail.service.ThreadingBackfillService}.
     * Paged on purpose: entities carry the {@code @Lob} body, so loading the whole
     * unthreaded set at once does not fit the 384m heap on a populated account.
     * Re-querying advances naturally — assignThread always sets {@code thread_id},
     * so processed rows drop out of the predicate.
     */
    @Query("SELECT m FROM MessageEntity m " + "WHERE m.account.id = :accId AND m.threadId IS NULL "
            + "ORDER BY m.receivedAt ASC, m.id ASC")
    List<MessageEntity> findUnthreadedByAccountOrderByReceivedAt(@Param("accId") Long accountId, Pageable pageable);

    /**
     * Upfront count for the backfill's start log/audit entry — the batched loop
     * itself never knows the total.
     */
    @Query("SELECT COUNT(m) FROM MessageEntity m WHERE m.account.id = :accId AND m.threadId IS NULL")
    long countUnthreadedByAccount(@Param("accId") Long accountId);

    /**
     * One References-index backfill batch — messages of an account that carry a
     * References header but have no {@code message_reference} rows yet, ordered by
     * id ascending after {@code afterId}. Used by
     * {@link org.voxrox.mailbackend.feature.mail.service.ThreadingBackfillService}
     * to populate the index for rows that predate this feature (rows are otherwise
     * indexed inline by {@code ThreadingService.assignThread} at persist).
     *
     * <p>
     * Cursor by id, not the {@code NOT EXISTS} predicate alone: a References header
     * that tokenizes to nothing (whitespace only) yields no index rows and would
     * otherwise re-match forever. Advancing {@code afterId} past every processed
     * row guarantees the pass terminates in one sweep. A native projection keeps
     * the {@code @Lob} body out of the batch.
     */
    @Query(value = "SELECT m.id AS id, m.reply_references AS refs FROM messages m "
            + "WHERE m.account_id = :accId AND m.reply_references IS NOT NULL AND m.id > :afterId "
            + "AND NOT EXISTS (SELECT 1 FROM message_reference r WHERE r.message_id = m.id) "
            + "ORDER BY m.id ASC LIMIT :batch", nativeQuery = true)
    List<MessageReferenceBackfillRow> findMessagesNeedingReferenceIndex(@Param("accId") Long accountId,
            @Param("afterId") Long afterId, @Param("batch") int batch);

    /**
     * Targeted write for the subject-norm backfill — sets the norm of one row
     * without loading the entity (and its {@code @Lob} body) into the persistence
     * context. Inline assignment for new rows happens on the managed entity in
     * {@code ThreadingService.assignThread} instead.
     */
    @Modifying
    @Query("UPDATE MessageEntity m SET m.subjectNorm = :norm WHERE m.id = :id")
    void updateSubjectNorm(@Param("id") Long id, @Param("norm") String norm);

    /**
     * One subject-norm backfill batch — rows that predate the {@code subject_norm}
     * column (non-null subject, null norm), ordered by id ascending after
     * {@code afterId}. Rows persisted after the column shipped are normalized
     * inline by {@code ThreadingService.assignThread}. A NULL norm means "not
     * processed"; a subject that carries no grouping key is stored as the empty
     * sentinel instead, so every visited row drops out of this predicate (see
     * {@code ThreadingBackfillService.backfillSubjectNorms}).
     */
    @Query(value = "SELECT m.id AS id, m.subject AS subject FROM messages m "
            + "WHERE m.account_id = :accId AND m.subject IS NOT NULL AND m.subject_norm IS NULL AND m.id > :afterId "
            + "ORDER BY m.id ASC LIMIT :batch", nativeQuery = true)
    List<SubjectNormBackfillRow> findMessagesNeedingSubjectNorm(@Param("accId") Long accountId,
            @Param("afterId") Long afterId, @Param("batch") int batch);

    /**
     * One correspondent-backfill batch — the address headers of every message of
     * the account, ordered by id ascending after {@code afterId}.
     *
     * <p>
     * No "needs processing" predicate, unlike the other backfill queries: the
     * harvest writes to a different table, so nothing on the message row records
     * that it was visited. The pass is instead gated as a whole on the
     * correspondent table being empty for the account
     * ({@code CorrespondentBackfillService}) — re-running it over an already
     * populated cache would double every counter.
     */
    @Query("SELECT new org.voxrox.mailbackend.feature.mail.repository.CorrespondentBackfillRow("
            + "m.id, m.folderName, m.sender, m.recipientsTo, m.recipientsCc, m.recipientsBcc, m.receivedAt) "
            + "FROM MessageEntity m WHERE m.account.id = :accId AND m.id > :afterId AND m.id <= :maxId "
            + "ORDER BY m.id ASC")
    List<CorrespondentBackfillRow> findMessagesForCorrespondentBackfill(@Param("accId") Long accountId,
            @Param("afterId") Long afterId, @Param("maxId") Long maxId, Pageable pageable);

    /**
     * Highest message id of the account, or {@code null} on an empty mailbox.
     *
     * <p>
     * The correspondent backfill pins this before it starts and never walks past
     * it. Without the ceiling the pass would sweep straight into messages the sync
     * persisted <em>while it was running</em> — and
     * {@code MessageDownloader.saveMessagesBatchAtomic} has already harvested those
     * inline, so each one would be counted a second time and skew the ranking the
     * counters exist to produce.
     */
    @Query("SELECT MAX(m.id) FROM MessageEntity m WHERE m.account.id = :accId")
    @Nullable
    Long findMaxMessageIdByAccount(@Param("accId") Long accountId);

    /**
     * Cross-folder conversation sizes for the threads on one page of the
     * conversation-grouped listing — the {@code messageCount} used for every folder
     * except Trash/Junk/Drafts/Sent. The page itself stays the folder-scoped
     * {@link #findConversationRepresentatives}; only the size is recomputed here,
     * over the whole account minus {@code :excludedFolders} (the account's
     * trash/junk/drafts/sent names, so deleted, junked, still-unsent and
     * self-authored messages never surface in a received conversation).
     * <p>
     * Split into a second query on purpose. Doing it in one window pass means
     * partitioning every row of the account before the page can be cut — no index
     * covers {@code PARTITION BY}, so a 10k-message account materializes and sorts
     * in full on every page load and every {@code sync_completed} refetch. Here the
     * {@code IN} list holds at most {@code size} thread ids and rides
     * {@code idx_messages_account_thread}.
     * <p>
     * {@code COUNT(DISTINCT …)} collapses copies of one mail stored in several
     * folders: Gmail-style providers keep the same {@code message_id} and
     * {@code thread_id} in INBOX and All Mail (see
     * {@link #findByAccountIdAndMessageId}), and a plain {@code COUNT} would report
     * every such thread at double length. A row without a Message-ID falls back to
     * its own stable id, so it never merges with anything.
     * <p>
     * Each row is {@code [threadId, messageCount]}; same boxed-numeric caveat as
     * {@link #findConversationRepresentatives}. Threads with no member outside the
     * excluded folders simply do not come back — the caller keeps the folder-scoped
     * count for those. {@code :excludedFolders} must never be empty; callers pad it
     * with a sentinel (empty string, which is never a folder name) so the
     * {@code NOT IN} stays well-formed.
     */
    @Query("SELECT m.threadId, COUNT(DISTINCT COALESCE(m.messageId, m.stableId)) FROM MessageEntity m "
            + "WHERE m.account.id = :accId AND m.threadId IN :threadIds "
            + "AND m.folderName NOT IN :excludedFolders GROUP BY m.threadId")
    List<Object[]> countCrossFolderConversationSizes(@Param("accId") Long accountId,
            @Param("threadIds") List<String> threadIds, @Param("excludedFolders") List<String> excludedFolders);
}
