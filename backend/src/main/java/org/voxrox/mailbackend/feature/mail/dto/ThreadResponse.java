package org.voxrox.mailbackend.feature.mail.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

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
 *
 * <p>
 * Folder scope: with a {@code folderRef} this response mirrors that folder's
 * conversation row field for field. {@code participantsTotal} equals its
 * {@code messageCount} (the members are exactly the ones it counted) and
 * {@code unreadCount} equals its
 * {@link ConversationSummaryResponse#unreadCount()} — folder-scoped even when
 * the member list is cross-folder, because marking read from that row only
 * reaches the folder's own messages. Without a {@code folderRef} both counts
 * describe the whole account-wide thread.
 */
public record ThreadResponse(
        @Schema(description = "Stable thread identifier shared by every message of the conversation.") String threadId,
        @Schema(nullable = true, description = "RFC 5322 Message-ID of the oldest message in the thread. Null when the root message has no Message-ID.") @Nullable String rootMessageId,
        @Schema(description = "Number of returned messages. With folderRef this is that folder's conversation messageCount.") int participantsTotal,
        @Schema(description = "Unread messages of the conversation. With folderRef only those in that folder (matching the row's unreadCount); otherwise all unseen members.") int unreadCount,
        @Schema(description = "Thread members in ascending threadPosition order, scoped by folderRef when given.") List<MailSummaryResponse> messages) {
}
