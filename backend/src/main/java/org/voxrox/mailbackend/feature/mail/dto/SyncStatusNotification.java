package org.voxrox.mailbackend.feature.mail.dto;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * Outcome of a sync pass whose error state differs from the previous pass:
 * {@code sync_failed} when the account started failing (or started failing
 * differently), {@code sync_recovered} when a standing failure cleared.
 *
 * <p>
 * Emitted on the transition only, never per pass — see
 * {@code MailSyncErrorStateChangedEvent}. Without it a persistently failing
 * account is invisible in the mail view: the list simply stops changing, and
 * the standing error is only rendered in Settings &rarr; Accounts, which is not
 * where anyone looks when mail seems to have stopped arriving.
 *
 * <p>
 * The payload carries the code rather than a rendered message, like
 * {@link SendNotification} — user copy is resolved client-side from the
 * {@code account.lastError.*} keys, which already exist for the Settings view.
 * Placeholder values (folder, error class, detail) are deliberately not
 * duplicated here: the client refetches the account, so the notification and
 * Settings can never disagree about the same failure.
 */
public record SyncStatusNotification(String type, Long accountId, @Nullable String errorCode,
        Instant timestamp) implements SseEvent {

    public static SyncStatusNotification failed(Long accountId, String errorCode, Instant timestamp) {
        return new SyncStatusNotification("sync_failed", accountId, errorCode, timestamp);
    }

    public static SyncStatusNotification recovered(Long accountId, Instant timestamp) {
        return new SyncStatusNotification("sync_recovered", accountId, null, timestamp);
    }
}
