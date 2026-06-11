package org.voxrox.mailbackend.feature.mail.event;

import java.time.Instant;

public record MailSyncCompletedEvent(Long accountId, String folderName, int newMessagesCount, Instant timestamp) {
}
