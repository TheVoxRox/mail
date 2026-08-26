package org.voxrox.mailbackend.feature.contact.repository;

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
import org.voxrox.mailbackend.feature.contact.entity.CorrespondentEntity;

@Repository
public interface CorrespondentRepository extends JpaRepository<CorrespondentEntity, Long> {

    /**
     * Records one sighting of an address, inserting the row or folding the sighting
     * into the existing one. A single native {@code ON CONFLICT DO UPDATE} rather
     * than a read-modify-write: the harvest runs for every address of every newly
     * synced message, and a SELECT per address would double the statement count on
     * the sync path — which holds the IMAP connection lock while it runs.
     *
     * <p>
     * Three details in the UPDATE clause:
     * <ul>
     * <li>{@code display_name} falls back to the stored value when the incoming one
     * is NULL, so a header that carried a bare address does not erase a name
     * learned earlier.</li>
     * <li>the counters add rather than assign, because each call reports one
     * sighting.</li>
     * <li>{@code last_seen_at} takes the later of the two. The backfill walks by
     * id, not by date, so "the row written last" is not "the most recent message" —
     * without the MAX a rebuild would leave every correspondent stamped with
     * whatever message happened to be last in id order.</li>
     * </ul>
     *
     * @param sentDelta
     *            1 when the sighting came from the user's own Sent/Drafts copy,
     *            else 0
     * @param receivedDelta
     *            1 when it came from incoming mail, else 0
     */
    @Modifying
    @Query(value = """
            INSERT INTO correspondent (account_id, email, display_name, sent_count, received_count, last_seen_at)
            VALUES (:accountId, :email, :displayName, :sentDelta, :receivedDelta, :lastSeenAt)
            ON CONFLICT (account_id, email) DO UPDATE SET
                display_name   = COALESCE(excluded.display_name, correspondent.display_name),
                sent_count     = correspondent.sent_count + excluded.sent_count,
                received_count = correspondent.received_count + excluded.received_count,
                last_seen_at   = MAX(correspondent.last_seen_at, excluded.last_seen_at)
            """, nativeQuery = true)
    void upsert(@Param("accountId") Long accountId, @Param("email") String email,
            @Param("displayName") @Nullable String displayName, @Param("sentDelta") int sentDelta,
            @Param("receivedDelta") int receivedDelta, @Param("lastSeenAt") LocalDateTime lastSeenAt);

    /**
     * Typeahead lookup over the harvested addresses, already ranked and capped.
     *
     * <p>
     * Matching is an address-prefix match OR a display-name substring match. The
     * name half is a substring because a Czech address book is searched by surname
     * ("novak" has to find "Jana Novakova") while the stored name starts with the
     * given name. {@code lower()} folds ASCII only, so the caller lower-cases the
     * query the same way — an all-caps query with diacritics is the one case that
     * can miss, which is why the address half carries the load.
     *
     * <p>
     * Ranking, strongest signal first:
     * <ol>
     * <li>addresses the user has written to, ahead of ones that only wrote in —
     * this is the evidence that separates a correspondent from a mailing list</li>
     * <li>address-prefix matches ahead of name-only matches, mirroring the contact
     * ranking so the user cannot tell which source a row came from</li>
     * <li>recency ahead of frequency: someone written to last week beats someone
     * written to a hundred times two years ago</li>
     * </ol>
     *
     * <p>
     * The robot filter drops {@code no-reply} and friends, but only while
     * {@code sent_count = 0}: an address the user actually wrote to is a real
     * correspondent no matter what its local part says. Matching is exact, so
     * dressed-up variants ({@code noreply-news@}) still get through — they rank
     * below everything the user has replied to, which is the cheap half of the same
     * job.
     */
    @Query(value = """
            SELECT * FROM correspondent c
            WHERE c.account_id = :accountId
              AND (c.email LIKE :prefixPattern ESCAPE '\\'
                   OR lower(c.display_name) LIKE :containsPattern ESCAPE '\\')
              AND (c.sent_count > 0
                   OR substr(c.email, 1, instr(c.email, '@') - 1) NOT IN (:robotLocalParts))
            ORDER BY
                CASE WHEN c.sent_count > 0 THEN 0 ELSE 1 END,
                CASE WHEN c.email LIKE :prefixPattern ESCAPE '\\' THEN 0 ELSE 1 END,
                c.last_seen_at DESC,
                c.sent_count + c.received_count DESC,
                c.email
            LIMIT :limit
            """, nativeQuery = true)
    List<CorrespondentEntity> search(@Param("accountId") Long accountId, @Param("prefixPattern") String prefixPattern,
            @Param("containsPattern") String containsPattern,
            @Param("robotLocalParts") Collection<String> robotLocalParts, @Param("limit") int limit);

    // @callerless Identity lookup for the integration tests. Production reaches
    // correspondents through upsert() and search(), neither of which can show
    // what a single row ended up holding after an upsert.
    Optional<CorrespondentEntity> findByAccountIdAndEmail(Long accountId, String email);

    long countByAccountId(Long accountId);

    /**
     * Drops the whole cache for one account, so it can be rebuilt from
     * {@code messages}. A bulk DELETE rather than the derived
     * {@code deleteByAccountId}, which would load every row as an entity first only
     * to delete it.
     */
    @Modifying
    @Query("DELETE FROM CorrespondentEntity c WHERE c.account.id = :accountId")
    int deleteAllForAccount(@Param("accountId") Long accountId);
}
