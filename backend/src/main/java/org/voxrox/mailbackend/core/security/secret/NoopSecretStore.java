package org.voxrox.mailbackend.core.security.secret;

/**
 * Identity {@link SecretStore} — returns its input unchanged. Used on platforms
 * without a wired-up OS secret store (everything except Windows today) and as
 * the deterministic default in non-DPAPI unit tests. On those platforms the
 * {@code crypto.bin} secret rests on the data dir's filesystem permissions, as
 * it did before the secret-store abstraction existed.
 */
public final class NoopSecretStore implements SecretStore {

    @Override
    public byte[] protect(byte[] plaintext) {
        return plaintext.clone();
    }

    @Override
    public byte[] unprotect(byte[] blob) {
        return blob.clone();
    }
}
