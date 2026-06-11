package org.voxrox.mailbackend.feature.mail.dto;

import java.time.Instant;

public record SyncNotification(String type, Long accountId, String folderName, int newMessagesCount,
        Instant timestamp) implements SseEvent {
}
