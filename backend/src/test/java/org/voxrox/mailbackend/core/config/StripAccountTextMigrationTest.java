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
 * {@code V4__Strip_account_text} over accounts a V3 build stored, with the
 * whitespace the form sent along. Plain Flyway over the shipped location, as in
 * {@link FoldKeysMigrationTest}.
 */
class StripAccountTextMigrationTest {

    @Test
    @DisplayName("V4 strips account names, display names, signatures and usernames, and leaves the address alone")
    void stripsTheAccountsAlreadyThere(@TempDir Path tempDir) throws SQLException {
        String url = "jdbc:sqlite:" + tempDir.resolve("v3.db");
        flyway(url).target("3").load().migrate();

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
                    + "(1, 'PASSWORD', 'jan@example.com ', 'sealed'), (2, 'PASSWORD', 'eva', 'sealed'), "
                    + "(3, 'PASSWORD', ' petr', 'sealed')");
        }

        MigrateResult result = flyway(url).load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(1);

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
