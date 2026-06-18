package org.voxrox.mailbackend.feature.mail.service;

import java.util.Properties;

/**
 * TLS hardening for JavaMail connection properties. Centralises the transport
 * security policy so the SMTP and IMAP connection builders cannot drift apart
 * (the gap CodeQL flagged as {@code java/insecure-smtp-ssl}).
 *
 * <p>
 * Sibling of {@link MailAuthMechanisms}; package-private, stateless.
 */
final class MailTlsConfig {

    private MailTlsConfig() {
    }

    /**
     * Always verify the server certificate identity (hostname) on the TLS handshake
     * — set explicitly rather than relying on the Angus Mail default, so the
     * control survives a library-default change. Harmless on a plaintext connection
     * (the property is simply never consulted).
     */
    static void verifyServerIdentity(Properties props, String protocol) {
        props.put("mail." + protocol + ".ssl.checkserveridentity", "true");
    }

    /**
     * For a STARTTLS connection (explicit-TLS upgrade, no implicit SSL): require
     * the upgrade so the connection fails instead of silently sending credentials
     * over plaintext when the server does not offer STARTTLS or a man-in-the-middle
     * strips it (downgrade attack).
     */
    static void requireStartTls(Properties props, String protocol) {
        props.put("mail." + protocol + ".starttls.enable", "true");
        props.put("mail." + protocol + ".starttls.required", "true");
    }
}
