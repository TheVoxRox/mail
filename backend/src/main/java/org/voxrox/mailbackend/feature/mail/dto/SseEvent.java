package org.voxrox.mailbackend.feature.mail.dto;

/**
 * Marker for events broadcast over the SSE notification stream. The
 * {@link #type()} value becomes the SSE event name and the implementing record
 * is serialized (Jackson) as the event data payload.
 */
public sealed interface SseEvent permits SyncNotification, SendNotification, ThreadUpdated {

    String type();
}
