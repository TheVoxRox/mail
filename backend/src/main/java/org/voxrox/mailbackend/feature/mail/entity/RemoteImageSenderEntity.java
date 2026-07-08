package org.voxrox.mailbackend.feature.mail.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.*;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;

/**
 * A sender the user has explicitly allowed to load remote (https) images from.
 * Remote images are blocked by default as a tracking-pixel defense; a row here
 * opts the given sender's messages into auto-loading their remote images (see
 * docs/CONTENT_RENDERING_AUDIT.md finding F2). Account-scoped: the allow decision
 * is isolated per account and cleaned up when the account is deleted.
 *
 * <p>
 * {@code senderEmail} is stored normalized (lowercase) by
 * {@code RemoteImageAllowlistService} so lookups match regardless of the casing
 * an IMAP server reports in the From header.
 */
@Entity
@Table(name = "remote_image_sender", uniqueConstraints = {
        @UniqueConstraint(name = "uk_remote_image_account_sender", columnNames = {"account_id", "sender_email"})})
public class RemoteImageSenderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AccountEntity account;

    @Column(name = "sender_email", nullable = false, length = 255)
    private String senderEmail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected RemoteImageSenderEntity() {
    }

    public RemoteImageSenderEntity(AccountEntity account, String senderEmail) {
        this.account = account;
        this.senderEmail = senderEmail;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public AccountEntity getAccount() {
        return account;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RemoteImageSenderEntity other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
