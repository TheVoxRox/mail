package org.voxrox.mailbackend.core.config.mail;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param roleLookupTimeout
 *            how long a read request may wait for the account's IMAP connection
 *            when resolving a folder role it cannot answer from the DB. Short
 *            on purpose: the caller degrades to a folder-scoped answer, which
 *            is correct but narrower, and a user waiting on a message list
 *            would rather have that immediately than the fuller answer after a
 *            sync cycle finishes. Only ever paid once per account thanks to the
 *            folder-list TTL cache.
 */
public record ImapProperties(@Min(1) @Max(65535) @DefaultValue("993") int defaultPort,
        @NotNull @DefaultValue("30s") Duration connectionTimeout, @NotNull @DefaultValue("60s") Duration readTimeout,
        @NotBlank @DefaultValue("imaps") String protocolSsl, @NotBlank @DefaultValue("imap") String protocolStandard,
        @NotNull @DefaultValue("1s") Duration roleLookupTimeout) {
}
