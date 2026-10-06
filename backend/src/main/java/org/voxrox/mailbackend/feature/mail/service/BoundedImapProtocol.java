package org.voxrox.mailbackend.feature.mail.service;

import java.io.IOException;
import java.util.Properties;
import java.util.regex.Pattern;

import org.eclipse.angus.mail.iap.Argument;
import org.eclipse.angus.mail.iap.ByteArray;
import org.eclipse.angus.mail.iap.Protocol;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.protocol.BODY;
import org.eclipse.angus.mail.imap.protocol.FetchItem;
import org.eclipse.angus.mail.imap.protocol.FetchResponse;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.eclipse.angus.mail.imap.protocol.IMAPResponse;
import org.eclipse.angus.mail.imap.protocol.RFC822DATA;
import org.eclipse.angus.mail.imap.protocol.UIDSet;
import org.eclipse.angus.mail.util.MailLogger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voxrox.mailbackend.exception.MailFailureCause;
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
 * and an {@code ENVELOPE}'s address groups the same way, and enough levels
 * overflow the stack of the thread reading it (B1-10). The
 * {@link StackOverflowError} is an {@code Error}, which nothing between here
 * and Spring's {@code @Async} interceptor catches, and that one only logs it,
 * so the sync pass ended unrecorded on every cycle. Catching it is not the fix:
 * an overflow can land inside a class initializer and leave that class unusable
 * for the life of the process. So {@link #readResponse()} has Angus parse a
 * FETCH through {@link DepthBoundedFetchResponse}, which counts the levels
 * Angus's own parser opens as it opens them and stops the parse past
 * {@link #MAX_NESTING}, and drops that response.
 * <p>
 * A refusal of a size is an {@link IOException} on purpose. Angus ends a
 * command that fails to read a response with a synthetic BYE, so the command
 * fails with a {@code ConnectionException}, the folder or store closes, and the
 * sync records the failure like any dropped connection. A
 * {@link ProtocolException} would not do: {@code Protocol.command} logs and
 * <em>skips</em> a response that fails that way, which here would quietly drop
 * an EXISTS and leave Angus counting a folder wrongly.
 * <p>
 * A FETCH nested too deeply is the one response dropped that way, with one
 * whose parse stops consuming it (B1-15), and on purpose too. It has been read
 * in full, so the connection is in step. It describes one message, whose
 * structure a sender may have chosen rather than the server, and closing the
 * connection over it would stop the folder's sync at that message on every
 * cycle, since the sync downloads the newest mail first. Dropped, it leaves
 * Angus without that message's items: it loads the envelope and flags with
 * FETCHes of their own, and loading the structure fails, which the sync already
 * keeps as an envelope-only stub.
 * <p>
 * The bounds above are per response; {@link #readResponse()} adds one per
 * command (B1-8), because Angus holds all of a command's responses until the
 * tagged one, and refuses the response that overspends it the same way. And two
 * per selected folder: on what is spent in time rather than memory, since each
 * EXPUNGE costs Angus a pass over the folder, however short its line (B1-13),
 * and on what the folder keeps of the FETCH responses it is handed, which
 * outlives the command that brought it (B1-14).
 * <p>
 * The superclass constructor reads the server greeting through
 * {@link #readResponse()}, which runs before any field of this class is
 * initialized. So the per-response checks keep no state, and the fields the
 * budgets need have no initializer — null is what that first read must see.
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
     * Levels Angus's parse of one FETCH response may open, which bounds the
     * recursion of its {@code BODYSTRUCTURE} and address-group parsers (B1-10), as
     * {@link DepthBoundedFetchResponse} counts them: the lists open at once, plus
     * every address that starts or ends a group. A structure spends one level per
     * MIME level and a few around them — the FETCH list, the structure's own pair,
     * a parameter list, a disposition — so 256 admits MIME nested some 250 deep,
     * where {@code MimePartExtractor} walks 20 and Postfix by default accepts 100;
     * and an envelope that names a hundred groups. The audit measured Angus 2.0.5
     * on a test thread's stack: a structure 3,000 levels deep parses and 5,000
     * overflow, and 3,000 nested address groups parse and 10,000 overflow. The
     * bound leaves an order of magnitude for a thread that is already deep, or
     * small.
     */
    static final int MAX_NESTING = 256;

    /**
     * Calls in a row Angus's parse of one FETCH may make through
     * {@link DepthBoundedFetchResponse}'s methods without consuming a byte, past
     * which the parse has stopped making progress and is dropped (B1-15). Angus
     * 2.0.5's {@code parseBodyExtension} loops on an element it cannot read, a bare
     * atom, calling {@code readString} and {@code isNextNonSpace} at the same index
     * for ever. An honest parse makes a few such calls in a row at most — an
     * {@code isNextNonSpace} that finds no {@code )} before the read that consumes
     * the next element — so 64 leaves a wide margin.
     */
    static final int MAX_STALLED_CALLS = 64;

    /**
     * What the responses of one command may add up to, as {@link CommandBudget}
     * charges them (B1-8). Every bound above holds one response, and Angus keeps
     * every response of a command until the tagged one arrives —
     * {@code Protocol.command} does, and so does its authentication, in a loop of
     * its own — so without this a server that repeats a response the bounds admit
     * (60 {@code VANISHED (EARLIER)} lines held 499 MB at 1.16) sums past the heap.
     * Every response is charged, tagged or not, from the command's
     * {@link #writeCommand} to the next.
     * <p>
     * Twice {@link #MAX_RESPONSE_BYTES}, so the one command that may legitimately
     * carry a response that large still fits, with the same room again for the
     * responses around it. The per-message answers of the client's own commands
     * that grow with the folder are not charged: {@code ImapCondstoreCommands}
     * reads the UID listing's and the {@code CHANGEDSINCE} flags' FETCH responses
     * one at a time, keeps what it needs, and bounds that itself; anything else
     * those commands bring is charged. What is left is small per message and
     * batched (envelopes, bodies in 16 KiB partial fetches), or a SELECT's report
     * of what changed since the last cycle; a QRESYNC SELECT that a mass flag
     * change pushes past the budget is refused and {@code ImapFolderExecutor} opens
     * the folder again without resynchronization.
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

    /**
     * What the EXPUNGEs of one selected folder may cost together, in the steps
     * {@link SelectionBudget} counts (B1-13). Angus's {@code MessageCache} handles
     * each EXPUNGE, and each UID of a live VANISHED it knows, with a pass over an
     * array as long as the folder, so a server repeating {@code * 1 EXPUNGE} spends
     * the sync thread's time rather than the heap: measured at 1.26, 0.45 ms an
     * EXPUNGE at 2,000,000 messages and linear in their number, where the command
     * budget alone admitted some 229,000 a command, command after command.
     * <p>
     * Just under a thousand EXPUNGEs in the largest folder {@link #MAX_MESSAGES}
     * admits, about half a second at that rate, or some 46,000 in a folder of
     * 20,000: each one lengthens the next pass, since Angus keeps the expunged
     * entry until the folder closes, so k of them in a folder of n cost
     * {@code k·n + k(k-1)/2} steps. This client expunges one message per folder it
     * opens; more come only from other clients while the folder is selected, and a
     * burst past the budget costs a retried cycle, not the folder: the reconnected
     * SELECT reports what is left in its EXISTS count, with no EXPUNGE to process.
     */
    static final long MAX_SELECTION_EXPUNGE_STEPS = 1_000L * MAX_MESSAGES;

    /**
     * What one selected folder may keep from the FETCH responses handed to it, as
     * {@link #keptBytes} estimates the heap it takes, unless
     * {@code mail.client.imap.open-folder-budget} says otherwise (B1-14). Angus
     * keeps what a FETCH gives a message — envelope, structure, flags, headers, a
     * UID-table entry — until the folder closes, whether or not it was asked for,
     * and every command to the open folder adds to it, so the command budget bounds
     * each of them and nothing bounded the sum.
     * <p>
     * An estimate, not a measurement of an honest pass, and configurable for that
     * reason. The sync opens a folder for one pass and the user's actions open it
     * for one action, so a selection is one of those. The largest honest one is a
     * pass that catches up {@code local-window-limit} (10,000) new messages: 88 MB
     * at the 8.8 KB an ordinary message is charged (envelope with five addresses, a
     * three-part structure, three threading headers), and on a server without
     * CONDSTORE some 1.5 KB more for each mirrored message, whose flags and UIDs
     * the pass reads through the folder — up to twice the window before the pruner
     * runs, 30 MB. Heavier messages pass the budget: the attempt is refused like
     * any implausible response, the connection closes, which releases what the
     * folder kept, the batches stored so far stay, newest first, and the sync's
     * next attempt opens the folder again and brings the rest down as holes.
     * <p>
     * The budget has to hold what a pass spends on the window it already mirrors,
     * plus one batch: below that, every attempt is refused at the same point and
     * the folder's sync stops there. At the defaults that is some 15 MB and 2 MB.
     * <p>
     * Its heap side, per connection: what a hostile server can make the folder keep
     * is the budget scaled by the worst ratio measured at 1.49 of what a response
     * leaves to what it is charged — 82 bytes an object against 96, and 268 bytes
     * against 403 for a response that makes Angus create a message — some 115 MB of
     * the packaged 384 MB heap.
     */
    static final long DEFAULT_OPEN_FOLDER_BUDGET = 128L * 1024 * 1024;

    /**
     * What {@link #keptBytes} charges a FETCH for each object its parse made
     * ({@link DepthBoundedFetchResponse#parsedObjects}), on top of its bytes.
     * Measured at 1.49 against Angus 2.0.5, as the heap retained by the items a
     * folder keeps less their bytes on the wire, per object: 82 for a bare UID, 73
     * for an ordinary message, 66 for a structure dense with parameters, 67 for a
     * capitalised flag (counted twice), 25 for an envelope dense with addresses.
     * Per byte on the wire the same items cost from 2.5 to 18 times their size, so
     * a charge by bytes alone would leave the budget one a server could exceed
     * eighteen-fold.
     */
    static final int PARSED_OBJECT_BYTES = 96;

    /**
     * The session property, under {@code mail.<protocol>.}, that carries
     * {@code mail.client.imap.open-folder-budget} to the protocol in bytes;
     * {@link BoundedImapStore#install} writes it.
     */
    static final String OPEN_FOLDER_BUDGET_PROPERTY = "voxrox.openfolderbudget";

    /** The body sections {@link #isContentSection} counts as content. */
    private static final Pattern CONTENT_SECTION = Pattern.compile("(?i)(\\d+(\\.\\d+)*)?(\\.?TEXT)?");

    /** What Angus starts a response buffer at when it is handed none. */
    private static final int INITIAL_RESPONSE_BYTES = 128;

    /*
     * The budget of the command in progress, started afresh by writeCommand (the
     * greeting, read before any command, gets one of its own), and whether the
     * command's FETCH responses are being read one at a time. No initializers: the
     * constructor reads the greeting through readResponse before any field of this
     * class is initialized, and an initializer would run after that read. Volatile
     * because Angus does not promise that the thread reading a response is the one
     * that sent the command, though today it always is.
     */
    private volatile @Nullable CommandBudget budget;
    private volatile boolean oneAtATime;
    /*
     * What the EXPUNGEs of the folder selected on this connection have cost,
     * started afresh by the SELECT or EXAMINE that selects it. Same initializer and
     * visibility reasoning as the two above.
     */
    private volatile @Nullable SelectionBudget selection;
    /*
     * Whether the command in progress fetches content for a caller that reads it
     * and lets it go (see fetchSectionBody). Same initializer and visibility
     * reasoning as the three above.
     */
    private volatile boolean fetchingContent;
    /*
     * What the open-folder budget is on this connection. Zero while the constructor
     * reads the greeting, before it is assigned; openFolderBudget() reads the
     * default then.
     */
    private final long configuredOpenFolderBudget;

    BoundedImapProtocol(String name, String host, int port, Properties props, boolean isSSL, MailLogger logger)
            throws IOException, ProtocolException {
        super(name, host, port, props, isSSL, logger);
        configuredOpenFolderBudget = openFolderBudget(props, name);
    }

    /**
     * The open-folder budget a session carries for {@code protocol}, or
     * {@link #DEFAULT_OPEN_FOLDER_BUDGET} when it carries none: a session this
     * backend opens always carries one, written by {@link BoundedImapStore#install}
     * from a value its configuration has already checked to be positive.
     */
    static long openFolderBudget(Properties props, String protocol) {
        String value = props.getProperty("mail." + protocol + "." + OPEN_FOLDER_BUDGET_PROPERTY);
        return value == null ? DEFAULT_OPEN_FOLDER_BUDGET : Long.parseLong(value);
    }

    private long openFolderBudget() {
        long configured = configuredOpenFolderBudget;
        return configured > 0 ? configured : DEFAULT_OPEN_FOLDER_BUDGET;
    }

    /**
     * Every body section Angus fetches comes through here: the content of a message
     * or a part, an attachment in 16 KiB pieces, and the headers of a message or a
     * part. The content goes to a caller that reads it and lets it go, so its data
     * is not charged to the {@link SelectionBudget} (B1-14); the folder's handler
     * takes only flags, UIDs and MODSEQs from the responses. Headers are charged:
     * {@code IMAPMessage} and {@code IMAPBodyPart} keep what they load this way.
     * <p>
     * Which of the two a fetch is goes by the section the client asked for, never
     * by the one the server's answer names. Angus returns the one {@code BODY} a
     * response carries for the message whatever section it names, so a server that
     * labelled the headers it was asked for as content would otherwise have them
     * kept uncharged.
     */
    @Override
    protected BODY fetchSectionBody(int msgno, @Nullable String section, String body) throws ProtocolException {
        fetchingContent = isContentSection(section);
        try {
            return super.fetchSectionBody(msgno, section, body);
        } finally {
            fetchingContent = false;
        }
    }

    /**
     * {@link #fetchSectionBody} for a server that speaks IMAP4 rather than
     * IMAP4rev1: the whole message ({@code what} null) or its text is content, its
     * headers are not.
     */
    @Override
    public RFC822DATA fetchRFC822(int msgno, @Nullable String what) throws ProtocolException {
        fetchingContent = what == null || "TEXT".equalsIgnoreCase(what);
        try {
            return super.fetchRFC822(msgno, what);
        } finally {
            fetchingContent = false;
        }
    }

    /**
     * Whether a body section names content rather than headers: the whole message
     * (none), a part's number, or {@code TEXT}, alone or after a part's number —
     * what Angus's {@code IMAPMessage}, {@code IMAPBodyPart} and
     * {@code IMAPInputStream} ask for to read content. Anything else is charged:
     * {@code HEADER}, {@code HEADER.FIELDS (…)}, a part's {@code MIME}, and any
     * section this does not know.
     */
    static boolean isContentSection(@Nullable String section) {
        return section == null || CONTENT_SECTION.matcher(section).matches();
    }

    /**
     * Whether the untagged FETCH responses of the command in progress go one at a
     * time to a caller that keeps what it needs of them and hands them to no
     * response handler — {@code ImapCondstoreCommands.readEach}, which bounds what
     * it keeps itself — and so are not charged to a {@link CommandBudget}. Every
     * other response is, in that command too: not only {@code Protocol.command}'s,
     * but Angus's authentication's, which collects the responses to AUTHENTICATE in
     * a loop of its own.
     */
    void readingOneAtATime(boolean on) {
        oneAtATime = on;
    }

    /**
     * Starts a command, and with it a fresh {@link CommandBudget}. The one place a
     * budget starts over: a command's end is not a response the server can be
     * trusted to mark, since it may send tagged lines that are not the command's
     * own, and every command the client sends — {@code Protocol.command}, the
     * AUTHENTICATE loops, {@code readEach} — begins here. A SELECT or EXAMINE also
     * starts a fresh {@link SelectionBudget}, for the folder it selects.
     */
    @Override
    public String writeCommand(String command, @Nullable Argument args) throws IOException, ProtocolException {
        budget = null;
        if ("SELECT".equalsIgnoreCase(command) || "EXAMINE".equalsIgnoreCase(command)) {
            selection = null;
        }
        return super.writeCommand(command, args);
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
             * StackOverflowError, is prevented instead, by stopping the parse of a FETCH
             * once it opens more than MAX_NESTING levels and dropping the response.
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
            SelectionBudget folder = selection;
            if (folder == null) {
                folder = new SelectionBudget();
                selection = folder;
            }
            String overworked = folder.charge(imapResponse);
            if (overworked != null) {
                throw refused(overworked);
            }
            /*
             * What the folder keeps of a FETCH (B1-14). Not a FETCH that readEach streams
             * to its caller, which goes to no handler, as for the command budget below.
             */
            if (response instanceof FetchResponse fetch && !oneAtATime) {
                long budget = openFolderBudget();
                long before = folder.kept();
                String overkept = folder.chargeKept(keptBytes(fetch, read.wireSize(), fetchingContent), budget);
                if (overkept != null) {
                    throw refused(overkept);
                }
                // Once per selection, so an honest folder nearing the budget shows in the
                // log before it is refused.
                if (before <= budget / 2 && folder.kept() > budget / 2) {
                    log.warn("{} A folder open on IMAP server {} keeps over half of its {}-byte budget from FETCH "
                            + "responses (mail.client.imap.open-folder-budget); past it the connection is closed.",
                            LogCategory.IMAP, host, budget);
                }
            }
        }
        /*
         * Every response is charged, tagged or not, until writeCommand starts the next
         * command: Protocol.command and the AUTHENTICATE loops collect a tagged
         * response whose tag is not their own and read on, so a budget that started
         * over at any tagged line was one a server could reset at will (1.24). The one
         * exception is a FETCH that readEach streams to a caller bounding what it
         * keeps.
         */
        if (!(oneAtATime && response instanceof FetchResponse)) {
            CommandBudget current = budget;
            if (current == null) {
                current = new CommandBudget();
                budget = current;
            }
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
     * {@code IMAPProtocol.readResponse} with the FETCH parse depth-bounded. Angus's
     * own reads the whole response and then, in the same call, parses a FETCH's
     * items, which is where a {@code BODYSTRUCTURE} and an {@code ENVELOPE} are
     * parsed; this makes the same two calls, the way
     * {@code IMAPProtocol.readResponse} makes them in 2.0.5, with the second one
     * through {@link DepthBoundedFetchResponse}. A FETCH is the only response Angus
     * parses further, and so the only one bounded. A later Angus may read responses
     * differently, so {@code BoundedImapProtocolTest} pins the version.
     */
    private Read readNestingChecked() throws IOException, ProtocolException {
        WireSizedResponse response = new WireSizedResponse(this);
        if (!response.keyEquals("FETCH")) {
            return new Read(response, response.wireSize());
        }
        return new Read(parseFetch(response, getFetchItems(), this, host), response.wireSize());
    }

    /**
     * Angus's parse of a FETCH, stopped once it opens more than
     * {@link #MAX_NESTING} levels: then the response is dropped, with
     * {@link NestedTooDeepException}.
     */
    static FetchResponse parseFetch(IMAPResponse response, FetchItem @Nullable [] fetchItems, Protocol protocol,
            String host) throws IOException, ProtocolException {
        try {
            return new DepthBoundedFetchResponse(response, fetchItems, protocol);
        } catch (NestingLimitReached e) {
            log.warn(
                    "{} Dropped a FETCH response from IMAP server {} nested more than {} levels deep; "
                            + "the message it describes is left without its structure.",
                    LogCategory.IMAP, host, MAX_NESTING);
            throw new NestedTooDeepException();
        } catch (ParseStalled e) {
            log.warn("{} Dropped a FETCH response from IMAP server {} that the parser stopped consuming; "
                    + "the message it describes is left without its structure.", LogCategory.IMAP, host);
            throw new StalledParseException();
        }
    }

    /**
     * A response as Angus parsed it, and its length on the wire, which the budget
     * charges.
     */
    private record Read(Response response, int wireSize) {
    }

    /**
     * What a FETCH may leave in the selected folder, as the heap it takes: its
     * bytes on the wire, {@link #PARSED_OBJECT_BYTES} for each object its parse
     * made, and {@link #RESPONSE_OVERHEAD_BYTES} for the response (B1-14). An upper
     * bound for everything measured at 1.49, and for an ordinary message about a
     * third over what it keeps.
     * <p>
     * The whole response is charged, whatever was asked for: {@code IMAPFolder}'s
     * fetch gives a message every item a response names, a whole body included,
     * which Angus parses into the message and keeps. The one exception is a fetch
     * of content ({@link #fetchSectionBody}): there Angus hands the body item to
     * the caller and the folder's handler takes no body from any response, so the
     * data of every body item in the command's responses goes uncharged.
     */
    static long keptBytes(FetchResponse fetch, int wireBytes, boolean content) {
        long objects = fetch instanceof DepthBoundedFetchResponse counted ? counted.parsedObjects() : 0;
        long bytes = wireBytes;
        if (content) {
            for (int i = 0; i < fetch.getItemCount(); i++) {
                ByteArray data = switch (fetch.getItem(i)) {
                    case BODY body -> body.getByteArray();
                    case RFC822DATA rfc822 -> rfc822.getByteArray();
                    default -> null;
                };
                if (data != null) {
                    bytes -= data.getCount();
                }
            }
        }
        return Math.max(0, bytes) + objects * PARSED_OBJECT_BYTES + RESPONSE_OVERHEAD_BYTES;
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
     * A response as Angus reads it, before a FETCH's items are parsed, with its
     * length on the wire. A subclass because the length is Angus's protected field.
     */
    static final class WireSizedResponse extends IMAPResponse {

        WireSizedResponse(Protocol protocol) throws IOException, ProtocolException {
            super(protocol);
        }

        /** The response's length as read, literals included. */
        int wireSize() {
            return size;
        }
    }

    /**
     * A FETCH as Angus 2.0.5 parses it, counting the levels the parse opens as it
     * opens them, and stopping it with {@link NestingLimitReached} past
     * {@link #MAX_NESTING} — before the recursion can come near the end of the
     * stack (B1-10).
     * <p>
     * The count is taken from Angus's parser itself rather than from a scan of the
     * bytes beforehand. Three scans in turn modelled how Angus tokenizes a FETCH —
     * quoted strings, literals, a {@code BODY} section, then each item's own
     * reading — and each was bypassed by the next reading it did not model: a
     * {@code "} where the scan saw a string and Angus a flag or a NIL (reopened at
     * 1.24 and 1.31). Here nothing is modelled about where data ends: the parser
     * consumes every byte through the methods overridden below, and they see what
     * it consumes as structure and what as data.
     * <p>
     * Every recursion of the parse in 2.0.5 starts by consuming a {@code (}: a
     * {@code BODYSTRUCTURE} with {@code readByte}, a body-extension list with
     * {@code skip(1)}, an address with {@code readByte}. So:
     * <ul>
     * <li><b>Lists.</b> A {@code (} consumed by {@code readByte}, {@code skip} or
     * {@code isNextNonSpace} opens a level; a {@code )} consumed by
     * {@code readByte} or {@code isNextNonSpace} closes one. Within the parse each
     * such close ends a list the same frame opened, so the count is the lists open
     * at once. The one {@code )} that is data to Angus, the byte {@code BODY}
     * consumes after its section, can only come at the top of the FETCH list, and
     * the count stops at zero, so it can hide one level at most. The lists
     * {@code readStringList} reads are data — it opens them by moving the index,
     * not through these methods — so the {@code )} it consumes with
     * {@code isNextNonSpace} is not counted either.</li>
     * <li><b>Address groups.</b> An address opens and closes its own list before
     * the next address recurses inside its group, so the lists open at once do not
     * grow with groups nested inside groups, and 10,000 of them overflow the stack.
     * An address is a {@code (}, four {@code readString}s and a {@code )}, and
     * starts or ends a group exactly when the fourth, the host, is NIL; each such
     * address is counted. Nothing Angus consumes says where a group ends, but every
     * group of a list ends when the list does: the recursion happens only inside
     * the list's own loop, where the count of lists never drops below the list's
     * level, and it has returned by the time the list's {@code )} is consumed. So
     * the addresses counted are forgotten once the count of lists drops below the
     * level they were counted at, and an envelope's groups do not add up with the
     * next envelope's.</li>
     * <li><b>Progress.</b> Every one of these methods notes whether the parse has
     * consumed anything since the last; {@link #MAX_STALLED_CALLS} in a row without
     * a byte consumed stop it with {@link ParseStalled} (B1-15).</li>
     * </ul>
     * The bound is on the two together. A miscount can only be one way that
     * matters: a level counted that Angus did not open costs an honest response at
     * worst, and a level opened uncounted is what the above rules out for 2.0.5.
     * <p>
     * No field has an initializer: {@code FetchResponse}'s constructor parses,
     * through these overrides, before an initializer would run.
     */
    static final class DepthBoundedFetchResponse extends FetchResponse {

        /** Lists open in the parse. */
        private int depth;
        /** Addresses read with a NIL host: group starts and ends. */
        private int groupMarkers;
        /** Inside {@code readStringList}, whose list is data. */
        private int inStringList;
        /**
         * Where the parse is in what may be an address: 0 none, 1 after its {@code (},
         * 2 to 5 after its first to fourth string.
         */
        private int addressStage;
        private boolean addressHostNil;
        /** The lowest level of lists at which an address now counted was read. */
        private int groupLevel;
        /**
         * Where the last call left the parse, and how many calls in a row left it
         * there.
         */
        private int lastIndex;
        private int stalledCalls;
        /**
         * Objects the parse has made: every string, byte array and list it read, and
         * every list it opened (an address, a structure, an envelope). What a folder
         * keeps of a FETCH costs per object far more than per byte on the wire, so the
         * selection budget charges this count (B1-14). Counted, not modelled, for the
         * reason the depth is: every object Angus 2.0.5 makes from a FETCH comes out of
         * one of these methods. A list read with {@code readStringList} counts once
         * here and each of its strings once in {@code readString} or
         * {@code readAtomString}, which it calls; {@code readSimpleList} reads its
         * items itself, so they count there.
         */
        private long parsedObjects;

        DepthBoundedFetchResponse(IMAPResponse response, FetchItem @Nullable [] fetchItems, Protocol protocol)
                throws IOException, ProtocolException {
            super(response, fetchItems, protocol);
        }

        /** What the parse made, as {@link #parsedObjects} counts it. */
        long parsedObjects() {
            return parsedObjects;
        }

        @Override
        public byte readByte() {
            byte b = super.readByte();
            if (b == '(') {
                open();
                addressStage = 1;
            } else {
                if (b == ')') {
                    close();
                }
                addressStage = 0;
            }
            progress();
            return b;
        }

        @Override
        public void skip(int count) {
            for (int i = index; i < Math.min(size, index + count); i++) {
                if (buffer[i] == '(') {
                    open();
                }
            }
            addressStage = 0;
            super.skip(count);
            progress();
        }

        @Override
        public boolean isNextNonSpace(char c) {
            boolean consumed = super.isNextNonSpace(c);
            if (consumed && inStringList == 0) {
                if (c == '(') {
                    open();
                } else if (c == ')') {
                    close();
                    if (addressStage == 5 && addressHostNil) {
                        groupLevel = groupMarkers == 0 ? depth : Math.min(groupLevel, depth);
                        groupMarkers++;
                        checkBound();
                    }
                }
            }
            addressStage = 0;
            progress();
            return consumed;
        }

        @Override
        public @Nullable String readString() {
            String read = super.readString();
            if (addressStage >= 1 && addressStage <= 4) {
                if (addressStage == 4) {
                    addressHostNil = read == null;
                }
                addressStage++;
            } else {
                addressStage = 0;
            }
            progress();
            return made(read);
        }

        @Override
        public @Nullable String readString(char delim) {
            return made(super.readString(delim));
        }

        @Override
        public @Nullable String readAtom() {
            return made(super.readAtom());
        }

        @Override
        public @Nullable String readAtomString() {
            return made(super.readAtomString());
        }

        @Override
        public @Nullable ByteArray readByteArray() {
            return made(super.readByteArray());
        }

        /**
         * A FETCH's {@code FLAGS}, the one list Angus reads this way: each item counts
         * twice, since {@code Flags} keeps a user flag in a table keyed by its
         * lower-cased copy, a second string whenever the flag has a capital — measured
         * at 1.49, 134 bytes a flag against some 80 for every other object.
         */
        @Override
        public String @Nullable [] readSimpleList() {
            String[] read = super.readSimpleList();
            if (read != null) {
                parsedObjects += 1 + 2L * read.length;
            }
            return read;
        }

        @Override
        public String @Nullable [] readStringList() {
            inStringList++;
            addressStage = 0;
            try {
                return made(super.readStringList());
            } finally {
                inStringList--;
            }
        }

        @Override
        public String @Nullable [] readAtomStringList() {
            inStringList++;
            addressStage = 0;
            try {
                return made(super.readAtomStringList());
            } finally {
                inStringList--;
            }
        }

        private <T> @Nullable T made(@Nullable T read) {
            if (read != null) {
                parsedObjects++;
            }
            return read;
        }

        private void open() {
            depth++;
            parsedObjects++;
            checkBound();
        }

        private void close() {
            if (depth > 0) {
                depth--;
            }
            if (depth < groupLevel) {
                groupMarkers = 0;
                groupLevel = 0;
            }
        }

        private void progress() {
            if (index != lastIndex) {
                lastIndex = index;
                stalledCalls = 0;
            } else if (++stalledCalls > MAX_STALLED_CALLS) {
                throw new ParseStalled();
            }
        }

        private void checkBound() {
            if (depth + groupMarkers > MAX_NESTING) {
                throw new NestingLimitReached();
            }
        }
    }

    /**
     * Thrown from inside Angus's parse by {@link DepthBoundedFetchResponse}, whose
     * overrides cannot declare a checked exception; {@link #parseFetch} turns it
     * into {@link NestedTooDeepException}, and it must not escape this class. No
     * stack trace: it is thrown on purpose, from up to {@link #MAX_NESTING} levels
     * down.
     */
    static final class NestingLimitReached extends RuntimeException {

        NestingLimitReached() {
            super("nested past " + MAX_NESTING + " levels", null, false, false);
        }
    }

    /**
     * Thrown from inside Angus's parse by {@link DepthBoundedFetchResponse} when it
     * stops consuming the response; {@link #parseFetch} turns it into
     * {@link StalledParseException}, and it must not escape this class. No stack
     * trace, as for {@link NestingLimitReached}.
     */
    static final class ParseStalled extends RuntimeException {

        ParseStalled() {
            super("no byte consumed in " + MAX_STALLED_CALLS + " calls", null, false, false);
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
     * names. A new one per command, started by {@link #writeCommand}, so nothing
     * carries over from one command into the next and nothing the server sends can
     * start one over.
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
     * What the EXPUNGEs of one selected folder have cost Angus so far (B1-13), in
     * steps of the pass {@code MessageCache.expungeMessage} makes over its array:
     * each EXPUNGE, and each UID a live VANISHED names, charged the length that
     * array may have reached — the largest EXISTS since the folder was selected,
     * plus the expunged entries Angus keeps in it until the folder closes. An upper
     * bound: Angus ignores an EXPUNGE past its count, and a VANISHED UID it does
     * not know. A new one per selection, started by {@link #writeCommand} for a
     * SELECT or EXAMINE, so it spans every command sent to the open folder.
     * <p>
     * It also sums what the folder keeps of the FETCH responses it is handed, as
     * {@link #keptBytes} estimates it (B1-14).
     */
    static final class SelectionBudget {

        private long largestExists;
        private long expunged;
        private long steps;
        private long kept;

        /** What the folder keeps of its FETCH responses so far, in estimated bytes. */
        long kept() {
            return kept;
        }

        /**
         * Charges what one FETCH leaves in the folder against {@code budget}: the
         * reason to refuse it, or null.
         */
        @Nullable
        String chargeKept(long bytes, long budget) {
            kept += bytes;
            return kept > budget
                    ? "the FETCH responses kept since the folder was selected passed its budget of " + budget
                            + " bytes (mail.client.imap.open-folder-budget)"
                    : null;
        }

        /** Charges one untagged response: the reason to refuse it, or null. */
        @Nullable
        String charge(IMAPResponse response) {
            if (response.keyEquals("EXISTS")) {
                largestExists = Math.max(largestExists, response.getNumber());
                return null;
            }
            long count;
            if (response.keyEquals("EXPUNGE")) {
                count = 1;
            } else if (response.keyEquals("VANISHED")) {
                count = liveVanishedUids(response);
            } else {
                return null;
            }
            steps += count * (largestExists + expunged);
            expunged += count;
            return steps > MAX_SELECTION_EXPUNGE_STEPS
                    ? "the EXPUNGE responses since the folder was selected passed " + MAX_SELECTION_EXPUNGE_STEPS
                            + " steps of the message cache"
                    : null;
        }
    }

    /**
     * The UIDs of a live VANISHED — the form that replaces EXPUNGE once QRESYNC is
     * enabled, and the only one Angus expunges messages for; zero for the EARLIER
     * form, which answers a SELECT or UID FETCH, and for a response that does not
     * parse. Called after {@link #refusal}, which has bounded the set.
     */
    static long liveVanishedUids(IMAPResponse response) {
        IMAPResponse copy = new IMAPResponse(response);
        try {
            if (copy.readAtomStringList() != null) {
                return 0;
            }
            return Math.max(0, uidCount(copy.readAtom(), MAX_LIVE_VANISHED_UIDS));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * A response refused by {@link #refusal}; see the class for why it is an
     * IOException.
     */
    static final class ImplausibleResponseException extends IOException {
        ImplausibleResponseException(String reason) {
            super(MailFailureCause.REFUSED_RESPONSE_MARKER + ": " + reason);
        }

        ImplausibleResponseException(String reason, Throwable cause) {
            super(MailFailureCause.REFUSED_RESPONSE_MARKER + ": " + reason, cause);
        }
    }

    /**
     * A FETCH nested past {@link #MAX_NESTING}, dropped with its parse stopped
     * there; see the class for why it is a ProtocolException.
     */
    static final class NestedTooDeepException extends ProtocolException {
        NestedTooDeepException() {
            super("Dropped an IMAP FETCH response nested more than " + MAX_NESTING + " levels deep");
        }
    }

    /**
     * A FETCH whose parse stopped consuming it, dropped there (B1-15); a
     * ProtocolException for the same reason as {@link NestedTooDeepException}.
     */
    static final class StalledParseException extends ProtocolException {
        StalledParseException() {
            super("Dropped an IMAP FETCH response whose parse made no progress in " + MAX_STALLED_CALLS + " calls");
        }
    }
}
