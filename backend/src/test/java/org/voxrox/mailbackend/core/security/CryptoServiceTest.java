package org.voxrox.mailbackend.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.core.init.StartupTimingService;

/**
 * Unit tests for {@link CryptoService}.
 *
 * No Spring context — {@link CryptoProperties} is a record, instantiated
 * directly. PBKDF2 iterations are set to the minimum (1000) so tests stay fast.
 *
 * Covers security-critical invariants: - round-trip encrypt/decrypt - AAD
 * account-id binding (ciphertext of account A must not decrypt as account B) -
 * GCM tamper detection - random IV (same plaintext 2x = different outputs) -
 * null/blank handling - cache eviction
 */
class CryptoServiceTest {

    private static final String TEST_KEY = "this-is-a-32-char-test-secret!!!";
    private static final String TEST_SALT = "test-salt-value";

    private CryptoService crypto;

    @BeforeEach
    void setUp() {
        crypto = newCryptoService(TEST_KEY, TEST_SALT);
    }

    private static CryptoService newCryptoService(String key, String salt) {
        // Low PBKDF2 iterations — tests must be fast.
        CryptoProperties props = new CryptoProperties(key, salt, 1000, 10);
        CryptoService service = new CryptoService(props, new StartupTimingService());
        service.init(); // @PostConstruct does not run automatically in a unit test
        return service;
    }

    @Nested
    @DisplayName("Round-trip encrypt/decrypt")
    class RoundTrip {

        @Test
        void shouldDecryptWhatItEncrypted() {
            String plain = "secret-password-123";
            String encrypted = crypto.encrypt(plain, 42L);
            String decrypted = crypto.decrypt(encrypted, 42L);

            assertThat(decrypted).isEqualTo(plain);
        }

        @Test
        void shouldHandleUnicodePlaintext() {
            String plain = "héllo wörld 🔒 příliš žluťoučký kůň";
            String encrypted = crypto.encrypt(plain, 1L);

            assertThat(crypto.decrypt(encrypted, 1L)).isEqualTo(plain);
        }

        @Test
        void shouldHandleLongPlaintext() {
            String plain = "x".repeat(10_000);
            String encrypted = crypto.encrypt(plain, 1L);

            assertThat(crypto.decrypt(encrypted, 1L)).isEqualTo(plain);
        }

        @Test
        void shouldProduceBase64Output() {
            String encrypted = crypto.encrypt("foo", 1L);

            // Must not throw — the output must be valid Base64.
            assertThat(Base64.getDecoder().decode(encrypted)).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("AAD account-id binding")
    class AccountBinding {

        @Test
        void ciphertextFromOneAccountMustNotDecryptForAnother() {
            // CRITICAL: without AAD binding, one account could read another's data
            // if its derived key was leaked — or even worse, if someone swapped
            // accountId in the DB.
            String encrypted = crypto.encrypt("user-A-secret", 1L);

            assertThatThrownBy(() -> crypto.decrypt(encrypted, 2L)).isInstanceOf(CryptoOperationException.class)
                    .hasMessageContaining("integrity");
        }

        @Test
        void differentAccountsProduceDifferentDerivedKeys() {
            // Same plaintext, different accounts -> different outputs.
            // (On its own this property could also be due to random IV alone, so
            // we also rely on the test above which verifies AAD binding directly.)
            String enc1 = crypto.encrypt("same", 1L);
            String enc2 = crypto.encrypt("same", 2L);

            assertThat(enc1).isNotEqualTo(enc2);
        }
    }

    @Nested
    @DisplayName("GCM tamper detection")
    class Tampering {

        @Test
        void modifiedCiphertextShouldFailIntegrityCheck() {
            String encrypted = crypto.encrypt("important", 1L);
            byte[] raw = Base64.getDecoder().decode(encrypted);

            // Flip one bit in the ciphertext (skip the 12B IV).
            raw[raw.length - 1] ^= 0x01;
            String tampered = Base64.getEncoder().encodeToString(raw);

            assertThatThrownBy(() -> crypto.decrypt(tampered, 1L)).isInstanceOf(CryptoOperationException.class)
                    .hasMessageContaining("integrity");
        }

        @Test
        void modifiedIvShouldFailIntegrityCheck() {
            String encrypted = crypto.encrypt("important", 1L);
            byte[] raw = Base64.getDecoder().decode(encrypted);

            // Flip a bit in the IV (first 12 bytes).
            raw[0] ^= 0x01;
            String tampered = Base64.getEncoder().encodeToString(raw);

            assertThatThrownBy(() -> crypto.decrypt(tampered, 1L)).isInstanceOf(CryptoOperationException.class);
        }

        @Test
        void completelyGarbageCiphertextShouldThrow() {
            assertThatThrownBy(() -> crypto.decrypt("not-valid-base64-!!!", 1L))
                    .isInstanceOf(CryptoOperationException.class);
        }
    }

    @Nested
    @DisplayName("Random IV — encryption non-determinism")
    class RandomIv {

        @Test
        void sameInputEncryptedTwiceShouldProduceDifferentCiphertexts() {
            // Without a random IV, GCM would be catastrophically insecure.
            // This test fails only if someone accidentally hard-codes the IV.
            String enc1 = crypto.encrypt("same-plaintext", 1L);
            String enc2 = crypto.encrypt("same-plaintext", 1L);

            assertThat(enc1).isNotEqualTo(enc2);
            assertThat(crypto.decrypt(enc1, 1L)).isEqualTo(crypto.decrypt(enc2, 1L));
        }
    }

    @Nested
    @DisplayName("Null & blank handling")
    class NullBlank {

        @Test
        void encryptNullReturnsNull() {
            assertThat(crypto.encrypt(null, 1L)).isNull();
        }

        @Test
        void encryptBlankReturnsNull() {
            assertThat(crypto.encrypt("   ", 1L)).isNull();
        }

        @Test
        void decryptNullReturnsNull() {
            assertThat(crypto.decrypt(null, 1L)).isNull();
        }

        @Test
        void decryptBlankReturnsNull() {
            assertThat(crypto.decrypt("   ", 1L)).isNull();
        }
    }

    @Nested
    @DisplayName("Cache eviction")
    class Cache {

        @Test
        void evictCacheShouldNotBreakSubsequentDecryption() {
            // After eviction the key must be re-derived from PBKDF2 and still
            // decrypt previously encrypted text — otherwise eviction would mean data loss.
            String encrypted = crypto.encrypt("payload", 7L);

            crypto.evictCache(7L);

            assertThat(crypto.decrypt(encrypted, 7L)).isEqualTo("payload");
        }

        @Test
        void evictCacheForUnknownAccountIsNoOp() {
            // Must not throw even for an accountId that was never in the cache.
            crypto.evictCache(999L);
        }
    }

    @Nested
    @DisplayName("Cross-instance compatibility")
    class CrossInstance {

        @Test
        void cipherTextShouldBeDecryptableByFreshInstanceWithSameKeyAndSalt() {
            // Simulates app restart: same master key + salt -> same derived key ->
            // previously stored data in the DB must decrypt. Without this property an
            // upgrade
            // = data loss.
            String encrypted = crypto.encrypt("persistent-data", 5L);

            CryptoService freshInstance = newCryptoService(TEST_KEY, TEST_SALT);
            assertThat(freshInstance.decrypt(encrypted, 5L)).isEqualTo("persistent-data");
        }

        @Test
        void differentMasterKeyMustNotDecryptOldCiphertext() {
            String encrypted = crypto.encrypt("data", 5L);

            CryptoService differentKey = newCryptoService("DIFFERENT-32-char-secret-aaaaaaa", TEST_SALT);

            assertThatThrownBy(() -> differentKey.decrypt(encrypted, 5L)).isInstanceOf(CryptoOperationException.class);
        }

        @Test
        void differentSaltMustNotDecryptOldCiphertext() {
            String encrypted = crypto.encrypt("data", 5L);

            CryptoService differentSalt = newCryptoService(TEST_KEY, "different-salt");

            assertThatThrownBy(() -> differentSalt.decrypt(encrypted, 5L)).isInstanceOf(CryptoOperationException.class);
        }
    }

}
