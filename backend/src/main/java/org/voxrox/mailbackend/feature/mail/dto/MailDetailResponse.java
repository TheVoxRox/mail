package org.voxrox.mailbackend.feature.mail.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The {@code body} field may be {@code null} or a cached (potentially stale)
 * version if the current content could not be loaded — in that case
 * {@code contentError} contains a description of the error.
 */
public record MailDetailResponse(
        @Schema(description = "Stable message identifier for the REST API. The client sends it to the detail, content and action endpoints.") String stableId,
        @JsonIgnore @Schema(hidden = true) Long uid, String subject, String sender, String recipientsTo,
        String recipientsCc, String body, LocalDateTime receivedAt, boolean seen, boolean flagged, boolean answered,
        String messageId, String inReplyTo, String references, boolean hasAttachments,
        List<AttachmentResponse> attachments, String contentError,
        @Schema(nullable = true, description = "Conversation identifier shared by every message of the same thread. "
                + "Null only during the V2 backfill window.") String threadId) {
}
