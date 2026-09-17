package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.ErrorDetails;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.voxrox.mailbackend.core.backup.DatabaseBackupService;
import org.voxrox.mailbackend.core.init.StartupTimingService;
import org.voxrox.mailbackend.core.lifecycle.StartupFailure;
import org.voxrox.mailbackend.core.lifecycle.StartupFailure.Reason;

class DatabaseConfigTest {

    @Test
    @DisplayName("verifySqlitePragmas reads the PRAGMAs and leaves the integrity check to the migration hook")
    void verifySqlitePragmasDoesNotRepeatTheIntegrityCheck() throws Exception {
        DataSource dataSource = dataSourceWithResults("wal", "1", "1", "5000", "-20000", "*** in database main ***");
        DatabaseConfig config = new DatabaseConfig(dataSource, new StartupTimingService());

        assertThatCode(config::verifySqlitePragmas).doesNotThrowAnyException();
        verify(dataSource.getConnection().createStatement(), never()).executeQuery("PRAGMA quick_check;");
    }

    @Test
    @DisplayName("a damaged database fails startup before Flyway reads it and before any backup")
    void preMigrationHookFailsOnDamagedDatabase() throws Exception {
        DatabaseBackupService backupService = mock(DatabaseBackupService.class);
        Flyway flyway = flywayWith(validationSuccess(), 1);
        FlywayMigrationStrategy strategy = new DatabaseConfig(
                dataSourceWithResults("wal", "1", "1", "5000", "-20000", "*** in database main ***"),
                new StartupTimingService()).preMigrationBackupStrategy(backupService);

        assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOfSatisfying(StartupFailure.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.DATABASE_DAMAGED))
                .hasMessageContaining("database is damaged").hasMessageContaining("backup-pre-v")
                .hasMessageContaining("OPERATIONS.md");

        // A damaged file is neither migrated nor kept as if it were a restore point.
        verify(flyway, never()).validateWithResult();
        verify(backupService, never()).createPreMigrationBackup();
        verify(flyway, never()).migrate();
    }

    @Test
    @DisplayName("a backup that cannot be written fails startup as a storage problem, before migrate")
    void preMigrationHookFailsOnBackupFailure() throws Exception {
        DatabaseBackupService backupService = mock(DatabaseBackupService.class);
        when(backupService.createPreMigrationBackup())
                .thenThrow(new IllegalStateException("Failed to create pre-migration DB backup"));
        Flyway flyway = flywayWith(validationSuccess(), 1);
        FlywayMigrationStrategy strategy = strategy(backupService);

        assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOfSatisfying(StartupFailure.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.STORAGE_UNAVAILABLE))
                .hasMessageContaining("free disk space");

        verify(flyway, never()).migrate();
    }

    @Test
    @DisplayName("pre-migration hook backs up and migrates when a migration is pending")
    void preMigrationHookBacksUpPendingMigration() throws Exception {
        DatabaseBackupService backupService = mock(DatabaseBackupService.class);
        Flyway flyway = flywayWith(validationSuccess(), 1);

        strategy(backupService).migrate(flyway);

        verify(backupService).createPreMigrationBackup();
        verify(flyway).migrate();
    }

    @Test
    @DisplayName("pre-migration hook skips the backup when nothing is pending")
    void preMigrationHookSkipsBackupWhenUpToDate() throws Exception {
        DatabaseBackupService backupService = mock(DatabaseBackupService.class);
        Flyway flyway = flywayWith(validationSuccess(), 0);

        strategy(backupService).migrate(flyway);

        verify(backupService, never()).createPreMigrationBackup();
        verify(flyway).migrate();
    }

    @Test
    @DisplayName("a migration altered after being applied fails startup before migrate, with a backup taken")
    void preMigrationHookFailsOnAlteredAppliedMigration() throws Exception {
        DatabaseBackupService backupService = mock(DatabaseBackupService.class);
        Flyway flyway = flywayWith(validationFailure(CoreErrorCode.CHECKSUM_MISMATCH), 0);
        FlywayMigrationStrategy strategy = strategy(backupService);

        assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOfSatisfying(StartupFailure.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.SCHEMA_MISMATCH))
                .hasMessageContaining("V1 CHECKSUM_MISMATCH").hasMessageContaining("will NOT help")
                .hasMessageContaining("OPERATIONS.md");

        // The snapshot is the restore point taken before anyone attempts a repair;
        // migrate() must not run, or Flyway throws its own opaque validation error.
        verify(backupService).createPreMigrationBackup();
        verify(flyway, never()).migrate();
    }

    @Test
    @DisplayName("a pending migration failing validation is not mistaken for an altered one")
    void preMigrationHookIgnoresPendingValidationFailure() throws Exception {
        DatabaseBackupService backupService = mock(DatabaseBackupService.class);
        // Validation reports pending migrations as a failure too. Treating any failed
        // validation as "the build is wrong" would refuse to start on every update
        // that carries a new migration — the exact opposite of the intended guard.
        Flyway flyway = flywayWith(validationFailure(CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED), 1);

        strategy(backupService).migrate(flyway);

        verify(flyway).migrate();
    }

    private FlywayMigrationStrategy strategy(DatabaseBackupService backupService) throws Exception {
        DatabaseConfig config = new DatabaseConfig(dataSourceWithResults("wal", "1", "1", "5000", "-20000", "ok"),
                new StartupTimingService());
        return config.preMigrationBackupStrategy(backupService);
    }

    private Flyway flywayWith(ValidateResult validation, int pendingCount) {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        when(flyway.validateWithResult()).thenReturn(validation);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.pending()).thenReturn(new MigrationInfo[pendingCount]);
        return flyway;
    }

    private ValidateResult validationSuccess() {
        return new ValidateResult("12", "sqlite", null, true, 1, List.of(), List.of());
    }

    private ValidateResult validationFailure(CoreErrorCode errorCode) {
        ValidateOutput output = new ValidateOutput("1", "init", "V1__init.sql",
                new ErrorDetails(errorCode, errorCode.name()));
        return new ValidateResult("12", "sqlite", output.errorDetails, false, 1, List.of(output), List.of());
    }

    private DataSource dataSourceWithResults(String journalMode, String synchronous, String foreignKeys,
            String busyTimeout, String cacheSize, String quickCheck) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        ResultSet journalModeResult = resultSet(journalMode);
        ResultSet synchronousResult = resultSet(synchronous);
        ResultSet foreignKeysResult = resultSet(foreignKeys);
        ResultSet busyTimeoutResult = resultSet(busyTimeout);
        ResultSet cacheSizeResult = resultSet(cacheSize);
        ResultSet quickCheckResult = resultSet(quickCheck);

        when(statement.executeQuery("PRAGMA journal_mode;")).thenReturn(journalModeResult);
        when(statement.executeQuery("PRAGMA synchronous;")).thenReturn(synchronousResult);
        when(statement.executeQuery("PRAGMA foreign_keys;")).thenReturn(foreignKeysResult);
        when(statement.executeQuery("PRAGMA busy_timeout;")).thenReturn(busyTimeoutResult);
        when(statement.executeQuery("PRAGMA cache_size;")).thenReturn(cacheSizeResult);
        when(statement.executeQuery("PRAGMA quick_check;")).thenReturn(quickCheckResult);

        return dataSource;
    }

    private ResultSet resultSet(String value) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn(value);
        return resultSet;
    }
}
