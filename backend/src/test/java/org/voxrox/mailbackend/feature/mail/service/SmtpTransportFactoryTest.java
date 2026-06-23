package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.config.mail.SmtpProperties;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionDetails;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.auth.service.GoogleTokenService;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;

/**
 * Unit tests for {@link SmtpTransportFactory}.
 *
 * <p>
 * The full OAuth retry path ({@code openTransport} ->
 * {@code Session.getTransport} -> connect) would require static mocking of
 * {@code Session.getTransport} and is intentionally skipped (parity with the
 * decision in {@link SmtpMessageServiceTest}). Coverage:
 * <ul>
 * <li>SSL guard ({@code requireSslForOAuth2}) — all three combinations of
 * authType/SSL.</li>
 * <li>{@code createSession} — OAuth mechanism plus STARTTLS/SSL branches.</li>
 * <li>{@code closeQuietly} — swallows {@link MessagingException} and is a no-op
 * for null.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SmtpTransportFactoryTest {

    private static final Long ACCOUNT_ID = 11L;

    @Mock
    private OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;

    private SmtpTransportFactory factory;

    @BeforeEach
    void setUp() {
        // MailClientProperties record — `createSession` only reads
        // `smtp().readTimeout()`
        // and `smtp().connectionTimeout()`. The other nested properties (imap, sync,
        // retry) are not in the code path, so we leave them `null` — the record
        // constructor does not apply Bean Validation (Spring binding does the
        // validating).
        SmtpProperties smtp = new SmtpProperties(Duration.ofSeconds(15), Duration.ofSeconds(10));
        MailClientProperties props = new MailClientProperties(null, smtp, null, null);
        factory = new SmtpTransportFactory(oauth2TokenServiceRegistry, props);
    }

    private AccountConnectionDetails details(AuthType authType, boolean useSsl) {
        return new AccountConnectionDetails("user@gmail.com", "smtp.gmail.com", useSsl ? 465 : 587, useSsl,
                "user@gmail.com", "secret", authType,
                authType == AuthType.OAUTH2 ? GoogleTokenService.PROVIDER_NAME : null);
    }

    @Nested
    @DisplayName("requireSslForOAuth2 — defense-in-depth guard")
    class RequireSslForOAuth2 {

        // Neither implicit SSL nor required STARTTLS -> the XOAUTH2 token would
        // travel in cleartext. createSession never builds this, so we construct it
        // explicitly to exercise the guard's reject path.
        private final Session plaintextSession = Session.getInstance(new Properties());

        @Test
        @DisplayName("OAuth2 + session without TLS -> MailOperationException with MAIL_CONNECTION_ERROR and STARTTLS mention")
        void oauth2WithoutTlsThrows() {
            AccountConnectionDetails d = details(AuthType.OAUTH2, false);

            assertThatThrownBy(() -> SmtpTransportFactory.requireSslForOAuth2(ACCOUNT_ID, plaintextSession, d))
                    .isInstanceOf(MailOperationException.class).hasMessageContaining("STARTTLS")
                    .satisfies(ex -> assertThat(((MailOperationException) ex).getCode())
                            .isEqualTo(ErrorCode.MAIL_CONNECTION_ERROR));
        }

        @Test
        @DisplayName("OAuth2 + implicit-SSL session -> passes without exception")
        void oauth2WithSslPasses() {
            AccountConnectionDetails d = details(AuthType.OAUTH2, true);
            Session session = factory.createSession(d);

            assertThatCode(() -> SmtpTransportFactory.requireSslForOAuth2(ACCOUNT_ID, session, d))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("OAuth2 + mandatory-STARTTLS session (e.g. Office 365 on 587) -> passes without exception")
        void oauth2WithStartTlsPasses() {
            AccountConnectionDetails d = details(AuthType.OAUTH2, false);
            Session session = factory.createSession(d);

            assertThatCode(() -> SmtpTransportFactory.requireSslForOAuth2(ACCOUNT_ID, session, d))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PASSWORD account — guard does not engage, passes even with a plaintext session")
        void passwordAuthBypassesGuardRegardlessOfTls() {
            assertThatCode(() -> SmtpTransportFactory.requireSslForOAuth2(ACCOUNT_ID, plaintextSession,
                    details(AuthType.PASSWORD, false))).doesNotThrowAnyException();
            assertThatCode(() -> SmtpTransportFactory.requireSslForOAuth2(ACCOUNT_ID, plaintextSession,
                    details(AuthType.PASSWORD, true))).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("createSession — SMTP properties")
    class CreateSession {

        @Test
        @DisplayName("OAuth2 + SSL -> ssl.enable=true and auth.mechanisms=XOAUTH2")
        void oauth2WithSslSetsXoauth2Mechanism() {
            Session session = factory.createSession(details(AuthType.OAUTH2, true));

            assertThat(session.getProperty("mail.smtp.host")).isEqualTo("smtp.gmail.com");
            assertThat(session.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
            assertThat(session.getProperty("mail.smtp.starttls.enable")).isNull();
            // Implicit-SSL path: identity verified, no STARTTLS requirement.
            assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
            assertThat(session.getProperty("mail.smtp.starttls.required")).isNull();
            assertThat(session.getProperty("mail.smtp.auth.mechanisms")).isEqualTo(MailAuthMechanisms.XOAUTH2);
            assertThat(session.getProperty("mail.smtp.auth.login.disable")).isEqualTo("true");
            assertThat(session.getProperty("mail.smtp.auth.plain.disable")).isEqualTo("true");
        }

        @Test
        @DisplayName("PASSWORD + no SSL -> starttls.enable=true and no OAuth mechanism")
        void passwordWithoutSslSetsStartTls() {
            Session session = factory.createSession(details(AuthType.PASSWORD, false));

            assertThat(session.getProperty("mail.smtp.ssl.enable")).isNull();
            assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
            // STARTTLS path: upgrade is required (no silent plaintext fallback) and the
            // server identity is verified on the upgraded connection.
            assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
            assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
            assertThat(session.getProperty("mail.smtp.auth.mechanisms")).isNull();
            assertThat(session.getProperty("mail.smtp.auth.login.disable")).isNull();
            assertThat(session.getProperty("mail.smtp.auth.plain.disable")).isNull();
            // Timeouts from MailClientProperties (10s = 10000ms, smtp.connectionTimeout 15s
            // =
            // 15000ms).
            assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo("10000");
            assertThat(session.getProperty("mail.smtp.connectiontimeout")).isEqualTo("15000");
            assertThat(session.getProperty("mail.smtp.writetimeout")).isEqualTo("10000");
        }
    }

    @Nested
    @DisplayName("closeQuietly — exception-free close")
    class CloseQuietly {

        @Test
        @DisplayName("MessagingException from close is swallowed (logged at warn)")
        void swallowsMessagingException() throws Exception {
            Transport transport = mock(Transport.class);
            doThrow(new MessagingException("close failed")).when(transport).close();

            assertThatCode(() -> factory.closeQuietly(transport, ACCOUNT_ID)).doesNotThrowAnyException();
            verify(transport).close();
            verifyNoInteractions(oauth2TokenServiceRegistry);
        }

        @Test
        @DisplayName("null transport — no-op, no NPE and no close call")
        void nullTransportIsNoOp() throws Exception {
            assertThatCode(() -> factory.closeQuietly(null, ACCOUNT_ID)).doesNotThrowAnyException();
            // No way to assert "nothing was closed" when we have no object;
            // it is enough that the call does not blow up (no NPE).
        }

        @Test
        @DisplayName("Successful close — no exception, no follow-up side effects")
        void successfulCloseIsCleanCall() throws Exception {
            Transport transport = mock(Transport.class);

            factory.closeQuietly(transport, ACCOUNT_ID);

            verify(transport).close();
        }
    }
}
