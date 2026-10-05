package org.voxrox.mailbackend.feature.mail.repository;

import java.time.LocalDateTime;

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
     * Drops the account's current entries — those no later save of this client has
     * superseded — older than its newest {@code keep}: drafts deleted somewhere
     * this client does not see, and a draft the server hides. Only a draft the user
     * starts adds a current entry, since each save marks the revision it replaces
     * superseded ({@link #markSuperseded}) whatever the server answers, so these
     * grow by drafts and not by saves, and only the user's further drafts can push
     * one out. An age limit, the rule until IMAP/SMTP audit 1.29, was the server's
     * to outlast (B1-5); a single count over every entry, the rule until 1.36, was
     * the server's to fill, since a save retired the revision it replaced only when
     * the server let it find and delete that revision. Ties at the boundary are
     * kept.
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM draft_recipients
            WHERE account_id = :accountId AND superseded_at IS NULL
              AND saved_at < (SELECT saved_at FROM draft_recipients
                              WHERE account_id = :accountId AND superseded_at IS NULL
                              ORDER BY saved_at DESC LIMIT 1 OFFSET :keep - 1)
            """, nativeQuery = true)
    int deleteCurrentButNewest(@Param("accountId") Long accountId, @Param("keep") int keep);

    /**
     * Drops the account's superseded entries older than its newest {@code keep}.
     * They grow by saves: one for every revision a server kept after the save that
     * replaced it, by refusing the delete or by withholding what the save needed to
     * address it. Kept apart from the current ones so that autosaves push out only
     * each other. Ties at the boundary are kept.
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM draft_recipients
            WHERE account_id = :accountId AND superseded_at IS NOT NULL
              AND saved_at < (SELECT saved_at FROM draft_recipients
                              WHERE account_id = :accountId AND superseded_at IS NOT NULL
                              ORDER BY saved_at DESC LIMIT 1 OFFSET :keep - 1)
            """, nativeQuery = true)
    int deleteSupersededButNewest(@Param("accountId") Long accountId, @Param("keep") int keep);

    /**
     * Marks the account's entry minted under {@code stableId} superseded, if it is
     * current: a later save of this client has replaced that revision. Decided by
     * what the client names, not by whether the server lets the save find or delete
     * the replaced revision.
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
