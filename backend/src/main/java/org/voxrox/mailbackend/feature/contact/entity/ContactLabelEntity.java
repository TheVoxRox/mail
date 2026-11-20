package org.voxrox.mailbackend.feature.contact.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;

import org.voxrox.mailbackend.util.TextFold;

/**
 * A user-defined contact label ("Family", "Clients").
 * <p>
 * Not scoped to a mail account: it groups entries of the one address book (see
 * the `contacts` table comment in V1__init.sql).
 * <p>
 * Not to be confused with
 * {@link org.voxrox.mailbackend.feature.contact.EmailLabel} — that is the
 * closed WORK/HOME/OTHER type of a single address (vCard {@code TYPE}); this
 * groups whole contacts and its values come from the user (vCard
 * {@code CATEGORIES}).
 * <p>
 * The M:N link is owned by {@link ContactEntity#getLabels()}; there is
 * deliberately no inverse collection here — a label can be on thousands of
 * contacts and nothing in the app needs to walk that direction in memory. The
 * counts come from an aggregate query instead.
 */
@Entity
@Table(name = "contact_labels", uniqueConstraints = {
        @UniqueConstraint(name = "ux_contact_labels_name_key", columnNames = {"name_key"})})
public class ContactLabelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Display form, exactly as the user typed it. */
    @Column(name = "name", nullable = false, length = 60)
    private String name;

    /**
     * Case-folded {@link #name} carrying the uniqueness constraint. Maintained by
     * the service (never by the caller) so "Rodina" and "rodina" collide — SQLite's
     * COLLATE NOCASE would not fold the diacritics.
     */
    @Column(name = "name_key", nullable = false, length = 60)
    private String nameKey;

    /**
     * {@link #name} without case and diacritics ({@link TextFold#fold}), which the
     * labels are listed in: a label starting with an S with a caron sorts among the
     * S labels, where the code point order of {@link #nameKey} put it after Z. Not
     * the uniqueness key — two Czech words can differ by a diacritic alone. Derived
     * from the name by {@link #setName}, never set on its own.
     */
    @Column(name = "name_fold", nullable = false, length = 60)
    private String nameFold;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ContactLabelEntity() {
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null)
            createdAt = LocalDateTime.now();
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof ContactLabelEntity other))
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.nameFold = TextFold.fold(name);
    }

    public String getNameFold() {
        return nameFold;
    }

    public String getNameKey() {
        return nameKey;
    }

    public void setNameKey(String nameKey) {
        this.nameKey = nameKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
