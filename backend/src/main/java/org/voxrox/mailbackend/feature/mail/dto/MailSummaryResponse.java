package org.voxrox.mailbackend.feature.mail.dto;

import java.time.LocalDateTime;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;

public record MailSummaryResponse(Long id,
        @Schema(description = "Stable message identifier for the REST API. The client uses it for message detail and message actions.") String stableId,
        String folderName, String subject, String sender,
        @Schema(description = "Primary recipients (To). Useful for Drafts/Sent listings where the sender is the account owner.") @Nullable String recipientsTo,
        LocalDateTime receivedAt, boolean seen, boolean flagged, boolean answered, boolean hasAttachments,
        @Schema(nullable = true, description = "Conversation identifier — every message of the same thread shares it. "
                + "Null only until the threading backfill has processed the message "
                + "(see /api/internal/threading/recompute). Frontends MAY group rows by this "
                + "id; the V0.1.0 desktop client ignores it.") String threadId,
        @Schema(nullable = true, description = "RFC 5322 Message-ID. Copies of one mail stored in several folders "
                + "(Gmail's INBOX + All Mail) share it while having different stableIds, so a client rendering a "
                + "thread must collapse rows by this value to match the server's messageCount. Null when the "
                + "sender omitted the header.") String messageId,
        @JsonIgnore @Schema(hidden = true) Long uid) {
}
