package org.voxrox.mailbackend.feature.mail.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response shape for {@code GET
 * /api/v1/messages/account/{accountId}/threads/{threadId}}.
 *
 * <p>
 * The {@code messages} list is ordered by {@code threadPosition} ascending,
 * which matches receivedAt ascending for the canonical case (each message was
 * assigned a position at sync time). The client renders the conversation
 * top-down from this list.
 *
 * <p>
 * Per-account scope: the controller enforces ownership before this DTO is
 * built. A thread always belongs to exactly one account; callers cannot receive
 * a thread belonging to a different account in the same response.
 */
public record ThreadResponse(
        @Schema(description = "Stable thread identifier shared by every message of the conversation.") String threadId,
        @Schema(nullable = true, description = "RFC 5322 Message-ID of the oldest message in the thread. Null when the root message has no Message-ID.") String rootMessageId,
        @Schema(description = "Number of messages in the thread.") int participantsTotal,
        @Schema(description = "Number of messages in the thread that are not yet marked as seen.") int unreadCount,
        @Schema(description = "Thread members in ascending threadPosition order.") List<MailSummaryResponse> messages) {
}
