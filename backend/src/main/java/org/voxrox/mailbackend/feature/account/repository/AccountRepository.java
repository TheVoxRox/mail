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
    List<AccountEntity> findByProviderId(Long providerId);

    @Transactional(readOnly = true)
    @Query("SELECT a.active FROM AccountEntity a WHERE a.id = :id")
    Optional<Boolean> isAccountActive(@Param("id") Long id);

    @Transactional(readOnly = true)
    @Query("SELECT a.requiresReauth FROM AccountEntity a WHERE a.id = :id")
    Optional<Boolean> isRequiresReauth(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("UPDATE AccountEntity a SET a.lastError = :error, a.lastErrorCode = :code, "
            + "a.lastErrorArgs = :argsJson, a.lastSyncAt = :timestamp WHERE a.id = :id")
    void updateLastErrorFields(@Param("id") Long id, @Param("error") @Nullable String error,
            @Param("code") @Nullable String code, @Param("argsJson") @Nullable String argsJson,
            @Param("timestamp") LocalDateTime timestamp);

    default void updateLastError(Long id, String error, LocalDateTime timestamp) {
        updateLastErrorFields(id, error, null, null, timestamp);
    }

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

    /**
     * Atomic update of the reauth flag — written from async threads (token refresh
     * failure handler), so we avoid loading the entity.
     */
    @Modifying
    @Transactional
    @Query("UPDATE AccountEntity a SET a.requiresReauth = :value WHERE a.id = :id")
    void updateRequiresReauth(@Param("id") Long id, @Param("value") boolean value);
}
