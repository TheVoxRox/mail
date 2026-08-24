package org.voxrox.mailbackend.feature.contact.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.*;

import org.hibernate.annotations.BatchSize;
import org.jspecify.annotations.Nullable;

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
     * joined. Ordered by nameKey so the response order matches the sidebar.
     */
    @ManyToMany
    @JoinTable(name = "contact_label_links", joinColumns = @JoinColumn(name = "contact_id"), inverseJoinColumns = @JoinColumn(name = "label_id"))
    @OrderBy("nameKey ASC")
    @BatchSize(size = 50)
    private Set<ContactLabelEntity> labels = new LinkedHashSet<>();

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "surname", length = 255)
    private String surname;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSurname() {
        return surname;
    }

    public void setSurname(String surname) {
        this.surname = surname;
    }

    public @Nullable String getNote() {
        return note;
    }

    public void setNote(@Nullable String note) {
        this.note = note;
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
