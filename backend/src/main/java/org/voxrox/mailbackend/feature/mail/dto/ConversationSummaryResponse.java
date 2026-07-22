package org.voxrox.mailbackend.feature.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of the conversation-grouped folder listing (Threading Phase 2). Each
 * row collapses every message of one conversation <em>within the requested
 * folder</em> into a single entry, represented by its newest message.
 *
 * <p>
 * Scope note: threading itself is per-account and cross-folder (a sent reply
 * shares the inbox thread), but this listing groups only the folder's own
 * messages, so {@code messageCount} / {@code unreadCount} count the members
 * present in that folder. The full cross-folder conversation is available via
 * {@code GET /api/v1/messages/account/{accountId}/threads/{threadId}}.
 */
public record ConversationSummaryResponse(
        @Schema(nullable = true, description = "Conversation identifier shared by every message of the thread. "
                + "Null only for a message the threading backfill has not processed yet; such a row is a "
                + "singleton (messageCount = 1) and cannot be expanded via the thread endpoint.") String threadId,
        @Schema(description = "Newest message of the conversation in this folder — the row's representative.") MailSummaryResponse latest,
        @Schema(description = "Number of messages of this conversation present in the folder (>= 1).") int messageCount,
        @Schema(description = "Number of those messages that are not yet marked as seen.") int unreadCount) {
}
