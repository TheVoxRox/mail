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
 * {@code V2__Fold_keys_and_strip_text} on a database that already holds data,
 * which is the case that matters: every installation reaches V2 from a V1
 * database full of contacts and accounts, while the slices and the fresh
 * installs only ever see it run over empty tables. Plain Flyway over the
 * shipped location, the way {@link FlywayBaselineChecksumTest} runs it, so the
 * migration also has to be found the way the application finds it. Each test
 * migrates up to V2 alone, so a later migration cannot change what it measures.
 */
class FoldKeysAndStripTextMigrationTest {

    private static final String AT = "'2026-11-20 10:00:00'";

    @Test
    @DisplayName("V2 fills in the fold keys of the rows a V1 database already holds")
    void foldsTheRowsAlreadyThere(@TempDir Path tempDir) throws SQLException {
        String url = v1Database(tempDir);

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

        migrateToV2(url);

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

    @Test
    @DisplayName("V2 strips stored contact names and notes, nulls the blank ones and folds what is left")
    void stripsTheContactsAlreadyThere(@TempDir Path tempDir) throws SQLException {
        String url = v1Database(tempDir);

        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO contacts (id, name, surname, note, created_at, updated_at) VALUES "
                    // Whitespace around every field, an em space and a tab among it.
                    + "(1, '  Jan ', ' Novak', ' VIP\t', " + AT + ", " + AT + "), "
                    // A name of spaces alone, and a note of spaces alone.
                    + "(2, '   ', 'Dvorak', '  ', " + AT + ", " + AT + "), "
                    // Already clean.
                    + "(3, 'Eva', 'Mala', NULL, " + AT + ", " + AT + ")");
        }

        migrateToV2(url);

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(
                    rows(connection, "SELECT name, surname, note, name_fold, surname_fold FROM contacts ORDER BY id"))
                    .containsExactly(List.of("Jan", "Novak", "VIP", "jan", "novak"),
                            Arrays.asList(null, "Dvorak", null, null, "dvorak"),
                            Arrays.asList("Eva", "Mala", null, "eva", "mala"));
        }
    }

    @Test
    @DisplayName("V2 strips account names, display names, signatures and usernames, and leaves the address alone")
    void stripsTheAccountsAlreadyThere(@TempDir Path tempDir) throws SQLException {
        String url = v1Database(tempDir);

        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO accounts (id, account_name, email, display_name, signature, imap_host, "
                    + "imap_port, smtp_host, smtp_port) VALUES "
                    // Whitespace around every text field, a blank display name on the second.
                    + "(1, ' Work ', 'jan@example.com', ' Jan Novak\t', '\nJan\n', 'imap.example.com', 993, "
                    + "'smtp.example.com', 465), "
                    + "(2, 'Home', 'eva@example.com', '   ', '  ', 'imap.example.com', 993, 'smtp.example.com', 465), "
                    // Already clean: nothing to write.
                    + "(3, 'Other', 'petr@example.com', 'Petr', NULL, 'imap.example.com', 993, "
                    + "'smtp.example.com', 465)");
            statement.execute("INSERT INTO account_credentials (account_id, auth_type, username, password) VALUES "
                    // An em space before the third.
                    + "(1, 'PASSWORD', 'jan@example.com ', 'sealed'), (2, 'PASSWORD', 'eva', 'sealed'), "
                    + "(3, 'PASSWORD', ' petr', 'sealed')");
        }

        migrateToV2(url);

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(
                    rows(connection, "SELECT account_name, email, display_name, signature FROM accounts ORDER BY id"))
                    .containsExactly(List.of("Work", "jan@example.com", "Jan Novak", "Jan"),
                            Arrays.asList("Home", "eva@example.com", null, null),
                            Arrays.asList("Other", "petr@example.com", "Petr", null));
            assertThat(rows(connection, "SELECT username FROM account_credentials ORDER BY account_id"))
                    .containsExactly(List.of("jan@example.com"), List.of("eva"), List.of("petr"));
        }
    }

    private static String v1Database(Path tempDir) {
        String url = "jdbc:sqlite:" + tempDir.resolve("v1.db");
        flyway(url).target("1").load().migrate();
        return url;
    }

    private static void migrateToV2(String url) {
        MigrateResult result = flyway(url).target("2").load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(1);
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
