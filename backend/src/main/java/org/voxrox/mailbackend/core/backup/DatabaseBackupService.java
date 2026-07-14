package org.voxrox.mailbackend.core.backup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.sql.DataSource;

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
    private final DataSource dataSource;

    @Autowired
    public DatabaseBackupService(StorageProperties storageProperties, BackupProperties backupProperties,
            ApplicationVersion applicationVersion, DataSource dataSource) {
        this.storageProperties = storageProperties;
        this.backupProperties = backupProperties;
        this.applicationVersion = applicationVersion;
        this.dataSource = dataSource;
    }

    public DatabaseBackupService(StorageProperties storageProperties, BackupProperties backupProperties,
            String appVersion, DataSource dataSource) {
        this(storageProperties, backupProperties, new ApplicationVersion(appVersion), dataSource);
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
            vacuumInto(backupFile);
            log.info("{} Pre-migration backup created: {}", LogCategory.DATABASE, backupFile.getFileName());
            AuditLog.success("db_backup_created", "system", "file=" + backupFile.getFileName());
        } catch (SQLException e) {
            log.error("{} Failed to create pre-migration DB backup", LogCategory.DATABASE, e);
            AuditLog.failure("db_backup_failed", "system",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            deletePartialBackup(backupFile);
            throw new IllegalStateException("Failed to create pre-migration DB backup", e);
        }

        pruneOldBackups();
        return backupFile;
    }

    /**
     * Snapshots the live database with {@code VACUUM INTO}, which writes a
     * transactionally consistent, self-contained copy. Unlike a plain file copy of
     * the main {@code .db}, it includes committed rows that still live in the
     * uncheckpointed {@code -wal} file (WAL journal mode) — so a backup taken after
     * an unclean shutdown captures the most recent transactions instead of silently
     * dropping them. The target must not already exist, which the caller's
     * idempotency check (existing-backup no-op) guarantees.
     */
    private void vacuumInto(Path backupFile) throws SQLException {
        // The VACUUM INTO target is an SQL expression, so the path binds as an
        // ordinary parameter — no string literal to escape (quotes in the path,
        // e.g. C:\Users\O'Brien, arrive verbatim).
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, backupFile.toString());
            statement.execute();
        }
    }

    private void deletePartialBackup(Path backupFile) {
        try {
            Files.deleteIfExists(backupFile);
        } catch (IOException cleanupEx) {
            log.warn("{} Failed to delete a partial backup {}: {}", LogCategory.DATABASE, backupFile.getFileName(),
                    cleanupEx.getMessage());
        }
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
                // Optional.map short-circuits to empty if getFileName() is null (a root
                // path) — a backup file always has a name, but the guard keeps the deref
                // provably null-safe for the static analyzer.
                .map(Path::getFileName).map(name -> name.toString().substring(BACKUP_PREFIX.length())).orElse(null);
    }

    public String appVersion() {
        return applicationVersion.value();
    }

    private Path resolveDbFile() {
        return storageProperties.getDbPath().resolve(MailBackendApplication.APP_NAME + ".db");
    }

}
