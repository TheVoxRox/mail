package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.config.mail.ImapProperties;
import org.voxrox.mailbackend.core.config.mail.SmtpProperties;
import org.voxrox.mailbackend.exception.MailConnectionException;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionDetails;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * An IMAP account whose SSL setting is off meets a server that offers no TLS at
 * all. Before the fix for IMAP/SMTP audit B1-4 the backend logged in over that
 * connection, password in cleartext; now an unchecked SSL box means STARTTLS,
 * STARTTLS is required, and the connect fails before any credential is sent.
 * <p>
 * GreenMail's IMAP does not implement STARTTLS, which makes it exactly the
 * server this needs: plain IMAP on a loopback port, nothing to upgrade to. The
 * probe is the credential test the account form runs before saving, and it
 * shares its TLS settings with the connection pool
 * ({@link ImapTransportSecurity}), so it stands for both.
 */
class ImapStartTlsRequiredGreenMailIT {

    private static final String LOGIN = "plain-user";
    private static final String PASSWORD = "plain-password";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
            new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP)).withPerMethodLifecycle(false);

    private MailConnectionProbe probe;

    @BeforeEach
    void setUp() {
        greenMail.setUser("plain@greenmail.local", LOGIN, PASSWORD);
        ImapProperties imap = new ImapProperties(993, Duration.ofSeconds(5), Duration.ofSeconds(5), "imaps", "imap",
                Duration.ofSeconds(1), Duration.ofMinutes(5));
        SmtpProperties smtp = new SmtpProperties(Duration.ofSeconds(5), Duration.ofSeconds(5));
        probe = new MailConnectionProbe(new MailClientProperties(imap, smtp, null, null),
                mock(OAuth2TokenServiceRegistry.class), mock(SmtpTransportFactory.class));
    }

    @Test
    @DisplayName("A server without STARTTLS fails the connect instead of receiving the password in cleartext")
    void serverWithoutStartTlsIsRefused() {
        AccountConnectionDetails plaintext = new AccountConnectionDetails("plain@greenmail.local", "127.0.0.1",
                greenMail.getImap().getPort(), /* useSsl */ false, LOGIN, PASSWORD, AuthType.PASSWORD, null);

        assertThatThrownBy(() -> probe.testImap(1L, plaintext)).isInstanceOf(MailConnectionException.class)
                .hasMessageContaining("STARTTLS");
    }
}
