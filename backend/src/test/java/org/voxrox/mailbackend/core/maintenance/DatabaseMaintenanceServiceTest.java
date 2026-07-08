package org.voxrox.mailbackend.core.maintenance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class DatabaseMaintenanceServiceTest {

    private static final String OPTIMIZE = "PRAGMA optimize(0x10002)";
    private static final String CHECKPOINT = "PRAGMA wal_checkpoint(TRUNCATE)";
    private static final String VACUUM = "VACUUM";
    private static final long MIN_FREE_PAGES = 2000;
    private static final double FREE_RATIO = 0.25;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private DatabaseMaintenanceService service() {
        return new DatabaseMaintenanceService(jdbcTemplate, MIN_FREE_PAGES, FREE_RATIO);
    }

    @Test
    void optimizeRunsThePoolSafePragma() {
        service().optimize();
        verify(jdbcTemplate).execute(OPTIMIZE);
    }

    @Test
    void optimizeSwallowsFailuresSoTheScheduleKeepsRunning() {
        doThrow(new DataAccessResourceFailureException("db locked")).when(jdbcTemplate).execute(OPTIMIZE);

        assertThatCode(() -> service().optimize()).doesNotThrowAnyException();
    }

    @Test
    void reclaimSpaceTruncatesWalThenVacuumsWhenBloated() {
        stubFreePages(5000, 10_000); // 50% free, well over both thresholds

        service().reclaimSpace();

        verify(jdbcTemplate).execute(CHECKPOINT);
        verify(jdbcTemplate).execute(VACUUM);
    }

    @Test
    void reclaimSpaceSkipsVacuumWhenFreeRatioTooLow() {
        // 2500 free pages clears the absolute floor but is only ~2.5% of the file.
        stubFreePages(2500, 100_000);

        service().reclaimSpace();

        verify(jdbcTemplate).execute(CHECKPOINT);
        verify(jdbcTemplate, never()).execute(VACUUM);
    }

    @Test
    void reclaimSpaceSkipsVacuumWhenTooFewFreePagesEvenAtHighRatio() {
        // 40% free but only 400 pages — a tiny DB not worth rewriting.
        stubFreePages(400, 1000);

        service().reclaimSpace();

        verify(jdbcTemplate).execute(CHECKPOINT);
        verify(jdbcTemplate, never()).execute(VACUUM);
    }

    @Test
    void reclaimSpaceSwallowsCheckpointFailureAndStillEvaluatesVacuum() {
        // lenient: execute() is also invoked with VACUUM, which this stub does not
        // match.
        lenient().doThrow(new DataAccessResourceFailureException("db locked")).when(jdbcTemplate).execute(CHECKPOINT);
        stubFreePages(5000, 10_000);

        assertThatCode(() -> service().reclaimSpace()).doesNotThrowAnyException();
        verify(jdbcTemplate).execute(VACUUM);
    }

    @Test
    void reclaimSpaceSwallowsVacuumFailure() {
        stubFreePages(5000, 10_000);
        // lenient: execute() is also invoked with CHECKPOINT, which this stub does not
        // match.
        lenient().doThrow(new DataAccessResourceFailureException("db busy")).when(jdbcTemplate).execute(VACUUM);

        assertThatCode(() -> service().reclaimSpace()).doesNotThrowAnyException();
    }

    private void stubFreePages(long freePages, long totalPages) {
        when(jdbcTemplate.queryForObject("PRAGMA freelist_count", Long.class)).thenReturn(freePages);
        when(jdbcTemplate.queryForObject("PRAGMA page_count", Long.class)).thenReturn(totalPages);
    }
}
