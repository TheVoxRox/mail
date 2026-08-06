package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.voxrox.mailbackend.core.backup.DatabaseBackupService;
import org.voxrox.mailbackend.core.init.StartupTimingService;

/**
 * Drives {@code DatabaseConfig.preMigrationBackupStrategy} against a real
 * Flyway and a real SQLite file, reproducing the update sequence the mock-based
 * tests in {@link DatabaseConfigTest} can only approximate: install, then start
 * again from a build whose migration file has changed underneath an applied
 * version.
 *
 * <p>
 * The distinction the strategy has to get right is invisible to a mock, because
 * it depends on how Flyway itself classifies each state — a pending migration
 * and an altered applied one both make {@code validationSuccessful} false. This
 * test pins the real classification: first start (empty schema, migration
 * pending) and plain restart (nothing to do) must both proceed, only the
 * altered file may fail startup.
 *
 * <p>
 * Uses its own throwaway migration directory rather than the real
 * {@code classpath:db/migration}, whose baseline is frozen by
 * {@link FlywayBaselineChecksumTest} and must not be mutated by a test.
 */
class FlywayAlteredMigrationDetectionTest {

    @TempDir
    Path tempDir;

    private Path migrationDir;
    private String jdbcUrl;
    private DatabaseBackupService backupService;
    private FlywayMigrationStrategy strategy;

    @BeforeEach
    void setUp() throws Exception {
        migrationDir = Files.createDirectories(tempDir.resolve("migration"));
        jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("altered-probe.db");
        writeBaseline("CREATE TABLE probe (id INTEGER PRIMARY KEY);");

        backupService = mock(DatabaseBackupService.class);
        strategy = new DatabaseConfig(pragmaStubDataSource(), new StartupTimingService())
                .preMigrationBackupStrategy(backupService);
    }

    @Test
    @DisplayName("first start applies the pending baseline instead of reporting it as altered")
    void firstStartAppliesPendingBaseline() {
        assertThatCode(() -> strategy.migrate(flyway())).doesNotThrowAnyException();

        verify(backupService).createPreMigrationBackup();
    }

    @Test
    @DisplayName("restart on an unchanged migration set skips the backup and proceeds")
    void restartOnUnchangedMigrationsProceeds() {
        strategy.migrate(flyway());

        DatabaseBackupService restartBackupService = mock(DatabaseBackupService.class);
        FlywayMigrationStrategy restartStrategy = restartStrategy(restartBackupService);

        assertThatCode(() -> restartStrategy.migrate(flyway())).doesNotThrowAnyException();
        verify(restartBackupService, never()).createPreMigrationBackup();
    }

    @Test
    @DisplayName("a baseline edited after it was applied fails startup with recovery guidance")
    void alteredBaselineFailsStartup() throws Exception {
        strategy.migrate(flyway());

        // Exactly the post-release mistake the guard exists for: the schema change is
        // squashed into the already-applied baseline instead of landing as V2.
        writeBaseline("CREATE TABLE probe (id INTEGER PRIMARY KEY, added_later TEXT);");

        DatabaseBackupService updateBackupService = mock(DatabaseBackupService.class);
        FlywayMigrationStrategy updateStrategy = restartStrategy(updateBackupService);
        Flyway updated = flyway();

        assertThatThrownBy(() -> updateStrategy.migrate(updated)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V1").hasMessageContaining("CHECKSUM_MISMATCH")
                .hasMessageContaining("will NOT help").hasMessageContaining("OPERATIONS.md");

        // The snapshot is what the pending-only condition used to skip for this exact
        // failure — the one where the user is left with a sidecar that will not boot.
        verify(updateBackupService).createPreMigrationBackup();
    }

    private void writeBaseline(String ddl) throws Exception {
        Files.writeString(migrationDir.resolve("V1__probe.sql"), ddl + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    /**
     * Mirrors the production Flyway setup ({@code application.properties}):
     * baseline settings on, validation left at its {@code validate-on-migrate=true}
     * default. The baseline options are a no-op against an empty database and are
     * kept so the test cannot pass by accident under a laxer configuration than the
     * app ships.
     */
    private Flyway flyway() {
        return Flyway.configure().dataSource(jdbcUrl, null, null)
                .locations("filesystem:" + migrationDir.toAbsolutePath()).baselineOnMigrate(true).baselineVersion("1")
                .load();
    }

    private FlywayMigrationStrategy restartStrategy(DatabaseBackupService service) {
        return new DatabaseConfig(pragmaStubDataSource(), new StartupTimingService())
                .preMigrationBackupStrategy(service);
    }

    /**
     * {@code DatabaseConfig} needs a {@link DataSource} for its PRAGMA
     * verification, which this test never triggers — the migration strategy does
     * not touch it.
     */
    private DataSource pragmaStubDataSource() {
        try {
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            ResultSet resultSet = mock(ResultSet.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(resultSet);
            return dataSource;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to stub the DataSource", e);
        }
    }
}
