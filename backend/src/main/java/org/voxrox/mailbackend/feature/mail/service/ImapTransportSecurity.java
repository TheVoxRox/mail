package org.voxrox.mailbackend.feature.mail.service;

import java.util.Properties;

import javax.net.ssl.SSLSocketFactory;

/**
 * The TLS half of an IMAP session's properties, shared by the connection pool
 * ({@link ImapConnectionManager}) and the credential probe
 * ({@link MailConnectionProbe}) so the two can never disagree about it.
 * <p>
 * An account whose SSL setting is off does not get a cleartext connection: it
 * gets STARTTLS, and STARTTLS is required, so a server that does not offer it —
 * or a network that strips it — fails the connect instead of receiving the
 * password in cleartext (IMAP/SMTP audit B1-4). This mirrors what
 * {@link SmtpTransportFactory} has always done for SMTP.
 * {@code checkserveridentity} is pinned on both paths rather than left to the
 * Angus default, so the upgraded connection verifies the server's identity
 * exactly as an implicit-SSL one does.
 * <p>
 * Both paths take their sockets from {@code socketFactory}, the shared
 * {@link MailTlsContext}'s: Angus reads {@code ssl.socketFactory} for an
 * implicit-SSL connect and for the STARTTLS upgrade alike. The fallback to the
 * JDK's default factory is off, so a connect that factory refuses fails with
 * its own error rather than being tried a second time (#928).
 */
final class ImapTransportSecurity {

    static void configure(Properties props, String protocol, boolean implicitSsl, SSLSocketFactory socketFactory) {
        props.put("mail." + protocol + ".ssl.enable", String.valueOf(implicitSsl));
        props.put("mail." + protocol + ".ssl.checkserveridentity", "true");
        props.put("mail." + protocol + ".ssl.socketFactory", socketFactory);
        props.put("mail." + protocol + ".socketFactory.fallback", "false");
        if (!implicitSsl) {
            props.put("mail." + protocol + ".starttls.enable", "true");
            props.put("mail." + protocol + ".starttls.required", "true");
        }
    }

    private ImapTransportSecurity() {
    }
}
