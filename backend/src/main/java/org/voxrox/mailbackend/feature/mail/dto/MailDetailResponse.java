package org.voxrox.mailbackend.feature.mail.dto;

import java.time.LocalDateTime;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The {@code body} field may be {@code null} or a cached (potentially stale)
 * version if the current content could not be loaded — in that case
 * {@code contentError} contains a description of the error. Most components are
 * nullable: the record mirrors whatever the (potentially malformed) MIME
 * message carried, and {@code stableId}/{@code threadId} are assigned only at
 * persistence time.
 */
public record MailDetailResponse(
        @Schema(description = "Stable message identifier for the REST API. The client sends it to the detail, content and action endpoints.") @Nullable String stableId,
        @JsonIgnore @Schema(hidden = true) Long uid,
        @Schema(description = "Folder the message lives in (folderRef). Lets the client warn before DELETE on a message"
                + " whose folder has the TRASH role — there the delete is permanent (server-side expunge), not a move"
                + " to trash.") String folderName,
        @Nullable String subject, @Nullable String sender, @Nullable String recipientsTo, @Nullable String recipientsCc,
        @Schema(description = "Only ever present on the user's own draft/sent copies; received mail never carries the header.") @Nullable String recipientsBcc,
        @Nullable String body, @Nullable LocalDateTime receivedAt, boolean seen, boolean flagged, boolean answered,
        @Nullable String messageId, @Nullable String inReplyTo, @Nullable String references, boolean hasAttachments,
        List<AttachmentResponse> attachments, @Nullable String contentError,
        @Schema(nullable = true, description = "Conversation identifier shared by every message of the same thread. "
                + "Null only until the threading backfill has processed the message.") @Nullable String threadId) {
}
