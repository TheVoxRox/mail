package org.voxrox.mailbackend.core.metrics;

import java.util.function.ToDoubleFunction;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Central facade for Micrometer instrumentation of key operations.
 * <p>
 * It exposes the areas an operator should watch:
 * <ul>
 * <li><b>Sync</b> — IMAP synchronization of a single folder (success/failure +
 * duration + number of downloaded messages)</li>
 * <li><b>SMTP send</b> — sending a single message (success/failure +
 * duration)</li>
 * <li><b>IMAP connect</b> — establishing a new connection (success/failure +
 * duration), retry auth refresh, connection pool size</li>
 * <li><b>OAuth refresh</b> — refreshing the provider access token (Google,
 * Microsoft, ...; success / failure, distinguishing permanent revoke from a
 * transient error)</li>
 * </ul>
 * The goal is to keep instrumentation code out of business classes and avoid
 * dragging {@code MeterRegistry} across the whole application. No metric uses
 * {@code accountId} as a tag — that would grow unbounded and break the time-
 * series storage.
 */
@Component
public class MailMetrics {

    // Tag values are constants to keep dashboard queries grep-friendly.
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";
    public static final String OUTCOME_AUTH_REVOKED = "auth_revoked";

    // Metric names — kebab-case, dot-separated domains (Micrometer convention).
    private static final String METRIC_SYNC = "mail.sync.folder";
    private static final String METRIC_SYNC_MESSAGES = "mail.sync.messages.downloaded";
    private static final String METRIC_SMTP_SEND = "mail.smtp.send";
    private static final String METRIC_IMAP_CONNECT = "mail.imap.connect";
    private static final String METRIC_IMAP_AUTH_REFRESH = "mail.imap.auth.refresh";
    private static final String METRIC_IMAP_POOL_SIZE = "mail.imap.pool.size";
    private static final String METRIC_OAUTH_REFRESH = "mail.oauth.refresh";
    private static final String METRIC_IMAP_MOVE = "mail.imap.move";

    private final MeterRegistry registry;
    private final Counter syncMessagesDownloaded;
    private final Counter imapAuthRefresh;

    public MailMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.syncMessagesDownloaded = Counter.builder(METRIC_SYNC_MESSAGES)
                .description("Total number of messages downloaded during synchronization.").register(registry);
        this.imapAuthRefresh = Counter.builder(METRIC_IMAP_AUTH_REFRESH).description(
                "IMAP executeWithLock hit AuthenticationFailedException and had to refresh the token + reconnect.")
                .register(registry);
    }

    /**
     * Start a timer for one folder sync iteration. Pass the result to
     * {@link #recordSync}.
     */
    public Timer.Sample startSync() {
        return Timer.start(registry);
    }

    public void recordSync(Timer.Sample sample, String outcome, int messagesDownloaded) {
        sample.stop(Timer.builder(METRIC_SYNC).description("Duration of synchronizing a single IMAP folder.")
                .tag("outcome", outcome).register(registry));
        if (messagesDownloaded > 0) {
            syncMessagesDownloaded.increment(messagesDownloaded);
        }
    }

    public Timer.Sample startSmtpSend() {
        return Timer.start(registry);
    }

    public void recordSmtpSend(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder(METRIC_SMTP_SEND)
                .description("Duration of sending one message via SMTP (including append to Sent).")
                .tag("outcome", outcome).register(registry));
    }

    public Timer.Sample startImapConnect() {
        return Timer.start(registry);
    }

    public void recordImapConnect(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder(METRIC_IMAP_CONNECT)
                .description("Duration of establishing a new IMAP connection (including RetryTemplate retries).")
                .tag("outcome", outcome).register(registry));
    }

    /**
     * Invoked when {@code ImapConnectionManager.executeWithLock} hits
     * AuthenticationFailedException and attempts a refresh.
     */
    public void incrementImapAuthRefresh() {
        imapAuthRefresh.increment();
    }

    /**
     * Registers a gauge referencing the current IMAP connection pool size. Called
     * once from {@code ImapConnectionManager} in the post-construct phase —
     * Micrometer keeps a weak reference to the object and invokes {@code measure}
     * periodically.
     */
    public <T> void registerImapPoolGauge(T source, ToDoubleFunction<T> measure) {
        io.micrometer.core.instrument.Gauge.builder(METRIC_IMAP_POOL_SIZE, source, measure)
                .description("Number of live IMAP Store connections in the pool.").register(registry);
    }

    /**
     * Counter for IMAP COPY+EXPUNGE move of a message between folders (both
     * moveToTrash and moveToFolder).
     */
    public void recordMove(String outcome) {
        Counter.builder(METRIC_IMAP_MOVE)
                .description("IMAP move of a message between folders (COPY + EXPUNGE). Outcome: success | failure.")
                .tag("outcome", outcome).register(registry).increment();
    }

    public void recordOauthRefresh(String outcome) {
        Counter.builder(METRIC_OAUTH_REFRESH).description(
                "Refresh of an OAuth2 access token (Google, Microsoft, ...). Outcome: success | failure | auth_revoked.")
                .tag("outcome", outcome).register(registry).increment();
    }

    /** Tests only: direct access to MeterRegistry for reading values. */
    public MeterRegistry registry() {
        return registry;
    }

}
