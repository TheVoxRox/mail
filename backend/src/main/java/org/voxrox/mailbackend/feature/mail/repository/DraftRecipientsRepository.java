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
     * Drops the account's heads older than its newest {@code keep}. A head is an
     * entry its draft is current at — a revision the server stored that no later
     * stored save of this client has set aside — or its draft's newest, whatever
     * its state: a draft holds two at most, so these grow by drafts and not by
     * saves, and only the user's further drafts can push one out. What they lose
     * are drafts deleted somewhere this client does not see, and a draft the server
     * hides.
     * <p>
     * The newest counts whatever its state because a draft's last revision is not
     * always current: while its save waits for the server, when the server answers
     * its APPEND NO but keeps it, or when the statement that makes it current
     * fails. Bounded with the saves, as until IMAP/SMTP audit 1.50, it was pushed
     * out by some thousand autosaves of another draft, and the draft kept none
     * (B1-5, reopened at 1.48). Earlier rules were each the server's to defeat: an
     * age limit until 1.29, a single count over every entry until 1.36, a save
     * retiring only the revision it named until 1.40, and a current place from the
     * save's acceptance until 1.47.
     * <p>
     * A draft's newest is the entry of its chain with the largest
     * {@code saved_seq}, found once for the whole statement rather than per row,
     * which on a draft with a thousand set-aside revisions costs a quadratic scan
     * at every save. Newest by {@code saved_seq}, the order the entries were
     * written in, which is unique.
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM draft_recipients
            WHERE account_id = :accountId
              AND ((stored_at IS NOT NULL AND superseded_at IS NULL)
                   OR saved_seq IN (SELECT MAX(saved_seq) FROM draft_recipients
                                    WHERE account_id = :accountId GROUP BY chain_id))
              AND saved_seq < (SELECT saved_seq FROM draft_recipients
                               WHERE account_id = :accountId
                                 AND ((stored_at IS NOT NULL AND superseded_at IS NULL)
                                      OR saved_seq IN (SELECT MAX(saved_seq) FROM draft_recipients
                                                       WHERE account_id = :accountId GROUP BY chain_id))
                               ORDER BY saved_seq DESC LIMIT 1 OFFSET :keep - 1)
            """, nativeQuery = true)
    int deleteHeadsButNewest(@Param("accountId") Long accountId, @Param("keep") int keep);

    /**
     * Drops the account's followed entries older than its newest {@code keep}: the
     * entries that are not heads ({@link #deleteHeadsButNewest}), so revisions a
     * later save of the same draft has followed and that are not current — set
     * aside, or accepted and not stored. They grow by saves: one for every revision
     * a server kept after the save that replaced it, by refusing the delete or by
     * withholding what the save needed to address it, and one for every save that
     * was followed before it stored anything. Kept apart from the heads so that
     * autosaves push out only each other. Newest by {@code saved_seq}.
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM draft_recipients
            WHERE account_id = :accountId
              AND (stored_at IS NULL OR superseded_at IS NOT NULL)
              AND saved_seq NOT IN (SELECT MAX(saved_seq) FROM draft_recipients
                                    WHERE account_id = :accountId GROUP BY chain_id)
              AND saved_seq < (SELECT saved_seq FROM draft_recipients
                               WHERE account_id = :accountId
                                 AND (stored_at IS NULL OR superseded_at IS NOT NULL)
                                 AND saved_seq NOT IN (SELECT MAX(saved_seq) FROM draft_recipients
                                                       WHERE account_id = :accountId GROUP BY chain_id)
                               ORDER BY saved_seq DESC LIMIT 1 OFFSET :keep - 1)
            """, nativeQuery = true)
    int deleteFollowedButNewest(@Param("accountId") Long accountId, @Param("keep") int keep);

    /**
     * Drops the account's entry for {@code messageId}, if there is one. A plain
     * DELETE for the reason {@link #insertEntry} is a plain INSERT:
     * {@code deleteById} reads the entry and then writes in one transaction, and
     * SQLite refuses that read's upgrade to a write at once when another connection
     * writes between them, which left the entry in place (measured by the pass over
     * 1.47); starting with the write, it waits for the lock like any other writer.
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM draft_recipients WHERE account_id = :accountId AND message_id = :messageId
            """, nativeQuery = true)
    int deleteEntry(@Param("accountId") Long accountId, @Param("messageId") String messageId);

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
     * Makes the account's entry for the stored revision {@code messageId} its
     * draft's current one, and sets aside every entry of {@code chainId} written
     * before it that is not set aside already — current, or accepted and not stored
     * — whichever of them its save named. One statement, so the draft never holds
     * two current entries. An earlier revision whose save is still waiting is set
     * aside before it is stored, and stays so when it is: an entry already set
     * aside by a later stored revision is left alone, its own included, and so is a
     * revision of the chain written after this one. Before and after by
     * {@code saved_seq}, not by the clock: after a clock step back the earlier
     * revision used to stay current until a save passed its time. Nothing changes
     * when the revision has no entry here.
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE draft_recipients
            SET stored_at     = CASE WHEN message_id = :messageId THEN :at ELSE stored_at END,
                superseded_at = CASE WHEN message_id = :messageId THEN superseded_at ELSE :at END
            WHERE account_id = :accountId AND chain_id = :chainId AND superseded_at IS NULL
              AND saved_seq <= (SELECT saved_seq FROM draft_recipients
                                WHERE account_id = :accountId AND message_id = :messageId)
            """, nativeQuery = true)
    int markStored(@Param("accountId") Long accountId, @Param("chainId") String chainId,
            @Param("messageId") String messageId, @Param("at") LocalDateTime at);
}
