package org.voxrox.mailbackend.feature.mail.service;

import java.time.LocalDateTime;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.feature.mail.dto.AttachmentResponse;

/**
 * What the sync knows about a message before it is persisted: headers, flags
 * and attachment metadata, never the body, which is fetched on first open.
 * {@link MessageFetcher} produces it from IMAP and
 * {@link DraftPersistenceService} from a draft it has just appended;
 * {@code MessageMapper.toEntity} turns it into a row. It is never serialized —
 * the REST shape of a stored message is {@code MailDetailResponse}.
 * <p>
 * The folder, the stable id and the thread id are not part of it: the caller
 * persisting the message knows the folder, and the other two are assigned at
 * persistence time. Header fields are nullable because they mirror whatever the
 * (potentially malformed) MIME message carried.
 */
public record FetchedMessage(long uid, @Nullable String subject, @Nullable String sender, @Nullable String recipientsTo,
        @Nullable String recipientsCc, @Nullable String recipientsBcc, LocalDateTime receivedAt, boolean seen,
        boolean flagged, boolean answered, @Nullable String messageId, @Nullable String inReplyTo,
        @Nullable String references, List<AttachmentResponse> attachments) {

    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }
}
