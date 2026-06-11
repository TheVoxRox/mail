package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

import jakarta.mail.AuthenticationFailedException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;
import org.voxrox.mailbackend.core.config.mail.RetryProperties;

/**
 * Verifies that {@code imapRetryTemplate} retries only the agreed transient
 * exceptions and that {@link AuthenticationFailedException} propagates
 * immediately (it has its own refresh-token path in ImapConnectionManager).
 */
class RetryConfigTest {

    private RetryTemplate template;

    @BeforeEach
    void setUp() {
        // Very short delays so the test runs quickly.
        RetryProperties retry = new RetryProperties(3, Duration.ofMillis(1), Duration.ofMillis(5), 2.0);
        MailClientProperties mailProps = new MailClientProperties(null, null, null, retry);
        template = new RetryConfig().imapRetryTemplate(mailProps);
    }

    @Nested
    @DisplayName("Transient network errors are retried")
    class Transient {

        @Test
        void socketTimeoutJeRetryovanAUspeje() throws Throwable {
            AtomicInteger attempts = new AtomicInteger();
            String result = template.execute(ctx -> {
                if (attempts.incrementAndGet() < 2) {
                    throw new SocketTimeoutException("boom");
                }
                return "ok";
            });
            assertThat(result).isEqualTo("ok");
            assertThat(attempts.get()).isEqualTo(2);
        }

        @Test
        void connectExceptionJeRetryovan() throws Throwable {
            AtomicInteger attempts = new AtomicInteger();
            template.execute(ctx -> {
                if (attempts.incrementAndGet() < 2) {
                    throw new ConnectException("refused");
                }
                return null;
            });
            assertThat(attempts.get()).isEqualTo(2);
        }

        @Test
        void sslExceptionJeRetryovan() throws Throwable {
            AtomicInteger attempts = new AtomicInteger();
            template.execute(ctx -> {
                if (attempts.incrementAndGet() < 2) {
                    throw new SSLException("handshake");
                }
                return null;
            });
            assertThat(attempts.get()).isEqualTo(2);
        }

        @Test
        void obecnaIoExceptionJeRetryovana() throws Throwable {
            AtomicInteger attempts = new AtomicInteger();
            template.execute(ctx -> {
                if (attempts.incrementAndGet() < 2) {
                    throw new IOException("stream closed");
                }
                return null;
            });
            assertThat(attempts.get()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Auth failures are NOT retried (own refresh-token path)")
    class AuthFailure {

        @Test
        void authenticationFailedExceptionSePropagujeIhned() {
            AtomicInteger attempts = new AtomicInteger();
            assertThatThrownBy(() -> template.execute(ctx -> {
                attempts.incrementAndGet();
                throw new AuthenticationFailedException("invalid token");
            })).isInstanceOf(AuthenticationFailedException.class);
            assertThat(attempts.get()).as("auth failure must not be retried").isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Max attempts exhaustion")
    class MaxAttempts {

        @Test
        void poVycerpaniPokusuSePropagujePosledniChyba() {
            AtomicInteger attempts = new AtomicInteger();
            assertThatThrownBy(() -> template.execute(ctx -> {
                attempts.incrementAndGet();
                throw new SocketTimeoutException("persistent");
            })).isInstanceOf(SocketTimeoutException.class).hasMessageContaining("persistent");
            assertThat(attempts.get()).as("3 attempts per RetryProperties").isEqualTo(3);
        }
    }
}
