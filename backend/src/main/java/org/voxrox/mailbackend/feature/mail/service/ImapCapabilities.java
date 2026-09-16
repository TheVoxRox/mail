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
 * the line from {@code ImapFolderExecutor} naming how a folder was opened and
 * why not with more. Those two are the answer; anything written in prose is a
 * memory of one.
 * <p>
 * For unknown / legacy servers (rare custom Cyrus pre-2010, rare corporate
 * IMAPs) the fallback path uses a full UID sweep.
 */
public final class ImapCapabilities {

    private static final String CAP_CONDSTORE = "CONDSTORE";
    private static final String CAP_QRESYNC = "QRESYNC";
    private static final String CAP_ENABLE = "ENABLE";

    private final boolean condstore;
    private final boolean qresync;
    private final boolean condstoreSelect;

    private ImapCapabilities(boolean condstore, boolean qresync, boolean condstoreSelect) {
        this.condstore = condstore;
        this.qresync = qresync;
        this.condstoreSelect = condstoreSelect;
    }

    public static ImapCapabilities probe(Store store) throws MessagingException {
        if (!(store instanceof IMAPStore imapStore)) {
            return new ImapCapabilities(false, false, false);
        }
        boolean condstoreCap = imapStore.hasCapability(CAP_CONDSTORE);
        boolean qresyncCap = imapStore.hasCapability(CAP_QRESYNC);
        boolean condstoreSelect = condstoreCap && imapStore.hasCapability(CAP_ENABLE);
        return new ImapCapabilities(condstoreCap || qresyncCap, qresyncCap, condstoreSelect);
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

    /**
     * Whether the sync open can ask for CONDSTORE in the SELECT itself
     * ({@code EXAMINE folder (CONDSTORE)}, RFC 7162 §3.1.8), which is what makes
     * the server report HIGHESTMODSEQ in its response.
     * <p>
     * Narrower than {@link #hasCondstore()} on two counts, both Angus's rather than
     * the RFC's. Angus sends {@code ENABLE CONDSTORE} before such a SELECT and
     * refuses to when the server does not advertise ENABLE (RFC 5161); and it
     * checks for CONDSTORE by name, so a server that advertises only QRESYNC does
     * not qualify. Either refusal is a protocol error that makes Angus log the
     * connection out, which is too expensive a way to find out every cycle.
     */
    public boolean canSelectWithCondstore() {
        return condstoreSelect;
    }

    @Override
    public String toString() {
        return "ImapCapabilities{condstore=" + condstore + ", qresync=" + qresync + ", condstoreSelect="
                + condstoreSelect + "}";
    }
}
