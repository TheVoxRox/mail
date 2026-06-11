package org.voxrox.mailbackend.core.security;

/**
 * Unchecked failure of an AES/GCM credential crypto operation — key derivation,
 * encryption, decryption, or the GCM integrity check.
 *
 * <p>
 * Deliberately NOT an {@link org.voxrox.mailbackend.exception.AppException}
 * subtype: a crypto failure is an internal error that must surface as a generic
 * HTTP 500 via {@code GlobalExceptionHandler#handleGeneric} without leaking any
 * detail about the crypto subsystem to the client. Using a dedicated type
 * instead of a bare {@link RuntimeException} makes the failure greppable and
 * unit-testable while keeping the same runtime behavior (callers that catch
 * {@link RuntimeException} still catch it).
 */
public class CryptoOperationException extends RuntimeException {

    public CryptoOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
