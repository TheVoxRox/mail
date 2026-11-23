package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.sqlite.SQLiteDataSource;
import org.voxrox.mailbackend.core.backup.DatabaseBackupService;
import org.voxrox.mailbackend.core.init.StartupTimingService;
import org.voxrox.mailbackend.core.lifecycle.StartupFailure;
import org.voxrox.mailbackend.core.lifecycle.StartupFailure.Reason;

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
 * A Java migration is compared only while it has a checksum on both sides. The
 * class does not get one unless it overrides {@code getChecksum()}, and Flyway
 * skips the comparison when either the recorded checksum or the build's is null
 * ({@code MigrationInfo.isChecksumMatching()}).
 * {@code changedJavaChecksumFailsStartup},
 * {@code checksumAddedAfterApplyIsNeverCompared} and
 * {@code checksumRemovedAfterApplyIsNotCompared} pin that for the Flyway this
 * build ships, because two rules rest on it (#997, {@code docs/DECISIONS.md},
 * "A released migration is frozen by its tag"):
 * {@code V2__Fold_keys_and_strip_text}, recorded without a checksum, can be
 * guarded only by {@code check:migrations}, and every later Java migration
 * carries a checksum from its first release ({@link JavaMigrationRulesTest}).
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
        strategy = new DatabaseConfig(dataSource(), new StartupTimingService())
                .preMigrationBackupStrategy(backupService);
    }

    @Test
    @DisplayName("first start applies the pending baseline instead of reporting it as altered")
    void firstStartAppliesPendingBaseline() {
        assertThatCode(() -> strategy.migrate(flyway())).doesNotThrowAnyException();

        // Pending, but over an empty database: there is nothing to snapshot yet.
        verify(backupService, never()).createPreMigrationBackup();
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

        assertThatThrownBy(() -> updateStrategy.migrate(updated))
                .isInstanceOfSatisfying(StartupFailure.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.SCHEMA_MISMATCH))
                .hasMessageContaining("V1").hasMessageContaining("CHECKSUM_MISMATCH")
                .hasMessageContaining("will NOT help").hasMessageContaining("OPERATIONS.md");

        // The snapshot is what the pending-only condition used to skip for this exact
        // failure — the one where the user is left with a sidecar that will not boot.
        verify(updateBackupService).createPreMigrationBackup();
    }

    @Test
    @DisplayName("a Java migration whose checksum changes after it was applied fails startup like an edited SQL file")
    void changedJavaChecksumFailsStartup() {
        strategy.migrate(flyway(new V2__Java_probe(42)));

        FlywayMigrationStrategy updateStrategy = restartStrategy(mock(DatabaseBackupService.class));
        Flyway updated = flyway(new V2__Java_probe(43));

        assertThatThrownBy(() -> updateStrategy.migrate(updated))
                .isInstanceOfSatisfying(StartupFailure.class,
                        failure -> assertThat(failure.reason()).isEqualTo(Reason.SCHEMA_MISMATCH))
                .hasMessageContaining("V2").hasMessageContaining("CHECKSUM_MISMATCH");
    }

    @Test
    @DisplayName("a checksum given to a Java migration applied without one is neither compared nor recorded")
    void checksumAddedAfterApplyIsNeverCompared() {
        strategy.migrate(flyway(new V2__Java_probe(null)));

        FlywayMigrationStrategy updateStrategy = restartStrategy(mock(DatabaseBackupService.class));
        Flyway updated = flyway(new V2__Java_probe(42));

        assertThatCode(() -> updateStrategy.migrate(updated)).doesNotThrowAnyException();
        // The row keeps its null, so on this installation no later build is ever
        // compared for V2, and the audit field reads <none> for it for good.
        assertThat(applied(updated, "2").getChecksum()).isNull();
    }

    @Test
    @DisplayName("a checksum taken away from an applied Java migration is not compared either")
    void checksumRemovedAfterApplyIsNotCompared() {
        strategy.migrate(flyway(new V2__Java_probe(42)));

        FlywayMigrationStrategy updateStrategy = restartStrategy(mock(DatabaseBackupService.class));
        Flyway updated = flyway(new V2__Java_probe(null));

        assertThatCode(() -> updateStrategy.migrate(updated)).doesNotThrowAnyException();
    }

    private static MigrationInfo applied(Flyway flyway, String version) {
        MigrationVersion wanted = MigrationVersion.fromVersion(version);
        return Arrays.stream(flyway.info().applied()).filter(info -> wanted.equals(info.getVersion())).findFirst()
                .orElseThrow(() -> new AssertionError("V" + version + " is not applied"));
    }

    /**
     * A Java migration standing for {@code V2__Fold_keys_and_strip_text}, with the
     * checksum it is given, null being what a class that does not override
     * {@code getChecksum()} returns. Flyway takes version and description from the
     * simple class name, as it does for the real one.
     */
    static final class V2__Java_probe extends BaseJavaMigration {

        private final @Nullable Integer checksum;

        V2__Java_probe(@Nullable Integer checksum) {
            this.checksum = checksum;
        }

        @Override
        public @Nullable Integer getChecksum() {
            return checksum;
        }

        @Override
        public void migrate(Context context) throws SQLException {
            try (Statement statement = context.getConnection().createStatement()) {
                statement.execute("CREATE TABLE java_probe (id INTEGER PRIMARY KEY)");
            }
        }
    }

    private void writeBaseline(String ddl) throws Exception {
        Files.writeString(migrationDir.resolve("V1__probe.sql"), ddl + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    /**
     * Mirrors the production Flyway setup ({@code application.properties}):
     * baselining off, validation left at its {@code validate-on-migrate=true}
     * default, so the test cannot pass by accident under a laxer configuration than
     * the app ships.
     */
    private Flyway flyway(JavaMigration... javaMigrations) {
        return Flyway.configure().dataSource(jdbcUrl, null, null)
                .locations("filesystem:" + migrationDir.toAbsolutePath()).javaMigrations(javaMigrations).load();
    }

    private FlywayMigrationStrategy restartStrategy(DatabaseBackupService service) {
        return new DatabaseConfig(dataSource(), new StartupTimingService()).preMigrationBackupStrategy(service);
    }

    /**
     * The same file Flyway migrates. The hook runs {@code PRAGMA quick_check} on it
     * before anything else, so a stub that answers nothing would read as a damaged
     * database.
     */
    private SQLiteDataSource dataSource() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(jdbcUrl);
        return dataSource;
    }
}
