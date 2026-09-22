package org.voxrox.mailbackend.feature.mail.service;

import java.io.IOException;

import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.URLName;

import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;

/**
 * Angus's IMAP store, except that every connection it opens is a
 * {@link BoundedImapProtocol} (IMAP/SMTP audit B1-3). One class serves both
 * {@code imap} and {@code imaps}: it is what Angus's {@code IMAPStore} and
 * {@code IMAPSSLStore} are, down to the protocol name that prefixes the session
 * properties, which is the one the caller asked the session for — so the TLS
 * settings written under that name ({@link ImapTransportSecurity}) are the ones
 * read.
 * <p>
 * Public with a {@code (Session, URLName)} constructor because Jakarta Mail
 * instantiates a store provider by reflection. {@link #install} registers it on
 * a session, so the {@code session.getStore(protocol)} both callers already
 * make hands out this class.
 */
public final class BoundedImapStore extends IMAPStore {

    private static final String IMAPS = "imaps";

    public BoundedImapStore(Session session, URLName url) {
        super(session, url, url.getProtocol(), IMAPS.equals(url.getProtocol()));
    }

    /**
     * Makes this class the session's store for {@code protocol}. Called on every
     * session that opens an IMAP store, before its {@code getStore}.
     */
    static void install(Session session, String protocol) throws NoSuchProviderException {
        session.setProvider(
                new Provider(Provider.Type.STORE, protocol, BoundedImapStore.class.getName(), "VoxRox Mail", null));
    }

    @Override
    protected IMAPProtocol newIMAPProtocol(String host, int port) throws IOException, ProtocolException {
        return new BoundedImapProtocol(name, host, port, session.getProperties(), isSSL, logger);
    }
}
