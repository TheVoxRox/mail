package org.voxrox.mailbackend.feature.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of the conversation-grouped folder listing (Threading Phase 2). A
 * conversation appears when it has at least one message in the requested
 * folder, represented by its newest message <em>in that folder</em>.
 *
 * <p>
 * Scope note: for a regular folder {@code messageCount} is cross-folder — it
 * spans every message of the thread across the account except the trash, junk,
 * drafts and sent folders, so a conversation filed into an archive still counts
 * the copies left in the inbox, while neither the user's own reply nor the
 * half-written one is part of it. Copies of a single mail in several folders
 * (Gmail's INBOX + All Mail) count once. {@code unreadCount} stays
 * folder-scoped in every view, because marking read from this listing only
 * reaches the folder's own messages. Trash, Junk, Drafts and Sent views are
 * folder-scoped throughout. The messages behind {@code messageCount} are
 * available via {@code GET
 * /api/v1/messages/account/{accountId}/threads/{threadId}?folderRef=}, which
 * applies this same scope — pass the folder this row came from and the returned
 * list has exactly {@code messageCount} entries.
 */
public record ConversationSummaryResponse(
        @Schema(nullable = true, description = "Conversation identifier shared by every message of the thread. "
                + "Null only for a message the threading backfill has not processed yet; such a row is a "
                + "singleton (messageCount = 1) and cannot be expanded via the thread endpoint.") String threadId,
        @Schema(description = "Newest message of the conversation in this folder — the row's representative.") MailSummaryResponse latest,
        @Schema(description = "Number of messages of this conversation (>= 1). For a regular folder it is "
                + "cross-folder except trash/junk/drafts/sent, counting a mail stored in several folders once; "
                + "folder-scoped in Trash, Junk, Drafts and Sent views.") int messageCount,
        @Schema(description = "Number of messages of this conversation in THIS folder that are not yet marked "
                + "as seen — folder-scoped in every view, unlike messageCount.") int unreadCount) {
}
