package org.voxrox.mailbackend.core.config;

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
     * Pool-starvation safety: tasks running on this executor MUST NOT submit
     * further {@code @Async("mailSyncExecutor")} work — that pattern deadlocks the
     * pool (holder of the per-account IMAP lock waits for a permit, while other
     * sync tasks holding the remaining permits wait for that lock). Downstream side
     * effects of a sync cycle (event listeners, maintenance) must run on
     * {@link #mailEventExecutor}; user-initiated actions must run on
     * {@link #userMailExecutor}.
     * <p>
     * If the project later migrates to a DB with multiple writers (Postgres etc.),
     * the limit can be raised much higher with no code changes.
     */
    @Bean(name = "mailSyncExecutor")
    public AsyncTaskExecutor mailSyncExecutor(MailClientProperties props) {
        int limit = props.sync().maxConcurrentAccounts();
        log.info("{} Mail sync executor: virtual threads, concurrency limit={}", LogCategory.BOOT, limit);

        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("mail-sync-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(limit);
        executor.setTaskTerminationTimeout(10_000);
        executor.setCancelRemainingTasksOnClose(true);
        return executor;
    }

    /**
     * Executor for user-initiated mail operations: SMTP send / draft save / draft
     * send, IMAP move and flag updates. Separated from {@link #mailSyncExecutor} so
     * that background sync congestion (or a stuck IMAP fetch) cannot block a user
     * clicking Send or marking a message read.
     * <p>
     * Concurrency limit of 8 is well above realistic single-user simultaneous
     * actions (typically 1–2 in flight) and small enough that SQLite WAL contention
     * stays bounded. Tasks here may still acquire the same per-account IMAP lock
     * used by sync, so a few permits ensure progress even if one operation stalls
     * on the network.
     */
    @Bean(name = "userMailExecutor")
    public AsyncTaskExecutor userMailExecutor() {
        int limit = 8;
        log.info("{} User mail executor: virtual threads, concurrency limit={}", LogCategory.BOOT, limit);

        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("user-mail-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(limit);
        executor.setTaskTerminationTimeout(10_000);
        executor.setCancelRemainingTasksOnClose(true);
        return executor;
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

        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("mail-event-");
        executor.setVirtualThreads(true);
        executor.setTaskTerminationTimeout(10_000);
        executor.setCancelRemainingTasksOnClose(true);
        return executor;
    }
}
