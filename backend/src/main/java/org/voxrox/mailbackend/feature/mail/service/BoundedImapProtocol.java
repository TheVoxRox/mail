package org.voxrox.mailbackend.feature.mail.service;

import java.io.IOException;
import java.util.Properties;

import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.eclipse.angus.mail.imap.protocol.IMAPResponse;
import org.eclipse.angus.mail.imap.protocol.UIDSet;
import org.eclipse.angus.mail.util.MailLogger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Angus's IMAP connection with one check in front of it: a server response that
 * states a size Angus will allocate for is held against a bound before Angus
 * sees it, and one past the bound ends the connection instead (IMAP/SMTP audit
 * B1-3). Every store the backend opens talks through this class; see
 * {@link BoundedImapStore}.
 * <p>
 * Angus trusts three such sizes, all of them before any code of ours runs, and
 * each is one short line from a hostile server:
 * <ul>
 * <li>{@code * n EXISTS} — the SELECT's message count sizes the folder's
 * message cache, one reference per message.</li>
 * <li>{@code * VANISHED (EARLIER) set} — {@code IMAPFolder.open} expands the
 * set into one {@code long} per UID. It does so on every open, not just the
 * QRESYNC one the sync asks for: Angus collects VANISHED from any SELECT or
 * EXAMINE reply.</li>
 * <li>{@code * VANISHED set} — {@code IMAPFolder.handleResponse} expands it the
 * same way, plus a message object per UID, whenever a folder is open, and does
 * not check that QRESYNC was ever enabled.</li>
 * </ul>
 * {@link #readResponse()} is the one point between the wire and those
 * allocations: Angus parses a response only after reading it in full through
 * here, so the check sees each response before anything is sized from it.
 * <p>
 * A refusal is an {@link IOException} on purpose. Angus ends a command that
 * fails to read a response with a synthetic BYE, so the command fails with a
 * {@code ConnectionException}, the folder or store closes, and the sync records
 * the failure like any dropped connection. A {@link ProtocolException} would
 * not do: {@code Protocol.command} logs and <em>skips</em> a response that
 * fails that way, which here would quietly drop an EXISTS and leave Angus
 * counting a folder wrongly.
 * <p>
 * What this does not bound: the size a <em>literal</em> declares.
 * {@code ResponseInputStream} grows its buffer for a {@code {n}} before the
 * response exists as an object this class could look at — a separate finding
 * (B1-7) with a separate fix.
 * <p>
 * The check keeps no state of its own, and it must not start to. The superclass
 * constructor reads the server greeting through {@link #readResponse()}, which
 * runs before any field of this class is initialized.
 * {@link #isEnabled(String)} is safe there: Angus null-checks its set.
 */
final class BoundedImapProtocol extends IMAPProtocol {

    private static final Logger log = LoggerFactory.getLogger(BoundedImapProtocol.class);

    /**
     * Messages in one folder. The message cache holds a reference per message, so
     * this is 40 MB of references with compressed pointers, a tenth of the packaged
     * 384 MB heap, and an order of magnitude past any mailbox a desktop client
     * opens.
     */
    static final int MAX_MESSAGES = 10_000_000;

    /**
     * UIDs in one {@code VANISHED (EARLIER)}: 8 MB of {@code long}. The sync asks
     * for QRESYNC only over a known-UID range narrower than this
     * ({@code MailSyncService.buildResyncRequest}), so a server that keeps to that
     * range — RFC 7162 §3.2.5.1 says it "should" — never reaches it.
     */
    static final long MAX_EARLIER_VANISHED_UIDS = 1_000_000;

    /**
     * UIDs in one untagged {@code VANISHED}, which costs a message object per UID
     * on top of the {@code long}. A server sends one for messages expunged while
     * the folder is selected, so a real one is bounded by the folder, and a sync
     * keeps a folder selected for seconds. A larger one costs a retried cycle, not
     * the folder: the reconnected QRESYNC SELECT reports the same expunges as
     * {@code VANISHED (EARLIER)}, under the bound above.
     */
    static final long MAX_LIVE_VANISHED_UIDS = 100_000;

    BoundedImapProtocol(String name, String host, int port, Properties props, boolean isSSL, MailLogger logger)
            throws IOException, ProtocolException {
        super(name, host, port, props, isSSL, logger);
    }

    @Override
    public Response readResponse() throws IOException, ProtocolException {
        Response response = super.readResponse();
        if (response instanceof IMAPResponse imapResponse) {
            String refusal = refusal(imapResponse, isEnabled("QRESYNC"));
            if (refusal != null) {
                log.warn("{} Refused an implausible response from IMAP server {}: {}. Closing the connection.",
                        LogCategory.IMAP, host, refusal);
                throw new ImplausibleResponseException(refusal);
            }
        }
        return response;
    }

    /**
     * Why the response must not reach Angus, or {@code null} when it may. Reads a
     * copy, so the response Angus goes on to parse is untouched.
     */
    static @Nullable String refusal(IMAPResponse response, boolean qresyncEnabled) {
        if (response.keyEquals("EXISTS")) {
            int count = response.getNumber();
            return count < 0 || count > MAX_MESSAGES ? "EXISTS " + count + " is outside 0.." + MAX_MESSAGES : null;
        }
        if (!response.keyEquals("VANISHED")) {
            return null;
        }
        if (!qresyncEnabled) {
            // RFC 7162 §3.2.10: the EARLIER form answers a QRESYNC SELECT or UID FETCH
            // (VANISHED), the other replaces EXPUNGE once QRESYNC is enabled. A client
            // that never enabled it has asked for neither.
            return "VANISHED on a connection that never enabled QRESYNC";
        }
        IMAPResponse copy = new IMAPResponse(response);
        boolean earlier;
        long bound;
        long count;
        try {
            // The two shapes Angus acts on: "(EARLIER) set" in a SELECT reply, a bare
            // set anywhere else. Any other parenthesized list gets the larger bound.
            earlier = copy.readAtomStringList() != null;
            bound = earlier ? MAX_EARLIER_VANISHED_UIDS : MAX_LIVE_VANISHED_UIDS;
            count = uidCount(copy.readAtom(), bound);
        } catch (RuntimeException e) {
            // Angus's reader runs off the end of a truncated line ("* VANISHED" and
            // nothing else), as it would on the original once Angus parsed it.
            return "VANISHED that cannot be parsed (" + e.getClass().getSimpleName() + ")";
        }
        if (count < 0) {
            return "VANISHED with a malformed UID set";
        }
        return count > bound
                ? "VANISHED " + (earlier ? "(EARLIER) " : "") + "names more than " + bound + " UIDs"
                : null;
    }

    /**
     * How many UIDs Angus would expand the set into, read with Angus's own parser
     * so the two cannot disagree; {@code bound + 1} once it passes the bound, and
     * -1 for a set Angus would size wrongly. A descending range is that case: Angus
     * counts it negative, then writes nothing for it, which under-sizes the array
     * for the ranges around it.
     */
    private static long uidCount(@Nullable String uids, long bound) {
        UIDSet[] sets = UIDSet.parseUIDSets(uids);
        if (sets == null || sets.length == 0) {
            return -1;
        }
        long count = 0;
        for (UIDSet set : sets) {
            if (set.start < 1 || set.end < set.start) {
                return -1;
            }
            long size = set.end - set.start + 1;
            if (size > bound - count) {
                return bound + 1;
            }
            count += size;
        }
        return count;
    }

    /**
     * A response refused by {@link #refusal}; see the class for why it is an
     * IOException.
     */
    static final class ImplausibleResponseException extends IOException {
        ImplausibleResponseException(String reason) {
            super("Refused an implausible IMAP response: " + reason);
        }
    }
}
