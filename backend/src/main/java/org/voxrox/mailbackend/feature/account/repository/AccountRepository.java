package org.voxrox.mailbackend.feature.account.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
    void updateLastErrorFields(@Param("id") Long id, @Param("error") String error, @Param("code") String code,
            @Param("argsJson") String argsJson, @Param("timestamp") LocalDateTime timestamp);

    default void updateLastError(Long id, String error, LocalDateTime timestamp) {
        updateLastErrorFields(id, error, null, null, timestamp);
    }

    default void updateLastError(Long id, AccountLastError error, LocalDateTime timestamp) {
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
     * Atomic update of the reauth flag — written from async threads (token refresh
     * failure handler), so we avoid loading the entity.
     */
    @Modifying
    @Transactional
    @Query("UPDATE AccountEntity a SET a.requiresReauth = :value WHERE a.id = :id")
    void updateRequiresReauth(@Param("id") Long id, @Param("value") boolean value);
}
