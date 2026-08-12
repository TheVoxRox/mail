package org.voxrox.mailbackend.feature.contact.repository;

import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;

/**
 * The address book is application-wide, not per mail account — see the
 * {@code contacts} table comment in {@code V1__init.sql} — so none of these
 * queries take an account.
 */
@Repository
public interface ContactRepository extends JpaRepository<ContactEntity, Long> {

    /**
     * Listing with an optional filter by contact label. When
     * {@code labelId == null} the filter is not applied and all contacts are
     * returned.
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
            WHERE (:labelId IS NULL OR EXISTS (SELECT 1 FROM ContactEntity c2 JOIN c2.labels cl
                                                WHERE c2.id = c.id AND cl.id = :labelId))
            """)
    Page<ContactEntity> findAllFiltered(@Param("labelId") @Nullable Long labelId, Pageable pageable);

    /**
     * Case-insensitive substring search across all contact emails, first name, and
     * surname, with an optional filter by contact label. A subquery instead of JOIN
     * avoids duplicates when there are multiple emails and supports paging. The
     * caller wraps {@code q} in {@code %...%}.
     * <p>
     * Like {@link #findAllFiltered}, the collections are batch-loaded rather than
     * fetch-joined so {@code Pageable} stays a SQL-level limit (no HHH90003004).
     */
    @Query("""
            SELECT DISTINCT c FROM ContactEntity c
            WHERE (EXISTS (SELECT e FROM ContactEmailEntity e
                           WHERE e.contact = c AND LOWER(e.email) LIKE :q)
                   OR (c.name IS NOT NULL AND LOWER(c.name) LIKE :q)
                   OR (c.surname IS NOT NULL AND LOWER(c.surname) LIKE :q))
              AND (:labelId IS NULL OR EXISTS (SELECT 1 FROM ContactEntity c2 JOIN c2.labels cl
                                                WHERE c2.id = c.id AND cl.id = :labelId))
            """)
    Page<ContactEntity> search(@Param("q") String q, @Param("labelId") @Nullable Long labelId, Pageable pageable);

    /**
     * Finds contacts that have {@code email} among their addresses. Used for the
     * duplicate check before saving. Returns List instead of Optional — uniqueness
     * is enforced only at the application level, so if data leaks in through
     * another channel (manual SQL, future import), a duplicate must not blow up
     * with {@link org.springframework.dao.IncorrectResultSizeDataAccessException}.
     * Callers use {@code stream().findFirst()}.
     */
    @Query("""
            SELECT c FROM ContactEntity c
            JOIN c.emails e
            WHERE e.email = :email
            """)
    List<ContactEntity> findByAnyEmail(@Param("email") String email);

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
            WHERE e.email IN :emails
            """)
    List<ContactEntity> findByAnyEmailIn(@Param("emails") Collection<String> emails);

    /**
     * Every contact carrying the given label. Used when a label is deleted, to
     * clear the assignments through Hibernate — the DB cascade alone would leave
     * the in-session contacts holding a reference to a row that no longer exists.
     * Not paginated on purpose: the whole point is to touch all of them.
     */
    @Query("""
            SELECT DISTINCT c FROM ContactEntity c JOIN c.labels cl
            WHERE cl.id = :labelId
            """)
    List<ContactEntity> findByLabelId(@Param("labelId") Long labelId);

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
            GROUP BY cl.id
            """)
    List<ContactLabelCount> countGroupedByLabel();

    /**
     * Loads every contact for export (vCard, future backup). The entity graph
     * prevents N+1 through {@code c.emails}; {@code labels} stays batch-loaded (two
     * collection fetch joins in one query would multiply the rows). Without paging
     * — address books typically hold &lt;10k records, so loading the full export
     * into memory is acceptable.
     */
    @EntityGraph(attributePaths = "emails")
    List<ContactEntity> findAllBy(Sort sort);
}
