package org.voxrox.mailbackend.feature.mail.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.mail.entity.MessageReferenceEntity;

/**
 * Access to the normalized {@code message_reference} index (V2). Used only by
 * threading: population is write-once per message, and the reconciliation
 * lookup finds orphan children that reference a freshly-arrived Message-ID.
 * Native queries throughout — the table joins to {@code messages} by row id,
 * not a JPA association, so there is no entity graph to traverse.
 */
public interface MessageReferenceRepository extends JpaRepository<MessageReferenceEntity, Long> {

    /**
     * Removes any existing index rows for a message row before it is re-indexed.
     * Cheap via {@code idx_message_reference_message}; a no-op for a freshly
     * persisted message and the idempotency guard for a backfill re-run.
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM message_reference WHERE message_id = :messageId", nativeQuery = true)
    void deleteByMessageId(@Param("messageId") Long messageId);

    /**
     * Reconciliation lookup — the References-only counterpart of
     * {@code MessageRepository.findMergeableOrphanThreadIds}. Finds the distinct
     * thread ids of orphan messages that reference {@code messageId} (a child that
     * linked to this now-arrived ancestor only through {@code References}),
     * excluding the arriving message's own thread. Indexed on
     * {@code (account_id, referenced_message_id)}, so it stays cheap per arrival
     * during a bulk sync.
     */
    @Query(value = "SELECT DISTINCT m.thread_id FROM message_reference r " + "JOIN messages m ON m.id = r.message_id "
            + "WHERE r.account_id = :accId AND r.referenced_message_id = :messageId "
            + "AND m.thread_id IS NOT NULL AND m.thread_id <> :excludeThreadId", nativeQuery = true)
    List<String> findOrphanThreadIdsReferencing(@Param("accId") Long accountId, @Param("messageId") String messageId,
            @Param("excludeThreadId") String excludeThreadId);
}
