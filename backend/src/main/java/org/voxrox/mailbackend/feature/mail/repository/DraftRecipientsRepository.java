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
public interface DraftRecipientsRepository extends JpaRepository<DraftRecipientsEntity, String> {

    /**
     * Drops the account's entries saved before {@code cutoff} whose draft has no
     * row: drafts deleted somewhere this client does not see, or that left the
     * server before a sync met them. An entry whose draft still has a row stays
     * however old it is — an unsent draft may wait for weeks, and the check needs
     * the entry for as long as the draft can be sent.
     */
    @Transactional
    @Modifying
    @Query("""
            DELETE FROM DraftRecipientsEntity d
            WHERE d.account.id = :accountId AND d.savedAt < :cutoff
              AND NOT EXISTS (SELECT 1 FROM MessageEntity m WHERE m.stableId = d.stableId)
            """)
    int deleteOrphansSavedBefore(@Param("accountId") Long accountId, @Param("cutoff") LocalDateTime cutoff);
}
