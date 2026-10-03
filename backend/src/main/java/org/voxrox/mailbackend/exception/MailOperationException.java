package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class MailOperationException extends AppException {

    /**
     * A failed operation the user can do nothing about — a guard on the app's own
     * state, such as a provider with no registered implementation. The user is told
     * the operation failed unexpectedly; {@code logMessage} is English and stays in
     * the log. A failure the user can act on takes its own message key instead,
     * through the constructor that names one.
     */
    public MailOperationException(ErrorCode errorCode, String logMessage) {
        super(errorCode, logMessage, HttpStatus.INTERNAL_SERVER_ERROR, "error.mail.operationFailed",
                MailFailureCause.UNEXPECTED);
    }

    /**
     * A failed operation wrapping what caused it. The user is told the
     * {@link MailFailureCause}; {@code logMessage} and the cause's own text stay in
     * the log (API surface audit, §3).
     */
    public MailOperationException(ErrorCode errorCode, String logMessage, Throwable cause) {
        super(errorCode, logMessage, HttpStatus.INTERNAL_SERVER_ERROR, cause, "error.mail.operationFailed",
                MailFailureCause.classify(cause));
    }

    public MailOperationException(ErrorCode errorCode, String fallbackMessage, HttpStatus status, String messageKey,
            Object... messageArgs) {
        super(errorCode, fallbackMessage, status, messageKey, messageArgs);
    }
}
