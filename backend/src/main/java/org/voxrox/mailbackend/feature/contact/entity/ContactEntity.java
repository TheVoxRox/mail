package org.voxrox.mailbackend.feature.contact.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.*;

import org.hibernate.annotations.BatchSize;
import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.util.StoredText;
import org.voxrox.mailbackend.util.TextFold;

@Entity
@Table(name = "contacts")
public class ContactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * @BatchSize lets the paginated listing queries (ContactRepository) load this
     * collection in batched IN(...) selects instead of fetch-joining it. A fetch
     * join together with Pageable forces Hibernate to apply the limit/offset in
     * memory (HHH90003004); batch-loading keeps pagination at the SQL level while
     * still avoiding the N+1 during DTO mapping.
     */
    @OneToMany(mappedBy = "contact", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("primary DESC, id ASC")
    @BatchSize(size = 50)
    private List<ContactEmailEntity> emails = new ArrayList<>();

    /*
     * Owning side of the M:N with ContactLabelEntity. No cascade: labels have their
     * own lifecycle (ContactLabelService) and must outlive the contacts they are on
     * — cascading a contact delete into the label would wipe it off every other
     * contact too. Batch-loaded for the same reason as emails: the paginated
     * listings must keep their SQL-level limit/offset, so this cannot be fetch
     * joined. Ordered the way the sidebar orders labels, so the response order
     * matches it.
     */
    @ManyToMany
    @JoinTable(name = "contact_label_links", joinColumns = @JoinColumn(name = "contact_id"), inverseJoinColumns = @JoinColumn(name = "label_id"))
    @OrderBy("nameFold ASC, nameKey ASC")
    @BatchSize(size = 50)
    private Set<ContactLabelEntity> labels = new LinkedHashSet<>();

    @Column(name = "name", length = 255)
    private @Nullable String name;

    @Column(name = "surname", length = 255)
    private @Nullable String surname;

    /*
     * The folded forms of name and surname (TextFold.key), which the search matches
     * and the listing orders by — SQLite's own lower() and ORDER BY know nothing
     * beyond ASCII. Derived, never set on their own: the setters of the two names
     * write them, which is every write path the names have, and Hibernate's field
     * access means loading a row does not recompute them.
     */
    @Column(name = "name_fold", length = 255)
    private @Nullable String nameFold;

    @Column(name = "surname_fold", length = 255)
    private @Nullable String surnameFold;

    @Column(name = "note", length = 1000)
    private @Nullable String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ContactEntity() {
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null)
            createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof ContactEntity other))
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

    public List<ContactEmailEntity> getEmails() {
        return emails;
    }

    public Set<ContactLabelEntity> getLabels() {
        return labels;
    }

    public void setLabels(Set<ContactLabelEntity> labels) {
        this.labels = labels;
    }

    public @Nullable String getName() {
        return name;
    }

    /**
     * Stores the name without surrounding whitespace, a blank one as null
     * ({@link StoredText}), and derives its folded key. Here rather than in the
     * callers so that no write path can store the whitespace; the rows stored
     * before this rule are cleaned by {@code V2__Fold_keys_and_strip_text}.
     */
    public void setName(@Nullable String name) {
        this.name = StoredText.stripToNull(name);
        this.nameFold = TextFold.key(this.name);
    }

    public @Nullable String getSurname() {
        return surname;
    }

    /** As {@link #setName}. */
    public void setSurname(@Nullable String surname) {
        this.surname = StoredText.stripToNull(surname);
        this.surnameFold = TextFold.key(this.surname);
    }

    public @Nullable String getNameFold() {
        return nameFold;
    }

    public @Nullable String getSurnameFold() {
        return surnameFold;
    }

    public @Nullable String getNote() {
        return note;
    }

    /**
     * As {@link #setName}, without a folded key: the search does not read notes.
     */
    public void setNote(@Nullable String note) {
        this.note = StoredText.stripToNull(note);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
