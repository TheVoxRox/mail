package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code V3__Strip_contact_names} over contacts a V2 build stored, the way the
 * application stored them before {@code StoredText}: with the whitespace typed
 * around them, and their folded keys already computed. Plain Flyway over the
 * shipped location, as in {@link FoldKeysMigrationTest}.
 */
class StripContactNamesMigrationTest {

    private static final String AT = "'2026-11-20 10:00:00'";

    @Test
    @DisplayName("V3 strips stored names and notes, nulls the blank ones and keeps the clean rows as they are")
    void stripsTheRowsAlreadyThere(@TempDir Path tempDir) throws SQLException {
        String url = "jdbc:sqlite:" + tempDir.resolve("v2.db");
        flyway(url).target("2").load().migrate();

        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO contacts (id, name, surname, note, name_fold, surname_fold, created_at, "
                    + "updated_at) VALUES "
                    // Spaces around every field, an em space and a tab among them.
                    + "(1, '  Jan ', ' Novak', ' VIP\t', 'jan', 'novak', " + AT + ", " + AT + "), "
                    // A name of spaces alone, and a note of spaces alone.
                    + "(2, '   ', 'Dvorak', '  ', NULL, 'dvorak', " + AT + ", " + AT + "), "
                    // Already clean: nothing to write.
                    + "(3, 'Eva', 'Mala', NULL, 'eva', 'mala', " + AT + ", " + AT + ")");
        }

        MigrateResult result = flyway(url).load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(1);

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(
                    rows(connection, "SELECT name, surname, note, name_fold, surname_fold FROM contacts ORDER BY id"))
                    .containsExactly(List.of("Jan", "Novak", "VIP", "jan", "novak"),
                            Arrays.asList(null, "Dvorak", null, null, "dvorak"),
                            Arrays.asList("Eva", "Mala", null, "eva", "mala"));
        }
    }

    private static FluentConfiguration flyway(String url) {
        return Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration");
    }

    private static List<List<String>> rows(Connection connection, String sql) throws SQLException {
        List<List<String>> out = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    row.add(rs.getString(i));
                }
                out.add(row);
            }
        }
        return out;
    }
}
