package org.voxrox.mailbackend.core.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.MailBackendApplication;
import org.voxrox.mailbackend.core.config.ApplicationVersion;
import org.voxrox.mailbackend.core.config.StorageProperties;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Creates a snapshot of the SQLite DB before applying a new migration.
 * <p>
 * Invoked from {@code FlywayMigrationStrategy} before {@code flyway.migrate()},
 * so if the new migration corrupts data the user has a restore point (see
 * {@code OPERATIONS.md}, section "Update troubleshooting").
 */
@Component
public class DatabaseBackupService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupService.class);
    private static final String BACKUP_PREFIX = MailBackendApplication.APP_NAME + ".db.backup-pre-v";

    private final StorageProperties storageProperties;
    private final BackupProperties backupProperties;
    private final ApplicationVersion applicationVersion;

    @Autowired
    public DatabaseBackupService(StorageProperties storageProperties, BackupProperties backupProperties,
            ApplicationVersion applicationVersion) {
        this.storageProperties = storageProperties;
        this.backupProperties = backupProperties;
        this.applicationVersion = applicationVersion;
    }

    public DatabaseBackupService(StorageProperties storageProperties, BackupProperties backupProperties,
            String appVersion) {
        this(storageProperties, backupProperties, new ApplicationVersion(appVersion));
    }

    /**
     * Creates a DB backup for the current version (if one does not exist yet) and
     * prunes old backups outside the retention window. Idempotent — repeated calls
     * for the same version are a no-op (the backup captures the state <em>before
     * the first</em> migration of this version, not the state right before the
     * latest restart).
     *
     * @return path to the backup, or {@code null} if the DB does not yet exist
     *         (fresh install) or the backup for this version was already created
     *         earlier.
     */
    public @Nullable Path createPreMigrationBackup() {
        Path dbFile = resolveDbFile();
        if (!Files.exists(dbFile)) {
            log.info("{} DB file {} does not exist (fresh install), backup skipped.", LogCategory.DATABASE, dbFile);
            return null;
        }

        String appVersion = appVersion();
        Path backupFile = storageProperties.getDbPath().resolve(BACKUP_PREFIX + appVersion);
        if (Files.exists(backupFile)) {
            log.info("{} Pre-migration backup for version {} already exists ({}), no-op.", LogCategory.DATABASE,
                    appVersion, backupFile.getFileName());
            return backupFile;
        }

        try {
            Files.copy(dbFile, backupFile, StandardCopyOption.COPY_ATTRIBUTES);
            log.info("{} Pre-migration backup created: {}", LogCategory.DATABASE, backupFile.getFileName());
            AuditLog.success("db_backup_created", "system", "file=" + backupFile.getFileName());
        } catch (IOException e) {
            log.error("{} Failed to create pre-migration DB backup", LogCategory.DATABASE, e);
            AuditLog.failure("db_backup_failed", "system",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            throw new UncheckedIOException("Failed to create pre-migration DB backup", e);
        }

        pruneOldBackups();
        return backupFile;
    }

    private void pruneOldBackups() {
        Path dbDir = storageProperties.getDbPath();
        List<Path> backups = listBackups(dbDir);
        if (backups.size() <= backupProperties.retentionCount()) {
            return;
        }

        backups.sort(Comparator.comparingLong(DatabaseBackupService::lastModifiedMillis).reversed());
        List<Path> toDelete = backups.subList(backupProperties.retentionCount(), backups.size());
        for (Path stale : toDelete) {
            try {
                Files.deleteIfExists(stale);
                log.info("{} Deleted old backup outside retention: {}", LogCategory.DATABASE, stale.getFileName());
                AuditLog.success("db_backup_pruned", "system", "file=" + stale.getFileName());
            } catch (IOException e) {
                log.warn("{} Failed to delete old backup {}: {}", LogCategory.DATABASE, stale.getFileName(),
                        e.getMessage());
            }
        }
    }

    private List<Path> listBackups(Path dbDir) {
        if (!Files.isDirectory(dbDir)) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dbDir, BACKUP_PREFIX + "*")) {
            stream.forEach(result::add);
        } catch (IOException e) {
            log.warn("{} Failed to list backups in {}: {}", LogCategory.DATABASE, dbDir, e.getMessage());
        }
        return result;
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Version of the previous run, derived from the most recent backup file. Used
     * only for the {@code app_started} audit log so that support can see which
     * version the user upgraded from. {@code null} = first run or all backups
     * deleted.
     */
    public @Nullable String previousAppVersion() {
        return listBackups(storageProperties.getDbPath()).stream()
                .max(Comparator.comparingLong(DatabaseBackupService::lastModifiedMillis))
                .map(p -> p.getFileName().toString().substring(BACKUP_PREFIX.length())).orElse(null);
    }

    public String appVersion() {
        return applicationVersion.value();
    }

    private Path resolveDbFile() {
        return storageProperties.getDbPath().resolve(MailBackendApplication.APP_NAME + ".db");
    }

}
