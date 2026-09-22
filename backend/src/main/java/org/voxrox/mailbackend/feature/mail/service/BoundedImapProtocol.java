package org.voxrox.mailbackend.feature.mail.service;

import java.io.IOException;
import java.util.Properties;

import org.eclipse.angus.mail.iap.ByteArray;
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
 * A fourth size is stated lower down, and needs a second hook. A literal
 * announces its length as {@code {n}} at the end of a line, and
 * {@code ResponseInputStream.readResponse} grows the buffer to hold {@code n}
 * before the literal's first byte arrives — before the response exists as an
 * object {@link #refusal} could look at (B1-7). Its one allocation point is
 * {@code ByteArray.grow}, on the buffer {@link #getResponseBuffer()} hands
 * Angus, so the buffer this class hands over is a {@link BoundedByteArray} that
 * refuses to grow past {@link #MAX_RESPONSE_BYTES}.
 * <p>
 * A refusal is an {@link IOException} on purpose. Angus ends a command that
 * fails to read a response with a synthetic BYE, so the command fails with a
 * {@code ConnectionException}, the folder or store closes, and the sync records
 * the failure like any dropped connection. A {@link ProtocolException} would
 * not do: {@code Protocol.command} logs and <em>skips</em> a response that
 * fails that way, which here would quietly drop an EXISTS and leave Angus
 * counting a folder wrongly.
 * <p>
 * The check keeps no state of its own, and it must not start to. The superclass
 * constructor reads the server greeting through {@link #readResponse()}, which
 * runs before any field of this class is initialized.
 * {@link #isEnabled(String)} is safe there: Angus null-checks its set.
 */
final class BoundedImapProtocol extends IMAPProtocol {

    private static final Logger log = LoggerFactory.getLogger(BoundedImapProtocol.class);

    /**
     * Messages in one folder. Priced per connection, since that is what a hostile
     * server gets to size, with compressed pointers:
     * <ul>
     * <li>The SELECT's count sizes the message cache, 4 bytes a message: 8 MB
     * here.</li>
     * <li>A later EXISTS on the open folder costs up to three times that.
     * {@code IMAPFolder.handleResponse} allocates a {@code Message[]} for the new
     * messages whether or not anyone listens, the cache grows its array, and a
     * cache that has seen an EXPUNGE grows its sequence-number array too: 24 MB
     * when a folder opened empty is then told it holds the full count.</li>
     * </ul>
     * An account opens several such connections at once. Its two Stores open one
     * per open folder: a move's source and destination on one, a body fetch on the
     * other. Three at 24 MB is 72 MB, under a fifth of the packaged 384 MB heap.
     * That is also less than the sync itself allocates for an honest folder this
     * size on a CONDSTORE server. A folder wider than
     * {@link #MAX_EARLIER_VANISHED_UIDS} is not resynchronized, so its deletions
     * are found by listing every UID on the server into a set. The bound still
     * leaves twice the size at which the sync stops using QRESYNC.
     */
    static final int MAX_MESSAGES = 2_000_000;

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

    /**
     * Bytes one response may occupy, which is what a literal's declared size buys a
     * hostile server (B1-7). Measured against Angus 2.0.5 on the packaged 384 MB
     * heap: {@code {300000000}} followed by nothing allocates 300,000,042 bytes and
     * holds them until the read timeout, and {@code {2000000000}} ends in an
     * {@code OutOfMemoryError}.
     * <p>
     * 32 MiB is far above any literal this client asks for and far below what hurts
     * it. Bodies are fetched in partial fetches — {@code partialfetch} is pinned on
     * in {@code ImapConnectionManager} and Angus's {@code fetchsize} default is 16
     * KiB — so a body literal is three orders of magnitude under the bound,
     * whatever the message weighs; the largest single literal left is a header
     * block or an envelope string. The headroom is for a server that ignores the
     * partial request and answers with the whole part: that stays readable up to 32
     * MiB, above which {@code MimePartExtractor}'s own 8 MiB cap would have served
     * the "message too large" placeholder anyway.
     */
    static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

    /** What Angus starts a response buffer at when it is handed none. */
    private static final int INITIAL_RESPONSE_BYTES = 128;

    BoundedImapProtocol(String name, String host, int port, Properties props, boolean isSSL, MailLogger logger)
            throws IOException, ProtocolException {
        super(name, host, port, props, isSSL, logger);
    }

    @Override
    public Response readResponse() throws IOException, ProtocolException {
        Response response;
        try {
            response = super.readResponse();
        } catch (RuntimeException e) {
            /*
             * A response Angus cannot read leaves the connection mid-response, with the
             * bytes of whatever it was reading still to come, so it must not go back to the
             * pool: the next command would parse the remainder as its own reply. An
             * unchecked exception would leave it there, because ImapFolderExecutor turns
             * one into a MailOperationException and closes only the folder.
             *
             * The refusal from BoundedByteArray arrives this way, and so do the two shapes
             * a literal takes at the top of the int range, both measured: a declared size
             * in 2147483603..2147483631 overflows the array length inside grow
             * (NegativeArraySizeException), and 2147483632 and up overflow the
             * "does it fit" test so Angus skips the grow and reads past the buffer
             * (IndexOutOfBoundsException). Neither allocates anything, which is why the
             * bound alone does not cover them. A genuine parser fault lands here too and is
             * handled the same way on purpose — the connection's state is unknown either
             * way — with the cause kept for the log.
             */
            log.warn("{} Could not read a response from IMAP server {} ({}). Closing the connection.", LogCategory.IMAP,
                    host, e.toString());
            throw new ImplausibleResponseException("the response could not be read (" + e + ")", e);
        }
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
     * The buffer Angus reads the next response into: the one it was given for a
     * body fetch, or a fresh one of the size Angus would have made itself, in
     * either case bounded. Angus reads only {@code getBytes()} and
     * {@code getCount()} back off it, so wrapping the caller's buffer keeps its
     * backing array in use and changes nothing else.
     * <p>
     * It must be non-null: {@code ResponseInputStream.readResponse} makes a plain
     * {@code ByteArray} of its own for a null, which no bound would reach.
     */
    @Override
    protected ByteArray getResponseBuffer() {
        ByteArray reused = super.getResponseBuffer();
        return reused == null
                ? new BoundedByteArray(new byte[INITIAL_RESPONSE_BYTES], 0, INITIAL_RESPONSE_BYTES)
                : new BoundedByteArray(reused.getBytes(), reused.getStart(), reused.getCount());
    }

    /**
     * A response buffer that will not grow past {@link #MAX_RESPONSE_BYTES}. Both
     * of Angus's growth paths run through here: the doubling that reads a long
     * line, and the one jump to a literal's declared size, which is the one a
     * server chooses.
     */
    static final class BoundedByteArray extends ByteArray {

        BoundedByteArray(byte[] bytes, int start, int count) {
            super(bytes, start, count);
        }

        @Override
        public void grow(int increment) {
            int current = getBytes().length;
            // Subtraction rather than current + increment, which overflows for exactly
            // the increments this refuses.
            if (increment < 0 || current > MAX_RESPONSE_BYTES - increment) {
                throw new OversizedResponseException(current, increment);
            }
            super.grow(increment);
        }
    }

    /**
     * Thrown from {@link BoundedByteArray#grow}, which cannot declare a checked
     * exception. {@link #readResponse()} turns it into the connection-closing
     * {@link ImplausibleResponseException}; it must not escape this class.
     */
    static final class OversizedResponseException extends RuntimeException {

        OversizedResponseException(int current, int increment) {
            super("a response asking to grow " + current + " bytes by " + increment + " past " + MAX_RESPONSE_BYTES);
        }
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

        ImplausibleResponseException(String reason, Throwable cause) {
            super("Refused an implausible IMAP response: " + reason, cause);
        }
    }
}
