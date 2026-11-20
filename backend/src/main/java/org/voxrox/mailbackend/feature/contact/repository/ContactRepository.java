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
     * Each query word ({@code ContactSearchTerms}) has to occur in the contact's
     * folded given name, folded surname or one of its addresses; a word may match a
     * different field than the one before it. {@code :w2} to {@code :w4} are null
     * when the query has fewer words, which drops their condition. The words arrive
     * folded and wrapped in {@code %...%} with LIKE's own wildcards escaped, hence
     * the {@code ESCAPE} on every comparison.
     * <p>
     * Addresses are compared with the same folded word as the names: they are
     * stored lower-cased and in practice ASCII, so folding the word is what lets a
     * name typed with its diacritics find an address spelled without them.
     * <p>
     * Shared by {@link #search} and {@link #findForTypeahead} so the list and the
     * compose window can never disagree on what matches.
     */
    String WORDS_MATCH = """
            (:w1 IS NULL OR c.nameFold LIKE :w1 ESCAPE '\\' OR c.surnameFold LIKE :w1 ESCAPE '\\'
                OR EXISTS (SELECT 1 FROM ContactEmailEntity e1
                           WHERE e1.contact = c AND LOWER(e1.email) LIKE :w1 ESCAPE '\\'))
            AND (:w2 IS NULL OR c.nameFold LIKE :w2 ESCAPE '\\' OR c.surnameFold LIKE :w2 ESCAPE '\\'
                OR EXISTS (SELECT 1 FROM ContactEmailEntity e2
                           WHERE e2.contact = c AND LOWER(e2.email) LIKE :w2 ESCAPE '\\'))
            AND (:w3 IS NULL OR c.nameFold LIKE :w3 ESCAPE '\\' OR c.surnameFold LIKE :w3 ESCAPE '\\'
                OR EXISTS (SELECT 1 FROM ContactEmailEntity e3
                           WHERE e3.contact = c AND LOWER(e3.email) LIKE :w3 ESCAPE '\\'))
            AND (:w4 IS NULL OR c.nameFold LIKE :w4 ESCAPE '\\' OR c.surnameFold LIKE :w4 ESCAPE '\\'
                OR EXISTS (SELECT 1 FROM ContactEmailEntity e4
                           WHERE e4.contact = c AND LOWER(e4.email) LIKE :w4 ESCAPE '\\'))
            """;

    /**
     * The address book search: {@link #WORDS_MATCH} with an optional filter by
     * contact label. Subqueries instead of a JOIN avoid duplicates when a contact
     * has several addresses, and keep paging exact.
     * <p>
     * Like {@link #findAllFiltered}, the collections are batch-loaded rather than
     * fetch-joined so {@code Pageable} stays a SQL-level limit (no HHH90003004).
     */
    @Query("SELECT DISTINCT c FROM ContactEntity c WHERE " + WORDS_MATCH + """
              AND (:labelId IS NULL OR EXISTS (SELECT 1 FROM ContactEntity c2 JOIN c2.labels cl
                                                WHERE c2.id = c.id AND cl.id = :labelId))
            """)
    Page<ContactEntity> search(@Param("w1") String w1, @Param("w2") @Nullable String w2,
            @Param("w3") @Nullable String w3, @Param("w4") @Nullable String w4,
            @Param("labelId") @Nullable Long labelId, Pageable pageable);

    /**
     * The address book half of the compose typeahead, best matches first, so the
     * {@code Pageable}'s limit keeps the contacts worth suggesting. Fetching in the
     * listing's alphabetical order and ranking afterwards used to let a short query
     * fill the limit with weak matches: for "in", every Martin and Kristina sorted
     * before Z, and the contact whose address starts with {@code info@} but whose
     * surname starts with Z never reached the ranking at all.
     * <p>
     * The rank mirrors {@code ContactAutocompleteService}'s per-address ranking,
     * taken at the contact's best address: an address starting with the query, then
     * the folded "surname name" starting with it, then "name surname", then any
     * other match. {@code :prefix} is the query's words joined by spaces as an
     * escaped prefix pattern, so "Novak, Jan" ranks as the surname-first prefix it
     * is. Within one rank, the listing's own order.
     * <p>
     * No {@code DISTINCT}: every condition is a subquery, so a contact appears
     * once, and an {@code ORDER BY} on an expression outside the select list is not
     * portable under {@code DISTINCT}.
     */
    @Query("SELECT c FROM ContactEntity c WHERE " + WORDS_MATCH + """
            ORDER BY
                CASE
                    WHEN EXISTS (SELECT 1 FROM ContactEmailEntity ep
                                 WHERE ep.contact = c AND LOWER(ep.email) LIKE :prefix ESCAPE '\\') THEN 0
                    WHEN CONCAT(c.surnameFold, COALESCE(CONCAT(' ', c.nameFold), '')) LIKE :prefix ESCAPE '\\'
                        THEN 1
                    WHEN CONCAT(c.nameFold, COALESCE(CONCAT(' ', c.surnameFold), '')) LIKE :prefix ESCAPE '\\'
                        THEN 2
                    ELSE 3
                END,
                c.surnameFold ASC NULLS LAST, c.nameFold ASC NULLS LAST, c.surname, c.name, c.id
            """)
    List<ContactEntity> findForTypeahead(@Param("w1") String w1, @Param("w2") @Nullable String w2,
            @Param("w3") @Nullable String w3, @Param("w4") @Nullable String w4, @Param("prefix") String prefix,
            Pageable pageable);

    /**
     * Batch variant of the duplicate check for create/update contact. Returns
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
