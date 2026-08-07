package org.voxrox.mailbackend.feature.contact.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;

/**
 * One address the account has exchanged mail with, harvested from message
 * headers at sync time. Feeds the compose-window typeahead so a fresh install
 * can suggest the people the user actually writes to — the address book itself
 * starts empty and only fills by hand or by a vCard import.
 *
 * <p>
 * <b>A derived cache, not a source of truth.</b> Every row is reconstructible
 * from {@code messages} by {@code CorrespondentBackfillService}, and nothing
 * reads the table except the typeahead, so it can be dropped and rebuilt
 * whenever it looks wrong. That is what keeps the harvest cheap: no data
 * migration to design, no consistency to defend, and a bug is repaired by
 * deleting rows rather than by fixing them in place.
 *
 * <p>
 * It is deliberately <em>not</em> the address book. {@link ContactEntity} stays
 * hand-curated so labels and merge keep operating on a set the user chose; a
 * table that silently absorbed every sender would flood both with newsletters
 * and no-reply addresses.
 *
 * <p>
 * Writes do not go through this entity — {@code CorrespondentRepository.upsert}
 * issues a single native {@code ON CONFLICT DO UPDATE}, because the harvest runs
 * once per address of every newly synced message and a read-modify-write per
 * address would double the statement count on the sync path. The mapping exists
 * for the read side and for tests.
 */
@Entity
@Table(name = "correspondent", uniqueConstraints = {
        @UniqueConstraint(name = "ux_correspondent_account_email", columnNames = {"account_id", "email"})})
public class CorrespondentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AccountEntity account;

    /** Normalized address: trimmed and lower-cased. */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /**
     * Most recently seen display name from the header, or {@code null} when every
     * sighting so far was a bare address. The upsert never overwrites a known name
     * with {@code null}, so one badly formed header does not erase it.
     */
    @Column(name = "display_name", length = 255)
    private @Nullable String displayName;

    /**
     * How many messages the user sent to this address. Kept apart from
     * {@link #receivedCount} because the ranking weighs the two differently: an
     * address written TO is a far stronger signal of a real correspondent than one
     * that merely wrote in, and the split drops most bulk mail for free.
     */
    @Column(name = "sent_count", nullable = false)
    private int sentCount;

    @Column(name = "received_count", nullable = false)
    private int receivedCount;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    public CorrespondentEntity() {
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof CorrespondentEntity other))
            return false;
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public final int hashCode() {
        return getClass().hashCode();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public AccountEntity getAccount() {
        return account;
    }

    public void setAccount(AccountEntity account) {
        this.account = account;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public @Nullable String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(@Nullable String displayName) {
        this.displayName = displayName;
    }

    public int getSentCount() {
        return sentCount;
    }

    public void setSentCount(int sentCount) {
        this.sentCount = sentCount;
    }

    public int getReceivedCount() {
        return receivedCount;
    }

    public void setReceivedCount(int receivedCount) {
        this.receivedCount = receivedCount;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
