package org.voxrox.mailbackend.exception;

import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.FileSystemException;
import java.security.cert.CertificateException;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Predicate;

import javax.net.ssl.SSLException;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FolderClosedException;
import jakarta.mail.SendFailedException;
import jakarta.mail.StoreClosedException;

import org.eclipse.angus.mail.iap.BadCommandException;
import org.eclipse.angus.mail.iap.CommandFailedException;
import org.eclipse.angus.mail.iap.ConnectionException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessException;

/**
 * Why a mail operation failed, as a stable code the user is told in their own
 * language, instead of the text the failure carried. That text is a mail
 * library's, a server's or a database's — a server's reply, a host name, a TLS
 * error, an SQL statement — in English, and it went into the Czech message the
 * user saw (API surface audit, §3). It stays in the log, where it helps; the
 * response and the account's last error carry this code.
 *
 * <p>
 * A {@link MessageSourceResolvable}, so a code passed as a message argument is
 * resolved in the caller's locale by the message source itself; as JSON it is
 * its name.
 */
public enum MailFailureCause implements MessageSourceResolvable {
    // spotless:off
    REFUSED_RESPONSE("error.mail.cause.refusedResponse", "the server sent a response the app refused for safety"),
    AUTHENTICATION("error.mail.cause.authentication", "the server rejected the sign-in"),
    TLS("error.mail.cause.tls", "a secure connection to the server could not be established"),
    UNKNOWN_HOST("error.mail.cause.unknownHost", "the server could not be found"),
    TIMEOUT("error.mail.cause.timeout", "the server did not answer in time"),
    RECIPIENTS_REJECTED("error.mail.cause.recipientsRejected", "the server rejected one or more recipients"),
    SERVER_REJECTED("error.mail.cause.serverRejected", "the server rejected the request"),
    CONNECTION("error.mail.cause.connection", "the connection to the server failed or was interrupted"),
    STORAGE("error.mail.cause.storage", "the app's local database failed"),
    LOCAL_FILE("error.mail.cause.localFile", "a file on this computer could not be read or written"),
    UNEXPECTED("error.mail.cause.unexpected", "unexpected error, the details are in the app's log");
    // spotless:on

    /**
     * What {@code BoundedImapProtocol} refusals start with. Angus turns the
     * refusal, an {@code IOException}, into a synthetic BYE whose text carries it,
     * and does not keep the exception as a cause, so the text is the only trace of
     * it by the time the failure arrives here.
     */
    public static final String REFUSED_RESPONSE_MARKER = "Refused an implausible IMAP response";

    /** How deep {@link #classify} follows a cause chain. */
    private static final int MAX_CHAIN = 20;

    private final String messageKey;
    private final String defaultMessage;

    MailFailureCause(String messageKey, String defaultMessage) {
        this.messageKey = messageKey;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String[] getCodes() {
        return new String[]{messageKey};
    }

    @Override
    public String getDefaultMessage() {
        return defaultMessage;
    }

    /**
     * The cause a failure is reported as. Each rule is tried against the whole
     * cause chain, most specific first, because the same failure arrives wrapped
     * differently on different paths: a refused response and a rejected sign-in
     * decide the answer wherever in the chain they sit, and a socket error does
     * only when nothing more specific is there.
     */
    public static MailFailureCause classify(@Nullable Throwable failure) {
        if (failure == null) {
            return UNEXPECTED;
        }
        if (anyInChain(failure, MailFailureCause::isRefusedResponse)) {
            return REFUSED_RESPONSE;
        }
        if (anyInChain(failure,
                e -> e instanceof AuthenticationFailedException || e instanceof MailAuthenticationException)) {
            return AUTHENTICATION;
        }
        if (anyInChain(failure, e -> e instanceof SSLException || e instanceof CertificateException)) {
            return TLS;
        }
        if (anyInChain(failure, UnknownHostException.class::isInstance)) {
            return UNKNOWN_HOST;
        }
        if (anyInChain(failure, SocketTimeoutException.class::isInstance)) {
            return TIMEOUT;
        }
        if (anyInChain(failure, e -> e instanceof SendFailedException sent && hasInvalidAddresses(sent))) {
            return RECIPIENTS_REJECTED;
        }
        if (anyInChain(failure, e -> e instanceof SendFailedException || e instanceof CommandFailedException
                || e instanceof BadCommandException)) {
            return SERVER_REJECTED;
        }
        if (anyInChain(failure,
                e -> e instanceof ConnectException || e instanceof NoRouteToHostException
                        || e instanceof SocketException || e instanceof MailConnectException
                        || e instanceof ConnectionException || e instanceof StoreClosedException
                        || e instanceof FolderClosedException || e instanceof MailConnectionException)) {
            return CONNECTION;
        }
        if (anyInChain(failure, e -> e instanceof DataAccessException || e instanceof SQLException)) {
            return STORAGE;
        }
        if (anyInChain(failure, e -> e instanceof FileSystemException || e instanceof FileNotFoundException)) {
            return LOCAL_FILE;
        }
        return UNEXPECTED;
    }

    /** The cause an account's last error stored under {@code name}, if any. */
    public static Optional<MailFailureCause> fromName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean anyInChain(Throwable failure, Predicate<Throwable> rule) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CHAIN; depth++) {
            if (rule.test(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isRefusedResponse(Throwable e) {
        String message = e.getMessage();
        return message != null && message.contains(REFUSED_RESPONSE_MARKER);
    }

    private static boolean hasInvalidAddresses(SendFailedException e) {
        return e.getInvalidAddresses() != null && e.getInvalidAddresses().length > 0;
    }
}
