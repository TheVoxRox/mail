package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.MessagingException;
import jakarta.mail.Store;

import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * Detects the IMAP extensions that an efficient sync depends on (RFC 7162).
 * <p>
 * Capability matrix (observed in the field 2026-05-19):
 * <ul>
 * <li><b>Gmail</b> — CONDSTORE + QRESYNC</li>
 * <li><b>Outlook / Exchange Online</b> — CONDSTORE + QRESYNC</li>
 * <li><b>Seznam.cz</b> (Dovecot) — CONDSTORE + QRESYNC</li>
 * <li><b>iCloud</b> — CONDSTORE + QRESYNC</li>
 * </ul>
 * For unknown / legacy servers (rare custom Cyrus pre-2010, rare corporate
 * IMAPs) the fallback path uses a full UID sweep.
 */
public final class ImapCapabilities {

    private static final String CAP_CONDSTORE = "CONDSTORE";
    private static final String CAP_QRESYNC = "QRESYNC";

    private final boolean condstore;
    private final boolean qresync;

    private ImapCapabilities(boolean condstore, boolean qresync) {
        this.condstore = condstore;
        this.qresync = qresync;
    }

    public static ImapCapabilities probe(Store store) throws MessagingException {
        if (!(store instanceof IMAPStore imapStore)) {
            return new ImapCapabilities(false, false);
        }
        boolean condstoreCap = imapStore.hasCapability(CAP_CONDSTORE);
        boolean qresyncCap = imapStore.hasCapability(CAP_QRESYNC);
        return new ImapCapabilities(condstoreCap || qresyncCap, qresyncCap);
    }

    /**
     * CONDSTORE (RFC 7162 §3) — enables {@code UID FETCH ... CHANGEDSINCE
     * <modseq>}. QRESYNC implies CONDSTORE per the spec.
     */
    public boolean hasCondstore() {
        return condstore;
    }

    /**
     * QRESYNC (RFC 7162 §3.2) — builds on CONDSTORE and additionally allows
     * {@code SELECT folder (QRESYNC (uidvalidity modseq))} with an untagged
     * VANISHED response instead of UID enumeration to detect deleted messages.
     */
    public boolean hasQresync() {
        return qresync;
    }

    @Override
    public String toString() {
        return "ImapCapabilities{condstore=" + condstore + ", qresync=" + qresync + "}";
    }
}
