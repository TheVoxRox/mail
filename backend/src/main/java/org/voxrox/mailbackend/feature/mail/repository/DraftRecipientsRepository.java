package org.voxrox.mailbackend.feature.mail.repository;

import java.time.LocalDateTime;
import java.util.List;
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
     * Drops every entry of the account's drafts older than its newest {@code keep}
     * besides {@code chainId}, the draft being saved. A draft is a chain, as old as
     * its newest entry, and goes whole: no save of another draft pushes out one of
     * its revisions, only newer drafts, and only the user starts one. What they
     * lose are drafts deleted somewhere this client does not see, sent ones, whose
     * entries stay set aside whatever the server answers to their delete, and a
     * draft the server hides.
     * <p>
     * Whole because the server chooses which revision of a draft to present. Until
     * IMAP/SMTP audit 1.54 the entries were bounded by account in two kinds, heads
     * (a draft's current entry and its newest) and the revisions a later save of
     * their draft had followed, and some thousand autosaves of another draft pushed
     * out the revision before a draft's newest, which a server keeps by withholding
     * APPENDUID; it then presented that one in place of the newest (B1-5, reopened
     * at 1.53). Earlier rules were each the server's to defeat: an age limit until
     * 1.29, a single count over every entry until 1.36, a save retiring only the
     * revision it named until 1.40, a current place from the save's acceptance
     * until 1.47, and a draft's newest bounded with every other draft's saves until
     * 1.50. Newest by {@code saved_seq}, the order the entries were written in,
     * which is unique.
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM draft_recipients
            WHERE account_id = :accountId AND chain_id <> :chainId
              AND chain_id NOT IN (SELECT chain_id FROM draft_recipients
                                   WHERE account_id = :accountId AND chain_id <> :chainId
                                   GROUP BY chain_id
                                   ORDER BY MAX(saved_seq) DESC LIMIT :keep)
            """, nativeQuery = true)
    int deleteDraftsButNewest(@Param("accountId") Long accountId, @Param("chainId") String chainId,
            @Param("keep") int keep);

    /**
     * Drops the followed revisions of draft {@code chainId} older than its newest
     * {@code keep}: entries a later save of the same draft has followed and that
     * are not current — set aside, or accepted and never stored. The draft's
     * current entry and its newest are never among them. They grow by the draft's
     * own saves, one per save, since a replaced revision keeps its entry whatever
     * the server answers to its delete, so only that draft's later saves push one
     * out, and the revision a server can then present is that many saves older than
     * the one the user last saw. Until IMAP/SMTP audit 1.58 a save dropped the
     * entry of the revision it replaced once the server answered the delete, and a
     * server that claimed the delete and kept the revision presented it after one
     * autosave (B1-5, reopened at 1.57). Newest by {@code saved_seq}.
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM draft_recipients
            WHERE account_id = :accountId AND chain_id = :chainId
              AND (stored_at IS NULL OR superseded_at IS NOT NULL)
              AND saved_seq < (SELECT MAX(saved_seq) FROM draft_recipients
                               WHERE account_id = :accountId AND chain_id = :chainId)
              AND saved_seq < (SELECT saved_seq FROM draft_recipients
                               WHERE account_id = :accountId AND chain_id = :chainId
                                 AND (stored_at IS NULL OR superseded_at IS NOT NULL)
                                 AND saved_seq < (SELECT MAX(saved_seq) FROM draft_recipients
                                                  WHERE account_id = :accountId AND chain_id = :chainId)
                               ORDER BY saved_seq DESC LIMIT 1 OFFSET :keep - 1)
            """, nativeQuery = true)
    int deleteRevisionsButNewest(@Param("accountId") Long accountId, @Param("chainId") String chainId,
            @Param("keep") int keep);

    /**
     * Sets aside the account's entry for {@code messageId}, unless it is set aside
     * already: a revision this client has just sent is no longer its draft's
     * current one. Set aside rather than dropped, since a server can claim the
     * delete that follows the send and keep the revision, and present it again; the
     * entry then stays with its draft like any other revision set aside (B1-5,
     * reopened at 1.57). A plain UPDATE for the reason {@link #insertEntry} is a
     * plain INSERT: a read and then a write in one transaction is refused at once
     * when another connection writes between them (measured for {@code deleteById}
     * by the pass over 1.47); starting with the write, it waits for the lock like
     * any other writer.
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE draft_recipients SET superseded_at = :at
            WHERE account_id = :accountId AND message_id = :messageId AND superseded_at IS NULL
            """, nativeQuery = true)
    int setAside(@Param("accountId") Long accountId, @Param("messageId") String messageId,
            @Param("at") LocalDateTime at);

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
     * The stableIds of the account's revisions of the same draft as
     * {@code stableId}, its chain, written before it, newest first: the copies a
     * stored save or a send leaves the server one too many of when the revision the
     * client names is not the last one the server stored. Empty when
     * {@code stableId} has no entry here. Bounded by the entries a chain keeps.
     */
    @Query(value = """
            SELECT stable_id FROM draft_recipients
            WHERE account_id = :accountId AND stable_id IS NOT NULL
              AND chain_id = (SELECT chain_id FROM draft_recipients
                              WHERE account_id = :accountId AND stable_id = :stableId LIMIT 1)
              AND saved_seq < (SELECT saved_seq FROM draft_recipients
                               WHERE account_id = :accountId AND stable_id = :stableId LIMIT 1)
            ORDER BY saved_seq DESC
            """, nativeQuery = true)
    List<String> findEarlierRevisions(@Param("accountId") Long accountId, @Param("stableId") String stableId);

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
