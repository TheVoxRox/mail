package org.voxrox.mailbackend.core.security.secret;

/**
 * Thrown when an OS secret store fails to protect or unprotect a blob (e.g. a
 * DPAPI {@code CryptUnprotectData} failure because the {@code crypto.bin} was
 * copied from a different Windows user / machine, or the blob was tampered
 * with). Unchecked so it propagates out of the pre-context
 * {@code StorageContextInitializer} as a hard boot failure — a secret-store
 * failure must never silently fall back to plaintext.
 *
 * <p>
 * No {@code serialVersionUID} by repo convention (no exception in this codebase
 * declares one).
 */
public class SecretStoreException extends RuntimeException {

    public SecretStoreException(String message) {
        super(message);
    }

    public SecretStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
