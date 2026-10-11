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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keeps the long text columns of {@code messages} at the end of the row, the
 * cached body last.
 *
 * <p>
 * SQLite reaches a column that sits after a long value by walking that value's
 * chain of overflow pages, so a column placed behind the body costs a read of
 * the whole body every time it is read. The conversation listing reads
 * {@code received_at}, {@code seen} and {@code thread_id} of every row in a
 * folder; while {@code content} stood in front of them, each page load read
 * every cached body of the folder (the note above the messages table in
 * {@code V1__init.sql} has the measurement).
 *
 * <p>
 * The order is easy to lose without anyone noticing: {@code ALTER TABLE ADD
 * COLUMN} always appends, which puts any column a later migration adds behind
 * the body. Nothing fails and nothing looks wrong; the listing just gets slower
 * with every message the user opens. Hence this test, which runs the real
 * migrations and reads the order the database ends up with.
 */
class MessagesColumnOrderTest {

    /**
     * The columns that end the row, in order. Everything a listing or a scan reads
     * comes before them.
     */
    private static final List<String> LONG_TEXT_TAIL = List.of("recipients_to", "recipients_cc", "recipients_bcc",
            "reply_references", "body_text", "content");

    @Test
    @DisplayName("messages ends with its long text columns, the cached body last")
    void longTextColumnsEndTheRow(@TempDir Path tempDir) throws SQLException {
        String url = "jdbc:sqlite:" + tempDir.resolve("column-order.db");
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").load().migrate();

        List<String> columns = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT name FROM pragma_table_info('messages') ORDER BY cid")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }

        assertThat(columns).as("the migrated messages table").hasSizeGreaterThan(LONG_TEXT_TAIL.size());
        assertThat(columns.subList(columns.size() - LONG_TEXT_TAIL.size(), columns.size())).as("""
                messages no longer ends with its long text columns (full column order: %s).

                A column after the cached body is read by walking the body's overflow pages, so \
                every listing that reads it reads the bodies of the whole folder. ALTER TABLE ADD \
                COLUMN appends, so a migration that adds a column puts it there.

                Put the new column in front of the long text with a table rebuild: create the new \
                table, copy the rows, drop the old one, rename, then recreate its indexes and the \
                message_search triggers. Only a long text column that no listing reads belongs in \
                the tail, and then in front of body_text, in LONG_TEXT_TAIL as well.\
                """, columns).containsExactlyElementsOf(LONG_TEXT_TAIL);
    }
}
