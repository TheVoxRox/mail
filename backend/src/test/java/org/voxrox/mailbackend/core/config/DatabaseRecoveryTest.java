package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.core.backup.BackupProperties;
import org.voxrox.mailbackend.core.backup.DatabaseBackupService;
import org.voxrox.mailbackend.core.init.StartupTimingService;
import org.voxrox.mailbackend.core.lifecycle.StartupFailure;
import org.voxrox.mailbackend.core.lifecycle.StartupFailure.Reason;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The recovery half of RELEASE_CHECKLIST §6, against a real SQLite file: what a
 * start does with a damaged database, and whether the restore procedure in
 * OPERATIONS.md brings it back. Each "start" is the production migration hook
 * over a Hikari pool opened with the production URL parameters, because the
 * pool is what wraps the SQLite error of a file that is not a database, and the
 * {@code journal_mode=WAL} parameter is what makes the open itself fail.
 * <p>
 * The migrations are a throwaway directory, like in
 * {@link FlywayAlteredMigrationDetectionTest}; the real baseline is frozen and
 * the restore case needs a second version to update to.
 */
class DatabaseRecoveryTest {

    private static final String URL_PARAMETERS = "?journal_mode=WAL&synchronous=NORMAL&foreign_keys=ON"
            + "&busy_timeout=5000";
    private static final int MARKER_ROWS = 2000;

    private Path dataDir;
    private Path migrationDir;
    private Path dbFile;

    @BeforeEach
    void setUp() throws IOException {
        Path baseDir = Path.of("target", "test-tmp", "DatabaseRecoveryTest").toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        dataDir = Files.createTempDirectory(baseDir, "case-");
        migrationDir = Files.createDirectories(dataDir.resolve("migration"));
        dbFile = Files.createDirectories(dataDir.resolve("db")).resolve("mail.db");
        writeMigration("V1__marker.sql", "CREATE TABLE marker (id INTEGER PRIMARY KEY, label TEXT NOT NULL);");
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(dataDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    @DisplayName("a file that is not a database is reported as damaged, and nothing writes to it")
    void notADatabaseIsDamaged() throws Exception {
        byte[] garbage = new byte[8192];
        new Random(7).nextBytes(garbage);
        Files.write(dbFile, garbage);

        assertThatThrownBy(() -> start("1.0.0")).isInstanceOfSatisfying(StartupFailure.class,
                failure -> assertThat(failure.reason()).isEqualTo(Reason.DATABASE_DAMAGED));

        assertThat(Files.readAllBytes(dbFile)).isEqualTo(garbage);
        assertThat(backups()).isEmpty();
    }

    @Test
    @DisplayName("a database with damaged pages is reported as damaged")
    void damagedPagesAreDamaged() throws Exception {
        start("1.0.0");
        insertMarkers();
        corruptMiddlePages();

        assertThatThrownBy(() -> start("1.0.0")).isInstanceOfSatisfying(StartupFailure.class,
                failure -> assertThat(failure.reason()).isEqualTo(Reason.DATABASE_DAMAGED));
    }

    @Test
    @DisplayName("a database path that cannot be opened is a storage problem, not damage")
    void unopenableDatabaseIsStorageUnavailable() throws Exception {
        // A directory where the file should be, which SQLite cannot open.
        Files.createDirectories(dbFile);

        assertThatThrownBy(() -> start("1.0.0")).isInstanceOfSatisfying(StartupFailure.class,
                failure -> assertThat(failure.reason()).isEqualTo(Reason.STORAGE_UNAVAILABLE));
    }

    @Test
    @DisplayName("the OPERATIONS.md restore brings a damaged database back from the pre-migration backup")
    void restoreFromPreMigrationBackup() throws Exception {
        start("1.0.0");
        // The install itself had a migration to apply, so it left a snapshot too: an
        // empty one, as OPERATIONS.md warns.
        assertThat(dbFile.resolveSibling("mail.db.backup-pre-v1.0.0")).exists();
        insertMarkers();

        // An update with a migration: the hook snapshots the database first.
        writeMigration("V2__marker_note.sql", "ALTER TABLE marker ADD COLUMN note TEXT;");
        start("1.1.0");
        Path backup = dbFile.resolveSibling("mail.db.backup-pre-v1.1.0");
        assertThat(backup).exists();

        corruptMiddlePages();
        assertThatThrownBy(() -> start("1.1.0")).isInstanceOf(StartupFailure.class);

        // The procedure in OPERATIONS.md, section "Damaged database", step by step.
        Files.move(dbFile, dbFile.resolveSibling("mail.db.broken"));
        Files.deleteIfExists(dbFile.resolveSibling("mail.db-wal"));
        Files.deleteIfExists(dbFile.resolveSibling("mail.db-shm"));
        Files.copy(backup, dbFile, StandardCopyOption.COPY_ATTRIBUTES);

        assertThatCode(() -> start("1.1.0")).doesNotThrowAnyException();
        // The snapshot predates V2, so the same build applies V2 again.
        assertThat(markerCount()).isEqualTo(MARKER_ROWS);
        assertThat(columns()).contains("note");
    }

    /**
     * One application start as far as the database goes: the production hook with a
     * real backup service over a fresh pool, closed again so the file is free to
     * move — Windows refuses to rename a file SQLite still holds.
     */
    private void start(String appVersion) {
        try (HikariDataSource dataSource = pool()) {
            DatabaseBackupService backupService = new DatabaseBackupService(new StorageProperties(dataDir.toString()),
                    new BackupProperties(3), appVersion, dataSource);
            Flyway flyway = Flyway.configure().dataSource(dataSource)
                    .locations("filesystem:" + migrationDir.toAbsolutePath()).baselineOnMigrate(true)
                    .baselineVersion("1").load();
            new DatabaseConfig(dataSource, new StartupTimingService()).preMigrationBackupStrategy(backupService)
                    .migrate(flyway);
        }
    }

    private HikariDataSource pool() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:" + dbFile + URL_PARAMETERS);
        dataSource.setMaximumPoolSize(2);
        return dataSource;
    }

    private void insertMarkers() throws SQLException {
        try (HikariDataSource dataSource = pool(); Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO marker (label) VALUES (?)")) {
                for (int i = 0; i < MARKER_ROWS; i++) {
                    insert.setString(1, "marker row %05d with enough text to fill pages".formatted(i));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                // Move the rows out of the WAL, so damaging the main file damages them.
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE);");
            }
        }
    }

    /**
     * Overwrites two pages in the middle of the file with noise. They hold the
     * marker rows, so {@code quick_check} finds broken b-tree pages there.
     */
    private void corruptMiddlePages() throws IOException {
        int pageSize = 4096;
        byte[] noise = new byte[pageSize * 2];
        new Random(11).nextBytes(noise);
        try (RandomAccessFile file = new RandomAccessFile(dbFile.toFile(), "rw")) {
            long pages = file.length() / pageSize;
            assertThat(pages).as("database pages to damage").isGreaterThan(8);
            file.seek((pages / 2) * pageSize);
            file.write(noise);
        }
    }

    private List<Path> backups() throws IOException {
        try (Stream<Path> files = Files.list(dbFile.getParent())) {
            return files.filter(path -> path.getFileName().toString().startsWith("mail.db.backup-pre-v")).toList();
        }
    }

    private int markerCount() throws SQLException {
        try (HikariDataSource dataSource = pool();
                Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM marker")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private List<String> columns() throws SQLException {
        try (HikariDataSource dataSource = pool();
                Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT name FROM pragma_table_info('marker')")) {
            List<String> names = new java.util.ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            return names;
        }
    }

    private void writeMigration(String name, String sql) throws IOException {
        Files.writeString(migrationDir.resolve(name), sql + System.lineSeparator(), StandardCharsets.UTF_8);
    }
}
