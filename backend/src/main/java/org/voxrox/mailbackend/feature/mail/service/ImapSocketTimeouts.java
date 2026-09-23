package org.voxrox.mailbackend.feature.mail.service;

import java.util.Properties;

import org.voxrox.mailbackend.core.config.mail.ImapProperties;

/**
 * The socket timeouts of an IMAP session, shared by the connection pool
 * ({@link ImapConnectionManager}) and the credential probe
 * ({@link MailConnectionProbe}) so the two can never disagree about them — the
 * same reason {@link ImapTransportSecurity} exists for the TLS half.
 * <p>
 * All three bounds are set, the write one since the B1-6 fix. Without it a
 * server that stops reading — a full TCP window in the middle of an APPEND, a
 * command left unanswered — blocked the writing thread with nothing to end it,
 * and that thread holds its account's lane lock for as long as it is blocked,
 * so the account stopped syncing until the process was restarted. The bound is
 * per {@code write} call rather than per command: a healthy server takes the
 * bytes as fast as the link carries them, so the budget only has to cover one
 * socket buffer draining, which is why it can sit at the read timeout's value
 * without ever cutting a large APPEND short.
 * <p>
 * JavaMail's {@code PropUtil} reads only String and Integer values out of
 * Properties, so each duration goes in as a millisecond string: a raw
 * {@link java.time.Duration} is silently ignored and leaves the connection with
 * no effective timeout at all. The scheduler is the exception — Angus reads
 * that one back as an object, and it is shared rather than left to the
 * per-connection default (see {@link MailWriteTimeoutScheduler}).
 */
final class ImapSocketTimeouts {

    static void configure(Properties props, String protocol, ImapProperties imap) {
        props.put("mail." + protocol + ".timeout", String.valueOf(imap.readTimeout().toMillis()));
        props.put("mail." + protocol + ".connectiontimeout", String.valueOf(imap.connectionTimeout().toMillis()));
        props.put("mail." + protocol + ".writetimeout", String.valueOf(imap.writeTimeout().toMillis()));
        props.put("mail." + protocol + ".executor.writetimeout", MailWriteTimeoutScheduler.shared());
    }

    private ImapSocketTimeouts() {
    }
}
