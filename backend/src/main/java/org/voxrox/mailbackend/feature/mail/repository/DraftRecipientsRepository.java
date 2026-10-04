package org.voxrox.mailbackend.feature.mail.repository;

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
     * Drops the account's entries older than its newest {@code keep}: drafts
     * deleted somewhere this client does not see, and a draft the server hides.
     * Only the user's own saves add entries, and each save replaces the revision
     * before it, so the account's entries grow by drafts, not by saves, and only
     * the user's further work can push one out. An age limit, the rule until
     * IMAP/SMTP audit 1.29, was the server's to outlast: it hid a draft for a week
     * and presented it altered once the entry was gone (B1-5). Ties at the boundary
     * are kept.
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM draft_recipients
            WHERE account_id = :accountId
              AND saved_at < (SELECT saved_at FROM draft_recipients WHERE account_id = :accountId
                              ORDER BY saved_at DESC LIMIT 1 OFFSET :keep - 1)
            """, nativeQuery = true)
    int deleteAllButNewest(@Param("accountId") Long accountId, @Param("keep") int keep);
}
