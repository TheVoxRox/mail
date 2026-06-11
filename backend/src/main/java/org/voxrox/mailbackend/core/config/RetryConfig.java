package org.voxrox.mailbackend.core.config;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import javax.net.ssl.SSLException;

import jakarta.mail.AuthenticationFailedException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
}
