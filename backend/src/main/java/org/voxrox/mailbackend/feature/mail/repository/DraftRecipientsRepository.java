package org.voxrox.mailbackend.feature.mail.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.mail.entity.DraftRecipientsEntity;

@Repository
public interface DraftRecipientsRepository extends JpaRepository<DraftRecipientsEntity, DraftRecipientsEntity.Key> {

    /**
     * Writes an entry, numbered one past the largest {@code saved_seq} in the
     * table. A plain INSERT whose transaction begins with it: {@code save} on an
     * assigned key is a merge, a SELECT and then the INSERT in one transaction, and
     * SQLite refuses that read's upgrade to a write at once — {@code SQLITE_BUSY}
     * or {@code SQLITE_BUSY_SNAPSHOT}, without waiting out {@code busy_timeout} —
     * when another connection writes between them, which left the revision without
     * an entry (B1-5, reopened at 1.41). Starting with the write, it waits for the
     * lock like any other writer. The number is taken inside the same statement, so
     * it cannot collide with a concurrent insert.
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO draft_recipients (account_id, message_id, stable_id, chain_id, recipients_to,
                                          recipients_cc, recipients_bcc, saved_at, saved_seq)
            VALUES (:accountId, :messageId, :stableId, :chainId, :recipientsTo, :recipientsCc,
                    :recipientsBcc, :savedAt,
                    (SELECT COALESCE(MAX(saved_seq), 0) + 1 FROM draft_recipients))
            """, nativeQuery = true)
    int insertEntry(@Param("accountId") Long accountId, @Param("messageId") String messageId,
            @Param("stableId") String stableId, @Param("chainId") String chainId,
            @Param("recipientsTo") @Nullable String recipientsTo, @Param("recipientsCc") @Nullable String recipientsCc,
            @Param("recipientsBcc") @Nullable String recipientsBcc, @Param("savedAt") LocalDateTime savedAt);

    /**
     * Drops the account's current entries — those no later save of this client has
     * superseded — older than its newest {@code keep}: drafts deleted somewhere
     * this client does not see, and a draft the server hides. Only a draft the user
     * starts adds a current entry, since a stored save sets aside every earlier
     * revision of its draft ({@link #supersedeEarlierInChain}) and a failed one
     * sets aside its own ({@link #markSuperseded}), whatever the server answers, so
     * these grow by drafts and not by saves, and only the user's further drafts can
     * push one out. An age limit, the rule until IMAP/SMTP audit 1.29, was the
     * server's to outlast (B1-5); a single count over every entry, the rule until
     * 1.36, was the server's to fill, since a save retired the revision it replaced
     * only when the server let it find and delete that revision; and until 1.40 a
     * save retired only the revision it named, which after a rejected save was the
     * rejected one. Newest by {@code saved_seq}, the order the entries were written
     * in.
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM draft_recipients
            WHERE account_id = :accountId AND superseded_at IS NULL
              AND saved_seq < (SELECT saved_seq FROM draft_recipients
                               WHERE account_id = :accountId AND superseded_at IS NULL
                               ORDER BY saved_seq DESC LIMIT 1 OFFSET :keep - 1)
            """, nativeQuery = true)
    int deleteCurrentButNewest(@Param("accountId") Long accountId, @Param("keep") int keep);

    /**
     * Drops the account's superseded entries older than its newest {@code keep}.
     * They grow by saves: one for every revision a server kept after the save that
     * replaced it, by refusing the delete or by withholding what the save needed to
     * address it. Kept apart from the current ones so that autosaves push out only
     * each other. Newest by {@code saved_seq}.
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM draft_recipients
            WHERE account_id = :accountId AND superseded_at IS NOT NULL
              AND saved_seq < (SELECT saved_seq FROM draft_recipients
                               WHERE account_id = :accountId AND superseded_at IS NOT NULL
                               ORDER BY saved_seq DESC LIMIT 1 OFFSET :keep - 1)
            """, nativeQuery = true)
    int deleteSupersededButNewest(@Param("accountId") Long accountId, @Param("keep") int keep);

    /**
     * The chain of the account's entry minted under {@code stableId}: what a save
     * naming that revision as the one it replaces joins. Empty when there is no
     * such entry — a draft composed in another client, or one the server presents
     * under a stableId of its own.
     */
    @Query(value = """
            SELECT chain_id FROM draft_recipients
            WHERE account_id = :accountId AND stable_id = :stableId
            LIMIT 1
            """, nativeQuery = true)
    Optional<String> findChainId(@Param("accountId") Long accountId, @Param("stableId") String stableId);

    /**
     * Sets aside the account's current entries of {@code chainId} written before
     * the stored revision {@code messageId}, which stays current: that revision
     * replaces them all, whichever of them its save named. A revision of the chain
     * written after it is left alone, so a slow save stored late does not set aside
     * the newer one. Before and after by {@code saved_seq}, not by the clock: after
     * a clock step back the earlier revision used to stay current until a save
     * passed its time.
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE draft_recipients SET superseded_at = :at
            WHERE account_id = :accountId AND chain_id = :chainId AND superseded_at IS NULL
              AND saved_seq < (SELECT saved_seq FROM draft_recipients
                               WHERE account_id = :accountId AND message_id = :messageId)
            """, nativeQuery = true)
    int supersedeEarlierInChain(@Param("accountId") Long accountId, @Param("chainId") String chainId,
            @Param("messageId") String messageId, @Param("at") LocalDateTime at);

    /**
     * Marks the account's entry minted under {@code stableId} superseded, if it is
     * current: the revision's own save failed. Decided by what the client names,
     * not by the server's answers.
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE draft_recipients SET superseded_at = :at
            WHERE account_id = :accountId AND stable_id = :stableId AND superseded_at IS NULL
            """, nativeQuery = true)
    int markSuperseded(@Param("accountId") Long accountId, @Param("stableId") String stableId,
            @Param("at") LocalDateTime at);
}
