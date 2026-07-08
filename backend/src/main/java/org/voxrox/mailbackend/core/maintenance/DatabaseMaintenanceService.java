package org.voxrox.mailbackend.core.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Periodic SQLite housekeeping. Two independent passes on separate cadences:
 *
 * <ol>
 * <li><b>Planner statistics</b> ({@link #optimize()}, ~6h) — keeps the
 * query-planner's histograms fresh so it picks index-based plans. Without them
 * the worst case is {@code MessageRepository.findMergeableOrphanThreadIds} — an
 * OR over two indexed columns run once per message during a bulk sync — which
 * degrades to a full per-account scan, i.e. O(n^2) over a large mailbox. A
 * single {@code ANALYZE} pass restores the optimal plan.</li>
 * <li><b>Space reclamation</b> ({@link #reclaimSpace()}, ~daily) — truncates
 * the WAL sidecar and, when the free-page ratio warrants it, {@code VACUUM}s
 * the file. The local cache churns (prune of the recency window, folder
 * deletes, re-sync churn) so free pages accumulate; nothing shrinks the file
 * without this.</li>
 * </ol>
 *
 * <p>
 * {@link #optimize()} uses {@code PRAGMA optimize(0x10002)} rather than a bare
 * {@code ANALYZE} or {@code PRAGMA optimize}: the {@code 0x10000} bit makes it
 * consider <em>all</em> tables, not just those touched on the pooled connection
 * the scheduler happens to borrow, and {@code 0x02} runs ANALYZE only on tables
 * that would benefit. It is cheap and self-throttling — a no-op when nothing
 * changed enough since the last run.
 */
@Service
public class DatabaseMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMaintenanceService.class);

    private final JdbcTemplate jdbcTemplate;
    private final long vacuumMinFreePages;
    private final double vacuumFreeRatio;

    public DatabaseMaintenanceService(JdbcTemplate jdbcTemplate,
            @Value("${mail.client.db.vacuum-min-free-pages:2000}") long vacuumMinFreePages,
            @Value("${mail.client.db.vacuum-free-ratio:0.25}") double vacuumFreeRatio) {
        this.jdbcTemplate = jdbcTemplate;
        this.vacuumMinFreePages = vacuumMinFreePages;
        this.vacuumFreeRatio = vacuumFreeRatio;
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

    /**
     * Reclaims on-disk space on a slow cadence (daily by default): first a TRUNCATE
     * checkpoint to reset the WAL sidecar, then a {@code VACUUM} gated on a
     * free-page threshold. Deliberately separate from {@link #optimize()} — VACUUM
     * is far more expensive (rewrites the whole file under an exclusive lock) and
     * must run rarely, whereas the ANALYZE pass is cheap and wants to run often.
     */
    @Scheduled(initialDelayString = "${mail.client.db.reclaim-initial-delay:PT30M}", fixedDelayString = "${mail.client.db.reclaim-interval:PT24H}")
    public void reclaimSpace() {
        truncateWal();
        vacuumIfBloated();
    }

    /**
     * Flushes the WAL back into the main DB and resets the {@code -wal} sidecar to
     * zero length. The default PASSIVE autocheckpoints reuse the WAL in place, so
     * without this the {@code -wal} file only ever grows to its high-water mark and
     * stays there. When a writer holds the lock the checkpoint returns busy and
     * no-ops (no exception) — the next cycle retries.
     */
    private void truncateWal() {
        try {
            jdbcTemplate.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            log.debug("{} WAL checkpoint (TRUNCATE) done.", LogCategory.DATABASE);
        } catch (RuntimeException e) {
            log.warn("{} WAL checkpoint failed: {}", LogCategory.DATABASE, e.getMessage());
        }
    }

    /**
     * {@code VACUUM}s only when the file carries enough free pages to be worth it —
     * both an absolute floor ({@code vacuum-min-free-pages}, so tiny DBs are left
     * alone) and a fraction of the file ({@code vacuum-free-ratio}, so a large but
     * dense mailbox is not rewritten for a handful of free pages).
     *
     * <p>
     * VACUUM takes SQLite's exclusive write lock and rewrites the entire file.
     * {@code busy_timeout} (see {@code spring.datasource.url}) makes it wait for an
     * in-flight sync write rather than failing instantly; if it still cannot
     * acquire the lock it throws {@code SQLITE_BUSY}, which is swallowed and
     * retried on the next cycle. The daily cadence plus the threshold keep
     * collisions with the 5-minute sync rare. See
     * {@code backend/docs/CONCURRENCY.md}, rule 3.
     */
    private void vacuumIfBloated() {
        try {
            long freePages = queryLong("PRAGMA freelist_count");
            long totalPages = queryLong("PRAGMA page_count");
            if (totalPages <= 0) {
                return;
            }

            double freeRatio = (double) freePages / totalPages;
            if (freePages < vacuumMinFreePages || freeRatio < vacuumFreeRatio) {
                log.debug("{} VACUUM skipped: {} free of {} pages ({}%); threshold is {} pages and {}%.",
                        LogCategory.DATABASE, freePages, totalPages, Math.round(freeRatio * 100), vacuumMinFreePages,
                        Math.round(vacuumFreeRatio * 100));
                return;
            }

            long started = System.currentTimeMillis();
            jdbcTemplate.execute("VACUUM");
            log.info("{} VACUUM reclaimed ~{} free pages in {} ms.", LogCategory.DATABASE, freePages,
                    System.currentTimeMillis() - started);
        } catch (RuntimeException e) {
            // Reclamation is best-effort — a busy DB or transient failure just defers
            // the shrink to the next cycle; the data is untouched either way.
            log.warn("{} VACUUM failed (will retry next cycle): {}", LogCategory.DATABASE, e.getMessage());
        }
    }

    /**
     * Reads a single-integer PRAGMA. Requests {@link Long} explicitly so the value
     * survives the sqlite-jdbc driver returning either {@code Integer} or
     * {@code Long} depending on magnitude; Spring's single-column mapper coerces to
     * the requested type.
     */
    private long queryLong(String pragma) {
        Long value = jdbcTemplate.queryForObject(pragma, Long.class);
        return value != null ? value : 0L;
    }
}
