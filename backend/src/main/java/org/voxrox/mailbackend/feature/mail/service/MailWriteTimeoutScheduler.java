package org.voxrox.mailbackend.feature.mail.service;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The one scheduler behind every mail socket's write timeout.
 * <p>
 * Angus implements {@code .writetimeout} with a task that closes the socket
 * when a single {@code write} has not returned in time, and it builds a
 * {@link ScheduledThreadPoolExecutor} of its own for every connection unless
 * the session names one under {@code .executor.writetimeout}. Per connection is
 * what this avoids: an account holds two IMAP connections at once and each open
 * folder another, and every send opens a transport of its own, so the default
 * would cost a platform thread per connection, and one more per message sent
 * (IMAP/SMTP audit B1-6).
 * <p>
 * One thread carries all of them because in the normal case none of the work
 * runs: a write schedules a task and cancels it on return, and
 * {@code setRemoveOnCancelPolicy} takes the cancelled task out of the queue
 * rather than leaving it there until its delay elapses — without that the queue
 * would hold every write of the last minute. The thread is a daemon and times
 * out when idle, so a sidecar that is not writing carries no thread for this at
 * all.
 * <p>
 * It is never shut down, deliberately. A socket outlives the bean that opened
 * it during shutdown, and {@code schedule} on a stopped executor throws — which
 * would fail the very write the timeout exists to protect. A daemon thread that
 * has already timed out costs nothing to leave behind.
 */
final class MailWriteTimeoutScheduler {

    private static final ScheduledThreadPoolExecutor SCHEDULER = create();

    static ScheduledExecutorService shared() {
        return SCHEDULER;
    }

    private static ScheduledThreadPoolExecutor create() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
                MailWriteTimeoutScheduler::newThread);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setKeepAliveTime(60, TimeUnit.SECONDS);
        scheduler.allowCoreThreadTimeOut(true);
        return scheduler;
    }

    private static Thread newThread(Runnable task) {
        Thread thread = new Thread(task, "mail-write-timeout");
        thread.setDaemon(true);
        return thread;
    }

    private MailWriteTimeoutScheduler() {
    }
}
