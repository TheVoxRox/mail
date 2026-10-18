package org.voxrox.mailbackend.feature.account.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorJson;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;

@Repository
public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    @Override
    @EntityGraph(attributePaths = {"credentials", "provider"})
    List<AccountEntity> findAll();

    default List<AccountEntity> findAllWithDetails() {
        return findAll();
    }

    @Transactional(readOnly = true)
    @EntityGraph(attributePaths = {"provider"})
    List<AccountEntity> findByActiveTrue();

    /**
     * Used by {@code MailSyncScheduler} — returns only accounts that make sense to
     * synchronize. Accounts with {@code requires_reauth=true} are in limbo after a
     * rejected OAuth refresh token and repeated sync would generate needless
     * traffic to the Google API and clutter the logs.
     */
    @Transactional(readOnly = true)
    @EntityGraph(attributePaths = {"provider"})
    List<AccountEntity> findByActiveTrueAndRequiresReauthFalse();

    /*
     * The three address lookups below ignore case without saying so in their names:
     * accounts.email is COLLATE NOCASE (V1__init.sql), and SQLite compares a column
     * with a bound value under the column's collation. A derived ...IgnoreCase
     * query would wrap both sides in upper() to the same effect.
     */
    @Transactional(readOnly = true)
    Optional<AccountEntity> findByEmail(String email);

    @Transactional(readOnly = true)
    boolean existsByEmail(String email);

    @Transactional(readOnly = true)
    boolean existsByEmailAndIdNot(String email, Long id);

    /**
     * Finds the account by the pair (oauth2_provider, external_id). The composite
     * key is required because identifiers across providers live in different
     * namespaces (Google {@code sub}, Microsoft {@code oid}, …) and could
     * hypothetically collide.
     */
    @Transactional(readOnly = true)
    @EntityGraph(attributePaths = {"provider"})
    Optional<AccountEntity> findByOauth2ProviderAndExternalId(String oauth2Provider, String externalId);

    @Transactional(readOnly = true)
    @Query("SELECT a.requiresReauth FROM AccountEntity a WHERE a.id = :id")
    Optional<Boolean> isRequiresReauth(@Param("id") Long id);

    /**
     * Standing error code of the account, read back after a sync pass to decide
     * whether the account's error state <em>changed</em>. The slot is written
     * through {@code @Modifying} queries that bypass the persistence context, so a
     * loaded {@link org.voxrox.mailbackend.feature.account.entity.AccountEntity}
     * still holds the pre-pass value — that is what makes the before/after
     * comparison possible without a version column.
     * <p>
     * An empty result means "no standing error" and, for a deleted account, the
     * same — both leave nothing to report, so the caller does not distinguish them.
     */
    @Transactional(readOnly = true)
    @Query("SELECT a.lastErrorCode FROM AccountEntity a WHERE a.id = :id")
    Optional<String> findLastErrorCode(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("UPDATE AccountEntity a SET a.lastError = :error, a.lastErrorCode = :code, "
            + "a.lastErrorArgs = :argsJson, a.lastSyncAt = :timestamp WHERE a.id = :id")
    void updateLastErrorFields(@Param("id") Long id, @Param("error") @Nullable String error,
            @Param("code") @Nullable String code, @Param("argsJson") @Nullable String argsJson,
            @Param("timestamp") LocalDateTime timestamp);

    default void updateLastError(Long id, @Nullable AccountLastError error, LocalDateTime timestamp) {
        if (error == null) {
            updateLastErrorFields(id, null, null, null, timestamp);
            return;
        }
        updateLastErrorFields(id, error.fallbackMessage(), error.code().name(),
                AccountLastErrorJson.write(error.args()), timestamp);
    }

    default void clearLastError(Long id, LocalDateTime timestamp) {
        updateLastErrorFields(id, null, null, null, timestamp);
    }

    /**
     * Clears {@code last_error} only when the standing error carries one of the
     * given codes. The slot is account-scoped and shared between the sync and the
     * send/draft pipelines — a successful SEND may clear a previous send failure,
     * but must not erase an unrelated standing SYNC error (the same masking class
     * as the per-folder clear fixed in {@code MailSyncService}). Unlike
     * {@link #clearLastError} this deliberately does not touch
     * {@code last_sync_at}: sending is not a sync.
     */
    @Modifying
    @Transactional
    @Query("UPDATE AccountEntity a SET a.lastError = null, a.lastErrorCode = null, a.lastErrorArgs = null "
            + "WHERE a.id = :id AND a.lastErrorCode IN :codes")
    void clearLastErrorIfCodeIn(@Param("id") Long id, @Param("codes") Collection<String> codes);

    /*
     * The crash guard's writes (SyncCrashGuard). Targeted UPDATEs for the same
     * reason as the last_error slot above: they run from sync threads, beside a
     * loaded entity that would otherwise overwrite them on its next save.
     */

    /** Accounts a previous backend process left with a sync running. */
    @Transactional(readOnly = true)
    List<AccountEntity> findBySyncStartedAtIsNotNull();

    @Modifying
    @Transactional
    @Query("UPDATE AccountEntity a SET a.syncStartedAt = :now WHERE a.id = :id")
    void markSyncStarted(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * The last running sync ended inside the process, so the crash count starts
     * over.
     */
    @Modifying
    @Transactional
    @Query("UPDATE AccountEntity a SET a.syncStartedAt = null, a.syncCrashes = 0 WHERE a.id = :id")
    void markSyncEnded(@Param("id") Long id);

    /** A previous process ended during a sync the guard does not count. */
    @Modifying
    @Transactional
    @Query("UPDATE AccountEntity a SET a.syncStartedAt = null WHERE a.id = :id")
    void clearSyncStarted(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("UPDATE AccountEntity a SET a.syncStartedAt = null, a.syncCrashes = :crashes, a.syncPaused = :paused "
            + "WHERE a.id = :id")
    void recordSyncCrash(@Param("id") Long id, @Param("crashes") int crashes, @Param("paused") boolean paused);

    @Modifying
    @Transactional
    @Query("UPDATE AccountEntity a SET a.syncPaused = false, a.syncCrashes = :crashes WHERE a.id = :id")
    void resumeSync(@Param("id") Long id, @Param("crashes") int crashes);

    /**
     * Atomic update of the reauth flag — written from async threads (token refresh
     * failure handler), so we avoid loading the entity.
     */
    @Modifying
    @Transactional
    @Query("UPDATE AccountEntity a SET a.requiresReauth = :value WHERE a.id = :id")
    void updateRequiresReauth(@Param("id") Long id, @Param("value") boolean value);
}
