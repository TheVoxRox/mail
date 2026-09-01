package org.voxrox.mailbackend.feature.mail.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * End of a user-triggered account sync pass, {@code sync_cycle_completed}.
 *
 * <p>
 * The counterpart of {@link SyncNotification}, which reports a single folder
 * and is suppressed when that folder downloaded nothing. That suppression is
 * why this event has to exist separately: a sync that found no new mail
 * produced no event at all, so the client had nothing to turn "Synchronising…"
 * back into "Synchronise" with, and a screen-reader user never learned the
 * operation had ended.
 *
 * <p>
 * Broadcast for manual passes only — see {@code SyncTrigger}. The scheduled
 * pass runs every five minutes and would otherwise announce itself that often.
 */
public record SyncCycleNotification(String type, Long accountId,
        @Schema(description = "Messages that arrived for the user across the pass, zero included. Mirrored downloads into Sent, Drafts, Junk and Trash are not counted — they are not new mail.") int newMessagesCount,
        @Schema(description = "Whether every role-matched folder actually ran. When false the count is a floor, not a total, and a zero must not be reported to the user as \"nothing arrived\".") boolean allFoldersSynced,
        Instant timestamp) implements SseEvent {

    public static SyncCycleNotification completed(Long accountId, int newMessagesCount, boolean allFoldersSynced,
            Instant timestamp) {
        return new SyncCycleNotification("sync_cycle_completed", accountId, newMessagesCount, allFoldersSynced,
                timestamp);
    }
}
