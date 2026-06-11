package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public abstract sealed class AppException extends RuntimeException
        permits AccountAlreadyExistsException, AccountNotFoundException, ContactNotFoundException,
        DuplicateContactException, MailAuthenticationException, MailConnectionException, MailOperationException,
        ProviderNotFoundException, ResourceNotFoundException, ValidationException {
    private final ErrorCode code;
    private final HttpStatus status;
    private final String messageKey;
    private final Object[] messageArgs;

    public AppException(ErrorCode code, String message, HttpStatus status) {
        this(code, message, status, null);
    }

    public AppException(ErrorCode code, String fallbackMessage, HttpStatus status, String messageKey,
            Object... messageArgs) {
        this(code, fallbackMessage, status, null, messageKey, messageArgs);
    }

    /**
     * Variant carrying the underlying cause. Use whenever the AppException wraps a
     * lower-level exception (network, protocol, IO) — async logging call sites
     * print the full chain, so without the cause the root stack trace would be lost
     * at the wrap point.
     */
    public AppException(ErrorCode code, String fallbackMessage, HttpStatus status, Throwable cause, String messageKey,
            Object... messageArgs) {
        super(fallbackMessage, cause);
        this.code = code;
        this.status = status;
        this.messageKey = messageKey;
        this.messageArgs = messageArgs == null ? new Object[0] : messageArgs.clone();
    }

    public ErrorCode getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Object[] getMessageArgs() {
        return messageArgs.clone();
    }
}
