package org.voxrox.mailbackend.core.init;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.voxrox.mailbackend.core.security.secret.SecretStore;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

/**
 * Creates the directory structure BEFORE the Spring context is initialized.
 * Runs earlier than Hibernate/Hikari so SQLite never starts against a missing
 * db/ directory.
 *
 * Registered in the main class via {@code app.addInitializers()}. Intentionally
 * does not use Spring beans — the context is not yet available.
 */
public class StorageContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(StorageContextInitializer.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String CRYPTO_FILE = "crypto.bin";
    private static final String CRYPTO_FINGERPRINT_FILE = "crypto.fingerprint";
    private static final String READY_FILE = ".ready";
    private static final String DEFAULT_DATA_DIR = "${user.home}/.voxrox/${spring.application.name}";

    /**
     * First line of a {@code crypto.bin} written in the OS-secret-store-protected
     * format.
     */
    private static final String SECRET_HEADER = "VOXSEC1";

    private final SecretStore secretStore;

    public StorageContextInitializer() {
        this(SecretStore.forCurrentOs());
    }

    /**
     * Test seam: inject a deterministic {@link SecretStore} so the bootstrap
     * protect/unprotect/migration paths can be exercised without a real
     * (Windows-only) DPAPI call.
     */
    StorageContextInitializer(SecretStore secretStore) {
        this.secretStore = secretStore;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        long started = StartupTimingService.startNanos();
        ConfigurableEnvironment env = context.getEnvironment();

        /*
         * PropertySourcesPropertyResolver expands placeholders (${user.home},
         * ${spring.application.name}) the same way Spring does internally —
         * app.data-dir stays defined only in properties.
         */
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(env.getPropertySources());
        resolver.setIgnoreUnresolvableNestedPlaceholders(false);
        String dataDirStr = resolver.resolveRequiredPlaceholders(env.getProperty("app.data-dir", DEFAULT_DATA_DIR));
        Path dataDir = Path.of(dataDirStr).toAbsolutePath().normalize();

        List<Path> dirs = List.of(dataDir, dataDir.resolve("db"), dataDir.resolve("logs"),
                dataDir.resolve("attachments"), dataDir.resolve("tmp"));

        try {
            for (Path dir : dirs) {
                boolean created = !Files.exists(dir);
                Files.createDirectories(dir);
                if (created) {
                    applyPrivatePermissions(dir);
                    log.info("{} Created directory: {}", LogCategory.BOOT, dir);
                }
            }
            long readyCleanupStarted = StartupTimingService.startNanos();
            Files.deleteIfExists(dataDir.resolve(READY_FILE));
            StartupTimingService.recordPhase("storage.ready-cleanup", readyCleanupStarted);

            long cryptoBootstrapStarted = StartupTimingService.startNanos();
            bootstrapCryptoIfNeeded(env, dataDir);
            StartupTimingService.recordPhase("crypto.bootstrap", cryptoBootstrapStarted);
        } catch (IOException e) {
            throw new IllegalStateException("Storage initialization failed at " + dataDir, e);
        } finally {
            StartupTimingService.recordPhase("storage.initialize", started);
        }
    }

    private void bootstrapCryptoIfNeeded(ConfigurableEnvironment env, Path dataDir) throws IOException {
        String configuredKey = resolveLenient(env, "mail.crypto.key");
        String configuredSalt = resolveLenient(env, "mail.crypto.salt");
        boolean hasConfiguredKey = hasConcreteText(configuredKey);
        boolean hasConfiguredSalt = hasConcreteText(configuredSalt);

        if (hasConfiguredKey && hasConfiguredSalt) {
            verifyConfiguredCrypto(dataDir, configuredKey, configuredSalt);
            return;
        }
        if (hasConfiguredKey != hasConfiguredSalt) {
            throw new IllegalStateException(
                    "Crypto configuration is incomplete: set both MAIL_CRYPTO_KEY and MAIL_CRYPTO_SALT, or neither.");
        }

        Path cryptoFile = dataDir.resolve(CRYPTO_FILE);
        CryptoBootstrap bootstrap = Files.exists(cryptoFile)
                ? loadOrMigrate(cryptoFile)
                : createCryptoBootstrap(cryptoFile);

        env.getPropertySources().addFirst(new MapPropertySource("cryptoBootstrap",
                Map.of("mail.crypto.key", bootstrap.key(), "mail.crypto.salt", bootstrap.salt())));
        syncBootstrapFingerprint(dataDir.resolve(CRYPTO_FINGERPRINT_FILE), bootstrap.key(), bootstrap.salt());
    }

    private void verifyConfiguredCrypto(Path dataDir, String configuredKey, String configuredSalt) throws IOException {
        Path cryptoFile = dataDir.resolve(CRYPTO_FILE);
        if (Files.exists(cryptoFile)) {
            CryptoBootstrap bootstrap = readCryptoBootstrap(cryptoFile);
            if (!constantTimeEquals(bootstrap.key(), configuredKey)
                    || !constantTimeEquals(bootstrap.salt(), configuredSalt)) {
                throw new IllegalStateException("Crypto configuration does not match existing " + cryptoFile
                        + ". Do not change MAIL_CRYPTO_KEY/MAIL_CRYPTO_SALT against an existing DB without a "
                        + "re-encrypt migration. For a clean dev start, back up or remove app.data-dir.");
            }
        }

        ensureCryptoFingerprint(dataDir.resolve(CRYPTO_FINGERPRINT_FILE), configuredKey, configuredSalt);
    }

    private String resolveLenient(ConfigurableEnvironment env, String propertyName) {
        try {
            return env.getProperty(propertyName);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean hasConcreteText(String value) {
        return value != null && !value.isBlank() && !value.contains("${");
    }

    /**
     * Reads the bootstrap without side effects, transparently handling both the
     * protected ({@code VOXSEC1}) and the legacy plaintext format. Used by the
     * env-var path, which must not rewrite the file.
     */
    private CryptoBootstrap readCryptoBootstrap(Path cryptoFile) throws IOException {
        String raw = Files.readString(cryptoFile, StandardCharsets.UTF_8);
        return parseBootstrap(decodeBootstrap(raw), cryptoFile);
    }

    /**
     * Reads the bootstrap and, if the file is still in the legacy plaintext format,
     * rewrites it through {@link #secretStore} (in-place migration to the protected
     * format). Used by the desktop bootstrap path.
     */
    private CryptoBootstrap loadOrMigrate(Path cryptoFile) throws IOException {
        String raw = Files.readString(cryptoFile, StandardCharsets.UTF_8);
        CryptoBootstrap bootstrap = parseBootstrap(decodeBootstrap(raw), cryptoFile);
        if (!isProtectedFormat(raw)) {
            writeProtectedBootstrap(cryptoFile, toBootstrapContent(bootstrap));
            log.info("{} Migrated {} to the protected secret-store format.", LogCategory.SECURITY, cryptoFile);
        }
        return bootstrap;
    }

    private String decodeBootstrap(String raw) {
        if (isProtectedFormat(raw)) {
            return new String(secretStore.unprotect(decodeProtectedPayload(raw)), StandardCharsets.UTF_8);
        }
        return raw; // legacy plaintext properties
    }

    private static boolean isProtectedFormat(String fileText) {
        return fileText.startsWith(SECRET_HEADER + "\n") || fileText.startsWith(SECRET_HEADER + "\r\n");
    }

    private static byte[] decodeProtectedPayload(String fileText) {
        int newline = fileText.indexOf('\n');
        return Base64.getDecoder().decode(fileText.substring(newline + 1).strip());
    }

    private CryptoBootstrap parseBootstrap(String content, Path cryptoFile) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(content));

        String key = properties.getProperty("key");
        String salt = properties.getProperty("salt");
        if (!hasConcreteText(key) || !hasConcreteText(salt)) {
            throw new IllegalStateException("File " + cryptoFile + " does not contain a valid crypto bootstrap.");
        }
        return new CryptoBootstrap(key, salt);
    }

    private static String toBootstrapContent(CryptoBootstrap bootstrap) {
        return "key=" + bootstrap.key() + System.lineSeparator() + "salt=" + bootstrap.salt() + System.lineSeparator();
    }

    private CryptoBootstrap createCryptoBootstrap(Path cryptoFile) throws IOException {
        CryptoBootstrap bootstrap = new CryptoBootstrap(generateSecret(), generateSecret());

        writeProtectedBootstrap(cryptoFile, toBootstrapContent(bootstrap));
        log.info("{} Generated local crypto bootstrap: {}", LogCategory.SECURITY, cryptoFile);
        AuditLog.success("crypto_bootstrapped", "system", "file=" + cryptoFile);
        return bootstrap;
    }

    /**
     * Writes the bootstrap key/salt protected by {@link #secretStore}:
     * {@code VOXSEC1\n<base64(protect(content))>}. On Windows the payload is a
     * DPAPI blob bound to the logged-in user; with the no-op store it is the base64
     * of the plaintext and confidentiality rests on filesystem permissions.
     */
    private void writeProtectedBootstrap(Path cryptoFile, String content) throws IOException {
        byte[] payload = secretStore.protect(content.getBytes(StandardCharsets.UTF_8));
        String fileText = SECRET_HEADER + System.lineSeparator() + Base64.getEncoder().encodeToString(payload)
                + System.lineSeparator();
        writeAtomic(cryptoFile, fileText.getBytes(StandardCharsets.UTF_8));
        applyPrivatePermissions(cryptoFile);
    }

    private void ensureCryptoFingerprint(Path fingerprintFile, String key, String salt) throws IOException {
        String expected = cryptoFingerprint(key, salt);
        if (Files.exists(fingerprintFile)) {
            String actual = Files.readString(fingerprintFile, StandardCharsets.UTF_8).trim();
            if (!constantTimeEquals(actual, expected)) {
                throw new IllegalStateException("Crypto fingerprint does not match existing " + fingerprintFile
                        + ". MAIL_CRYPTO_KEY or MAIL_CRYPTO_SALT likely changed against an existing DB. "
                        + "Restore the original values, or perform a clean start after backing up app.data-dir.");
            }
            return;
        }

        writeAtomic(fingerprintFile, (expected + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        applyPrivatePermissions(fingerprintFile);
    }

    private void syncBootstrapFingerprint(Path fingerprintFile, String key, String salt) throws IOException {
        String expected = cryptoFingerprint(key, salt);
        if (Files.exists(fingerprintFile)) {
            String actual = Files.readString(fingerprintFile, StandardCharsets.UTF_8).trim();
            if (constantTimeEquals(actual, expected)) {
                return;
            }

            log.warn(
                    "{} Crypto fingerprint does not match local {}. Desktop bootstrap mode uses {} as the "
                            + "source of truth; the fingerprint will be regenerated.",
                    LogCategory.SECURITY, fingerprintFile, CRYPTO_FILE);
        }

        writeAtomic(fingerprintFile, (expected + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        applyPrivatePermissions(fingerprintFile);
    }

    private String cryptoFingerprint(String key, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("voxrox-mail-crypto-v1".getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void writeAtomic(Path target, byte[] content) throws IOException {
        Path absoluteTarget = target.toAbsolutePath();
        Path parent = absoluteTarget.getParent();
        Path fileName = absoluteTarget.getFileName();
        if (parent == null || fileName == null) {
            throw new IOException("Target path is not valid: " + target);
        }

        Path temp = Files.createTempFile(parent, ".tmp-", fileName.toString());
        try {
            Files.write(temp, content);
            Files.move(temp, absoluteTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Sets owner-only permissions (directory rwx------, file rw-------). Uses
     * standard Java NIO — no Panama, no external processes. Skipped on Windows
     * (NTFS inherits permissions from the parent).
     */
    private void applyPrivatePermissions(Path path) {
        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        if (isWindows)
            return;

        try {
            Set<PosixFilePermission> perms = Files.isDirectory(path)
                    ? PosixFilePermissions.fromString("rwx------")
                    : PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, perms);
        } catch (IOException e) {
            log.warn("{} Failed to set permissions for {}: {}", LogCategory.BOOT, path, e.getMessage());
        }
    }

    private record CryptoBootstrap(String key, String salt) {
    }
}
