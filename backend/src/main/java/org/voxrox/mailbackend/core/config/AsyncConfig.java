package org.voxrox.mailbackend.core.config;

import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.voxrox.mailbackend.util.LogCategory;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Executor for background mail sync tasks (scheduled cycles + cascading folder
     * backfill). Virtual threads with bounded concurrency.
     * <p>
     * The concurrency limit is driven by
     * {@code mail.client.sync.max-concurrent-accounts}. SQLite in WAL mode
     * serializes writes (1 writer + N readers); with {@code busy_timeout=5000} (see
     * {@code spring.datasource.url}) contention degrades gracefully — writers queue
     * up instead of failing immediately with SQLITE_BUSY. The default of 4 matches
     * the HikariCP pool size and gives comfortable overlap between IMAP network
     * waits and short DB bursts.
     * <p>
     * The limit is enforced by {@link GatedTaskExecutor}, NOT by
     * {@code SimpleAsyncTaskExecutor.setConcurrencyLimit} — the built-in throttle
     * blocks the <em>submitter</em> when the limit is reached. Submitters here are
     * user-facing: {@code GET /emails} dispatches a folder cycle and the
     * {@code @Scheduled} tick dispatches account passes; blocking either while four
     * sync cycles run means a hung HTTP response (or a stalled scheduler thread,
     * which also delays the SSE heartbeat). With the gate, submission always
     * returns immediately and the spawned virtual thread parks until a permit frees
     * — a parked virtual thread holds no locks and costs almost nothing.
     * <p>
     * Pool-starvation safety: tasks running on this executor MUST NOT submit
     * further {@code @Async("mailSyncExecutor")} work and then wait for its result
     * — the child task would park for a permit the parent holds. Fire-and-forget
     * submission is safe, but the convention stands: downstream side effects of a
     * sync cycle (event listeners, maintenance) must run on
     * {@link #mailEventExecutor}; user-initiated actions must run on
     * {@link #userMailExecutor}.
     * <p>
     * If the project later migrates to a DB with multiple writers (Postgres etc.),
     * the limit can be raised much higher with no code changes.
     */
    @Bean(name = "mailSyncExecutor")
    public AsyncTaskExecutor mailSyncExecutor(MailClientProperties props) {
        int limit = props.sync().maxConcurrentAccounts();
        log.info("{} Mail sync executor: virtual threads, concurrency limit={} (non-blocking submit)", LogCategory.BOOT,
                limit);
        return new GatedTaskExecutor(newVirtualThreadExecutor("mail-sync-"), limit);
    }

    /**
     * Executor for user-initiated mail operations: SMTP send / draft save / draft
     * send, IMAP move and flag updates. Separated from {@link #mailSyncExecutor} so
     * that background sync congestion (or a stuck IMAP fetch) cannot block a user
     * clicking Send or marking a message read.
     * <p>
     * Concurrency limit of 8 is well above realistic single-user simultaneous
     * actions and small enough that SQLite WAL contention stays bounded. Enforced
     * by {@link GatedTaskExecutor} so a bulk operation (the client fires one
     * request per selected message — 50 deletes at once) never blocks HTTP threads
     * at dispatch: every request returns as soon as its local write commits, while
     * the server-side IMAP actions drain through the gate in the background.
     */
    @Bean(name = "userMailExecutor")
    public AsyncTaskExecutor userMailExecutor() {
        int limit = 8;
        log.info("{} User mail executor: virtual threads, concurrency limit={} (non-blocking submit)", LogCategory.BOOT,
                limit);
        return new GatedTaskExecutor(newVirtualThreadExecutor("user-mail-"), limit);
    }

    /**
     * Executor for downstream side effects of a sync cycle: SSE broadcast, mailbox
     * window-limit maintenance. Decoupled from {@link #mailSyncExecutor} because a
     * sync task publishes events / triggers maintenance from inside its own async
     * run; submitting back to the throttled sync executor while holding sync
     * resources causes pool-starvation deadlock.
     * <p>
     * No concurrency limit — handlers are short and IO-bound (SSE writes, single DB
     * delete). Virtual threads stay cheap even at hundreds in flight.
     */
    @Bean(name = "mailEventExecutor")
    public AsyncTaskExecutor mailEventExecutor() {
        log.info("{} Mail event executor: virtual threads, unbounded", LogCategory.BOOT);
        return newVirtualThreadExecutor("mail-event-");
    }

    private static SimpleAsyncTaskExecutor newVirtualThreadExecutor(String threadNamePrefix) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(threadNamePrefix);
        executor.setVirtualThreads(true);
        executor.setTaskTerminationTimeout(10_000);
        executor.setCancelRemainingTasksOnClose(true);
        return executor;
    }

    /**
     * Bounds task <em>execution</em> concurrency without ever blocking the
     * <em>submitter</em>: every submitted task gets its own virtual thread
     * immediately and acquires a semaphore permit as its first action. Contrast
     * with {@code SimpleAsyncTaskExecutor.setConcurrencyLimit}, whose
     * {@code ConcurrencyThrottleSupport} parks the submitting thread inside
     * {@code execute()} — for this app that submitter is an HTTP request thread or
     * the shared scheduler thread.
     * <p>
     * The permit is acquired before the task body runs, so a parked task holds no
     * application locks (folder locks, IMAP connection locks are all taken inside
     * task bodies). Shutdown: {@code close()} delegates to the underlying
     * {@link SimpleAsyncTaskExecutor}, whose cancel-remaining interrupt unparks
     * waiting acquires — an interrupted task exits before running its body.
     * <p>
     * The semaphore is fair so cycles start roughly in submission order and a
     * steady stream of new submissions cannot starve an old one.
     */
    static final class GatedTaskExecutor implements AsyncTaskExecutor, AutoCloseable {

        private final SimpleAsyncTaskExecutor delegate;
        private final Semaphore permits;

        GatedTaskExecutor(SimpleAsyncTaskExecutor delegate, int concurrencyLimit) {
            this.delegate = delegate;
            this.permits = new Semaphore(concurrencyLimit, true);
        }

        @Override
        public void execute(Runnable task) {
            delegate.execute(() -> {
                try {
                    permits.acquire();
                } catch (InterruptedException e) {
                    // Shutdown interrupt while parked for a permit — exit without
                    // running the task, matching cancelRemainingTasksOnClose.
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    task.run();
                } finally {
                    permits.release();
                }
            });
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
