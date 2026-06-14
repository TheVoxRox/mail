package org.voxrox.mailbackend.core.config;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import javax.net.ssl.SSLException;

import jakarta.mail.AuthenticationFailedException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.voxrox.mailbackend.core.config.mail.RetryProperties;

/**
 * Spring-retry configuration for IMAP connect attempts.
 *
 * The RetryTemplate is used programmatically in
 * {@link org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager}
 * around {@code store.connect(...)}. Intentionally without {@code @Retryable}
 * annotations: {@code @Retryable} is AOP proxy-based and internal calls
 * (self-invocation in the same class) bypass it. An explicit
 * {@code RetryTemplate.execute()} is more transparent and easy to test.
 *
 * Retryable exceptions — only genuinely transient network errors:
 * {@link SocketTimeoutException}, {@link ConnectException},
 * {@link SSLException}, the generic {@link IOException}.
 * {@link AuthenticationFailedException} explicitly is NOT retryable — it has
 * its own refresh-token path in {@code executeWithLock}; retrying with backoff
 * here would be counterproductive (the token will not heal itself within 4 s).
 */
@Configuration
public class RetryConfig {

    @Bean
    public RetryTemplate imapRetryTemplate(MailClientProperties mailClientProperties) {
        RetryProperties retryProperties = mailClientProperties.retry();
        RetryTemplate template = new RetryTemplate();

        /*
         * Retry is for transient network errors only. AuthenticationFailedException is
         * explicitly false so that SimpleRetryPolicy does not catch it via the
         * MessagingException subclass match.
         */
        Map<Class<? extends Throwable>, Boolean> retryable = Map.of(SocketTimeoutException.class, true,
                ConnectException.class, true, SSLException.class, true, IOException.class, true,
                AuthenticationFailedException.class, false);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(retryProperties.maxAttempts(), retryable,
                /* traverseCauses */ true);
        template.setRetryPolicy(retryPolicy);

        // Jitter prevents concurrent accounts from retrying in the same second after an
        // outage.
        ExponentialRandomBackOffPolicy backOff = new ExponentialRandomBackOffPolicy();
        backOff.setInitialInterval(retryProperties.initialDelay().toMillis());
        backOff.setMultiplier(retryProperties.multiplier());
        backOff.setMaxInterval(retryProperties.maxDelay().toMillis());
        template.setBackOffPolicy(backOff);

        return template;
    }

    /**
     * Retry for transient SQLite write contention. SQLite permits a single writer
     * at a time; under the Hikari pool a user action can collide with a background
     * sync, or a multi-select trash can fire several DELETEs at once.
     *
     * <p>
     * Most of that contention never reaches here: the JDBC URL sets
     * {@code busy_timeout=5000} (see {@code spring.datasource.url}), so a writer
     * blocked by another writer waits for the lock — up to 5s — instead of failing.
     * What {@code busy_timeout} does <em>not</em> cover is a WAL snapshot conflict:
     * a transaction that read first and then tries to write after another writer
     * has moved the snapshot on is failed immediately, with no wait, because
     * waiting cannot help. Those (and the rare {@literal >}5s wait) surface via
     * {@code org.hibernate.community.dialect.SQLiteDialect} as a Hibernate
     * {@code LockAcquisitionException}, which Spring maps to
     * {@link org.springframework.dao.CannotAcquireLockException} (a
     * {@link TransientDataAccessException}). The contending writer commits within
     * milliseconds, so a couple of short, jittered retries turn the failure into a
     * successful write instead of a 500.
     *
     * <p>
     * The attempt cap is deliberately small: each attempt may itself have already
     * waited up to the 5s {@code busy_timeout}, so a low cap bounds the worst-case
     * user-facing stall.
     *
     * <p>
     * Applied at the (non-transactional) call sites in {@code MailFacade}: each
     * retry re-invokes a {@code @Transactional} write, so a fresh transaction runs
     * after the previous one rolled back. Only {@link TransientDataAccessException}
     * is retried — permanent failures (constraint violations, not-found, ...)
     * propagate immediately.
     */
    @Bean
    public RetryTemplate dbWriteRetryTemplate() {
        // 3 attempts = 2 retries. Kept low because each attempt may already have
        // blocked on busy_timeout; this bounds the worst-case latency of a hammered
        // write while still absorbing the millisecond-scale snapshot conflicts.
        Map<Class<? extends Throwable>, Boolean> retryable = Map.of(TransientDataAccessException.class, true);
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(3, retryable, /* traverseCauses */ true);

        // Short, jittered backoff: the lock frees in ms; jitter de-syncs sibling
        // writers (e.g. the DELETEs of a multi-select trash) so they do not all
        // retry into the same contended instant.
        ExponentialRandomBackOffPolicy backOff = new ExponentialRandomBackOffPolicy();
        backOff.setInitialInterval(25);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(250);

        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOff);

        return template;
    }
}
