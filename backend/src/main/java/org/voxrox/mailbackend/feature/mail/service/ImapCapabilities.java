package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.MessagingException;
import jakarta.mail.Store;

import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * Detects the IMAP extensions that an efficient sync depends on (RFC 7162).
 * <p>
 * What a given server advertises is read at runtime, per connection, and is not
 * written down here. A table used to be, dated "observed in the field
 * 2026-05-19" and listing CONDSTORE + QRESYNC for all four providers; when it
 * was finally measured on 2026-09-09 three of its four rows were wrong (Gmail
 * advertises CONDSTORE but not QRESYNC, Exchange Online neither, seznam.cz
 * neither), and nothing had failed in the meantime to say so. A hand-kept list
 * of someone else's capabilities has no mechanism that can notice it rotting,
 * so this one is not kept.
 * <p>
 * To find out what a server actually advertises, run a sync cycle with
 * {@code LOGGING_LEVEL_ORG_VOXROX_MAILBACKEND=DEBUG} and read the per-folder
 * line from {@code MailSyncService} ("Folder ... flag sync capabilities"), or
 * the line from {@code ImapFolderExecutor} naming why a folder was opened
 * plainly. Those two are the answer; anything written in prose is a memory of
 * one.
 * <p>
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
     * <p>
     * Gates the resynchronized open in
     * {@link ImapFolderExecutor#executeReadOnlyResynced}: asking a server that does
     * not advertise QRESYNC for it is a protocol error, not a graceful degradation.
     */
    public boolean hasQresync() {
        return qresync;
    }

    @Override
    public String toString() {
        return "ImapCapabilities{condstore=" + condstore + ", qresync=" + qresync + "}";
    }
}
