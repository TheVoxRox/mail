package org.voxrox.mailbackend.feature.mail.service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLException;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FolderClosedException;
import jakarta.mail.StoreClosedException;

import org.voxrox.mailbackend.util.Throwables;

/**
 * Classifies an IMAP failure as a <em>transient</em> connectivity blip that is
 * worth retrying after dropping and rebuilding the connection, versus a
 * permanent failure that must surface to the user immediately.
 *
 * <p>
 * The retryable set is intentionally aligned with the {@code imapRetryTemplate}
 * in {@link org.voxrox.mailbackend.core.config.RetryConfig}: that template
 * already absorbs transient errors around {@code store.connect()}, but Angus
 * Mail's {@code IMAPStore} grows its internal protocol-connection pool lazily,
 * so a network blip <em>during</em> folder operations (fetch, flag sync)
 * escapes the connect-time retry and surfaces as the
 * {@code MessagingException("failed
 * to create new store connection")} seen in the v0.1.0 smoke (todo.md bug D).
 * This helper lets {@link MailSyncService} catch that class at the folder-sync
 * seam and retry it instead of recording it as a hard {@code last_error}.
 *
 * <p>
 * {@link AuthenticationFailedException} is never transient — it has a dedicated
 * one-shot refresh-token path in
 * {@link ImapConnectionManager#executeWithLock(Long, ImapConnectionManager.StoreAction)};
 * a backoff cannot heal a stale or revoked token, so it is excluded even when
 * it appears wrapped deeper in the cause chain.
 */
final class TransientMailErrors {

    /**
     * Angus Mail {@code IMAPStore} message when its pool cannot open a new protocol
     * connection.
     */
    private static final String NEW_STORE_CONNECTION_FAILURE = "failed to create new store connection";

    private TransientMailErrors() {
    }

    /**
     * Walks the cause chain and decides whether {@code error} is a transient IMAP
     * connectivity failure. An authentication failure anywhere in the chain wins
     * (never transient).
     */
    static boolean isTransient(Throwable error) {
        // Bounded walk (see Throwables.causalChain) — an undetected transient cause
        // past the bound degrades to a recorded last_error, never to a hang.
        List<Throwable> chain = Throwables.causalChain(error);
        // Pass 1: an auth failure anywhere in the chain is decisive — it owns the
        // refresh-token path and must not be retried with a backoff here.
        for (Throwable cur : chain) {
            if (cur instanceof AuthenticationFailedException) {
                return false;
            }
        }
        // Pass 2: look for a genuinely transient network/connection error.
        for (Throwable cur : chain) {
            if (cur instanceof SocketTimeoutException || cur instanceof ConnectException
                    || cur instanceof UnknownHostException || cur instanceof SSLException
                    || cur instanceof StoreClosedException || cur instanceof FolderClosedException
                    || cur instanceof IOException) {
                return true;
            }
            String message = cur.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(NEW_STORE_CONNECTION_FAILURE)) {
                return true;
            }
        }
        return false;
    }
}
