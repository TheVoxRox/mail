package org.voxrox.mailbackend.feature.mail.event;

import java.time.Instant;

/**
 * A whole-account sync pass finished. Unlike {@link MailSyncCompletedEvent},
 * which reports one folder and only ever fires when that folder produced
 * something, this fires once per pass whatever the outcome — that is what makes
 * it usable as the "your sync is done" signal for a user who is waiting.
 *
 * @param newMessagesCount
 *            messages that arrived for the user across the pass, zero included.
 *            Counts only folders that deliver new mail — a mirrored SENT or
 *            TRASH download is not new mail.
 * @param allFoldersSynced
 *            whether every role-matched folder actually ran. When false the
 *            count is a floor, not a total: a folder skipped because its own
 *            cycle was already running downloads without this pass seeing it,
 *            so a zero must not be reported as "nothing arrived".
 */
public record MailSyncCycleCompletedEvent(Long accountId, int newMessagesCount, boolean allFoldersSynced,
        Instant timestamp) {
}
