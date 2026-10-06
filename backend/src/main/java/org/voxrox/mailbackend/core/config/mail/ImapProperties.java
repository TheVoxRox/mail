package org.voxrox.mailbackend.core.config.mail;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * IMAP client tuning: default port, protocol names, socket timeouts, the budget
 * a read request may spend resolving a folder role, and what an open folder may
 * keep.
 *
 * @param writeTimeout
 *            how long one {@code write} to the server may block before the
 *            socket is closed under it (IMAP/SMTP audit B1-6). It bounds a
 *            single write call, not a whole command, so a slow link never runs
 *            into it: what has to finish inside the budget is one socket buffer
 *            draining, not an APPEND. Without it a server that stops reading
 *            blocks the writing thread for good, and with it that thread's
 *            account lane.
 * @param roleLookupTimeout
 *            how long a read request may wait for the account's IMAP connection
 *            when resolving a folder role it cannot answer from the DB. Short
 *            on purpose: the caller degrades to a folder-scoped answer, which
 *            is correct but narrower, and a user waiting on a message list
 *            would rather have that immediately than the fuller answer after a
 *            sync cycle finishes. Only ever paid once per account thanks to the
 *            folder-list TTL cache.
 * @param interactiveLaneRetryAfter
 *            how long to stop attempting a second (interactive) connection for
 *            an account after one failed. The likely cause is a server limit on
 *            simultaneous sessions, which does not clear within a request, and
 *            without this every interactive operation would pay a full connect
 *            plus its retries before degrading to the shared connection —
 *            slower than having no second lane at all. Long enough to stop
 *            paying that repeatedly, short enough that a transient limit is not
 *            treated as permanent.
 * @param openFolderBudget
 *            what one open folder may keep of the FETCH responses it is handed,
 *            as the protocol estimates the heap they take (IMAP/SMTP audit
 *            B1-14); past it the connection is closed, which releases them.
 *            Configurable because the default rests on estimates rather than on
 *            a measured honest pass: a beta tester whose folder keeps running
 *            into it can raise it without a new build. See
 *            {@code BoundedImapProtocol.DEFAULT_OPEN_FOLDER_BUDGET}.
 */
public record ImapProperties(@Min(1) @Max(65535) @DefaultValue("993") int defaultPort,
        @NotNull @DefaultValue("30s") Duration connectionTimeout, @NotNull @DefaultValue("60s") Duration readTimeout,
        @NotNull @DefaultValue("60s") Duration writeTimeout, @NotBlank @DefaultValue("imaps") String protocolSsl,
        @NotBlank @DefaultValue("imap") String protocolStandard,
        @NotNull @DefaultValue("1s") Duration roleLookupTimeout,
        @NotNull @DefaultValue("5m") Duration interactiveLaneRetryAfter,
        @NotNull @DefaultValue("64MB") DataSize openFolderBudget) {

    public ImapProperties {
        // Zero would refuse every FETCH, so every folder; a startup failure says so
        // where a silent fallback to the default would not.
        if (openFolderBudget.toBytes() <= 0) {
            throw new IllegalArgumentException(
                    "mail.client.imap.open-folder-budget must be positive, was " + openFolderBudget);
        }
    }
}
