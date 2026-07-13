package org.voxrox.mailbackend.feature.mail.dto;

import org.jspecify.annotations.Nullable;

/**
 * Outcome of an asynchronous SMTP send, broadcast over the SSE notification
 * stream so the client can resolve its pending "sending…" indicator into a
 * success / failure notification.
 *
 * <p>
 * The payload deliberately carries no recipient or subject — the client
 * correlates the event with the original request via {@code sendId} and already
 * holds those values, so they are not duplicated over the wire.
 * {@code errorCode} is set only for {@link #TYPE_FAILED} and mirrors the
 * account {@code lastError} code. {@code recoveryDraftStableId} is set on a
 * failed send of a brand-new message whose content was parked as a draft — the
 * client can point the user at it (the composer is typically unmounted by the
 * time the outcome arrives).
 */
public record SendNotification(String type, String sendId, Long accountId, @Nullable String errorCode,
        @Nullable String recoveryDraftStableId) implements SseEvent {

    public static final String TYPE_COMPLETED = "send_completed";
    public static final String TYPE_FAILED = "send_failed";

    public static SendNotification completed(String sendId, Long accountId) {
        return new SendNotification(TYPE_COMPLETED, sendId, accountId, null, null);
    }

    public static SendNotification failed(String sendId, Long accountId, String errorCode) {
        return failed(sendId, accountId, errorCode, null);
    }

    public static SendNotification failed(String sendId, Long accountId, String errorCode,
            @Nullable String recoveryDraftStableId) {
        return new SendNotification(TYPE_FAILED, sendId, accountId, errorCode, recoveryDraftStableId);
    }
}
