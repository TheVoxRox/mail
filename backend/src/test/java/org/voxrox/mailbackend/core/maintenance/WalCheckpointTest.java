package org.voxrox.mailbackend.core.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Runs {@link DatabaseMaintenanceService#checkpoint()} against a real SQLite
 * file in WAL mode, then copies the main file alone, as a user exporting their
 * data does. {@link DatabaseMaintenanceServiceTest} can only show that the
 * PRAGMA is sent; this shows what the copy then holds.
 */
class WalCheckpointTest {

    @Test
    @DisplayName("after a checkpoint, a copy of mail.db alone holds what the running app committed")
    void checkpointMovesCommittedRowsIntoTheMainFile(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("mail.db");
        String url = "jdbc:sqlite:" + db + "?journal_mode=WAL&synchronous=NORMAL";

        // Held open throughout, as the running app holds its pool: SQLite also
        // checkpoints when the last connection closes, and the app never gets that
        // one, since the desktop client ends the sidecar with a kill.
        try (Connection app = DriverManager.getConnection(url); Statement statement = app.createStatement()) {
            statement.execute("CREATE TABLE marker (id INTEGER PRIMARY KEY, label TEXT NOT NULL)");
            statement.execute("INSERT INTO marker (label) VALUES ('a'), ('b'), ('c')");

            assertThat(markerRowsInCopy(db, dir.resolve("before.db"))).as("rows a copy of mail.db holds before")
                    .isZero();

            new DatabaseMaintenanceService(new JdbcTemplate(new DriverManagerDataSource(url)), 2000, 0.25).checkpoint();

            assertThat(markerRowsInCopy(db, dir.resolve("after.db"))).as("rows a copy of mail.db holds after")
                    .isEqualTo(3);
        }
    }

    /**
     * Copies the main database file without its {@code -wal} and counts the marker
     * rows the copy holds; a copy without the table holds none.
     */
    private static int markerRowsInCopy(Path db, Path copy) throws Exception {
        Files.copy(db, copy);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + copy);
                Statement statement = connection.createStatement()) {
            try (ResultSet table = statement
                    .executeQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'marker'")) {
                if (!table.next() || table.getInt(1) == 0) {
                    return 0;
                }
            }
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM marker")) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the copy " + copy, e);
        }
    }
}
