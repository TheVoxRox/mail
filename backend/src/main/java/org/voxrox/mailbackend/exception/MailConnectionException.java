package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class MailConnectionException extends AppException {
    public MailConnectionException(String msg) {
        this(msg, "error.mail.connectionFailed", msg);
    }

    public MailConnectionException(String msg, Throwable cause) {
        super(ErrorCode.MAIL_CONNECTION_ERROR, msg, HttpStatus.SERVICE_UNAVAILABLE, cause,
                "error.mail.connectionFailed", msg);
    }

    public MailConnectionException(String fallbackMessage, String messageKey, Object... messageArgs) {
        super(ErrorCode.MAIL_CONNECTION_ERROR, fallbackMessage, HttpStatus.SERVICE_UNAVAILABLE, messageKey,
                messageArgs);
    }
}
