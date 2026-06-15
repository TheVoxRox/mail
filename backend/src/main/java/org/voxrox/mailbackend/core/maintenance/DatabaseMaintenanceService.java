package org.voxrox.mailbackend.core.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Keeps SQLite's query-planner statistics fresh so the planner picks
 * index-based plans. Without statistics the planner falls back to heuristics;
 * the worst case is {@code MessageRepository.findMergeableOrphanThreadIds} — an
 * OR over two indexed columns run once per message during a bulk sync — which
 * degrades to a full per-account scan instead of the MULTI-INDEX OR plan, i.e.
 * O(n^2) over a large mailbox. A single {@code ANALYZE} pass restores the
 * optimal plan.
 *
 * <p>
 * Uses {@code PRAGMA optimize(0x10002)} rather than a bare {@code ANALYZE} or
 * {@code PRAGMA optimize}: the {@code 0x10000} bit makes it consider
 * <em>all</em> tables, not just those touched on the pooled connection the
 * scheduler happens to borrow, and {@code 0x02} runs ANALYZE only on tables
 * that would benefit. It is cheap and self-throttling — a no-op when nothing
 * changed enough since the last run.
 */
@Service
public class DatabaseMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMaintenanceService.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseMaintenanceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs once a configurable delay after startup (by which the first sync has
     * usually populated rows worth analysing) and then on a fixed delay. Fixed
     * delay, not cron, so a desktop machine asleep at a given wall-clock time still
     * gets the maintenance pass once it wakes.
     */
    @Scheduled(initialDelayString = "${mail.client.db.optimize-initial-delay:PT10M}", fixedDelayString = "${mail.client.db.optimize-interval:PT6H}")
    public void optimize() {
        try {
            jdbcTemplate.execute("PRAGMA optimize(0x10002)");
            log.debug("{} SQLite planner statistics refreshed (PRAGMA optimize).", LogCategory.DATABASE);
        } catch (RuntimeException e) {
            // Statistics are an optimisation, never correctness — a failed pass just
            // means the planner keeps the stats it already had.
            log.warn("{} PRAGMA optimize failed: {}", LogCategory.DATABASE, e.getMessage());
        }
    }
}
