package org.voxrox.mailbackend.core.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.voxrox.mailbackend.core.backup.DatabaseBackupService;
import org.voxrox.mailbackend.core.init.StartupTimingService;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Verifies SQLite PRAGMA settings after application startup.
 * <p>
 * The PRAGMAs themselves are applied via JDBC URL parameters (see
 * {@code spring.datasource.url}) — the xerial sqlite-jdbc driver applies them
 * to every connection in the Hikari pool. This class no longer
 * <strong>sets</strong> PRAGMAs; it only reads them back from a fresh
 * connection and logs them, so any regression (wrong URL, different driver)
 * surfaces at startup rather than at runtime.
 */
@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);
    private final DataSource dataSource;
    private final StartupTimingService startupTimingService;

    public DatabaseConfig(DataSource dataSource, StartupTimingService startupTimingService) {
        this.dataSource = dataSource;
        this.startupTimingService = startupTimingService;
    }

    /**
     * Pre-hook before {@code flyway.migrate()} — produces a DB snapshot for
     * recovery in case the new migration corrupts data. The backup is expensive
     * (copies the entire SQLite file aside) and used to run on every startup; in 99
     * % of cases there is nothing to migrate — the schema matches, no pending
     * migrations. The backup now runs only when Flyway reports at least one pending
     * migration, so a normal cold start does not pay this latency.
     */
    @Bean
    FlywayMigrationStrategy preMigrationBackupStrategy(DatabaseBackupService databaseBackupService) {
        return flyway -> {
            boolean hasPendingMigrations = flyway.info().pending().length > 0;
            if (hasPendingMigrations) {
                long backupStarted = startupTimingService.start();
                databaseBackupService.createPreMigrationBackup();
                startupTimingService.record("db.pre-migration-backup", backupStarted);
            } else {
                log.info("{} No pending migrations, skipping pre-migration backup.", LogCategory.DATABASE);
            }

            long migrateStarted = startupTimingService.start();
            flyway.migrate();
            startupTimingService.record("db.flyway-migrate", migrateStarted);
        };
    }

    @EventListener(ApplicationStartedEvent.class)
    public void verifySqlitePragmas() {
        long started = startupTimingService.start();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {

            String journalMode = readPragma(statement, "journal_mode");
            String synchronous = readPragma(statement, "synchronous");
            String foreignKeys = readPragma(statement, "foreign_keys");
            String busyTimeout = readPragma(statement, "busy_timeout");
            String cacheSize = readPragma(statement, "cache_size");

            log.info(
                    "{} SQLite PRAGMAs (per-connection): journal_mode={}, synchronous={}, "
                            + "foreign_keys={}, busy_timeout={}ms, cache_size={}",
                    LogCategory.DATABASE, journalMode, synchronous, foreignKeys, busyTimeout, cacheSize);

            // Sanity-check of critical values — failure is visible immediately at startup.
            if (!"wal".equalsIgnoreCase(journalMode)) {
                log.error("{} SQLite is NOT in WAL mode (current: {}). Check spring.datasource.url.",
                        LogCategory.DATABASE, journalMode);
            }
            if (!"1".equals(foreignKeys)) {
                log.error("{} SQLite foreign_keys are NOT enabled (current: {}). Integrity risks!",
                        LogCategory.DATABASE, foreignKeys);
            }
            if ("0".equals(busyTimeout)) {
                log.error("{} SQLite busy_timeout=0 — contention will cause immediate SQLITE_BUSY errors.",
                        LogCategory.DATABASE);
            }
            verifyDatabaseIntegrity(statement);

        } catch (SQLException e) {
            log.error("{} Failed to verify SQLite PRAGMAs", LogCategory.DATABASE, e);
            throw new IllegalStateException("SQLite configuration verification failed", e);
        } finally {
            startupTimingService.record("db.verify-pragmas", started);
        }
    }

    private void verifyDatabaseIntegrity(Statement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery("PRAGMA quick_check;")) {
            boolean ok = false;
            while (rs.next()) {
                String result = rs.getString(1);
                if ("ok".equalsIgnoreCase(result)) {
                    ok = true;
                    continue;
                }

                log.error("{} SQLite quick_check failed: {}", LogCategory.DATABASE, result);
                AuditLog.critical("db_corruption_detected", "system", result);
                AuditLog.critical("startup_health_gate_failed", "system", "quick_check=" + result);
                throw new IllegalStateException(formatHealthGateMessage("quick_check failed: " + result));
            }
            if (!ok) {
                log.error("{} SQLite quick_check returned no result.", LogCategory.DATABASE);
                AuditLog.critical("db_corruption_detected", "system", "quick_check_empty");
                AuditLog.critical("startup_health_gate_failed", "system", "quick_check_empty");
                throw new IllegalStateException(formatHealthGateMessage("quick_check returned no result"));
            }
            log.info("{} SQLite quick_check=ok", LogCategory.DATABASE);
        }
    }

    private String formatHealthGateMessage(String reason) {
        return "Application failed to start after update (" + reason + "). "
                + "Restore from the latest backup-pre-v* snapshot (see OPERATIONS.md, section 'Update troubleshooting').";
    }

    private String readPragma(Statement statement, String pragma) throws SQLException {
        try (ResultSet rs = statement.executeQuery("PRAGMA " + pragma + ";")) {
            return rs.next() ? rs.getString(1) : "<empty>";
        }
    }
}
