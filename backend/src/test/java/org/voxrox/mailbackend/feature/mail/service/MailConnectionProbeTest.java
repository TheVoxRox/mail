package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.config.mail.ImapProperties;
import org.voxrox.mailbackend.core.config.mail.SmtpProperties;
import org.voxrox.mailbackend.exception.MailConnectionException;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionDetails;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenService;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;

/**
 * Unit tests for the security-critical IMAP/SMTP probe used by the
 * connection-test endpoint.
 *
 * <p>
 * The IMAP path builds its {@link Session} via static factory and immediately
 * acquires a {@link Store}; we mock {@code Session.getInstance} so the probe
 * touches a fake {@link Store} instead of opening a real socket. The SMTP path
 * delegates entirely to {@link SmtpTransportFactory}, which is a plain mock.
 */
@ExtendWith(MockitoExtension.class)
class MailConnectionProbeTest {

    private static final Long ACCOUNT_ID = 42L;

    @Mock
    private OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;
    @Mock
    private SmtpTransportFactory smtpTransportFactory;
    @Mock
    private OAuth2TokenService tokenService;

    private MailConnectionProbe probe;

    @BeforeEach
    void setUp() {
        ImapProperties imap = new ImapProperties(993, Duration.ofSeconds(30), Duration.ofSeconds(60), "imaps", "imap");
        SmtpProperties smtp = new SmtpProperties(Duration.ofSeconds(15), Duration.ofSeconds(10));
        MailClientProperties props = new MailClientProperties(imap, smtp, null, null);
        probe = new MailConnectionProbe(props, oauth2TokenServiceRegistry, smtpTransportFactory);
    }

    private static AccountConnectionDetails passwordDetails(boolean useSsl) {
        return new AccountConnectionDetails("user@example.com", "imap.example.com", useSsl ? 993 : 143, useSsl,
                "user@example.com", "secret", AuthType.PASSWORD, null);
    }

    private static AccountConnectionDetails oauthDetails(boolean useSsl) {
        return new AccountConnectionDetails("user@gmail.com", "imap.gmail.com", useSsl ? 993 : 143, useSsl,
                "user@gmail.com", "refresh-token", AuthType.OAUTH2, "google");
    }

    @Nested
    @DisplayName("testImap — IMAP boundary")
    class TestImapBoundary {

        @Test
        @DisplayName("OAuth2 over plaintext is rejected before any socket open — defense in depth")
        void oauth2WithoutSslIsRejectedEarly() {
            assertThatThrownBy(() -> probe.testImap(ACCOUNT_ID, oauthDetails(false)))
                    .isInstanceOf(MailConnectionException.class).hasMessageContaining("SSL/TLS");

            verifyNoInteractions(oauth2TokenServiceRegistry, smtpTransportFactory);
        }

        @Test
        @DisplayName("PASSWORD + SSL — connects with username/password and closes the store in finally")
        void passwordOverSslConnectsAndClosesStore() throws Exception {
            Session sessionMock = mock(Session.class);
            Store storeMock = mock(Store.class);
            when(sessionMock.getStore("imaps")).thenReturn(storeMock);
            ArgumentCaptor<Properties> propsCaptor = ArgumentCaptor.forClass(Properties.class);

            try (MockedStatic<Session> staticSession = mockStatic(Session.class)) {
                staticSession.when(() -> Session.getInstance(propsCaptor.capture())).thenReturn(sessionMock);

                probe.testImap(ACCOUNT_ID, passwordDetails(true));
            }

            verify(storeMock).connect("imap.example.com", 993, "user@example.com", "secret");
            verify(storeMock).getDefaultFolder();
            verify(storeMock).close();
            verifyNoInteractions(oauth2TokenServiceRegistry);

            Properties props = propsCaptor.getValue();
            assertThat(props.getProperty("mail.store.protocol")).isEqualTo("imaps");
            assertThat(props.getProperty("mail.imaps.host")).isEqualTo("imap.example.com");
            assertThat(props.getProperty("mail.imaps.port")).isEqualTo("993");
            assertThat(props.getProperty("mail.imaps.ssl.enable")).isEqualTo("true");
            assertThat(props.getProperty("mail.imaps.timeout")).isEqualTo("60000");
            assertThat(props.getProperty("mail.imaps.connectiontimeout")).isEqualTo("30000");
            assertThat(props.getProperty("mail.imaps.auth.mechanisms")).isNull();
        }

        @Test
        @DisplayName("PASSWORD over plaintext uses the standard protocol and skips OAuth mechanism")
        void passwordOverPlaintextUsesStandardProtocol() throws Exception {
            Session sessionMock = mock(Session.class);
            Store storeMock = mock(Store.class);
            when(sessionMock.getStore("imap")).thenReturn(storeMock);
            ArgumentCaptor<Properties> propsCaptor = ArgumentCaptor.forClass(Properties.class);

            try (MockedStatic<Session> staticSession = mockStatic(Session.class)) {
                staticSession.when(() -> Session.getInstance(propsCaptor.capture())).thenReturn(sessionMock);

                probe.testImap(ACCOUNT_ID, passwordDetails(false));
            }

            verify(storeMock).connect("imap.example.com", 143, "user@example.com", "secret");

            Properties props = propsCaptor.getValue();
            assertThat(props.getProperty("mail.store.protocol")).isEqualTo("imap");
            assertThat(props.getProperty("mail.imap.ssl.enable")).isEqualTo("false");
            assertThat(props.getProperty("mail.imap.auth.mechanisms")).isNull();
        }

        @Test
        @DisplayName("MessagingException from connect is wrapped in MailConnectionException and the store is still closed")
        void connectFailureWrapsAndClosesStore() throws Exception {
            Session sessionMock = mock(Session.class);
            Store storeMock = mock(Store.class);
            when(sessionMock.getStore("imaps")).thenReturn(storeMock);
            doThrow(new MessagingException("auth failed")).when(storeMock).connect("imap.example.com", 993,
                    "user@example.com", "secret");

            try (MockedStatic<Session> staticSession = mockStatic(Session.class)) {
                staticSession.when(() -> Session.getInstance(any(Properties.class))).thenReturn(sessionMock);

                assertThatThrownBy(() -> probe.testImap(ACCOUNT_ID, passwordDetails(true)))
                        .isInstanceOf(MailConnectionException.class).hasMessageContaining("auth failed");
            }

            verify(storeMock).close();
            verify(storeMock, never()).getDefaultFolder();
        }

        @Test
        @DisplayName("Close exception in finally is swallowed so the original error reaches the caller")
        void closeExceptionIsSwallowed() throws Exception {
            Session sessionMock = mock(Session.class);
            Store storeMock = mock(Store.class);
            when(sessionMock.getStore("imaps")).thenReturn(storeMock);
            doThrow(new MessagingException("close failed")).when(storeMock).close();

            try (MockedStatic<Session> staticSession = mockStatic(Session.class)) {
                staticSession.when(() -> Session.getInstance(any(Properties.class))).thenReturn(sessionMock);

                assertThatCode(() -> probe.testImap(ACCOUNT_ID, passwordDetails(true))).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("OAuth2 + SSL resolves the provider token service and connects with an access token, not the stored refresh-token secret")
        void oauth2SuccessAuthenticatesWithAccessToken() throws Exception {
            Session sessionMock = mock(Session.class);
            Store storeMock = mock(Store.class);
            when(sessionMock.getStore("imaps")).thenReturn(storeMock);
            when(oauth2TokenServiceRegistry.resolve("google")).thenReturn(tokenService);
            when(tokenService.getAccessToken(eq(ACCOUNT_ID), eq("refresh-token"), eq("user@gmail.com")))
                    .thenReturn("access-XYZ");
            ArgumentCaptor<Properties> propsCaptor = ArgumentCaptor.forClass(Properties.class);

            try (MockedStatic<Session> staticSession = mockStatic(Session.class)) {
                staticSession.when(() -> Session.getInstance(propsCaptor.capture())).thenReturn(sessionMock);

                probe.testImap(ACCOUNT_ID, oauthDetails(true));
            }

            verify(storeMock).connect("imap.gmail.com", 993, "user@gmail.com", "access-XYZ");
            verify(storeMock).close();

            Properties props = propsCaptor.getValue();
            assertThat(props.getProperty("mail.imaps.auth.mechanisms")).isEqualTo("XOAUTH2");
            assertThat(props.getProperty("mail.imaps.auth.login.disable")).isEqualTo("true");
            assertThat(props.getProperty("mail.imaps.auth.plain.disable")).isEqualTo("true");
        }

        @Test
        @DisplayName("RuntimeException from token resolution is wrapped in MailConnectionException; store is still closed and connect is never reached")
        void oauthTokenResolutionRuntimeFailureIsWrapped() throws Exception {
            Session sessionMock = mock(Session.class);
            Store storeMock = mock(Store.class);
            when(sessionMock.getStore("imaps")).thenReturn(storeMock);
            when(oauth2TokenServiceRegistry.resolve("google")).thenReturn(tokenService);
            when(tokenService.getAccessToken(any(), any(), any())).thenThrow(new RuntimeException("provider down"));

            try (MockedStatic<Session> staticSession = mockStatic(Session.class)) {
                staticSession.when(() -> Session.getInstance(any(Properties.class))).thenReturn(sessionMock);

                assertThatThrownBy(() -> probe.testImap(ACCOUNT_ID, oauthDetails(true)))
                        .isInstanceOf(MailConnectionException.class).hasMessageContaining("provider down");
            }

            verify(storeMock, never()).connect(anyString(), anyInt(), anyString(), anyString());
            verify(storeMock).close();
        }
    }

    @Nested
    @DisplayName("testSmtp — SMTP boundary")
    class TestSmtpBoundary {

        @Test
        @DisplayName("Successful SMTP probe opens the transport and closes it via the factory")
        void smtpSuccess() throws Exception {
            Session session = mock(Session.class);
            Transport transport = mock(Transport.class);
            AccountConnectionDetails details = passwordDetails(true);
            when(smtpTransportFactory.createSession(details)).thenReturn(session);
            when(smtpTransportFactory.openTransport(ACCOUNT_ID, session, details)).thenReturn(transport);

            assertThatCode(() -> probe.testSmtp(ACCOUNT_ID, details)).doesNotThrowAnyException();

            verify(smtpTransportFactory).closeQuietly(transport, ACCOUNT_ID);
        }

        @Test
        @DisplayName("MessagingException from openTransport is wrapped in MailConnectionException; closeQuietly still runs with a null transport")
        void smtpMessagingExceptionWrapped() throws Exception {
            AccountConnectionDetails details = passwordDetails(true);
            Session session = mock(Session.class);
            when(smtpTransportFactory.createSession(details)).thenReturn(session);
            when(smtpTransportFactory.openTransport(ACCOUNT_ID, session, details))
                    .thenThrow(new MessagingException("smtp auth failed"));

            assertThatThrownBy(() -> probe.testSmtp(ACCOUNT_ID, details)).isInstanceOf(MailConnectionException.class)
                    .hasMessageContaining("smtp auth failed");

            verify(smtpTransportFactory).closeQuietly(null, ACCOUNT_ID);
        }

        @Test
        @DisplayName("RuntimeException from createSession is wrapped in MailConnectionException")
        void smtpRuntimeFailureWrapped() {
            AccountConnectionDetails details = passwordDetails(true);
            when(smtpTransportFactory.createSession(details)).thenThrow(new IllegalStateException("session boom"));

            assertThatThrownBy(() -> probe.testSmtp(ACCOUNT_ID, details)).isInstanceOf(MailConnectionException.class)
                    .hasMessageContaining("session boom");

            verify(smtpTransportFactory).closeQuietly(null, ACCOUNT_ID);
        }
    }
}
