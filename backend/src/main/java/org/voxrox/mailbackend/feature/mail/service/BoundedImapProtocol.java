package org.voxrox.mailbackend.feature.mail.service;

import java.io.IOException;
import java.util.Properties;

import org.eclipse.angus.mail.iap.Argument;
import org.eclipse.angus.mail.iap.ByteArray;
import org.eclipse.angus.mail.iap.Protocol;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.protocol.FetchResponse;
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
 * One more thing a response states is not a size but a depth. Angus parses a
 * FETCH's {@code BODYSTRUCTURE} by calling itself once per level of nesting,
 * and enough levels overflow the stack of the thread reading it (B1-10). The
 * {@link StackOverflowError} is an {@code Error}, which nothing between here
 * and Spring's {@code @Async} interceptor catches, and that one only logs it,
 * so the sync pass ended unrecorded on every cycle. Catching it is not the fix:
 * an overflow can land inside a class initializer and leave that class unusable
 * for the life of the process. So {@link #readResponse()} measures how deeply a
 * FETCH nests after reading it and before Angus parses it, and drops one past
 * {@link #MAX_NESTING}.
 * <p>
 * A refusal of a size is an {@link IOException} on purpose. Angus ends a
 * command that fails to read a response with a synthetic BYE, so the command
 * fails with a {@code ConnectionException}, the folder or store closes, and the
 * sync records the failure like any dropped connection. A
 * {@link ProtocolException} would not do: {@code Protocol.command} logs and
 * <em>skips</em> a response that fails that way, which here would quietly drop
 * an EXISTS and leave Angus counting a folder wrongly.
 * <p>
 * A FETCH nested too deeply is the one response dropped that way, and on
 * purpose too. It has been read in full, so the connection is in step. It
 * describes one message, whose structure a sender may have chosen rather than
 * the server, and closing the connection over it would stop the folder's sync
 * at that message on every cycle, since the sync downloads the newest mail
 * first. Dropped, it leaves Angus without that message's items: it loads the
 * envelope and flags with FETCHes of their own, and loading the structure
 * fails, which the sync already keeps as an envelope-only stub.
 * <p>
 * The bounds above are per response; {@link #command} adds one per command
 * (B1-8), because Angus holds all of a command's responses until the tagged
 * one, and refuses the response that overspends it the same way.
 * <p>
 * The superclass constructor reads the server greeting through
 * {@link #readResponse()}, which runs before any field of this class is
 * initialized. So the per-response checks keep no state, and the one field the
 * budget needs has no initializer — null is what that first read must see.
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

    /**
     * Levels of parentheses one FETCH response may nest, which bounds the recursion
     * of Angus's {@code BODYSTRUCTURE} parser (B1-10). A structure spends one level
     * per MIME level and a few around them — the FETCH list, the structure's own
     * pair, a parameter list, a disposition — so 256 admits MIME nested some 250
     * deep, where {@code MimePartExtractor} walks 20 and Postfix by default accepts
     * 100. The audit measured Angus 2.0.5 at 3,000 levels parsing and 5,000
     * overflowing on a test thread's stack, so the bound leaves an order of
     * magnitude for a thread that is already deep, or small.
     */
    static final int MAX_NESTING = 256;

    /**
     * What the responses of one command may add up to, as {@link CommandBudget}
     * charges them (B1-8). Every bound above holds one response, and
     * {@code Protocol.command} keeps every response of a command until the tagged
     * one arrives, so without this a server that repeats a response the bounds
     * admit — 60 {@code VANISHED (EARLIER)} lines held 499 MB at 1.16 — sums past
     * the heap.
     * <p>
     * Twice {@link #MAX_RESPONSE_BYTES}, so the one command that may legitimately
     * carry a response that large still fits, with the same room again for the
     * responses around it. The client's own commands whose answers grow with the
     * folder do not go through {@code Protocol.command} and are not charged:
     * {@code ImapCondstoreCommands} reads the UID listing and the
     * {@code CHANGEDSINCE} flags one response at a time. What is left is small per
     * message and batched (envelopes, bodies in 16 KiB partial fetches), or a
     * SELECT's report of what changed since the last cycle; a QRESYNC SELECT that a
     * mass flag change pushes past the budget is refused and
     * {@code ImapFolderExecutor} opens the folder again without resynchronization.
     */
    static final long MAX_COMMAND_BYTES = 2L * MAX_RESPONSE_BYTES;

    /**
     * What each response costs {@link CommandBudget} beyond its bytes on the wire:
     * the objects Angus parses it into. Measured at 1.16 for the smallest response
     * a folder answers per message, {@code * n FETCH (UID u)}: 308 bytes retained
     * for some 25 on the wire. Without the charge a server could send a few million
     * short lines that are cheap on the wire and not in the heap.
     */
    static final int RESPONSE_OVERHEAD_BYTES = 280;

    /**
     * UIDs the VANISHED responses of one command may name together (B1-8). Each is
     * bounded on its own ({@link #MAX_EARLIER_VANISHED_UIDS}), and
     * {@code IMAPFolder.open} expands all of them into {@code long[]}s it holds
     * until it returns. As many as a folder may hold messages
     * ({@link #MAX_MESSAGES}): 16 MB of UIDs, and more than an honest server can
     * report as vanished from a local range the resync asks about, which
     * {@code MailSyncService.buildResyncRequest} keeps under
     * {@link #MAX_EARLIER_VANISHED_UIDS}.
     */
    static final long MAX_COMMAND_VANISHED_UIDS = MAX_MESSAGES;

    /** What Angus starts a response buffer at when it is handed none. */
    private static final int INITIAL_RESPONSE_BYTES = 128;

    /*
     * The budget of the command in progress, null outside Protocol.command. No
     * initializer: the constructor reads the greeting through readResponse before
     * any field of this class is initialized, and an initializer would run after
     * that read. Volatile because Angus does not promise that the thread reading a
     * response is the one that sent the command, though today it always is.
     */
    private volatile @Nullable CommandBudget budget;

    BoundedImapProtocol(String name, String host, int port, Properties props, boolean isSSL, MailLogger logger)
            throws IOException, ProtocolException {
        super(name, host, port, props, isSSL, logger);
    }

    /**
     * {@code Protocol.command} under a {@link CommandBudget}: the one place Angus
     * collects a command's responses before anyone acts on them, so the one place
     * their sum has to be bounded. A command read another way — Angus's
     * authentication, or {@code ImapCondstoreCommands} reading responses one at a
     * time — holds only the response in hand and is not charged.
     */
    @Override
    public synchronized Response[] command(String command, @Nullable Argument args) {
        budget = new CommandBudget();
        try {
            return super.command(command, args);
        } finally {
            budget = null;
        }
    }

    @Override
    public Response readResponse() throws IOException, ProtocolException {
        Read read;
        try {
            read = readNestingChecked();
        } catch (RuntimeException e) {
            /*
             * A response Angus cannot read leaves the connection mid-response, with the
             * bytes of whatever it was reading still to come, so it must not go back to the
             * pool: the next command would parse the remainder as its own reply. An
             * unchecked exception would leave it there, because ImapFolderExecutor turns
             * one into a MailOperationException and closes only the folder.
             *
             * The refusal from BoundedByteArray arrives this way, and so does the one shape
             * of a literal at the top of the int range that gets past it, both measured: a
             * declared size in 2147483603..2147483631 would overflow the array length
             * inside grow, which the bounded buffer refuses first, and 2147483632 and up
             * overflow the "does it fit" test, so Angus skips the grow and reads past the
             * buffer (IndexOutOfBoundsException) having allocated nothing. A genuine parser
             * fault lands here too and is handled the same way on purpose — the
             * connection's state is unknown either way — with the cause kept for the log.
             * An Error is not caught: the one a parse of hostile input used to raise, a
             * StackOverflowError, is prevented before the parse instead, by dropping a
             * FETCH nested past MAX_NESTING unparsed.
             */
            log.warn("{} Could not read a response from IMAP server {} ({}). Closing the connection.", LogCategory.IMAP,
                    host, e.toString());
            throw new ImplausibleResponseException("the response could not be read (" + e + ")", e);
        }
        Response response = read.response();
        if (response instanceof IMAPResponse imapResponse) {
            String refusal = refusal(imapResponse, isEnabled("QRESYNC"));
            if (refusal != null) {
                throw refused(refusal);
            }
        }
        CommandBudget current = budget;
        if (current != null && !response.isTagged()) {
            long vanished = response instanceof IMAPResponse imapResponse && imapResponse.keyEquals("VANISHED")
                    ? vanishedUids(imapResponse)
                    : 0;
            String overspent = current.charge(read.wireSize(), vanished);
            if (overspent != null) {
                throw refused(overspent);
            }
        }
        return response;
    }

    /**
     * {@code IMAPProtocol.readResponse} with the nesting check between its two
     * steps. Angus's own reads the whole response and then, in the same call,
     * parses a FETCH's items, which is where a {@code BODYSTRUCTURE} is parsed; the
     * check has to come between the two. Both steps are Angus's own constructors,
     * called the way {@code IMAPProtocol.readResponse} calls them in 2.0.5 — a
     * FETCH is the only response it parses further, and so the only one measured. A
     * later Angus may read responses differently, so
     * {@code BoundedImapProtocolTest} pins the version.
     */
    private Read readNestingChecked() throws IOException, ProtocolException {
        NestingCheckedResponse response = new NestingCheckedResponse(this);
        if (!response.keyEquals("FETCH")) {
            return new Read(response, response.wireSize());
        }
        if (response.nestsDeeperThan(MAX_NESTING)) {
            log.warn(
                    "{} Dropped a FETCH response from IMAP server {} nested more than {} levels deep; "
                            + "the message it describes is left without its structure.",
                    LogCategory.IMAP, host, MAX_NESTING);
            throw new NestedTooDeepException();
        }
        return new Read(new FetchResponse(response, getFetchItems(), this), response.wireSize());
    }

    /**
     * A response as Angus parsed it, and its length on the wire, which the budget
     * charges.
     */
    private record Read(Response response, int wireSize) {
    }

    private ImplausibleResponseException refused(String reason) {
        log.warn("{} Refused an implausible response from IMAP server {}: {}. Closing the connection.",
                LogCategory.IMAP, host, reason);
        return new ImplausibleResponseException(reason);
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
     * A response as Angus reads it, before a FETCH's items are parsed, with the one
     * question {@link #readNestingChecked()} asks of it. A subclass because the
     * bytes are Angus's protected fields: this reads them in place, where
     * {@code toString()} would copy up to {@link #MAX_RESPONSE_BYTES} of them.
     */
    static final class NestingCheckedResponse extends IMAPResponse {

        NestingCheckedResponse(Protocol protocol) throws IOException, ProtocolException {
            super(protocol);
        }

        NestingCheckedResponse(String line) throws IOException, ProtocolException {
            super(line);
        }

        /** The response's length as read, literals included. */
        int wireSize() {
            return size;
        }

        /**
         * Whether the response's parentheses nest more than {@code bound} levels deep
         * anywhere, counted as Angus's parser meets them: a quoted string and a literal
         * are data, so what they hold does not count. Stops at the first level past the
         * bound, and never recurses itself.
         */
        boolean nestsDeeperThan(int bound) {
            int depth = 0;
            for (int i = 0; i < size; i++) {
                switch (buffer[i]) {
                    case '"' -> i = closingQuote(i);
                    case '{' -> i = endOfLiteral(i);
                    case '(' -> {
                        if (++depth > bound) {
                            return true;
                        }
                    }
                    case ')' -> depth = Math.max(0, depth - 1);
                    default -> {
                        // Anything else is inside an atom or between tokens.
                    }
                }
            }
            return false;
        }

        /**
         * Index of the quote that closes the string opening at {@code open}, skipping
         * an escaped quote; the end of the response when nothing closes it.
         */
        private int closingQuote(int open) {
            for (int i = open + 1; i < size; i++) {
                if (buffer[i] == '\\') {
                    i++;
                } else if (buffer[i] == '"') {
                    return i;
                }
            }
            return size;
        }

        /**
         * Index of the last byte of the literal {@code open} starts — {@code {n}}
         * ending its line, then {@code n} bytes of data — or {@code open} itself when
         * the brace starts no literal. The buffer already holds the whole literal:
         * Angus reads it in before the response exists.
         */
        private int endOfLiteral(int open) {
            int i = open + 1;
            long length = 0;
            while (i < size && buffer[i] >= '0' && buffer[i] <= '9' && length <= size) {
                length = length * 10 + (buffer[i] - '0');
                i++;
            }
            boolean literal = i > open + 1 && length <= size && i + 2 < size && buffer[i] == '}'
                    && buffer[i + 1] == '\r' && buffer[i + 2] == '\n';
            return literal ? (int) Math.min(size, i + 2 + length) : open;
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
     * How many UIDs a VANISHED response names, read the way {@link #refusal} reads
     * it — so a response it let through parses here too — and counted only up to
     * one past {@link #MAX_COMMAND_VANISHED_UIDS}, which is all the budget needs.
     */
    static long vanishedUids(IMAPResponse response) {
        IMAPResponse copy = new IMAPResponse(response);
        try {
            copy.readAtomStringList();
            return Math.max(0, uidCount(copy.readAtom(), MAX_COMMAND_VANISHED_UIDS));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * What one command's responses have cost so far (B1-8): each response its bytes
     * on the wire plus {@link #RESPONSE_OVERHEAD_BYTES}, and the UIDs its VANISHED
     * names. A new one per {@code Protocol.command}, so nothing carries over from
     * one command into the next.
     */
    static final class CommandBudget {

        private long bytes;
        private long vanishedUids;

        /** Charges one untagged response: the reason to refuse it, or null. */
        @Nullable
        String charge(int wireBytes, long vanished) {
            bytes += wireBytes + (long) RESPONSE_OVERHEAD_BYTES;
            vanishedUids += vanished;
            if (bytes > MAX_COMMAND_BYTES) {
                return "one command's responses passed " + MAX_COMMAND_BYTES + " bytes";
            }
            if (vanishedUids > MAX_COMMAND_VANISHED_UIDS) {
                return "one command's VANISHED responses named more than " + MAX_COMMAND_VANISHED_UIDS + " UIDs";
            }
            return null;
        }
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

    /**
     * A FETCH nested past {@link #MAX_NESTING}, dropped unparsed; see the class for
     * why it is a ProtocolException.
     */
    static final class NestedTooDeepException extends ProtocolException {
        NestedTooDeepException() {
            super("Dropped an IMAP FETCH response nested more than " + MAX_NESTING + " levels deep");
        }
    }
}
