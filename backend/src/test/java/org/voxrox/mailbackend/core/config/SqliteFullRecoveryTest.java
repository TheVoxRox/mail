package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.SQLExceptionOverride;

/**
 * What a full disk leaves in the connection pool. SQLite answers
 * {@code SQLITE_FULL} by rolling back the whole transaction on its own; the
 * JDBC driver does not notice, so the connection it hands back still believes a
 * transaction is open, and every later transaction on it fails with "cannot
 * commit - no transaction is active" — after the disk has room again, until the
 * process restarts. The accelerated soak found this.
 * <p>
 * {@code PRAGMA max_page_count} makes the same failure without filling a disk:
 * the database may not grow past its current size on that connection.
 */
class SqliteFullRecoveryTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a connection that hit a full database is not handed out again")
    void fullDatabaseDoesNotPoisonThePool() throws Exception {
        try (HikariDataSource pool = pool()) {
            try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE filler (payload BLOB NOT NULL)");
            }

            fillUntilFull(pool);

            // The next transaction, the way Spring runs one, on what the pool hands out.
            assertThatCode(() -> {
                try (Connection connection = pool.getConnection()) {
                    connection.setAutoCommit(false);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("INSERT INTO filler (payload) VALUES (x'00')");
                    }
                    connection.commit();
                    connection.setAutoCommit(true);
                }
            }).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("only errors after which SQLite may have dropped the transaction evict")
    void adjudication() {
        SqliteConnectionEviction eviction = new SqliteConnectionEviction();

        assertThat(eviction.adjudicate(new SQLException("database or disk is full", null, 13)))
                .isEqualTo(SQLExceptionOverride.Override.MUST_EVICT);
        assertThat(eviction.adjudicate(new SQLException("disk I/O error", null, 10 | (4 << 8))))
                .as("an extended I/O error code").isEqualTo(SQLExceptionOverride.Override.MUST_EVICT);
        assertThat(eviction.adjudicate(new SQLException("cannot commit - no transaction is active", null, 1)))
                .isEqualTo(SQLExceptionOverride.Override.MUST_EVICT);
        assertThat(eviction.adjudicate(new SQLException("database is locked", null, 5))).as("busy is routine")
                .isEqualTo(SQLExceptionOverride.Override.CONTINUE_EVICT);
        assertThat(eviction.adjudicate(new SQLException("UNIQUE constraint failed", null, 19)))
                .isEqualTo(SQLExceptionOverride.Override.CONTINUE_EVICT);
    }

    private HikariDataSource pool() {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:sqlite:" + dir.resolve("full.db")
                + "?journal_mode=WAL&synchronous=NORMAL&foreign_keys=ON&busy_timeout=5000");
        // One connection, so the next borrower gets the one that failed unless the
        // pool threw it away.
        pool.setMaximumPoolSize(1);
        pool.setExceptionOverrideClassName(SqliteConnectionEviction.class.getName());
        return pool;
    }

    /**
     * One transaction that writes until SQLite reports the database full, then ends
     * the way Spring ends it: a rollback and the connection handed back, with both
     * failures logged and swallowed. Handing it back fails too — the pool rolls
     * back once more, SQLite has nothing to roll back, and the pool skips restoring
     * auto-commit — which is the "Unable to release JDBC Connection" the soak's log
     * shows.
     */
    private static void fillUntilFull(HikariDataSource pool) throws SQLException {
        Connection connection = pool.getConnection();
        try {
            long pages;
            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("PRAGMA page_count")) {
                rs.next();
                pages = rs.getLong(1);
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA max_page_count = " + (pages + 2));
            }
            connection.setAutoCommit(false);
            SQLException full = null;
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO filler (payload) VALUES (?)")) {
                for (int i = 0; i < 1_000 && full == null; i++) {
                    insert.setBytes(1, new byte[16 * 1024]);
                    try {
                        insert.executeUpdate();
                    } catch (SQLException e) {
                        full = e;
                    }
                }
            }
            assertThat((Throwable) full).as("the insert that found the database full").isNotNull();
            assertThat(full.getErrorCode() & 0xFF).as("SQLite result code").isEqualTo(13);
            // What Spring does next: roll back; the pool restores auto-commit on return.
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // "cannot rollback - no transaction is active": SQLite already did.
            }
        } finally {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Spring logs this one and carries on.
            }
        }
    }
}
