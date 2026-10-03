package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class ResourceNotFoundException extends AppException {

    /**
     * {@code fallbackMessage} is English and goes to the log; the client is told
     * {@code messageKey}, rendered in the request's language. There is no
     * constructor taking the message alone: it passed the message as the key's
     * argument, and the English sentence reached the user inside a Czech one (API
     * surface audit, §3).
     */
    public ResourceNotFoundException(String fallbackMessage, String messageKey, Object... messageArgs) {
        super(ErrorCode.RESOURCE_NOT_FOUND, fallbackMessage, HttpStatus.NOT_FOUND, messageKey, messageArgs);
    }
}
