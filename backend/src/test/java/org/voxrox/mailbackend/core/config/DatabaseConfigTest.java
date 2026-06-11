package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.core.init.StartupTimingService;

class DatabaseConfigTest {

    @Test
    @DisplayName("verifySqlitePragmas passes when quick_check returns ok")
    void verifySqlitePragmasPassesWhenQuickCheckIsOk() throws Exception {
        DatabaseConfig config = new DatabaseConfig(dataSourceWithResults("wal", "1", "1", "5000", "-20000", "ok"),
                new StartupTimingService());

        assertThatCode(config::verifySqlitePragmas).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("verifySqlitePragmas fail-fasts with recovery guidance when quick_check fails")
    void verifySqlitePragmasFailsWhenQuickCheckReportsCorruption() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                dataSourceWithResults("wal", "1", "1", "5000", "-20000", "*** in database main ***"),
                new StartupTimingService());

        assertThatThrownBy(config::verifySqlitePragmas).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Application failed to start after update").hasMessageContaining("backup-pre-v")
                .hasMessageContaining("OPERATIONS.md");
    }

    private DataSource dataSourceWithResults(String journalMode, String synchronous, String foreignKeys,
            String busyTimeout, String cacheSize, String quickCheck) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        ResultSet journalModeResult = resultSet(journalMode);
        ResultSet synchronousResult = resultSet(synchronous);
        ResultSet foreignKeysResult = resultSet(foreignKeys);
        ResultSet busyTimeoutResult = resultSet(busyTimeout);
        ResultSet cacheSizeResult = resultSet(cacheSize);
        ResultSet quickCheckResult = resultSet(quickCheck);

        when(statement.executeQuery("PRAGMA journal_mode;")).thenReturn(journalModeResult);
        when(statement.executeQuery("PRAGMA synchronous;")).thenReturn(synchronousResult);
        when(statement.executeQuery("PRAGMA foreign_keys;")).thenReturn(foreignKeysResult);
        when(statement.executeQuery("PRAGMA busy_timeout;")).thenReturn(busyTimeoutResult);
        when(statement.executeQuery("PRAGMA cache_size;")).thenReturn(cacheSizeResult);
        when(statement.executeQuery("PRAGMA quick_check;")).thenReturn(quickCheckResult);

        return dataSource;
    }

    private ResultSet resultSet(String value) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn(value);
        return resultSet;
    }
}
