package org.voxrox.mailbackend.core.config.mail;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of retry logic for IMAP connect. Applied ONLY to transient
 * network errors (SocketTimeoutException, ConnectException, SSLException
 * without an auth subtype). Authentication failures
 * {@link jakarta.mail.AuthenticationFailedException} are not retried — they
 * have their own one-shot refresh-token path in
 * {@link org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager#executeWithLock}.
 *
 * Defaults target a typical "Wi-Fi blip" duration: 3 attempts with ~500 ms → 1
 * s → 2 s backoff (jittered) for at most ~4 s of total delay before the error
 * is propagated.
 */
public record RetryProperties(@Min(1) @Max(10) @DefaultValue("3") int maxAttempts,
        @NotNull @DefaultValue("500ms") Duration initialDelay, @NotNull @DefaultValue("5s") Duration maxDelay,
        @DefaultValue("2.0") double multiplier) {
}
