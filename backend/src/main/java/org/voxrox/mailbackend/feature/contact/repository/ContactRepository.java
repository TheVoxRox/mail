package org.voxrox.mailbackend.feature.contact.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.voxrox.mailbackend.feature.contact.EmailLabel;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;

@Repository
public interface ContactRepository extends JpaRepository<ContactEntity, Long> {

    /**
     * Listing with an optional filter by email label. When {@code label == null}
     * the filter is not applied and all account contacts are returned.
     * <p>
     * Deliberately no {@code @EntityGraph} / JOIN FETCH on {@code emails}: fetching
     * a collection together with {@code Pageable} makes Hibernate apply the
     * limit/offset in memory (HHH90003004) instead of in SQL. The page query
     * therefore selects contact roots with a real SQL {@code LIMIT}; the
     * {@code emails} collection is batch-loaded ({@code @BatchSize} on the entity)
     * during DTO mapping — one extra {@code IN} query per batch, not N+1.
     */
    @Query("""
            SELECT DISTINCT c FROM ContactEntity c
            WHERE c.account.id = :accountId
              AND (:label IS NULL OR EXISTS (SELECT 1 FROM ContactEmailEntity le
                                              WHERE le.contact = c AND le.label = :label))
            """)
    Page<ContactEntity> findByAccountId(@Param("accountId") Long accountId, @Param("label") @Nullable EmailLabel label,
            Pageable pageable);

    /**
     * Case-insensitive substring search across all contact emails, first name, and
     * surname, with an optional filter by label. A subquery instead of JOIN avoids
     * duplicates when there are multiple emails and supports paging. The caller
     * wraps {@code q} in {@code %...%}.
     * <p>
     * Like {@link #findByAccountId}, {@code emails} is batch-loaded rather than
     * fetch-joined so {@code Pageable} stays a SQL-level limit (no HHH90003004).
     */
    @Query("""
            SELECT DISTINCT c FROM ContactEntity c
            WHERE c.account.id = :accountId
              AND (EXISTS (SELECT e FROM ContactEmailEntity e
                           WHERE e.contact = c AND LOWER(e.email) LIKE :q)
                   OR (c.name IS NOT NULL AND LOWER(c.name) LIKE :q)
                   OR (c.surname IS NOT NULL AND LOWER(c.surname) LIKE :q))
              AND (:label IS NULL OR EXISTS (SELECT 1 FROM ContactEmailEntity le
                                              WHERE le.contact = c AND le.label = :label))
            """)
    Page<ContactEntity> searchByAccountId(@Param("accountId") Long accountId, @Param("q") String q,
            @Param("label") @Nullable EmailLabel label, Pageable pageable);

    /**
     * Finds contacts in the given account that have {@code email} among their email
     * addresses. Used for the duplicate check before saving (cross-contact
     * uniqueness per account). Returns List instead of Optional — uniqueness is
     * enforced only at the application level, so if data leaks in through another
     * channel (manual SQL, future import), a duplicate must not blow up with
     * {@link org.springframework.dao.IncorrectResultSizeDataAccessException}.
     * Callers use {@code stream().findFirst()}.
     */
    @Query("""
            SELECT c FROM ContactEntity c
            JOIN c.emails e
            WHERE c.account.id = :accountId AND e.email = :email
            """)
    List<ContactEntity> findByAccountIdAndAnyEmail(@Param("accountId") Long accountId, @Param("email") String email);

    /**
     * Batch variant of the duplicate check for create/update/patch contact. Returns
     * contacts that have at least one of the given emails; emails are fetched via
     * entity graph so the service does not have to issue further lazy queries to
     * determine the conflicting address.
     */
    @EntityGraph(attributePaths = "emails")
    @Query("""
            SELECT DISTINCT c FROM ContactEntity c
            JOIN c.emails e
            WHERE c.account.id = :accountId AND e.email IN :emails
            """)
    List<ContactEntity> findByAccountIdAndAnyEmailIn(@Param("accountId") Long accountId,
            @Param("emails") Collection<String> emails);

    Optional<ContactEntity> findByIdAndAccountId(Long id, Long accountId);

    /**
     * Loads all contacts of an account for export (vCard, future backup).
     * {@code @EntityGraph} prevents N+1 through {@code c.emails}. Without paging —
     * address books typically hold &lt;10k records, so loading the full export into
     * memory is acceptable.
     */
    @EntityGraph(attributePaths = "emails")
    List<ContactEntity> findAllByAccountId(Long accountId, Sort sort);
}
