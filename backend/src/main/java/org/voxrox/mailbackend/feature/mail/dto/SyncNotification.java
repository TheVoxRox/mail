package org.voxrox.mailbackend.feature.mail.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

public record SyncNotification(String type, Long accountId, String folderName,
        @Schema(description = "Every message the folder's cycle downloaded, a first sync's window and a mirror of Sent included: what the client has to reload.") int newMessagesCount,
        @Schema(description = "The part of newMessagesCount that has just arrived for the user: downloaded above the folder's existing mirror into Inbox or Newsletters. Zero for a first sync; what may be announced outside the app.") int arrivedMessagesCount,
        Instant timestamp) implements SseEvent {
}
