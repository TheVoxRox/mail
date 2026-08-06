package org.voxrox.mailbackend.core.config;

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

class DatabaseConfigTest {

    @Test
    @DisplayName("verifySqlitePragmas passes when quick_check returns ok")
    void verifySqlitePragmasPassesWhenQuickCheckIsOk() throws Exception {
        DatabaseConfig config = new DatabaseConfig(dataSourceWithResults("wal", "1", "1", "5000", "-20000", "ok"),
                new StartupTimingService());

        assertThatCode(config::verifySqlitePragmas).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("verifySqlitePragmas fail-fasts with recovery guidance when quick_check fails")
    void verifySqlitePragmasFailsWhenQuickCheckReportsCorruption() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                dataSourceWithResults("wal", "1", "1", "5000", "-20000", "*** in database main ***"),
                new StartupTimingService());

        assertThatThrownBy(config::verifySqlitePragmas).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Application failed to start after update").hasMessageContaining("backup-pre-v")
                .hasMessageContaining("OPERATIONS.md");
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

        assertThatThrownBy(() -> strategy.migrate(flyway)).isInstanceOf(IllegalStateException.class)
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
