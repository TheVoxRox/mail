package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class MailOperationException extends AppException {

    public MailOperationException(ErrorCode errorCode, String message) {
        this(errorCode, message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public MailOperationException(ErrorCode errorCode, String message, HttpStatus status) {
        super(errorCode, message, status, "error.mail.operationFailed", message);
    }

    public MailOperationException(ErrorCode errorCode, String fallbackMessage, HttpStatus status, String messageKey,
            Object... messageArgs) {
        super(errorCode, fallbackMessage, status, messageKey, messageArgs);
    }
}
