package org.voxrox.mailbackend.feature.mail.entity;

import jakarta.persistence.*;

/**
 * One normalized token of a message's {@code References} header — a single RFC
 * 5322 Message-ID that the owning message references. Populated when the
 * message is threaded (see {@code ThreadingService.indexReferences}) so that
 * late-arriving-parent reconciliation can find orphan children that link to a
 * not-yet-arrived ancestor only through {@code References} (no
 * {@code In-Reply-To}) via an indexed lookup instead of an unindexable
 * free-text scan of {@code messages.reply_references}.
 *
 * <p>
 * The {@code messageId} column is a foreign key to {@code messages.id} (the row
 * id), not the RFC Message-ID; {@code referencedMessageId} holds the RFC
 * Message-ID token. The FK carries {@code ON DELETE CASCADE} in the Flyway
 * schema (V2), so rows disappear with their message and account.
 */
@Entity
@Table(name = "message_reference", indexes = {
        @Index(name = "idx_message_reference_account_ref", columnList = "account_id, referenced_message_id"),
        @Index(name = "idx_message_reference_message", columnList = "message_id")})
public class MessageReferenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@code messages.id} — the owning message row. */
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /**
     * One RFC 5322 Message-ID token from the owning message's References header.
     */
    @Column(name = "referenced_message_id", nullable = false, length = 255)
    private String referencedMessageId;

    public MessageReferenceEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getReferencedMessageId() {
        return referencedMessageId;
    }

    public void setReferencedMessageId(String referencedMessageId) {
        this.referencedMessageId = referencedMessageId;
    }
}
