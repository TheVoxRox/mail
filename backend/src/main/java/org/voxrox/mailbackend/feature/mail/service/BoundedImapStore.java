package org.voxrox.mailbackend.feature.mail.service;

import java.io.IOException;

import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.URLName;

import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.eclipse.angus.mail.imap.protocol.ListInfo;
import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.core.config.mail.ImapProperties;

/**
 * Angus's IMAP store, except that every connection it opens is a
 * {@link BoundedImapProtocol} (IMAP/SMTP audit B1-3) and every folder it makes
 * a {@link BoundedImapFolder} (B1-19). One class serves both {@code imap} and
 * {@code imaps}: it is what Angus's {@code IMAPStore} and {@code IMAPSSLStore}
 * are, down to the protocol name that prefixes the session properties, which is
 * the one the caller asked the session for — so the TLS settings written under
 * that name ({@link ImapTransportSecurity}) are the ones read.
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
     * Makes this class the session's store for {@code protocol}, and hands its
     * connections the open-folder budget (B1-14) the way Angus hands them
     * everything else, as a session property they read when they open. Called on
     * every session that opens an IMAP store, before its {@code getStore}.
     */
    static void install(Session session, String protocol, ImapProperties imap) throws NoSuchProviderException {
        session.getProperties().put("mail." + protocol + "." + BoundedImapProtocol.OPEN_FOLDER_BUDGET_PROPERTY,
                String.valueOf(imap.openFolderBudget().toBytes()));
        session.setProvider(
                new Provider(Provider.Type.STORE, protocol, BoundedImapStore.class.getName(), "VoxRox Mail", null));
    }

    @Override
    protected IMAPProtocol newIMAPProtocol(String host, int port) throws IOException, ProtocolException {
        return new BoundedImapProtocol(name, host, port, session.getProperties(), isSSL, logger);
    }

    /**
     * Every folder is a {@link BoundedImapFolder}, whose messages bound what a
     * fetch merges into their headers (B1-19). Angus's own makes an
     * {@code IMAPFolder}, or the class {@code mail.<protocol>.folder.class} names,
     * which this application never sets; the two-argument form calls this one.
     */
    @Override
    protected IMAPFolder newIMAPFolder(String fullName, char separator, @Nullable Boolean isNamespace) {
        return new BoundedImapFolder(fullName, separator, this, isNamespace);
    }

    /** {@link #newIMAPFolder(String, char, Boolean)} for a folder a LIST named. */
    @Override
    protected IMAPFolder newIMAPFolder(ListInfo listInfo) {
        return new BoundedImapFolder(listInfo, this);
    }
}
