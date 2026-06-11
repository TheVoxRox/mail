package org.voxrox.mailbackend.feature.contact.entity;

import jakarta.persistence.*;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.voxrox.mailbackend.feature.contact.EmailLabel;

@Entity
@Table(name = "contact_emails", uniqueConstraints = {
        @UniqueConstraint(name = "ux_contact_emails_contact_email", columnNames = {"contact_id", "email"})})
public class ContactEmailEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ContactEntity contact;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "label", length = 10)
    private EmailLabel label;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    public ContactEmailEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ContactEntity getContact() {
        return contact;
    }

    public void setContact(ContactEntity contact) {
        this.contact = contact;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public EmailLabel getLabel() {
        return label;
    }

    public void setLabel(EmailLabel label) {
        this.label = label;
    }

    public boolean isPrimary() {
        return primary;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }
}
