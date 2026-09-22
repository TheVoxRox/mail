package org.voxrox.mailbackend.feature.mail.dto;

import java.time.LocalDateTime;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Everything about a stored message except its body, which
 * {@link MailContentResponse} carries; built from the local database only.
 * Header components are nullable because they mirror whatever the (potentially
 * malformed) MIME message carried. What the sync holds before a message is
 * stored is a different record, {@code FetchedMessage}.
 */
public record MailDetailResponse(
        @Schema(description = "Stable message identifier for the REST API. The client sends it to the detail, content and action endpoints.") @Nullable String stableId,
        @Schema(description = "Folder the message lives in (folderRef). Lets the client warn before DELETE on a message"
                + " whose folder has the TRASH role — there the delete is permanent (server-side expunge), not a move"
                + " to trash.") String folderName,
        @Nullable String subject, @Nullable String sender, @Nullable String recipientsTo, @Nullable String recipientsCc,
        @Schema(description = "Only ever present on the user's own draft/sent copies; received mail never carries the header.") @Nullable String recipientsBcc,
        @Nullable LocalDateTime receivedAt, boolean seen, boolean flagged, boolean answered, @Nullable String messageId,
        @Nullable String inReplyTo, @Nullable String references, boolean hasAttachments,
        List<AttachmentResponse> attachments,
        @Schema(nullable = true, description = "Conversation identifier shared by every message of the same thread. "
                + "Null only until the threading backfill has processed the message.") @Nullable String threadId) {
}
