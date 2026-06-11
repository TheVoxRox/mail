package org.voxrox.mailbackend.feature.mail.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;

@Repository
public interface FolderSyncStateRepository extends JpaRepository<FolderSyncStateEntity, Long> {

    Optional<FolderSyncStateEntity> findByAccountIdAndFolderName(Long accountId, String folderName);

    @Transactional
    void deleteByAccountIdAndFolderName(Long accountId, String folderName);

    boolean existsByAccountIdAndFolderName(Long accountId, String folderName);

    /**
     * Finds the technical folder name by role for the given account. Used instead
     * of hard-coded names when dealing with trash, sent, etc.
     */
    @Query("SELECT f.folderName FROM FolderSyncStateEntity f WHERE f.account.id = :accountId AND f.role = :role")
    Optional<String> findFolderNameByRole(@Param("accountId") Long accountId, @Param("role") FolderRole role);

    /**
     * For each account in {@code accountIds} returns the most recent
     * {@code lastSyncAt} across all its folders. A single aggregated SQL query —
     * independent of the number of accounts and folders. Used by
     * {@code SyncHealthIndicator}.
     * <p>
     * Result shape: {@code Object[]{ accountId (Long), maxLastSyncAt
     * (LocalDateTime) }}. Accounts that have no folder with a row where
     * {@code lastSyncAt != null} are absent from the result — the caller treats
     * them as "never synchronized".
     */
    @Query("SELECT s.account.id, MAX(s.lastSyncAt) FROM FolderSyncStateEntity s "
            + "WHERE s.account.id IN :accountIds AND s.lastSyncAt IS NOT NULL " + "GROUP BY s.account.id")
    List<Object[]> findMaxLastSyncAtByAccountIds(@Param("accountIds") Collection<Long> accountIds);

    /**
     * Targeted UPDATEs for fields that change incrementally within a single sync
     * cycle in independent transactions. Bypasses JPA merge() — that would fail on
     * a detached entity with an increasing DB version with
     * StaleObjectStateException. Cross-thread concurrency is handled by
     * {@code SyncLockManager} (per account), so the {@code @Version} guard is
     * unnecessary on these paths.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FolderSyncStateEntity s SET s.lastKnownUid = :uid WHERE s.id = :id")
    void updateLastKnownUid(@Param("id") Long id, @Param("uid") Long lastKnownUid);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FolderSyncStateEntity s SET s.uidValidity = :v WHERE s.id = :id")
    void updateUidValidity(@Param("id") Long id, @Param("v") Long uidValidity);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FolderSyncStateEntity s SET s.lastSyncAt = :t WHERE s.id = :id")
    void updateLastSyncAt(@Param("id") Long id, @Param("t") LocalDateTime lastSyncAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FolderSyncStateEntity s SET s.lastKnownModseq = :modseq WHERE s.id = :id")
    void updateLastKnownModseq(@Param("id") Long id, @Param("modseq") @Nullable Long modseq);

    /**
     * Resets the folder after a server-side UIDValidity change: new UIDValidity +
     * zero {@code lastKnownUid}. Atomic, a single UPDATE.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FolderSyncStateEntity s SET s.uidValidity = :v, s.lastKnownUid = 0, s.lastKnownModseq = NULL WHERE s.id = :id")
    void resetForUidValidityChange(@Param("id") Long id, @Param("v") Long uidValidity);
}
