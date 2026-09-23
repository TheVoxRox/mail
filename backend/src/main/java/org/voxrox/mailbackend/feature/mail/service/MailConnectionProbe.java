package org.voxrox.mailbackend.feature.mail.service;

import java.util.Properties;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.exception.MailAuthenticationException;
import org.voxrox.mailbackend.exception.MailConnectionException;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionDetails;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenService;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;

/**
 * Smoke-tests IMAP/SMTP credentials by opening (and closing) a real connection.
 *
 * <p>
 * Single concrete class — no interface. Mockito mocks concrete classes fine,
 * and there is no second probe implementation in this project.
 */
@Component
public class MailConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(MailConnectionProbe.class);

    private final MailClientProperties mailProperties;
    private final OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;
    private final SmtpTransportFactory smtpTransportFactory;

    public MailConnectionProbe(MailClientProperties mailProperties,
            OAuth2TokenServiceRegistry oauth2TokenServiceRegistry, SmtpTransportFactory smtpTransportFactory) {
        this.mailProperties = mailProperties;
        this.oauth2TokenServiceRegistry = oauth2TokenServiceRegistry;
        this.smtpTransportFactory = smtpTransportFactory;
    }

    public void testImap(Long accountId, AccountConnectionDetails details) {
        if (details.authType() == AuthType.OAUTH2 && !details.useSsl()) {
            throw new MailConnectionException("OAuth2 accounts require an SSL/TLS connection for IMAP.");
        }

        String protocol = details.useSsl()
                ? mailProperties.imap().protocolSsl()
                : mailProperties.imap().protocolStandard();
        Properties props = new Properties();
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", details.host());
        props.put("mail." + protocol + ".port", String.valueOf(details.port()));
        // The same TLS settings the connection pool uses — see ImapTransportSecurity.
        ImapTransportSecurity.configure(props, protocol, details.useSsl());
        // The same socket bounds the connection pool uses — see ImapSocketTimeouts.
        ImapSocketTimeouts.configure(props, protocol, mailProperties.imap());

        if (details.authType() == AuthType.OAUTH2) {
            MailAuthMechanisms.configureOAuth2(props, protocol);
        }

        Store store = null;
        try {
            Session session = Session.getInstance(props);
            // The same bounded store the connection pool uses — see BoundedImapStore.
            BoundedImapStore.install(session, protocol);
            store = session.getStore(protocol);
            if (details.authType() == AuthType.OAUTH2) {
                OAuth2TokenService tokenService = oauth2TokenServiceRegistry.resolve(details.oauth2Provider());
                String accessToken = tokenService.getAccessToken(accountId, details.passwordOrSecret(),
                        details.email());
                store.connect(details.host(), details.port(), details.email(), accessToken);
            } else {
                store.connect(details.host(), details.port(), details.username(), details.passwordOrSecret());
            }
            store.getDefaultFolder();
        } catch (AuthenticationFailedException e) {
            throw rejectedLogin(e);
        } catch (MessagingException | RuntimeException e) {
            throw new MailConnectionException("IMAP test connection failed: " + e.getMessage(), e);
        } finally {
            if (store != null) {
                try {
                    store.close();
                } catch (MessagingException e) {
                    log.debug("Closing the IMAP test connection failed: {}", e.getMessage());
                }
            }
        }
    }

    public void testSmtp(Long accountId, AccountConnectionDetails details) {
        Transport transport = null;
        try {
            Session session = smtpTransportFactory.createSession(details);
            transport = smtpTransportFactory.openTransport(accountId, session, details);
        } catch (AuthenticationFailedException e) {
            throw rejectedLogin(e);
        } catch (MessagingException | RuntimeException e) {
            throw new MailConnectionException("SMTP test connection failed: " + e.getMessage(), e);
        } finally {
            smtpTransportFactory.closeQuietly(transport, accountId);
        }
    }

    /**
     * A server that rejected the login is not a connection failure — the socket and
     * the TLS handshake worked. The distinction is what the caller sees:
     * {@code error.mail.connectionFailed} renders the wrapped text into its
     * {@code {0}} placeholder, so a rejected password surfaced as the raw English
     * server reply ("Mail server connection failed: IMAP test connection failed:
     * [AUTHENTICATIONFAILED] AUTHENTICATE Incorrect authentication data") inside an
     * otherwise localized sentence. {@link MailAuthenticationException} carries a
     * fully localized message and the machine-readable
     * {@code MAIL_AUTHENTICATION_FAILED} code the client keys on to point the user
     * at the password field; the original reply travels as the cause, so the log
     * keeps it.
     */
    private static MailAuthenticationException rejectedLogin(AuthenticationFailedException cause) {
        return new MailAuthenticationException(cause);
    }
}
