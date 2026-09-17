package org.voxrox.mailbackend.core.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.ErrorCode;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.voxrox.mailbackend.core.backup.DatabaseBackupService;
import org.voxrox.mailbackend.core.init.StartupTimingService;
import org.voxrox.mailbackend.core.lifecycle.StartupFailure;
import org.voxrox.mailbackend.core.lifecycle.StartupFailure.Reason;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Guards the database at startup: its integrity before anything migrates or
 * copies it, then the SQLite PRAGMA settings once the application has started.
 * <p>
 * The PRAGMAs themselves are applied via JDBC URL parameters (see
 * {@code spring.datasource.url}) — the xerial sqlite-jdbc driver applies them
 * to every connection in the Hikari pool. This class no longer
 * <strong>sets</strong> PRAGMAs; it only reads them back from a fresh
 * connection and logs them, so any regression (wrong URL, different driver)
 * surfaces at startup rather than at runtime.
 * <p>
 * A start this class refuses fails with a {@link StartupFailure}, whose exit
 * code tells the desktop client which of three things went wrong: a damaged
 * database, a build whose migrations do not match the database, or storage that
 * cannot be opened or written.
 */
@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    /**
     * SQLite primary result codes that mean the file itself is bad
     * ({@code SQLITE_CORRUPT}, {@code SQLITE_NOTADB}). Anything else that stops the
     * database from opening is a storage problem, not a damaged database.
     */
    private static final Set<Integer> DAMAGED_RESULT_CODES = Set.of(11, 26);
    private static final int PRIMARY_RESULT_CODE_MASK = 0xFF;
    private static final String RUNBOOK = "see OPERATIONS.md, section 'Damaged database'";

    private final DataSource dataSource;
    private final StartupTimingService startupTimingService;

    public DatabaseConfig(DataSource dataSource, StartupTimingService startupTimingService) {
        this.dataSource = dataSource;
        this.startupTimingService = startupTimingService;
    }

    /**
     * Error codes Flyway reports when a migration <em>that is already applied</em>
     * no longer matches the file shipped in this build. Distinguished from the rest
     * of the validation failures because they are the ones that mean "the shipped
     * build is wrong", not "the database is behind": a pending migration also fails
     * validation ({@code RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED}) and is the
     * normal state of every update.
     */
    private static final Set<ErrorCode> ALTERED_APPLIED_MIGRATION_CODES = Set.of(CoreErrorCode.CHECKSUM_MISMATCH,
            CoreErrorCode.DESCRIPTION_MISMATCH, CoreErrorCode.TYPE_MISMATCH);

    /**
     * Pre-hook before {@code flyway.migrate()} — produces a DB snapshot for
     * recovery in case the new migration corrupts data. The backup is expensive
     * (copies the entire SQLite file aside) and used to run on every startup; in 99
     * % of cases there is nothing to migrate — the schema matches, no pending
     * migrations. The backup runs only when Flyway reports at least one pending
     * migration, so a normal cold start does not pay this latency.
     * <p>
     * The second trigger is an altered applied migration (see
     * {@link #ALTERED_APPLIED_MIGRATION_CODES}). That state is <em>not</em>
     * "pending", so the pending-only condition used to skip the snapshot for the
     * one failure that leaves the user with a sidecar that will not boot. The
     * snapshot is taken first — cheap insurance and a clean restore point taken
     * before anyone attempts a manual repair — and only then does startup fail with
     * a named cause instead of a bare {@code FlywayValidateException}.
     * <p>
     * The integrity check runs before all of it. It used to run after the
     * migration, so a damaged database was first migrated and snapshotted, and a
     * file that is not a database at all failed inside Flyway with no name and no
     * audit record.
     */
    @Bean
    FlywayMigrationStrategy preMigrationBackupStrategy(DatabaseBackupService databaseBackupService) {
        return flyway -> {
            verifyIntegrity();

            List<ValidateOutput> alteredMigrations = alteredAppliedMigrations(flyway.validateWithResult());
            boolean hasPendingMigrations = flyway.info().pending().length > 0;

            if (hasPendingMigrations || !alteredMigrations.isEmpty()) {
                long backupStarted = startupTimingService.start();
                createBackup(databaseBackupService);
                startupTimingService.record("db.pre-migration-backup", backupStarted);
            } else {
                log.info("{} No pending migrations, skipping pre-migration backup.", LogCategory.DATABASE);
            }

            if (!alteredMigrations.isEmpty()) {
                failOnAlteredMigrations(alteredMigrations);
            }

            long migrateStarted = startupTimingService.start();
            flyway.migrate();
            startupTimingService.record("db.flyway-migrate", migrateStarted);
        };
    }

    /**
     * Runs {@code PRAGMA quick_check} on a fresh connection. A damaged file shows
     * either as rows other than {@code ok} or, when the damage reaches the header
     * or the schema, as an exception from opening the connection or running the
     * check — the pool wraps that one, so the SQLite result code is looked up
     * through the cause chain.
     */
    private void verifyIntegrity() {
        long started = startupTimingService.start();
        @Nullable
        String problem;
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("PRAGMA quick_check;")) {
            problem = quickCheckProblem(rs);
        } catch (SQLException | RuntimeException e) {
            throw openFailure(e);
        } finally {
            startupTimingService.record("db.integrity-check", started);
        }
        if (problem != null) {
            throw damaged(problem, null);
        }
        log.info("{} SQLite quick_check=ok", LogCategory.DATABASE);
    }

    private static @Nullable String quickCheckProblem(ResultSet rs) throws SQLException {
        boolean ok = false;
        while (rs.next()) {
            String result = rs.getString(1);
            if (!"ok".equalsIgnoreCase(result)) {
                return "quick_check: " + result;
            }
            ok = true;
        }
        return ok ? null : "quick_check returned no result";
    }

    private StartupFailure openFailure(Exception failure) {
        SQLException sqlFailure = findSqlException(failure);
        String detail = sqlFailure != null ? String.valueOf(sqlFailure.getMessage()) : failure.toString();
        if (sqlFailure != null && DAMAGED_RESULT_CODES.contains(sqlFailure.getErrorCode() & PRIMARY_RESULT_CODE_MASK)) {
            return damaged(detail, failure);
        }
        log.error("{} SQLite database cannot be opened: {}", LogCategory.DATABASE, detail, failure);
        AuditLog.critical("startup_health_gate_failed", "system", "db_unavailable=" + detail);
        return new StartupFailure(Reason.STORAGE_UNAVAILABLE,
                "Application failed to start: the database cannot be " + "opened (" + detail
                        + "). Check the free disk space and the permissions of the data directory, "
                        + "and that no other instance of the application is running.",
                failure);
    }

    private static @Nullable SQLException findSqlException(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        return null;
    }

    private StartupFailure damaged(String detail, @Nullable Throwable cause) {
        log.error("{} SQLite database is damaged: {}", LogCategory.DATABASE, detail);
        AuditLog.critical("db_corruption_detected", "system", detail);
        AuditLog.critical("startup_health_gate_failed", "system", "db_damaged=" + detail);
        return new StartupFailure(Reason.DATABASE_DAMAGED,
                "Application failed to start: the database is damaged (" + detail
                        + "). Restore the latest backup-pre-v* snapshot, or move the database aside to start with "
                        + "an empty one (" + RUNBOOK + ").",
                cause);
    }

    /**
     * Takes the pre-migration snapshot. The backup service has already logged and
     * audited a failure ({@code db_backup_failed}); what is left is telling the
     * client that storage, not the build, is what stopped the start.
     */
    private void createBackup(DatabaseBackupService databaseBackupService) {
        try {
            databaseBackupService.createPreMigrationBackup();
        } catch (IllegalStateException e) {
            throw new StartupFailure(Reason.STORAGE_UNAVAILABLE,
                    "Application failed to start: the pre-migration "
                            + "backup could not be written. Check the free disk space and the permissions of the data "
                            + "directory (see OPERATIONS.md, section 'Update troubleshooting').",
                    e);
        }
    }

    private List<ValidateOutput> alteredAppliedMigrations(ValidateResult validation) {
        if (validation.validationSuccessful) {
            return List.of();
        }
        return validation.invalidMigrations.stream().filter(output -> output.errorDetails != null
                && ALTERED_APPLIED_MIGRATION_CODES.contains(output.errorDetails.errorCode)).toList();
    }

    /**
     * Fails startup for a migration file that changed after it was applied. Flyway
     * would throw on its own inside {@code migrate()}, but only as a generic
     * validation error; naming the cause here is what lets the audit log, the
     * diagnostic dump and the OPERATIONS runbook identify it without a debugger on
     * the user's machine.
     * <p>
     * Deliberately does <em>not</em> call {@code flyway.repair()}. Repair rewrites
     * the recorded checksums to match whatever this build ships, which would turn a
     * loud refusal to start into a silent acceptance of a schema the database does
     * not actually have — the data-corrupting outcome. The only correct fix is a
     * build whose migrations match, so the message says exactly that instead of
     * pointing at the backup: unlike a corrupted database, this one is intact and
     * restoring a snapshot changes nothing.
     */
    private void failOnAlteredMigrations(List<ValidateOutput> alteredMigrations) {
        String detail = alteredMigrations.stream()
                .map(output -> "V" + output.version + " " + output.errorDetails.errorCode)
                .collect(Collectors.joining(", "));

        log.error("{} Migration file(s) changed after being applied: {}. The schema in this build does not match "
                + "the one recorded in the database.", LogCategory.DATABASE, detail);
        AuditLog.critical("db_migration_altered_after_apply", "system", detail);
        AuditLog.critical("startup_health_gate_failed", "system", "flyway_altered_migration=" + detail);

        throw new StartupFailure(Reason.SCHEMA_MISMATCH,
                "Application failed to start after update (" + detail
                        + "). The database is intact — restoring a backup-pre-v* snapshot will NOT help. "
                        + "This build has to be replaced by one whose migrations match the installed schema "
                        + "(see OPERATIONS.md, section 'Update troubleshooting').",
                null);
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

        } catch (SQLException e) {
            log.error("{} Failed to verify SQLite PRAGMAs", LogCategory.DATABASE, e);
            throw new IllegalStateException("SQLite configuration verification failed", e);
        } finally {
            startupTimingService.record("db.verify-pragmas", started);
        }
    }

    private String readPragma(Statement statement, String pragma) throws SQLException {
        try (ResultSet rs = statement.executeQuery("PRAGMA " + pragma + ";")) {
            return rs.next() ? rs.getString(1) : "<empty>";
        }
    }
}
