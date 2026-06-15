package org.voxrox.mailbackend.core.maintenance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class DatabaseMaintenanceServiceTest {

    private static final String PRAGMA = "PRAGMA optimize(0x10002)";

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void optimizeRunsThePoolSafePragma() {
        new DatabaseMaintenanceService(jdbcTemplate).optimize();
        verify(jdbcTemplate).execute(PRAGMA);
    }

    @Test
    void optimizeSwallowsFailuresSoTheScheduleKeepsRunning() {
        doThrow(new DataAccessResourceFailureException("db locked")).when(jdbcTemplate).execute(PRAGMA);

        assertThatCode(() -> new DatabaseMaintenanceService(jdbcTemplate).optimize()).doesNotThrowAnyException();
    }
}
