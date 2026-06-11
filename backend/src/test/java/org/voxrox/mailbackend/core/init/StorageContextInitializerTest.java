package org.voxrox.mailbackend.core.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.voxrox.mailbackend.core.security.secret.SecretStore;
import org.voxrox.mailbackend.core.security.secret.SecretStoreException;
import org.voxrox.mailbackend.core.security.secret.WindowsDpapiSecretStore;

class StorageContextInitializerTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Path baseDir = Path.of("target", "test-tmp", "StorageContextInitializerTest").toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        tempDir = Files.createTempDirectory(baseDir, "case-");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir == null || Files.notExists(tempDir)) {
            return;
        }
        try (var stream = Files.walk(tempDir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to delete " + path, e);
                }
            });
        }
    }

    @Test
    @DisplayName("Default app.data-dir fallback points to ~/.voxrox/<app>")
    void defaultDataDirUsesVoxroxNamespace() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testDefaults",
                Map.of("user.home", tempDir.toString(), "spring.application.name", "mail")));

        new StorageContextInitializer().initialize(context);

        Path dataDir = tempDir.resolve(".voxrox").resolve("mail");
        assertThat(dataDir).isDirectory();
        assertThat(dataDir.resolve("db")).isDirectory();
        assertThat(dataDir.resolve("logs")).isDirectory();
        assertThat(dataDir.resolve("attachments")).isDirectory();
        assertThat(dataDir.resolve("tmp")).isDirectory();
        assertThat(dataDir.resolve("crypto.bin")).isRegularFile();
        assertThat(dataDir.resolve("crypto.fingerprint")).isRegularFile();
        assertThat(tempDir.resolve(".mail")).doesNotExist();
    }

    @Test
    @DisplayName("Explicit env-supplied crypto must not override an existing crypto bootstrap")
    void configuredCryptoMustMatchExistingBootstrap() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("crypto.bin"), "key=original-key\nsalt=original-salt\n");

        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("testConfig", Map.of("app.data-dir", dataDir.toString(),
                        "mail.crypto.key", "different-key", "mail.crypto.salt", "different-salt")));

        assertThatThrownBy(() -> new StorageContextInitializer().initialize(context))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Crypto configuration does not match");
    }

    @Test
    @DisplayName("Desktop bootstrap rezim obnovi stale fingerprint podle crypto.bin")
    void bootstrapCryptoRefreshesStaleFingerprint() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("crypto.bin"), "key=original-key\nsalt=original-salt\n");
        Files.writeString(dataDir.resolve("crypto.fingerprint"), "stale-fingerprint\n");

        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("testConfig", Map.of("app.data-dir", dataDir.toString())));

        new StorageContextInitializer().initialize(context);

        assertThat(Files.readString(dataDir.resolve("crypto.fingerprint"))).isNotEqualTo("stale-fingerprint\n");
        assertThat(context.getEnvironment().getProperty("mail.crypto.key")).isEqualTo("original-key");
        assertThat(context.getEnvironment().getProperty("mail.crypto.salt")).isEqualTo("original-salt");
    }

    @Test
    @DisplayName("Explicit env-supplied crypto creates a fingerprint and further changes are rejected")
    void configuredCryptoFingerprintGuardsFutureChanges() {
        Path dataDir = tempDir.resolve("data");

        GenericApplicationContext firstContext = new GenericApplicationContext();
        firstContext.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("firstConfig", Map.of("app.data-dir", dataDir.toString(),
                        "mail.crypto.key", "stable-key", "mail.crypto.salt", "stable-salt")));

        new StorageContextInitializer().initialize(firstContext);

        assertThat(dataDir.resolve("crypto.fingerprint")).isRegularFile();
        assertThat(dataDir.resolve("crypto.bin")).doesNotExist();

        GenericApplicationContext secondContext = new GenericApplicationContext();
        secondContext.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("secondConfig", Map.of("app.data-dir", dataDir.toString(),
                        "mail.crypto.key", "changed-key", "mail.crypto.salt", "stable-salt")));

        assertThatThrownBy(() -> new StorageContextInitializer().initialize(secondContext))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Crypto fingerprint does not match");
    }

    @Test
    @DisplayName("Desktop bootstrap writes crypto.bin in the protected VOXSEC1 format and reads it back via the store")
    void bootstrapWritesAndReadsProtectedFormat() throws Exception {
        Path dataDir = tempDir.resolve("data");
        ReversibleSecretStore store = new ReversibleSecretStore();

        new StorageContextInitializer(store).initialize(bootstrapContext(dataDir));

        Path cryptoFile = dataDir.resolve("crypto.bin");
        String fileText = Files.readString(cryptoFile, StandardCharsets.UTF_8);
        assertThat(fileText).startsWith("VOXSEC1");

        // The bytes on disk are the protected blob, not the plaintext key=/salt=
        // properties.
        byte[] payload = Base64.getDecoder().decode(fileText.substring(fileText.indexOf('\n') + 1).strip());
        assertThat(new String(payload, StandardCharsets.UTF_8)).doesNotContain("key=").doesNotContain("salt=");

        // A second boot reads the protected file back through unprotect() and yields
        // the same key/salt,
        // leaving the file byte-for-byte unchanged (no spurious re-migration).
        GenericApplicationContext second = bootstrapContext(dataDir);
        new StorageContextInitializer(store).initialize(second);
        String key = second.getEnvironment().getProperty("mail.crypto.key");
        String salt = second.getEnvironment().getProperty("mail.crypto.salt");
        assertThat(key).isNotBlank();
        assertThat(salt).isNotBlank();
        assertThat(new String(store.unprotect(payload), StandardCharsets.UTF_8)).contains("key=" + key)
                .contains("salt=" + salt);
        assertThat(Files.readString(cryptoFile, StandardCharsets.UTF_8)).isEqualTo(fileText);
    }

    @Test
    @DisplayName("Legacy plaintext crypto.bin is migrated in-place to the protected format on first boot")
    void legacyPlaintextCryptoBinIsMigratedInPlace() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("crypto.bin"), "key=legacy-key\nsalt=legacy-salt\n");

        ReversibleSecretStore store = new ReversibleSecretStore();
        GenericApplicationContext context = bootstrapContext(dataDir);
        new StorageContextInitializer(store).initialize(context);

        Path cryptoFile = dataDir.resolve("crypto.bin");
        String fileText = Files.readString(cryptoFile, StandardCharsets.UTF_8);
        assertThat(fileText).startsWith("VOXSEC1");
        assertThat(fileText).doesNotContain("key=legacy-key");

        byte[] payload = Base64.getDecoder().decode(fileText.substring(fileText.indexOf('\n') + 1).strip());
        assertThat(new String(store.unprotect(payload), StandardCharsets.UTF_8)).contains("key=legacy-key")
                .contains("salt=legacy-salt");

        // The original key/salt survive the migration unchanged (no re-encrypt of the
        // DB needed).
        assertThat(context.getEnvironment().getProperty("mail.crypto.key")).isEqualTo("legacy-key");
        assertThat(context.getEnvironment().getProperty("mail.crypto.salt")).isEqualTo("legacy-salt");
    }

    @Test
    @DisplayName("A tampered protected crypto.bin fails closed instead of falling back to plaintext")
    void tamperedProtectedBlobFailsClosed() throws Exception {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        // VOXSEC1 header but a payload the store cannot unprotect (no protect()
        // marker).
        String tampered = "VOXSEC1\n"
                + Base64.getEncoder().encodeToString("not-a-valid-blob".getBytes(StandardCharsets.UTF_8))
                + System.lineSeparator();
        Files.writeString(dataDir.resolve("crypto.bin"), tampered);

        GenericApplicationContext context = bootstrapContext(dataDir);
        assertThatThrownBy(() -> new StorageContextInitializer(new ReversibleSecretStore()).initialize(context))
                .isInstanceOf(SecretStoreException.class);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Real Windows DPAPI store round-trips protect/unprotect and produces an opaque blob")
    void windowsDpapiRoundTrip() {
        SecretStore store = new WindowsDpapiSecretStore();
        byte[] plaintext = "key=abc\nsalt=def".getBytes(StandardCharsets.UTF_8);

        byte[] blob = store.protect(plaintext);

        assertThat(blob).isNotEqualTo(plaintext);
        assertThat(store.unprotect(blob)).isEqualTo(plaintext);
    }

    /**
     * Builds a context in desktop bootstrap mode (only app.data-dir set, no env
     * crypto).
     */
    private GenericApplicationContext bootstrapContext(Path dataDir) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("testConfig", Map.of("app.data-dir", dataDir.toString())));
        return context;
    }

    /**
     * Deterministic, reversible {@link SecretStore} for platform-independent tests:
     * {@code protect} prepends a marker byte and XORs the payload;
     * {@code unprotect} reverses it and throws {@link SecretStoreException} when
     * the marker is missing (simulating a tampered / foreign blob).
     */
    private static final class ReversibleSecretStore implements SecretStore {

        private static final byte MARKER = (byte) 0xAB;
        private static final byte MASK = 0x5A;

        @Override
        public byte[] protect(byte[] plaintext) {
            byte[] out = new byte[plaintext.length + 1];
            out[0] = MARKER;
            for (int i = 0; i < plaintext.length; i++) {
                out[i + 1] = (byte) (plaintext[i] ^ MASK);
            }
            return out;
        }

        @Override
        public byte[] unprotect(byte[] blob) {
            if (blob.length == 0 || blob[0] != MARKER) {
                throw new SecretStoreException("fake store: blob is not a protected payload (tampered or foreign)");
            }
            byte[] out = new byte[blob.length - 1];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) (blob[i + 1] ^ MASK);
            }
            return out;
        }
    }
}
