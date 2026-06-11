package org.voxrox.mailbackend.core.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Tunable knobs for the crypto subsystem.
 *
 * <p>
 * Only values that can change without breaking decryption of existing
 * ciphertexts live here. AES/GCM algorithm details (mode, IV length, tag
 * length, key length) are hard-coded in {@link CryptoService} as constants —
 * changing them would silently invalidate every stored credential and we have
 * no in-place migration since the {@code CryptoMigrationService} removal. Treat
 * them as part of the data format, not configuration.
 *
 * @param key
 *            master key material for PBKDF2 derivation (min. 32 characters)
 * @param salt
 *            PBKDF2 salt
 * @param pbkdf2Iterations
 *            number of PBKDF2 iterations; a higher value increases brute-force
 *            resistance
 * @param maxCacheSize
 *            maximum number of derived account keys kept in memory (LRU cache)
 */
@ConfigurationProperties(prefix = "mail.crypto")
@Validated
public record CryptoProperties(
        @NotBlank(message = "Encryption key must not be blank") @Size(min = 32, message = "Encryption key must be at least 32 characters") String key,

        @NotBlank(message = "Encryption salt must not be blank") String salt,

        @Min(value = 1000, message = "Iteration count must be at least 1000") @DefaultValue("600000") int pbkdf2Iterations,

        @Min(1) @DefaultValue("100") int maxCacheSize) {
}
