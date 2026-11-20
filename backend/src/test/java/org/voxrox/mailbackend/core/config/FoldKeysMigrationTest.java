package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code V2__Fold_keys_for_names} on a database that already holds data, which
 * is the case that matters: every installation reaches V2 from a V1 database
 * full of contacts, while the slices and the fresh installs only ever see it
 * run over empty tables. Plain Flyway over the shipped location, the way
 * {@link FlywayBaselineChecksumTest} runs it, so the migration also has to be
 * found the way the application finds it.
 */
class FoldKeysMigrationTest {

    private static final String AT = "'2026-11-20 10:00:00'";

    @Test
    @DisplayName("V2 fills in the fold keys of the rows a V1 database already holds")
    void foldsTheRowsAlreadyThere(@TempDir Path tempDir) throws SQLException {
        String url = "jdbc:sqlite:" + tempDir.resolve("v1.db");
        flyway(url).target("1").load().migrate();

        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO contacts (id, name, surname, created_at, updated_at) VALUES "
                    + "(1, 'Řehoř', 'Čermák', " + AT + ", " + AT + "), (2, NULL, '  ', " + AT + ", " + AT + ")");
            statement
                    .execute("INSERT INTO contact_labels (id, name, name_key, created_at) VALUES (1, 'Škola', 'škola', "
                            + AT + ")");
            statement.execute("INSERT INTO accounts (id, email, imap_host, imap_port, smtp_host, smtp_port) "
                    + "VALUES (1, 'me@example.com', 'imap.example.com', 993, 'smtp.example.com', 465)");
            statement.execute("INSERT INTO correspondent (id, account_id, email, display_name, last_seen_at) VALUES "
                    + "(1, 1, 'jan@example.com', 'Jan Šťastný', " + AT + "), (2, 1, 'bare@example.com', NULL, " + AT
                    + ")");
        }

        // Up to V2 alone: V3 strips the names, and this test is about what V2 wrote.
        MigrateResult result = flyway(url).target("2").load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(1);

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(rows(connection, "SELECT name_fold, surname_fold FROM contacts ORDER BY id"))
                    .containsExactly(List.of("rehor", "cermak"), nulls(2));
            assertThat(rows(connection, "SELECT name_fold FROM contact_labels")).containsExactly(List.of("skola"));
            assertThat(rows(connection, "SELECT display_name_fold FROM correspondent ORDER BY id"))
                    .containsExactly(List.of("jan stastny"), nulls(1));
            // Dropped: it could not serve the NULLS LAST order it was there for.
            assertThat(rows(connection,
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'ix_contacts_surname_name'"))
                    .isEmpty();
        }
    }

    private static FluentConfiguration flyway(String url) {
        return Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration");
    }

    private static List<String> nulls(int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(null);
        }
        return out;
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
