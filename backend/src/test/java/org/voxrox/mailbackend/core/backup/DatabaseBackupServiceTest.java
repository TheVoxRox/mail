package org.voxrox.mailbackend.core.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.core.config.StorageProperties;

class DatabaseBackupServiceTest {

    private Path tempDir;
    private Path dbDir;
    private Path dbFile;

    @BeforeEach
    void setUp() throws Exception {
        Path baseDir = Path.of("target", "test-tmp", "DatabaseBackupServiceTest").toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        tempDir = Files.createTempDirectory(baseDir, "case-");
        dbDir = tempDir.resolve("db");
        Files.createDirectories(dbDir);
        dbFile = dbDir.resolve("mail.db");
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
    @DisplayName("Returns null and creates nothing when DB does not exist (fresh install)")
    void freshInstallSkipsBackup() {
        DatabaseBackupService service = service("0.2.0");

        Path result = service.createPreMigrationBackup();

        assertThat(result).isNull();
        assertThat(dbDir).isEmptyDirectory();
    }

    @Test
    @DisplayName("First run of a new version creates a backup with a matching name")
    void firstRunOfNewVersionCreatesBackup() throws Exception {
        Files.writeString(dbFile, "fake-db-content");

        Path result = service("0.2.0").createPreMigrationBackup();

        assertThat(result).isNotNull();
        assertThat(result.getFileName().toString()).isEqualTo("mail.db.backup-pre-v0.2.0");
        assertThat(Files.readString(result)).isEqualTo("fake-db-content");
    }

    @Test
    @DisplayName("Restart of the same version does not overwrite the existing backup (idempotent)")
    void restartOfSameVersionIsNoop() throws Exception {
        Files.writeString(dbFile, "current-content");
        Path backupFile = dbDir.resolve("mail.db.backup-pre-v0.2.0");
        Files.writeString(backupFile, "original-backup-content");

        Path result = service("0.2.0").createPreMigrationBackup();

        assertThat(result).isEqualTo(backupFile);
        assertThat(Files.readString(backupFile)).isEqualTo("original-backup-content");
    }

    @Test
    @DisplayName("Retention removes old backups and keeps only the N most recent")
    void retentionPrunesOldBackups() throws Exception {
        Files.writeString(dbFile, "current");
        // Five backups, each with increasing lastModified — the new '0.6.0' backup
        // is added as the 6th.
        createBackup("1.1.0", 1_000L);
        createBackup("1.2.0", 2_000L);
        createBackup("1.3.0", 3_000L);
        createBackup("1.4.0", 4_000L);
        createBackup("1.5.0", 5_000L);

        service("1.6.0").createPreMigrationBackup();

        List<String> remaining;
        try (var stream = Files.list(dbDir)) {
            remaining = stream.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("mail.db.backup-pre-v")).sorted().toList();
        }
        assertThat(remaining).containsExactly("mail.db.backup-pre-v1.4.0", "mail.db.backup-pre-v1.5.0",
                "mail.db.backup-pre-v1.6.0");
    }

    @Test
    @DisplayName("previousAppVersion returns the version from the most recent backup")
    void previousAppVersionResolvedFromLatestBackup() throws Exception {
        createBackup("1.1.0", 1_000L);
        createBackup("1.2.0", 5_000L);
        createBackup("1.1.5", 3_000L);

        assertThat(service("1.3.0").previousAppVersion()).isEqualTo("1.2.0");
    }

    @Test
    @DisplayName("previousAppVersion is null when there are no backups")
    void previousAppVersionNullWithoutBackups() {
        assertThat(service("1.1.0").previousAppVersion()).isNull();
    }

    private DatabaseBackupService service(String appVersion) {
        return new DatabaseBackupService(new StorageProperties(tempDir.toString()), new BackupProperties(3),
                appVersion);
    }

    private void createBackup(String version, long lastModifiedMillis) throws Exception {
        Path backup = dbDir.resolve("mail.db.backup-pre-v" + version);
        Files.writeString(backup, version, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(backup, java.nio.file.attribute.FileTime.fromMillis(lastModifiedMillis));
    }
}
