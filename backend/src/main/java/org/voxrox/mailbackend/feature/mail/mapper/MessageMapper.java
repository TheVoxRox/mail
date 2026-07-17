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

@Component
public class MessageMapper {

    private final MessageSource messageSource;

    public MessageMapper(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public MessageEntity toEntity(MailDetailResponse dto, AccountEntity account, String folderName, Long uidValidity) {
        MessageEntity entity = new MessageEntity();

        // Deterministic, identity-derived id (survives a folder re-download) instead
        // of a random UUID — see MessageStableId for why this matters for "ghost" 404s.
        entity.setStableId(
                MessageStableId.compute(account.getId(), folderName, dto.messageId(), dto.uid(), uidValidity));

        // Message metadata
        entity.setAccount(account);
        entity.setFolderName(folderName);
        entity.setUid(dto.uid());
        entity.setUidValidity(uidValidity);

        // Content
        entity.setSubject(blankToNull(dto.subject()));
        entity.setSender(blankToNull(dto.sender()));
        entity.setRecipientsTo(dto.recipientsTo());
        entity.setRecipientsCc(dto.recipientsCc());
        entity.setRecipientsBcc(dto.recipientsBcc());
        entity.setContent(dto.body()); // Usually null during sync; the body is fetched separately.

        // Flags and timestamps. The column is NOT NULL; MessageFetcher already
        // defaults a missing Date header to now(), this mirrors that for any
        // other producer of the DTO.
        entity.setReceivedAt(Objects.requireNonNullElse(dto.receivedAt(), java.time.LocalDateTime.now()));
        entity.setSeen(dto.seen());
        entity.setFlagged(dto.flagged());
        entity.setAnswered(dto.answered());

        // Threading
        entity.setMessageId(dto.messageId());
        entity.setInReplyTo(dto.inReplyTo());
        entity.setReferences(dto.references());

        // Attachments
        if (dto.attachments() != null) {
            dto.attachments().forEach(attDto -> {
                AttachmentEntity att = new AttachmentEntity();
                att.setPartPath(attDto.partPath());
                att.setFileName(attDto.fileName());
                att.setContentType(attDto.contentType());
                att.setSize(attDto.size());
                entity.addAttachment(att);
            });
        }

        entity.setHasAttachments(dto.hasAttachments());

        return entity;
    }

    public MailDetailResponse toDto(MessageEntity entity, @Nullable String content) {
        return toDto(entity, content, null);
    }

    /**
     * Variant for the case when the current body could not be fetched. {@code body}
     * typically holds the cached version from the DB (may be null) and the client
     * is informed about the issue via {@code contentError}.
     */
    public MailDetailResponse toDto(MessageEntity entity, @Nullable String content, @Nullable String contentError) {
        return new MailDetailResponse(entity.getStableId(), entity.getUid(), entity.getFolderName(),
                displaySubject(entity.getSubject()), displaySender(entity.getSender()), entity.getRecipientsTo(),
                entity.getRecipientsCc(), entity.getRecipientsBcc(), content, entity.getReceivedAt(), entity.isSeen(),
                entity.isFlagged(), entity.isAnswered(), entity.getMessageId(), entity.getInReplyTo(),
                entity.getReferences(), entity.isHasAttachments(),
                entity.getAttachments().stream().map(AttachmentResponse::fromEntity).toList(), contentError,
                entity.getThreadId());
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
                raw.uid());
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
