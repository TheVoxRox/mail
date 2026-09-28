package org.voxrox.mailbackend.feature.mail.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;

/**
 * The recipients the user gave a draft saved here, kept for as long as the
 * draft can be sent. Sending an untouched draft sends the server's copy, and
 * checks its recipients against these rather than against the draft's row,
 * which the sync writes from the server's copy whenever the server presents the
 * draft anew (IMAP/SMTP audit, B1-5). See {@code V1__init.sql} section 14.
 */
@Entity
@Table(name = "draft_recipients")
public class DraftRecipientsEntity {

    @Id
    @Column(name = "stable_id", length = 32)
    private String stableId;

    /*
     * Written by the constructor and read only by Hibernate when it persists the
     * account_id FK, as in CorrespondentEntity: the FK is what removes the entry
     * with its account and what the stale-entry delete filters on, and nothing
     * navigates from an entry back to its account.
     */
    @SuppressWarnings("UnusedVariable")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AccountEntity account;

    @Column(name = "recipients_to", columnDefinition = "TEXT")
    private @Nullable String recipientsTo;

    @Column(name = "recipients_cc", columnDefinition = "TEXT")
    private @Nullable String recipientsCc;

    @Column(name = "recipients_bcc", columnDefinition = "TEXT")
    private @Nullable String recipientsBcc;

    @Column(name = "saved_at", nullable = false)
    private LocalDateTime savedAt;

    protected DraftRecipientsEntity() {
    }

    public DraftRecipientsEntity(String stableId, AccountEntity account, @Nullable String recipientsTo,
            @Nullable String recipientsCc, @Nullable String recipientsBcc, LocalDateTime savedAt) {
        this.stableId = stableId;
        this.account = account;
        this.recipientsTo = recipientsTo;
        this.recipientsCc = recipientsCc;
        this.recipientsBcc = recipientsBcc;
        this.savedAt = savedAt;
    }

    public String getStableId() {
        return stableId;
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
