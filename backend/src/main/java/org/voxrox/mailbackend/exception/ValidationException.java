package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class ValidationException extends AppException {
    public ValidationException(String message) {
        super(ErrorCode.VALIDATION_ERROR, message, HttpStatus.BAD_REQUEST, "error.validation", message);
    }

    public ValidationException(String fallbackMessage, String messageKey, Object... messageArgs) {
        super(ErrorCode.VALIDATION_ERROR, fallbackMessage, HttpStatus.BAD_REQUEST, messageKey, messageArgs);
    }
}
