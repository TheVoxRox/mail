package org.voxrox.mailbackend.feature.mail.mapper;

import java.util.UUID;

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

        entity.setStableId(UUID.randomUUID().toString().replace("-", ""));

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
        entity.setContent(dto.body()); // Usually null during sync; the body is fetched separately.

        // Flags and timestamps
        entity.setReceivedAt(dto.receivedAt());
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

    public MailDetailResponse toDto(MessageEntity entity, String content) {
        return toDto(entity, content, null);
    }

    /**
     * Variant for the case when the current body could not be fetched. {@code body}
     * typically holds the cached version from the DB (may be null) and the client
     * is informed about the issue via {@code contentError}.
     */
    public MailDetailResponse toDto(MessageEntity entity, String content, String contentError) {
        return new MailDetailResponse(entity.getStableId(), entity.getUid(), displaySubject(entity.getSubject()),
                displaySender(entity.getSender()), entity.getRecipientsTo(), entity.getRecipientsCc(), content,
                entity.getReceivedAt(), entity.isSeen(), entity.isFlagged(), entity.isAnswered(), entity.getMessageId(),
                entity.getInReplyTo(), entity.getReferences(), entity.isHasAttachments(),
                entity.getAttachments().stream().map(AttachmentResponse::fromEntity).toList(), contentError,
                entity.getThreadId());
    }

    public MailSummaryResponse toSummaryDto(MessageEntity entity) {
        return new MailSummaryResponse(entity.getId(), entity.getStableId(), entity.getFolderName(),
                displaySubject(entity.getSubject()), displaySender(entity.getSender()), entity.getRecipientsTo(),
                entity.getReceivedAt(), entity.isSeen(), entity.isFlagged(), entity.isAnswered(),
                entity.isHasAttachments(), entity.getThreadId(), entity.getUid());
    }

    private String displaySubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return messageSource.getMessage("mail.message.noSubject", new Object[0], LocaleContextHolder.getLocale());
        }
        return subject;
    }

    private String displaySender(String sender) {
        if (sender == null || sender.isBlank()) {
            return messageSource.getMessage("mail.message.unknownSender", new Object[0],
                    LocaleContextHolder.getLocale());
        }
        return sender;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
