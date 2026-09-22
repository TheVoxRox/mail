package org.voxrox.mailbackend.feature.mail.mapper;

import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.mail.dto.AttachmentResponse;
import org.voxrox.mailbackend.feature.mail.dto.MailDetailResponse;
import org.voxrox.mailbackend.feature.mail.dto.MailSummaryResponse;
import org.voxrox.mailbackend.feature.mail.entity.AttachmentEntity;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.service.FetchedMessage;

@Component
public class MessageMapper {

    private final MessageSource messageSource;

    public MessageMapper(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public MessageEntity toEntity(FetchedMessage fetched, AccountEntity account, String folderName, Long uidValidity) {
        MessageEntity entity = new MessageEntity();

        // Deterministic, identity-derived id (survives a folder re-download) instead
        // of a random UUID — see MessageStableId for why this matters for "ghost" 404s.
        entity.setStableId(
                MessageStableId.compute(account.getId(), folderName, fetched.messageId(), fetched.uid(), uidValidity));

        // Message metadata
        entity.setAccount(account);
        entity.setFolderName(folderName);
        entity.setUid(fetched.uid());
        entity.setUidValidity(uidValidity);

        // Headers
        entity.setSubject(blankToNull(fetched.subject()));
        entity.setSender(blankToNull(fetched.sender()));
        entity.setRecipientsTo(fetched.recipientsTo());
        entity.setRecipientsCc(fetched.recipientsCc());
        entity.setRecipientsBcc(fetched.recipientsBcc());
        // No body: sync fetches metadata only, and MailContentService fills the
        // body in on first open.

        // Flags and timestamps. The column is NOT NULL, and so is the record
        // component: MessageFetcher defaults a missing Date header to now().
        entity.setReceivedAt(fetched.receivedAt());
        entity.setSeen(fetched.seen());
        entity.setFlagged(fetched.flagged());
        entity.setAnswered(fetched.answered());

        // Threading
        entity.setMessageId(fetched.messageId());
        entity.setInReplyTo(fetched.inReplyTo());
        entity.setReferences(fetched.references());

        // Attachments
        fetched.attachments().forEach(metadata -> {
            AttachmentEntity att = new AttachmentEntity();
            att.setPartPath(metadata.partPath());
            att.setFileName(metadata.fileName());
            att.setContentType(metadata.contentType());
            att.setSize(metadata.size());
            entity.addAttachment(att);
        });

        entity.setHasAttachments(fetched.hasAttachments());

        return entity;
    }

    public MailDetailResponse toDto(MessageEntity entity) {
        return new MailDetailResponse(entity.getStableId(), entity.getFolderName(), displaySubject(entity.getSubject()),
                displaySender(entity.getSender()), entity.getRecipientsTo(), entity.getRecipientsCc(),
                entity.getRecipientsBcc(), entity.getReceivedAt(), entity.isSeen(), entity.isFlagged(),
                entity.isAnswered(), entity.getMessageId(), entity.getInReplyTo(), entity.getReferences(),
                entity.isHasAttachments(),
                entity.getAttachments().stream().map(AttachmentResponse::fromEntity).toList(), entity.getThreadId());
    }

    /**
     * Applies the localized "(no subject)" / "(unknown sender)" fallbacks to a raw
     * summary projection. The search and thread read paths load
     * {@link MailSummaryResponse} directly via JPQL constructor projections (no
     * {@code @Lob} body) and re-map through this method, keeping their display
     * behavior identical to the detail mapping in {@link #toDto}.
     */
    public MailSummaryResponse withDisplayFallbacks(MailSummaryResponse raw) {
        String subject = displaySubject(raw.subject());
        String sender = displaySender(raw.sender());
        if (Objects.equals(subject, raw.subject()) && Objects.equals(sender, raw.sender())) {
            return raw;
        }
        return new MailSummaryResponse(raw.id(), raw.stableId(), raw.folderName(), subject, sender, raw.recipientsTo(),
                raw.receivedAt(), raw.seen(), raw.flagged(), raw.answered(), raw.hasAttachments(), raw.threadId(),
                raw.messageId(), raw.uid());
    }

    private String displaySubject(@Nullable String subject) {
        if (subject == null || subject.isBlank()) {
            return messageSource.getMessage("mail.message.noSubject", new Object[0], LocaleContextHolder.getLocale());
        }
        return subject;
    }

    private String displaySender(@Nullable String sender) {
        if (sender == null || sender.isBlank()) {
            return messageSource.getMessage("mail.message.unknownSender", new Object[0],
                    LocaleContextHolder.getLocale());
        }
        return sender;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
