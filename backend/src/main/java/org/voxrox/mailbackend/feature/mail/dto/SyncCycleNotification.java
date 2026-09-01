package org.voxrox.mailbackend.feature.mail.dto;

import java.time.Instant;

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
public record SyncCycleNotification(String type, Long accountId, int newMessagesCount,
        Instant timestamp) implements SseEvent {

    public static SyncCycleNotification completed(Long accountId, int newMessagesCount, Instant timestamp) {
        return new SyncCycleNotification("sync_cycle_completed", accountId, newMessagesCount, timestamp);
    }
}
