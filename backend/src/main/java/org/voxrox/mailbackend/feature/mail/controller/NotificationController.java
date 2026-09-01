package org.voxrox.mailbackend.feature.mail.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.voxrox.mailbackend.feature.mail.dto.SendNotification;
import org.voxrox.mailbackend.feature.mail.dto.SyncCycleNotification;
import org.voxrox.mailbackend.feature.mail.dto.SyncNotification;
import org.voxrox.mailbackend.feature.mail.dto.SyncStatusNotification;
import org.voxrox.mailbackend.feature.mail.dto.ThreadUpdated;
import org.voxrox.mailbackend.feature.mail.service.SseNotificationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * SSE endpoint for real-time notifications. The desktop client subscribes via a
 * streaming HTTP read (under Tauri) or an {@code EventSource} (browser dev) and
 * receives one of seven event types:
 *
 * <ul>
 * <li>{@code sync_completed} — emitted after a folder sync that produced new
 * messages; payload is {@link SyncNotification}.</li>
 * <li>{@code sync_cycle_completed} — emitted when a user-triggered
 * whole-account pass finishes, whatever it found; payload is
 * {@link SyncCycleNotification}. The scheduled pass is silent, and the
 * per-folder {@code sync_completed} above cannot stand in for this: it is
 * suppressed when nothing was downloaded, which is the common outcome of
 * pressing Synchronise.</li>
 * <li>{@code sync_failed} — emitted when an account starts failing to sync (or
 * starts failing differently); payload is {@link SyncStatusNotification} with
 * {@code errorCode} populated.</li>
 * <li>{@code sync_recovered} — emitted when a standing sync failure clears;
 * payload is {@link SyncStatusNotification} with a null {@code errorCode}. Both
 * {@code sync_*} state events fire on the transition only, never once per pass
 * — the scheduler runs every five minutes and a per-pass event would notify
 * that often for as long as a server stays down.</li>
 * <li>{@code send_completed} — emitted when an asynchronous SMTP send finishes
 * successfully; payload is {@link SendNotification}.</li>
 * <li>{@code send_failed} — emitted when an asynchronous SMTP send fails;
 * payload is {@link SendNotification} with {@code errorCode} populated.</li>
 * <li>{@code thread_updated} — emitted when the membership of a conversation
 * changes (new message lands in a thread, or a late-arriving parent merges
 * orphan chains); payload is {@link ThreadUpdated}. V0.1.0 desktop client does
 * not subscribe to this event yet — see {@code
 * backend/docs/THREADING_DESIGN.md}.</li>
 * </ul>
 *
 * The client correlates {@code send_*} events with the originating request via
 * {@code sendId} (returned in the 202 body of the send endpoint).
 */
@Tag(name = "Notifications", description = "Real-time SSE notifications about sync progress and send outcomes.")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final SseNotificationService sseNotificationService;

    public NotificationController(SseNotificationService sseNotificationService) {
        this.sseNotificationService = sseNotificationService;
    }

    @Operation(operationId = "streamNotifications", summary = "SSE notification stream", description = "Opens a Server-Sent Events stream. The server emits one of: "
            + "`sync_completed` (after a folder sync produced new messages), "
            + "`sync_cycle_completed` (a user-triggered whole-account sync pass finished, "
            + "whatever it found; scheduled passes stay silent), "
            + "`sync_failed` / `sync_recovered` (the account's standing sync error changed; "
            + "emitted on the transition only, payload carries the AccountLastErrorCode in "
            + "`errorCode`, null on recovery), " + "`send_completed` (asynchronous SMTP send finished successfully), "
            + "`send_failed` (asynchronous SMTP send failed; payload carries the "
            + "AccountLastErrorCode in `errorCode`), or `thread_updated` (a "
            + "conversation gained a message or two orphan chains merged). A "
            + "heartbeat comment is emitted every 30 s so intermediaries do not " + "close the connection.")
    @ApiResponse(responseCode = "200", description = "Long-lived event stream. Each event has a `type` "
            + "field that identifies the variant and matches the SSE event name.", content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(oneOf = {
                    SyncNotification.class, SyncCycleNotification.class, SyncStatusNotification.class,
                    SendNotification.class, ThreadUpdated.class})))
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return sseNotificationService.register();
    }
}
