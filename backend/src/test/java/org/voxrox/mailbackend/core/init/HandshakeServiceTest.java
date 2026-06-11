package org.voxrox.mailbackend.core.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.Properties;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.voxrox.mailbackend.core.backup.BackupProperties;
import org.voxrox.mailbackend.core.backup.DatabaseBackupService;
import org.voxrox.mailbackend.core.config.ApplicationVersion;
import org.voxrox.mailbackend.core.config.StorageProperties;
import org.voxrox.mailbackend.core.security.secret.NoopSecretStore;
import org.voxrox.mailbackend.core.service.FileSystemService;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class HandshakeServiceTest {

    private static final String TEST_VERSION = "9.8.7-test";

    Path tempDir;

    @Mock
    Environment environment;

    HandshakeService service;

    @BeforeEach
    void setUp() throws Exception {
        Path baseDir = Path.of("target", "test-tmp", "HandshakeServiceTest").toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        tempDir = Files.createTempDirectory(baseDir, "case-");
        Files.createDirectories(tempDir.resolve("db"));

        StorageProperties storageProperties = new StorageProperties(tempDir.toString());
        FileSystemService fileSystemService = new NoopPermissionsFileSystemService(storageProperties);
        DatabaseBackupService backupService = new DatabaseBackupService(storageProperties, new BackupProperties(3),
                TEST_VERSION);
        service = new HandshakeService(environment, fileSystemService, new ObjectMapper(), stubFlyway("1"),
                backupService, new ApplicationVersion(TEST_VERSION), new StartupTimingService(), tempDir.toString());
    }

    private static Flyway stubFlyway(String version) {
        // lenient — these stubs are not needed for tests that only call
        // StorageContextInitializer
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo currentInfo = mock(MigrationInfo.class);
        lenient().when(flyway.info()).thenReturn(infoService);
        lenient().when(infoService.current()).thenReturn(currentInfo);
        lenient().when(currentInfo.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        return flyway;
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
                    throw new IllegalStateException("Failed to delete test path " + path, e);
                }
            });
        }
    }

    @Test
    @DisplayName("ApplicationReadyEvent writes session.json with a fresh in-memory API key and then the ready gate")
    void applicationReadyWritesSessionAndReadyGate() throws Exception {
        when(environment.getProperty("local.server.port", "0")).thenReturn("61234");

        service.onApplicationReady();

        Path sessionFile = tempDir.resolve("session.json");
        Path readyFile = tempDir.resolve(".ready");

        assertThat(sessionFile).exists();
        assertThat(readyFile).exists();
        String sessionJson = Files.readString(sessionFile);
        assertThat(sessionJson).contains("\"appName\" : \"mail\"", "\"appVersion\" : \"" + TEST_VERSION + "\"",
                "\"apiVersion\" : \"1.0.0\"", "\"minClientVersion\" : \"0.0.1\"", "\"dbSchemaVersion\" : \"1\"",
                "\"port\" : 61234", "\"baseUrl\" : \"http://127.0.0.1:61234/api\"");
        // The exact key value is random per JVM, but session.json must echo whatever
        // getOrCreateApiKey returned — verify both halves of that contract.
        String apiKey = service.getOrCreateApiKey();
        assertThat(apiKey).isNotBlank();
        assertThat(sessionJson).contains("\"apiKey\" : \"" + apiKey + "\"");
        assertThat(Files.readString(readyFile)).isEqualTo("ready\n");
    }

    @Test
    @DisplayName("getOrCreateApiKey returns the same key for repeated calls within a process")
    void apiKeyIsStableWithinProcess() {
        String first = service.getOrCreateApiKey();
        String second = service.getOrCreateApiKey();

        assertThat(first).isNotBlank();
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("Handshake response returns compatibility metadata including dbSchemaVersion")
    void getHandshakeReturnsCompatibilityMetadata() {
        HandshakeResponse response = service.getHandshake();

        assertThat(response.appName()).isEqualTo("mail");
        assertThat(response.appVersion()).isEqualTo(TEST_VERSION);
        assertThat(response.apiVersion()).isEqualTo("1.0.0");
        assertThat(response.minClientVersion()).isEqualTo("0.0.1");
        assertThat(response.dbSchemaVersion()).isEqualTo("1");
    }

    @Test
    @DisplayName("StorageContextInitializer removes the stale ready gate before startup")
    void storageInitializerRemovesStaleReadyGate() throws Exception {
        Files.writeString(tempDir.resolve(".ready"), "stale");
        MockEnvironment mockEnvironment = new MockEnvironment().withProperty("spring.application.name", "mail")
                .withProperty("app.data-dir", tempDir.toString());
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(mockEnvironment);

        new StorageContextInitializer().initialize(context);

        assertThat(tempDir.resolve(".ready")).doesNotExist();
        assertThat(tempDir.resolve("db")).isDirectory();
        assertThat(tempDir.resolve("logs")).isDirectory();
        assertThat(tempDir.resolve("attachments")).isDirectory();
        assertThat(tempDir.resolve("tmp")).isDirectory();
    }

    @Test
    @DisplayName("StorageContextInitializer generates crypto bootstrap without env vars")
    void storageInitializerBootstrapsCryptoWhenEnvIsMissing() throws Exception {
        MockEnvironment mockEnvironment = new MockEnvironment().withProperty("spring.application.name", "mail")
                .withProperty("app.data-dir", tempDir.toString());
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(mockEnvironment);

        // Inject the no-op store so crypto.bin is written in the VOXSEC1 format with a
        // base64(plaintext) payload — deterministic and decodable here without a real
        // (Windows-only) DPAPI call. Real DPAPI round-trip is covered in
        // StorageContextInitializerTest.
        new StorageContextInitializer(new NoopSecretStore()).initialize(context);

        Properties bootstrap = readBootstrap(tempDir.resolve("crypto.bin"));
        assertThat(bootstrap.getProperty("key")).hasSizeGreaterThanOrEqualTo(32);
        assertThat(bootstrap.getProperty("salt")).isNotBlank();
        assertThat(mockEnvironment.getProperty("mail.crypto.key")).isEqualTo(bootstrap.getProperty("key"));
        assertThat(mockEnvironment.getProperty("mail.crypto.salt")).isEqualTo(bootstrap.getProperty("salt"));
    }

    @Test
    @DisplayName("StorageContextInitializer respects explicit crypto configuration")
    void storageInitializerKeepsExplicitCryptoConfig() {
        String explicitKey = "explicit-test-key-that-has-at-least-32-chars";
        String explicitSalt = "explicit-test-salt";
        MockEnvironment mockEnvironment = new MockEnvironment().withProperty("spring.application.name", "mail")
                .withProperty("app.data-dir", tempDir.toString()).withProperty("mail.crypto.key", explicitKey)
                .withProperty("mail.crypto.salt", explicitSalt);
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(mockEnvironment);

        new StorageContextInitializer().initialize(context);

        assertThat(tempDir.resolve("crypto.bin")).doesNotExist();
        assertThat(mockEnvironment.getProperty("mail.crypto.key")).isEqualTo(explicitKey);
        assertThat(mockEnvironment.getProperty("mail.crypto.salt")).isEqualTo(explicitSalt);
    }

    private Properties readBootstrap(Path path) throws Exception {
        // crypto.bin is the protected format: "VOXSEC1\n<base64(payload)>". With the
        // no-op
        // store the payload is the base64 of the plaintext key=/salt= properties.
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        byte[] payload = Base64.getDecoder().decode(raw.substring(raw.indexOf('\n') + 1).strip());
        Properties properties = new Properties();
        properties.load(new StringReader(new String(payload, StandardCharsets.UTF_8)));
        return properties;
    }

    private static final class NoopPermissionsFileSystemService extends FileSystemService {
        private NoopPermissionsFileSystemService(StorageProperties storageProperties) {
            super(storageProperties);
        }

        @Override
        public void applyPrivatePermissions(Path path) {
            // The ready/session contract is exercised here, not platform-specific ACLs.
        }
    }
}
