package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class MailConnectionException extends AppException {

    /**
     * A connection failure wrapping what caused it. The user is told the
     * {@link MailFailureCause}; {@code logMessage} and the cause's own text stay in
     * the log (API surface audit, §3).
     */
    public MailConnectionException(String logMessage, Throwable cause) {
        super(ErrorCode.MAIL_CONNECTION_ERROR, logMessage, HttpStatus.SERVICE_UNAVAILABLE, cause,
                "error.mail.connectionFailed", MailFailureCause.classify(cause));
    }

    public MailConnectionException(String fallbackMessage, String messageKey, Object... messageArgs) {
        super(ErrorCode.MAIL_CONNECTION_ERROR, fallbackMessage, HttpStatus.SERVICE_UNAVAILABLE, messageKey,
                messageArgs);
    }
}
