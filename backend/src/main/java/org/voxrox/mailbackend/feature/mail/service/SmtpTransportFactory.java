package org.voxrox.mailbackend.feature.mail.service;

import java.util.Properties;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionDetails;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenService;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

/**
 * Creates, authenticates and closes SMTP {@link Session}/{@link Transport}.
 * Encapsulates:
 * <ul>
 * <li>Assembly of the {@link Session} with SMTP properties (host, port,
 * timeouts, SSL/STARTTLS, OAuth mechanism).</li>
 * <li>Opening and authentication of the {@link Transport} including OAuth2
 * retry with the refresh token (we retry once after
 * {@link AuthenticationFailedException} — the cache may hold a token the
 * provider has meanwhile rejected: clock skew, premature revocation; a second
 * failure is propagated).</li>
 * <li>The defense-in-depth guard {@link #requireSslForOAuth2}, which prevents
 * sending an OAuth2 access token over plaintext SMTP (the XOAUTH2 SASL payload
 * contains the token; provider config should not allow it, but failing fast is
 * cheaper than a leaked token).</li>
 * <li>Quiet closing of the transport for {@code finally} paths.</li>
 * </ul>
 *
 * <p>
 * The specific OAuth2 provider (Google, Microsoft, …) is resolved through
 * {@link OAuth2TokenServiceRegistry} by {@code details.oauth2Provider()}.
 */
@Component
public class SmtpTransportFactory {

    private static final Logger log = LoggerFactory.getLogger(SmtpTransportFactory.class);

    private final OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;
    private final MailClientProperties mailProperties;

    public SmtpTransportFactory(OAuth2TokenServiceRegistry oauth2TokenServiceRegistry,
            MailClientProperties mailProperties) {
        this.oauth2TokenServiceRegistry = oauth2TokenServiceRegistry;
        this.mailProperties = mailProperties;
    }

    public Session createSession(AccountConnectionDetails details) {
        String timeoutMs = String.valueOf(mailProperties.smtp().readTimeout().toMillis());
        String smtpConnTimeoutMs = String.valueOf(mailProperties.smtp().connectionTimeout().toMillis());

        Properties props = new Properties();
        props.put("mail.smtp.host", details.host());
        props.put("mail.smtp.port", String.valueOf(details.port()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.timeout", timeoutMs);
        props.put("mail.smtp.connectiontimeout", smtpConnTimeoutMs);
        props.put("mail.smtp.writetimeout", timeoutMs);

        MailTlsConfig.verifyServerIdentity(props, "smtp");
        if (details.useSsl()) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            MailTlsConfig.requireStartTls(props, "smtp");
        }

        if (details.authType() == AuthType.OAUTH2) {
            MailAuthMechanisms.configureOAuth2(props, "smtp");
        }

        return Session.getInstance(props);
    }

    /**
     * Opens and authenticates an SMTP {@link Transport}. The caller is responsible
     * for closing it in a {@code finally} (typically via {@link #closeQuietly}).
     *
     * <p>
     * Calls {@link #requireSslForOAuth2} before every open: fail-fast if the OAuth2
     * account does not have SSL/TLS (otherwise the access token would travel in
     * plaintext).
     */
    public Transport openTransport(Long accountId, Session session, AccountConnectionDetails details)
            throws MessagingException {
        requireSslForOAuth2(accountId, details);

        if (details.authType() == AuthType.OAUTH2) {
            OAuth2TokenService tokenService = oauth2TokenServiceRegistry.resolve(details.oauth2Provider());
            try {
                return openOAuthTransport(accountId, session, details, tokenService);
            } catch (AuthenticationFailedException firstAuthFail) {
                log.warn("{} SMTP auth failed for account {} ({}), refreshing the token and retrying.",
                        LogCategory.SMTP, accountId, details.oauth2Provider());
                tokenService.invalidate(accountId);
                return openOAuthTransport(accountId, session, details, tokenService);
            }
        }

        Transport transport = session.getTransport("smtp");
        transport.connect(details.host(), details.username(), details.passwordOrSecret());
        return transport;
    }

    private Transport openOAuthTransport(Long accountId, Session session, AccountConnectionDetails details,
            OAuth2TokenService tokenService) throws MessagingException {
        Transport transport = session.getTransport("smtp");
        try {
            String accessToken = tokenService.getAccessToken(accountId, details.passwordOrSecret(), details.email());
            transport.connect(details.host(), details.email(), accessToken);
            return transport;
        } catch (MessagingException | RuntimeException e) {
            /*
             * If connect fails, close the unopened/half-opened transport here so that no
             * dangling TCP socket remains — the caller only sees the exception and has no
             * reference to the transport.
             */
            try {
                transport.close();
            } catch (Exception closeEx) {
                log.debug("{} Closing a half-opened SMTP transport failed: {}", LogCategory.SMTP, closeEx.getMessage());
            }
            throw e;
        }
    }

    /**
     * Quiet close of the transport — swallows {@link MessagingException} and logs a
     * warning. Called from {@code finally} blocks of the orchestrator.
     */
    public void closeQuietly(@Nullable Transport transport, Long accountId) {
        if (transport != null) {
            try {
                transport.close();
            } catch (MessagingException e) {
                log.warn("{} Error while closing the SMTP transport for account {}", LogCategory.SMTP, accountId, e);
            }
        }
    }

    /**
     * Defense-in-depth guard — package-private so it can be unit-tested directly
     * without a factory instance. Stateless.
     */
    static void requireSslForOAuth2(Long accountId, AccountConnectionDetails details) {
        if (details.authType() == AuthType.OAUTH2 && !details.useSsl()) {
            AuditLog.critical("smtp_oauth2_plaintext_blocked", LogMasker.maskEmail(details.email()),
                    "account=" + accountId);
            throw new MailOperationException(ErrorCode.MAIL_CONNECTION_ERROR,
                    "OAuth2 accounts require an SSL/TLS connection for SMTP (account " + accountId + ")");
        }
    }
}
