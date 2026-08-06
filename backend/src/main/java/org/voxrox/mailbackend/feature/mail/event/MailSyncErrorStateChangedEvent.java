package org.voxrox.mailbackend.feature.mail.event;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * Published when a sync pass leaves the account's standing error slot in a
 * different state than it found it: a first failure, a failure of a different
 * kind, or a recovery ({@code errorCode == null}).
 *
 * <p>
 * Edge-triggered by design. The scheduler runs a pass every five minutes, so an
 * event per failed pass would mean a notification every five minutes for as
 * long as a server stays unreachable. What the user needs to learn is that the
 * state changed — the standing error itself is rendered from the account, not
 * from a stream of events.
 *
 * <p>
 * Evaluated once at the end of the pass rather than at each write site. The
 * slot is written from several places during a pass (folder failure, account
 * failure, an OAuth refresh rejected inside {@code OAuth2TokenService}), and
 * comparing at the boundary catches all of them without every writer having to
 * know about notifications.
 */
public record MailSyncErrorStateChangedEvent(Long accountId, @Nullable String errorCode, Instant timestamp) {
}
