package org.voxrox.mailbackend.feature.mail.event;

import java.time.Instant;

/**
 * A whole-account sync pass finished. Unlike {@link MailSyncCompletedEvent},
 * which reports one folder and only ever fires when that folder produced
 * something, this fires once per pass whatever the outcome — that is what makes
 * it usable as the "your sync is done" signal for a user who is waiting.
 *
 * @param newMessagesCount
 *            messages downloaded across every folder of the pass, zero included
 */
public record MailSyncCycleCompletedEvent(Long accountId, int newMessagesCount, Instant timestamp) {
}
