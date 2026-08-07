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
     * Listing with an optional filter by contact label. When
     * {@code labelId == null} the filter is not applied and all account contacts
     * are returned. The label is matched by ID only — the caller has already
     * verified it belongs to the account (a foreign ID would otherwise silently
     * return an empty page).
     * <p>
     * Deliberately no {@code @EntityGraph} / JOIN FETCH on {@code emails}: fetching
     * a collection together with {@code Pageable} makes Hibernate apply the
     * limit/offset in memory (HHH90003004) instead of in SQL. The page query
     * therefore selects contact roots with a real SQL {@code LIMIT}; the
     * {@code emails} and {@code labels} collections are batch-loaded
     * ({@code @BatchSize} on the entity) during DTO mapping — one extra {@code IN}
     * query per batch, not N+1.
     */
    @Query("""
            SELECT DISTINCT c FROM ContactEntity c
            WHERE c.account.id = :accountId
              AND (:labelId IS NULL OR EXISTS (SELECT 1 FROM ContactEntity c2 JOIN c2.labels cl
                                                WHERE c2.id = c.id AND cl.id = :labelId))
            """)
    Page<ContactEntity> findByAccountId(@Param("accountId") Long accountId, @Param("labelId") @Nullable Long labelId,
            Pageable pageable);

    /**
     * Case-insensitive substring search across all contact emails, first name, and
     * surname, with an optional filter by contact label. A subquery instead of JOIN
     * avoids duplicates when there are multiple emails and supports paging. The
     * caller wraps {@code q} in {@code %...%}.
     * <p>
     * Like {@link #findByAccountId}, the collections are batch-loaded rather than
     * fetch-joined so {@code Pageable} stays a SQL-level limit (no HHH90003004).
     */
    @Query("""
            SELECT DISTINCT c FROM ContactEntity c
            WHERE c.account.id = :accountId
              AND (EXISTS (SELECT e FROM ContactEmailEntity e
                           WHERE e.contact = c AND LOWER(e.email) LIKE :q)
                   OR (c.name IS NOT NULL AND LOWER(c.name) LIKE :q)
                   OR (c.surname IS NOT NULL AND LOWER(c.surname) LIKE :q))
              AND (:labelId IS NULL OR EXISTS (SELECT 1 FROM ContactEntity c2 JOIN c2.labels cl
                                                WHERE c2.id = c.id AND cl.id = :labelId))
            """)
    Page<ContactEntity> searchByAccountId(@Param("accountId") Long accountId, @Param("q") String q,
            @Param("labelId") @Nullable Long labelId, Pageable pageable);

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
     * Every contact carrying the given label. Used when a label is deleted, to
     * clear the assignments through Hibernate — the DB cascade alone would leave
     * the in-session contacts holding a reference to a row that no longer exists.
     * Not paginated on purpose: the whole point is to touch all of them.
     */
    @Query("""
            SELECT DISTINCT c FROM ContactEntity c JOIN c.labels cl
            WHERE c.account.id = :accountId AND cl.id = :labelId
            """)
    List<ContactEntity> findByAccountIdAndLabelId(@Param("accountId") Long accountId, @Param("labelId") Long labelId);

    long countByAccountId(Long accountId);

    /**
     * Contact counts per contact label, for the sidebar badges. Each count equals
     * the size of the list filtered by the same label. Labels nobody uses are
     * absent from the result — the caller fills in the zeros from the label list,
     * which is the only place that knows a label exists at all.
     */
    @Query("""
            SELECT new org.voxrox.mailbackend.feature.contact.repository.ContactLabelCount(
                cl.id, COUNT(DISTINCT c.id))
            FROM ContactEntity c JOIN c.labels cl
            WHERE c.account.id = :accountId
            GROUP BY cl.id
            """)
    List<ContactLabelCount> countByAccountIdGroupedByLabel(@Param("accountId") Long accountId);

    /**
     * Loads all contacts of an account for export (vCard, future backup). The
     * entity graph prevents N+1 through {@code c.emails}; {@code labels} stays
     * batch-loaded (two collection fetch joins in one query would multiply the
     * rows). Without paging — address books typically hold &lt;10k records, so
     * loading the full export into memory is acceptable.
     */
    @EntityGraph(attributePaths = "emails")
    List<ContactEntity> findAllByAccountId(Long accountId, Sort sort);
}
