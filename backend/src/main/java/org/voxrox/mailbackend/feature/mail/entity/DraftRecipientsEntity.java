package org.voxrox.mailbackend.feature.mail.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import jakarta.persistence.*;

import org.jspecify.annotations.Nullable;

/**
 * The recipients the user gave a draft saved here, kept for as long as the
 * draft can be sent. Sending an untouched draft sends the server's copy, and
 * checks its recipients against these rather than against the draft's row,
 * which the sync writes from the server's copy whenever the server presents the
 * draft anew (IMAP/SMTP audit, B1-5). See {@code V1__init.sql} section 14.
 * <p>
 * Keyed by the account and the Message-ID the save minted, which no folder the
 * server moves the draft to changes. The draft's stable id hashes the Drafts
 * folder's name as well, so keying by it lost the entry when the server moved
 * the {@code \Drafts} role. The account is the bare id: the foreign key in the
 * schema removes the entry with its account, and nothing navigates from an
 * entry back to it.
 */
@Entity
@Table(name = "draft_recipients")
@IdClass(DraftRecipientsEntity.Key.class)
public class DraftRecipientsEntity {

    /** The primary key: an account and a Message-ID it saved a draft under. */
    public record Key(Long accountId, String messageId) implements Serializable {
    }

    @Id
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Id
    @Column(name = "message_id", length = 255, nullable = false)
    private String messageId;

    /**
     * The stableId the save minted for the draft with its Message-ID: what the next
     * save names as the revision it replaces, so the entry can be marked superseded
     * without the server's row.
     */
    @Column(name = "stable_id", length = 32)
    private @Nullable String stableId;

    @Column(name = "recipients_to", columnDefinition = "TEXT")
    private @Nullable String recipientsTo;

    @Column(name = "recipients_cc", columnDefinition = "TEXT")
    private @Nullable String recipientsCc;

    @Column(name = "recipients_bcc", columnDefinition = "TEXT")
    private @Nullable String recipientsBcc;

    @Column(name = "saved_at", nullable = false)
    private LocalDateTime savedAt;

    /**
     * When a later save of this client replaced the revision; null while it is
     * current.
     */
    @Column(name = "superseded_at")
    private @Nullable LocalDateTime supersededAt;

    protected DraftRecipientsEntity() {
    }

    public DraftRecipientsEntity(Long accountId, String messageId, @Nullable String stableId,
            @Nullable String recipientsTo, @Nullable String recipientsCc, @Nullable String recipientsBcc,
            LocalDateTime savedAt) {
        this.accountId = accountId;
        this.messageId = messageId;
        this.stableId = stableId;
        this.recipientsTo = recipientsTo;
        this.recipientsCc = recipientsCc;
        this.recipientsBcc = recipientsBcc;
        this.savedAt = savedAt;
    }

    public String getMessageId() {
        return messageId;
    }

    public @Nullable String getStableId() {
        return stableId;
    }

    public @Nullable LocalDateTime getSupersededAt() {
        return supersededAt;
    }

    public @Nullable String getRecipientsTo() {
        return recipientsTo;
    }

    public @Nullable String getRecipientsCc() {
        return recipientsCc;
    }

    public @Nullable String getRecipientsBcc() {
        return recipientsBcc;
    }
}
