package org.voxrox.mailbackend.feature.mail.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;

public record MailSummaryResponse(Long id,
        @Schema(description = "Stable message identifier for the REST API. The client uses it for message detail and message actions.") String stableId,
        String folderName, String subject, String sender,
        @Schema(description = "Primary recipients (To). Useful for Drafts/Sent listings where the sender is the account owner.") String recipientsTo,
        LocalDateTime receivedAt, boolean seen, boolean flagged, boolean answered, boolean hasAttachments,
        @Schema(nullable = true, description = "Conversation identifier — every message of the same thread shares it. "
                + "Null only for messages persisted before the V2 backfill completes "
                + "(see /api/internal/threading/recompute). Frontends MAY group rows by this "
                + "id; the V0.1.0 desktop client ignores it.") String threadId,
        @JsonIgnore @Schema(hidden = true) Long uid) {
}
