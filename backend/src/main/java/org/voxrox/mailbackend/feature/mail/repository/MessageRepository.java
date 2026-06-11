package org.voxrox.mailbackend.feature.mail.repository;

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

    Optional<MessageEntity> findByStableId(String stableId);

    /**
     * Loads a message including attachments in a single SQL query (JOIN FETCH).
     */
    @Query("SELECT m FROM MessageEntity m LEFT JOIN FETCH m.attachments WHERE m.stableId = :stableId")
    Optional<MessageEntity> findByStableIdWithAttachments(@Param("stableId") String stableId);

    /**
     * Returns paginated message summaries (DTO) for the given folder. Uses the
     * DTO_PATH constant, which is a valid constant expression for the annotation.
     */
    @Query("SELECT new " + DTO_PATH + "("
            + "m.id, m.stableId, m.folderName, m.subject, m.sender, m.recipientsTo, m.receivedAt, "
            + "m.seen, m.flagged, m.answered, m.hasAttachments, m.threadId, m.uid) " + "FROM MessageEntity m "
            + "WHERE m.account.id = :accId AND m.folderName = :folder " + "ORDER BY m.receivedAt DESC")
    Page<MailSummaryResponse> findSummariesByAccountAndFolder(@Param("accId") Long accId,
            @Param("folder") String folderName, Pageable pageable);

    /**
     * SQLite FTS5 search via the 'message_search' virtual table.
     */
    @Query(value = """
            SELECT * FROM messages
            WHERE id IN (SELECT rowid FROM message_search WHERE message_search MATCH :query)
            ORDER BY received_at DESC
            """, countQuery = "SELECT count(*) FROM message_search WHERE message_search MATCH :query", nativeQuery = true)
    Page<MessageEntity> fullTextSearchSummaries(@Param("query") String query, Pageable pageable);

    @Query(value = """
            SELECT * FROM messages
            WHERE account_id = :accountId
              AND id IN (SELECT rowid FROM message_search WHERE message_search MATCH :query)
            ORDER BY received_at DESC
            """, countQuery = """
            SELECT count(*) FROM messages
            WHERE account_id = :accountId
              AND id IN (SELECT rowid FROM message_search WHERE message_search MATCH :query)
            """, nativeQuery = true)
    Page<MessageEntity> fullTextSearchSummaries(@Param("query") String query, @Param("accountId") Long accountId,
            Pageable pageable);

    Page<MessageEntity> findByAccountIdAndFolderName(Long accountId, String folderName, Pageable pageable);

    long countByAccountIdAndFolderName(Long accountId, String folderName);

    /**
     * Number of messages in the folder that do not yet have {@code seen=true}. Used
     * for {@code unreadCount} in the folder listing; reflects the state in the
     * local DB, so it matches what the user sees in the UI (IMAP flags are
     * propagated after sync_completed).
     */
    long countByAccountIdAndFolderNameAndSeenFalse(Long accountId, String folderName);

    @Query("SELECT m.uid FROM MessageEntity m WHERE m.account.id = :accId AND m.folderName = :folder ORDER BY m.uid ASC")
    List<Long> findUidsByAccountAndFolder(@Param("accId") Long accId, @Param("folder") String folderName);

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
     * All messages of a thread within an account, ordered by position. Drives the
     * thread detail endpoint.
     */
    @Query("SELECT m FROM MessageEntity m " + "WHERE m.account.id = :accId AND m.threadId = :threadId "
            + "ORDER BY m.threadPosition ASC, m.id ASC")
    List<MessageEntity> findByAccountIdAndThreadId(@Param("accId") Long accountId, @Param("threadId") String threadId);

    /**
     * Max thread_position within a thread — used by
     * {@link org.voxrox.mailbackend.feature.mail.service.ThreadingService} when
     * appending a new message to an existing thread.
     */
    @Query("SELECT COALESCE(MAX(m.threadPosition), 0) FROM MessageEntity m "
            + "WHERE m.account.id = :accId AND m.threadId = :threadId")
    int findMaxThreadPosition(@Param("accId") Long accountId, @Param("threadId") String threadId);

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
     * {@code In-Reply-To}) is deliberately not back-reconciled here: a token match
     * inside the free-text references column is unindexable and would turn bulk
     * sync into an O(n^2) scan. The forward {@code References} walk in
     * {@link org.voxrox.mailbackend.feature.mail.service.ThreadingService} still
     * threads those messages when their ancestor is already present.
     */
    @Query("SELECT DISTINCT m.threadId FROM MessageEntity m " + "WHERE m.account.id = :accId "
            + "AND (m.threadRootMessageId = :messageId OR m.inReplyTo = :messageId) " + "AND m.threadId IS NOT NULL "
            + "AND m.threadId <> :excludeThreadId")
    List<String> findMergeableOrphanThreadIds(@Param("accId") Long accountId, @Param("messageId") String messageId,
            @Param("excludeThreadId") String excludeThreadId);

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
     * Streaming backfill — every message of an account in ascending
     * {@code receivedAt} order, used by
     * {@link org.voxrox.mailbackend.feature.mail.service.ThreadingBackfillService}.
     */
    @Query("SELECT m FROM MessageEntity m " + "WHERE m.account.id = :accId AND m.threadId IS NULL "
            + "ORDER BY m.receivedAt ASC, m.id ASC")
    List<MessageEntity> findUnthreadedByAccountOrderByReceivedAt(@Param("accId") Long accountId);
}
