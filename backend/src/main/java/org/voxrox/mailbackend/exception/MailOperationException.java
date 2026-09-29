package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class MailOperationException extends AppException {

    public MailOperationException(ErrorCode errorCode, String message) {
        this(errorCode, message, HttpStatus.INTERNAL_SERVER_ERROR);
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

    public MailOperationException(ErrorCode errorCode, String message, HttpStatus status) {
        super(errorCode, message, status, "error.mail.operationFailed", message);
    }

    public MailOperationException(ErrorCode errorCode, String fallbackMessage, HttpStatus status, String messageKey,
            Object... messageArgs) {
        super(errorCode, fallbackMessage, status, messageKey, messageArgs);
    }
}
