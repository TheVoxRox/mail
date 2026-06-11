package org.voxrox.mailbackend.core.security.secret;

import java.util.Locale;

/**
 * OS-backed protection for a small at-rest secret — the {@code crypto.bin}
 * bootstrap key + salt. {@link #protect} turns plaintext into an opaque blob
 * bound to the current OS principal; {@link #unprotect} reverses it only for
 * that principal.
 *
 * <p>
 * The point is to stop the at-rest secret from being decryptable after a
 * straight file/folder copy. On Windows the binding is to the logged-in user
 * via DPAPI ({@link WindowsDpapiSecretStore}); a {@code crypto.bin} carried to
 * another machine or user account is then useless. On other platforms there is
 * no OS store wired up yet, so {@link NoopSecretStore} is an identity transform
 * and confidentiality continues to rest on the data dir's filesystem
 * permissions (the desktop V0.1.0 release is Windows-only).
 *
 * <p>
 * Implementations are plain classes, not Spring beans:
 * {@code StorageContextInitializer} runs before the Spring context exists.
 */
public interface SecretStore {

    /** Protects plaintext into an opaque, principal-bound blob. */
    byte[] protect(byte[] plaintext);

    /**
     * Reverses {@link #protect}. Throws {@link SecretStoreException} when the blob
     * cannot be unprotected for the current principal (wrong user/machine or
     * tampering) — callers must treat this as fatal, never as "fall back to
     * plaintext".
     */
    byte[] unprotect(byte[] blob);

    /**
     * Returns the secret store for the current OS: Windows DPAPI, otherwise the
     * no-op identity store. The Windows implementation is only class-loaded when
     * this branch is taken, so its native ({@code Crypt32.dll}) static initialiser
     * never runs on non-Windows platforms.
     */
    static SecretStore forCurrentOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new WindowsDpapiSecretStore();
        }
        return new NoopSecretStore();
    }
}
