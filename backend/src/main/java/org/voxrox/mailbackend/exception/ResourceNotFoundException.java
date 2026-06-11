package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class ResourceNotFoundException extends AppException {
    public ResourceNotFoundException(String msg) {
        this(msg, "error.resource.notFoundWithDetail", msg);
    }

    public ResourceNotFoundException(String fallbackMessage, String messageKey, Object... messageArgs) {
        super(ErrorCode.RESOURCE_NOT_FOUND, fallbackMessage, HttpStatus.NOT_FOUND, messageKey, messageArgs);
    }
}
