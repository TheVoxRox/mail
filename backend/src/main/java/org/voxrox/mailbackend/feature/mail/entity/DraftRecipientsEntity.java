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
     * save names as the revision it replaces, so that save finds the draft's chain
     * without the server's row.
     */
    @Column(name = "stable_id", length = 32)
    private @Nullable String stableId;

    /**
     * The stableId of the draft's first revision saved here, inherited by every
     * save that names a revision of the draft as the one it replaces: a stored save
     * sets aside every earlier entry of its chain not already set aside, so the
     * draft keeps one current entry even when the revision a save names is one the
     * server rejected, which no later save names (B1-5).
     */
    @Column(name = "chain_id", length = 32)
    private @Nullable String chainId;

    @Column(name = "recipients_to", columnDefinition = "TEXT")
    private @Nullable String recipientsTo;

    @Column(name = "recipients_cc", columnDefinition = "TEXT")
    private @Nullable String recipientsCc;

    @Column(name = "recipients_bcc", columnDefinition = "TEXT")
    private @Nullable String recipientsBcc;

    @Column(name = "saved_at", nullable = false)
    private LocalDateTime savedAt;

    /**
     * The order the entries were written in, one more than the largest in the
     * table: what "newest" and "saved no later" go by, since the wall clock can
     * step back.
     */
    @Column(name = "saved_seq", nullable = false)
    private long savedSeq;

    /**
     * When the server stored the revision; null while its save is accepted but has
     * not stored it, waiting for the server or failed. Only a stored revision
     * becomes current: an entry current from acceptance let a server that held the
     * APPEND lane keep a thousand autosaves current and push out a hidden draft's
     * entry (B1-5, reopened at 1.46). A draft's newest entry is bounded with the
     * current ones whatever this says, so a revision the server has not stored is
     * not left to the saves to push out (reopened at 1.48).
     */
    @Column(name = "stored_at")
    private @Nullable LocalDateTime storedAt;

    /**
     * When a later stored save of this client replaced the revision; null while it
     * is current, or accepted and not yet stored.
     */
    @Column(name = "superseded_at")
    private @Nullable LocalDateTime supersededAt;

    /**
     * For JPA only: an entry is written by
     * {@code DraftRecipientsRepository.insertEntry}, a plain INSERT, never through
     * the persistence context (B1-5, reopened at 1.41).
     */
    protected DraftRecipientsEntity() {
    }

    public String getMessageId() {
        return messageId;
    }

    public @Nullable String getStableId() {
        return stableId;
    }

    public @Nullable String getChainId() {
        return chainId;
    }

    public @Nullable LocalDateTime getStoredAt() {
        return storedAt;
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
