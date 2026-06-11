package org.voxrox.mailbackend.feature.mail.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.util.LogMasker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_unique_uid", columnList = "account_id, folder_name, uid", unique = true),
        @Index(name = "idx_messages_lookup_desc", columnList = "account_id, folder_name, received_at DESC"),
        @Index(name = "idx_messages_stable_id", columnList = "stable_id"),
        @Index(name = "idx_messages_account_thread", columnList = "account_id, thread_id"),
        @Index(name = "idx_messages_account_thread_root", columnList = "account_id, thread_root_message_id"),
        @Index(name = "idx_messages_account_in_reply_to", columnList = "account_id, in_reply_to")})
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stable_id", nullable = false, unique = true, length = 32)
    private String stableId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private AccountEntity account;

    @Column(name = "folder_name", nullable = false)
    private String folderName;

    @Column(nullable = false)
    private Long uid;

    @Column(name = "uid_validity", nullable = false)
    private Long uidValidity;

    @Column(length = 500)
    private @Nullable String subject;

    @Column(length = 255)
    private @Nullable String sender;

    @Column(name = "recipients_to", columnDefinition = "TEXT")
    private @Nullable String recipientsTo;

    @Column(name = "recipients_cc", columnDefinition = "TEXT")
    private @Nullable String recipientsCc;

    @Lob
    @Column(columnDefinition = "TEXT")
    private @Nullable String content;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(nullable = false)
    private boolean seen;

    @Column(nullable = false)
    private boolean flagged;

    @Column(nullable = false)
    private boolean answered;

    @Column(name = "message_id", length = 255)
    private @Nullable String messageId;

    @Column(name = "in_reply_to", length = 255)
    private @Nullable String inReplyTo;

    @Column(name = "reply_references", columnDefinition = "TEXT")
    private @Nullable String references;

    @Column(name = "has_attachments", nullable = false)
    private boolean hasAttachments;

    /**
     * Stable identifier of the conversation this message belongs to. Generated once
     * when a new thread root is detected and inherited by every subsequent message
     * that links into the thread via {@code In-Reply-To} or {@code
     * References}. Nullable only during the V2 backfill window — see
     * {@code ThreadingBackfillService}.
     */
    @Column(name = "thread_id", length = 36)
    private String threadId;

    /**
     * RFC 5322 {@code Message-ID} of the oldest message in the thread (or the row's
     * own {@code message_id} when this row is a singleton root). Used by the
     * late-arriving-parent reconciliation step to merge orphan chains when a new
     * arrival is discovered to be the missing root.
     */
    @Column(name = "thread_root_message_id", length = 255)
    private @Nullable String threadRootMessageId;

    /**
     * Ordinal position within the thread, ascending by {@code receivedAt}, starting
     * at 1. Lets the thread detail endpoint stream messages without an extra ORDER
     * BY join. Cross-folder duplicates of the same {@code
     * Message-ID} (e.g. Gmail INBOX + All Mail) share the position.
     */
    @Column(name = "thread_position")
    private Integer threadPosition;

    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<AttachmentEntity> attachments = new ArrayList<>();

    public MessageEntity() {
    }

    public String getFromEmailOnly() {
        if (sender == null || sender.isBlank())
            return "";
        if (sender.contains("<") && sender.contains(">")) {
            return sender.substring(sender.indexOf("<") + 1, sender.indexOf(">")).trim();
        }
        return sender.trim();
    }

    public void addAttachment(AttachmentEntity attachment) {
        attachments.add(attachment);
        attachment.setMessage(this);
        this.hasAttachments = true;
    }

    public void removeAttachment(AttachmentEntity attachment) {
        attachments.remove(attachment);
        attachment.setMessage(null);
        this.hasAttachments = !attachments.isEmpty();
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof MessageEntity other))
            return false;
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public final int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "MessageEntity{" + "id=" + id + ", stableId='" + stableId + '\'' + ", folder='" + folderName + '\''
                + ", uid=" + uid + ", subject='"
                + (subject != null && subject.length() > 30 ? subject.substring(0, 27) + "..." : subject) + '\''
                + ", sender=" + LogMasker.maskEmail(sender) + '}';
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStableId() {
        return stableId;
    }

    public void setStableId(String stableId) {
        this.stableId = stableId;
    }

    public AccountEntity getAccount() {
        return account;
    }

    public void setAccount(AccountEntity account) {
        this.account = account;
    }

    public String getFolderName() {
        return folderName;
    }

    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }

    public Long getUid() {
        return uid;
    }

    public void setUid(Long uid) {
        this.uid = uid;
    }

    public Long getUidValidity() {
        return uidValidity;
    }

    public void setUidValidity(Long uidValidity) {
        this.uidValidity = uidValidity;
    }

    public @Nullable String getSubject() {
        return subject;
    }

    public void setSubject(@Nullable String subject) {
        this.subject = subject;
    }

    public @Nullable String getSender() {
        return sender;
    }

    public void setSender(@Nullable String sender) {
        this.sender = sender;
    }

    public @Nullable String getRecipientsTo() {
        return recipientsTo;
    }

    public void setRecipientsTo(@Nullable String recipientsTo) {
        this.recipientsTo = recipientsTo;
    }

    public @Nullable String getRecipientsCc() {
        return recipientsCc;
    }

    public void setRecipientsCc(@Nullable String recipientsCc) {
        this.recipientsCc = recipientsCc;
    }

    public @Nullable String getContent() {
        return content;
    }

    public void setContent(@Nullable String content) {
        this.content = content;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public boolean isSeen() {
        return seen;
    }

    public void setSeen(boolean seen) {
        this.seen = seen;
    }

    public boolean isFlagged() {
        return flagged;
    }

    public void setFlagged(boolean flagged) {
        this.flagged = flagged;
    }

    public boolean isAnswered() {
        return answered;
    }

    public void setAnswered(boolean answered) {
        this.answered = answered;
    }

    public @Nullable String getMessageId() {
        return messageId;
    }

    public void setMessageId(@Nullable String messageId) {
        this.messageId = messageId;
    }

    public @Nullable String getInReplyTo() {
        return inReplyTo;
    }

    public void setInReplyTo(@Nullable String inReplyTo) {
        this.inReplyTo = inReplyTo;
    }

    public @Nullable String getReferences() {
        return references;
    }

    public void setReferences(@Nullable String references) {
        this.references = references;
    }

    public boolean isHasAttachments() {
        return hasAttachments;
    }

    public void setHasAttachments(boolean hasAttachments) {
        this.hasAttachments = hasAttachments;
    }

    public List<AttachmentEntity> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<AttachmentEntity> attachments) {
        this.attachments = attachments;
    }

    public String getThreadId() {
        return threadId;
    }

    public void setThreadId(String threadId) {
        this.threadId = threadId;
    }

    public @Nullable String getThreadRootMessageId() {
        return threadRootMessageId;
    }

    public void setThreadRootMessageId(@Nullable String threadRootMessageId) {
        this.threadRootMessageId = threadRootMessageId;
    }

    public Integer getThreadPosition() {
        return threadPosition;
    }

    public void setThreadPosition(Integer threadPosition) {
        this.threadPosition = threadPosition;
    }
}
