package org.voxrox.mailbackend.feature.mail.service;

import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.core.config.MailClientProperties;
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
        props.put("mail." + protocol + ".ssl.enable", String.valueOf(details.useSsl()));
        // Explicit server-identity (hostname) check on the TLS handshake; a no-op on
        // the
        // plaintext protocol but present so the implicit-SSL path cannot regress.
        props.put("mail." + protocol + ".ssl.checkserveridentity", "true");
        props.put("mail." + protocol + ".timeout", String.valueOf(mailProperties.imap().readTimeout().toMillis()));
        props.put("mail." + protocol + ".connectiontimeout",
                String.valueOf(mailProperties.imap().connectionTimeout().toMillis()));

        if (details.authType() == AuthType.OAUTH2) {
            MailAuthMechanisms.configureOAuth2(props, protocol);
        }

        Store store = null;
        try {
            store = Session.getInstance(props).getStore(protocol);
            if (details.authType() == AuthType.OAUTH2) {
                OAuth2TokenService tokenService = oauth2TokenServiceRegistry.resolve(details.oauth2Provider());
                String accessToken = tokenService.getAccessToken(accountId, details.passwordOrSecret(),
                        details.email());
                store.connect(details.host(), details.port(), details.email(), accessToken);
            } else {
                store.connect(details.host(), details.port(), details.username(), details.passwordOrSecret());
            }
            store.getDefaultFolder();
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
        } catch (MessagingException | RuntimeException e) {
            throw new MailConnectionException("SMTP test connection failed: " + e.getMessage(), e);
        } finally {
            smtpTransportFactory.closeQuietly(transport, accountId);
        }
    }
}
