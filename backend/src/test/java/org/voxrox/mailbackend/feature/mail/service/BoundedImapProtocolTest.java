package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.atIndex;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import org.eclipse.angus.mail.iap.Protocol;
import org.eclipse.angus.mail.imap.protocol.FetchResponse;
import org.eclipse.angus.mail.imap.protocol.IMAPResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.voxrox.mailbackend.core.config.mail.ImapProperties;

/**
 * The bounds {@link BoundedImapProtocol} holds a server response to, one rule
 * at a time, on responses parsed by Angus itself. That the store the backend
 * opens actually reads through this check is {@code HostileImapResponseIT}'s
 * question, over the wire.
 */
class BoundedImapProtocolTest {

    private static IMAPResponse response(String line) throws Exception {
        return new IMAPResponse(line);
    }

    @Nested
    @DisplayName("EXISTS")
    class Exists {

        @Test
        @DisplayName("A message count up to the bound passes")
        void countAtTheBoundPasses() throws Exception {
            assertThat(
                    BoundedImapProtocol.refusal(response("* " + BoundedImapProtocol.MAX_MESSAGES + " EXISTS"), false))
                    .isNull();
        }

        @Test
        @DisplayName("A message count past the bound is refused")
        void countPastTheBoundIsRefused() throws Exception {
            assertThat(BoundedImapProtocol.refusal(response("* " + (BoundedImapProtocol.MAX_MESSAGES + 1) + " EXISTS"),
                    false)).contains("EXISTS");
        }

        @Test
        @DisplayName("A negative message count is refused")
        void negativeCountIsRefused() throws Exception {
            assertThat(BoundedImapProtocol.refusal(response("* -1 EXISTS"), false)).contains("EXISTS");
        }
    }

    @Nested
    @DisplayName("VANISHED")
    class Vanished {

        @Test
        @DisplayName("Any VANISHED is refused on a connection that never enabled QRESYNC")
        void refusedWithoutQresync() throws Exception {
            assertThat(BoundedImapProtocol.refusal(response("* VANISHED (EARLIER) 5"), false)).contains("QRESYNC");
            assertThat(BoundedImapProtocol.refusal(response("* VANISHED 5"), false)).contains("QRESYNC");
        }

        @Test
        @DisplayName("VANISHED (EARLIER) up to its bound passes, one UID more is refused")
        void earlierBound() throws Exception {
            long bound = BoundedImapProtocol.MAX_EARLIER_VANISHED_UIDS;

            assertThat(BoundedImapProtocol.refusal(response("* VANISHED (EARLIER) 1:" + bound), true)).isNull();
            assertThat(BoundedImapProtocol.refusal(response("* VANISHED (EARLIER) 1:" + (bound + 1)), true))
                    .contains("more than " + bound);
        }

        @Test
        @DisplayName("An untagged VANISHED has the tighter bound")
        void liveBound() throws Exception {
            long bound = BoundedImapProtocol.MAX_LIVE_VANISHED_UIDS;

            assertThat(BoundedImapProtocol.refusal(response("* VANISHED 1:" + bound), true)).isNull();
            assertThat(BoundedImapProtocol.refusal(response("* VANISHED 1:" + (bound + 1)), true))
                    .contains("more than " + bound);
        }

        @Test
        @DisplayName("The bound is on the UIDs of all ranges together, not on each")
        void rangesAreSummed() throws Exception {
            long half = BoundedImapProtocol.MAX_LIVE_VANISHED_UIDS / 2;

            assertThat(BoundedImapProtocol
                    .refusal(response("* VANISHED 1:" + half + "," + (half + 10) + ":" + (2 * half + 10)), true))
                    .isNotNull();
        }

        @Test
        @DisplayName("A range reaching the top of the UID space is refused without overflowing the count")
        void hugeRangeIsRefused() throws Exception {
            assertThat(BoundedImapProtocol.refusal(response("* VANISHED (EARLIER) 1,2:9223372036854775807"), true))
                    .contains("more than");
        }

        /**
         * Angus counts a descending range as negative and then writes nothing for it,
         * so the array it allocates is too small for the ranges around it.
         */
        @Test
        @DisplayName("A descending range, or an empty UID set, is refused as malformed")
        void malformedSetsAreRefused() throws Exception {
            assertThat(BoundedImapProtocol.refusal(response("* VANISHED 1:3,9:5"), true)).contains("malformed");
            assertThat(BoundedImapProtocol.refusal(response("* VANISHED ()"), true)).contains("malformed");
        }

        /**
         * A bare "* VANISHED" makes Angus's own reader run off the end of the line —
         * the reader the check borrows, and the one Angus would run on the original.
         * Refused, it ends the connection like any other refusal instead of throwing
         * out of the read.
         */
        @Test
        @DisplayName("A VANISHED line Angus cannot parse is refused, not thrown")
        void unparseableLineIsRefused() throws Exception {
            assertThat(BoundedImapProtocol.refusal(response("* VANISHED"), true)).contains("cannot be parsed");
        }

        @Test
        @DisplayName("The check reads a copy: Angus still parses the response it lets through")
        void responseIsLeftForAngus() throws Exception {
            IMAPResponse passed = response("* VANISHED (EARLIER) 3:5,9");

            assertThat(BoundedImapProtocol.refusal(passed, true)).isNull();

            assertThat(passed.readAtomStringList()).containsExactly("EARLIER");
            assertThat(passed.readAtom()).isEqualTo("3:5,9");
        }
    }

    /**
     * The other half of the class: the buffer Angus reads a response into. A
     * literal's declared size is allocated inside Angus's reader, before the
     * response exists for {@link BoundedImapProtocol#refusal} to look at (B1-7), so
     * the bound sits on the one call that allocates — {@code ByteArray.grow}. That
     * the bound is reached over the wire, and what a refusal then costs the sync,
     * is {@code HostileImapResponseIT}'s question.
     */
    @Nested
    @DisplayName("Response buffer")
    class ResponseBuffer {

        private static final int BOUND = BoundedImapProtocol.MAX_RESPONSE_BYTES;

        private BoundedImapProtocol.BoundedByteArray buffer(int size) {
            return new BoundedImapProtocol.BoundedByteArray(new byte[size], 0, size);
        }

        @Test
        @DisplayName("Growth up to the bound passes, one byte more is refused")
        void growthAtTheBound() {
            buffer(128).grow(BOUND - 128);

            assertThatThrownBy(() -> buffer(128).grow(BOUND - 128 + 1))
                    .isInstanceOf(BoundedImapProtocol.OversizedResponseException.class)
                    .hasMessageContaining(String.valueOf(BOUND));
        }

        @Test
        @DisplayName("A grown buffer keeps its bytes and is bounded by its new size")
        void growthIsCumulative() {
            BoundedImapProtocol.BoundedByteArray grown = buffer(128);
            grown.getBytes()[7] = 42;

            grown.grow(BOUND - 128);

            assertThat(grown.getBytes()).hasSize(BOUND).contains((byte) 42, atIndex(7));
            assertThatThrownBy(() -> grown.grow(1)).isInstanceOf(BoundedImapProtocol.OversizedResponseException.class);
        }

        /**
         * The increment Angus computes for a literal is {@code count + 16 - avail},
         * which for a declared size near {@link Integer#MAX_VALUE} overflows to a
         * negative number. Measured on Angus 2.0.5, an unbounded buffer turns that into
         * a {@code NegativeArraySizeException} from the array it then asks for.
         */
        @Test
        @DisplayName("An increment that would overflow the array length is refused, not attempted")
        void overflowingIncrementIsRefused() {
            assertThatThrownBy(() -> buffer(128).grow(Integer.MAX_VALUE))
                    .isInstanceOf(BoundedImapProtocol.OversizedResponseException.class);
            assertThatThrownBy(() -> buffer(128).grow(-1))
                    .isInstanceOf(BoundedImapProtocol.OversizedResponseException.class);
        }
    }

    /**
     * B1-10: Angus's parse of a FETCH, stopped once it opens more than the bound,
     * because its {@code BODYSTRUCTURE} and address-group parsers recurse once per
     * level. That the parse goes through this on a real connection is
     * {@code HostileImapResponseIT}'s question.
     */
    @Nested
    @DisplayName("Nesting")
    class Nesting {

        private static final int BOUND = BoundedImapProtocol.MAX_NESTING;

        /**
         * Deep enough to overflow Angus's parse on a test thread's stack: measured at
         * 1.16, 5,000 levels of structure overflow and 3,000 parse.
         */
        private static final int OVERFLOWING = 20_000;

        private static final String LEAF = "(\"text\" \"plain\" NIL NIL NIL \"7bit\" 1 1)";

        /**
         * "parsed", "dropped", "stalled", or the exception the parse ended in. Under a
         * time limit, so a parse that loops fails the test instead of hanging it.
         */
        private static String parse(String line) throws Exception {
            return assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                try {
                    BoundedImapProtocol.parseFetch(new IMAPResponse(line), null, mock(Protocol.class), "test");
                    return "parsed";
                } catch (BoundedImapProtocol.NestedTooDeepException e) {
                    return "dropped";
                } catch (BoundedImapProtocol.StalledParseException e) {
                    return "stalled";
                }
            });
        }

        /** A structure of {@code levels} multiparts around one text part. */
        private static String multipartNested(int levels) {
            return "(".repeat(levels) + LEAF + " \"mixed\")".repeat(levels);
        }

        private static String fetchWithStructure(String before, int levels) {
            return "* 1 FETCH (" + before + "BODYSTRUCTURE " + multipartNested(levels) + ")";
        }

        /**
         * The FETCH list is one level and the text part another, so the multiparts get
         * the rest.
         */
        @Test
        @DisplayName("Nesting up to the bound parses, one level more is dropped")
        void nestingAtTheBound() throws Exception {
            assertThat(parse(fetchWithStructure("", BOUND - 2))).isEqualTo("parsed");
            assertThat(parse(fetchWithStructure("", BOUND - 1))).isEqualTo("dropped");
        }

        @Test
        @DisplayName("A structure deep enough to overflow the stack is dropped, not overflowed")
        void anOverflowingStructureIsDropped() throws Exception {
            assertThat(parse(fetchWithStructure("", OVERFLOWING))).isEqualTo("dropped");
        }

        @Test
        @DisplayName("Depth is the deepest point, not the number of parts")
        void siblingPartsDoNotAddUp() throws Exception {
            String siblings = "* 1 FETCH (BODYSTRUCTURE (" + LEAF.repeat(BOUND * 4) + " \"mixed\"))";

            assertThat(parse(siblings)).isEqualTo("parsed");
        }

        @Test
        @DisplayName("Parentheses inside a quoted string, a literal or a flag are data, not depth")
        void dataDoesNotCount() throws Exception {
            String deep = "(".repeat(OVERFLOWING);

            assertThat(parse("* 1 FETCH (BODY[HEADER] \"" + deep + "\")")).isEqualTo("parsed");
            assertThat(parse("* 1 FETCH (BODY[] {" + deep.length() + "}\r\n" + deep + ")")).isEqualTo("parsed");
            assertThat(parse("* 1 FETCH (FLAGS (" + deep + "))")).isEqualTo("parsed");
        }

        /**
         * The shapes each earlier scan was bypassed by, at 1.24 and 1.31: a {@code "}
         * or a literal marker where the scan saw a string or a literal and Angus a
         * section, a flag, a NIL, literal data or an origin, so the structure after it
         * went uncounted and overflowed the stack. The count now comes from Angus's own
         * parse, so it meets the structure wherever Angus does.
         */
        @Test
        @DisplayName("Every shape that hid a structure from the earlier scans is dropped")
        void theShapesThatBypassedTheScanAreDropped() throws Exception {
            String literalRest = ") BODYSTRUCTURE " + multipartNested(OVERFLOWING) + ")";
            assertThat(parse("* 1 FETCH (FLAGS ({" + literalRest.length() + "}\r\n" + literalRest))
                    .as("FLAGS read raw into a literal's data").isEqualTo("dropped");

            for (String before : new String[]{"BODY[x\"] NIL ", "body[x\"] NIL ", "FLAGS (\") ",
                    "FLAGS (((a) BODY[x\"] NIL ", "RFC822 N\"x ", "RFC822 {3}xx\"ab ", "BODY[]\"NIL ",
                    "BODY[]<0\" NIL "}) {
                assertThat(parse(fetchWithStructure(before, OVERFLOWING))).as(before).isEqualTo("dropped");
            }
        }

        /** An envelope whose To list nests {@code levels} groups, one in another. */
        private static String envelopeWithNestedGroups(int levels) {
            return "* 1 FETCH (ENVELOPE (NIL \"s\" (" + "(NIL NIL \"g\" NIL)".repeat(levels)
                    + "(NIL NIL \"a\" \"example.com\")) NIL NIL NIL NIL NIL NIL NIL))";
        }

        /**
         * Found at 1.32: Angus parses a group's members inside the group's own parse,
         * so a group starting inside a group recurses, while every address closes its
         * parentheses before the next begins. Measured at 1.32: 10,000 such groups, 170
         * KB, overflow the stack, and no count of parentheses sees them.
         */
        @Test
        @DisplayName("Address groups nested deep enough to overflow the stack are dropped")
        void nestedAddressGroupsAreDropped() throws Exception {
            assertThat(parse(envelopeWithNestedGroups(OVERFLOWING))).isEqualTo("dropped");
            assertThat(parse(envelopeWithNestedGroups(BOUND))).isEqualTo("dropped");
        }

        @Test
        @DisplayName("An envelope with ordinary groups and many addresses parses")
        void ordinaryGroupsParse() throws Exception {
            String addresses = "(NIL NIL \"a\" \"example.com\")".repeat(BOUND * 4);
            String groups = "(NIL NIL \"team\" NIL)(NIL NIL \"b\" \"example.com\")(NIL NIL NIL NIL)".repeat(50);

            assertThat(parse(
                    "* 1 FETCH (ENVELOPE (NIL \"s\" (" + addresses + ") NIL (" + groups + ") NIL NIL NIL NIL NIL))"))
                    .isEqualTo("parsed");
        }

        /**
         * Found by the 1.33 verification pass: groups add up across envelopes, but they
         * nest only within one address list, so the count is forgotten when the list
         * closes. Before that, a message forwarding 126 messages, each sent to
         * {@code undisclosed-recipients:;}, was dropped though Angus parses it.
         */
        @Test
        @DisplayName("Groups in many envelopes do not add up: a message forwarding 200 messages with a group each parses")
        void groupsInSeparateEnvelopesDoNotAddUp() throws Exception {
            String envelope = "(NIL \"s\" ((NIL NIL \"a\" \"example.com\")) NIL NIL"
                    + " ((NIL NIL \"undisclosed-recipients\" NIL)(NIL NIL NIL NIL)) NIL NIL NIL NIL)";
            String forwarded = "(\"message\" \"rfc822\" NIL NIL NIL \"7bit\" 100 " + envelope + " " + LEAF + " 5)";

            assertThat(parse("* 1 FETCH (BODYSTRUCTURE (" + forwarded.repeat(200) + " \"mixed\"))"))
                    .isEqualTo("parsed");
        }

        /**
         * B1-15. Angus's {@code parseBodyExtension} reads an element that is neither a
         * list, a number nor a string with {@code readString}, which consumes nothing
         * for a bare atom, and loops back to the same index for ever. Both branches
         * that reach it: a single part's extension data, and a multipart's.
         */
        @Test
        @DisplayName("A body extension the parser cannot read is dropped instead of looping for ever")
        void anUnreadableBodyExtensionIsDropped() throws Exception {
            assertThat(parse("* 1 FETCH (UID 5 BODYSTRUCTURE (\"text\" \"plain\" NIL NIL NIL \"7bit\" 1 1"
                    + " NIL NIL NIL (x)))")).isEqualTo("stalled");
            assertThat(parse("* 1 FETCH (UID 5 BODYSTRUCTURE (" + LEAF + " \"mixed\" NIL NIL NIL (x)))"))
                    .isEqualTo("stalled");
        }

        @Test
        @DisplayName("An ordinary structure parses: a multipart with a text part, an attachment and extension data")
        void anOrdinaryStructureParses() throws Exception {
            String structure = "* 3 FETCH (UID 7 BODYSTRUCTURE ((\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL"
                    + " \"quoted-printable\" 120 4 NIL NIL (\"en\" \"cs\") NIL)(\"application\" \"pdf\""
                    + " (\"name\" \"a (1).pdf\") NIL NIL \"base64\" 5000 NIL (\"attachment\""
                    + " (\"filename\" \"a (1).pdf\")) NIL NIL (1 (2 \"x\")))"
                    + " \"mixed\" (\"boundary\" \"b1\") NIL NIL NIL))";

            assertThat(parse(structure)).isEqualTo("parsed");
        }
    }

    /**
     * {@link BoundedImapProtocol} hooks into Angus below its public API — the
     * response buffer's {@code grow}, the two steps of {@code readResponse}, the
     * protected bytes of a response — and the IMAP/SMTP audit's B1-3, B1-7 and
     * B1-10 sections rest on reading them in this version's bytecode. A different
     * Angus may do any of it differently while every test here still passes on
     * responses it parses itself, so the version is pinned: a dependency update
     * that moves it fails here, and passes once those hooks are re-read against the
     * new bytecode and this number is moved with them.
     */
    @Test
    @DisplayName("Angus is the version the protocol hooks were verified against")
    void angusIsTheVerifiedVersion() throws Exception {
        Properties pom = new Properties();
        try (var in = IMAPResponse.class.getClassLoader()
                .getResourceAsStream("META-INF/maven/org.eclipse.angus/angus-mail/pom.properties")) {
            assertThat(in).as("angus-mail's pom.properties").isNotNull();
            pom.load(in);
        }

        assertThat(pom.getProperty("version")).isEqualTo("2.0.5");
    }

    /**
     * B1-13. Angus spends a pass over the folder on each EXPUNGE; measured at 1.26,
     * 0.45 ms an EXPUNGE at 2,000,000 messages.
     */
    @Nested
    @DisplayName("Selection budget")
    class SelectionBudgetRules {

        @Test
        @DisplayName("The largest folder admits just under a thousand EXPUNGEs, and the next one is refused")
        void expungesAreChargedTheFolderSize() throws Exception {
            BoundedImapProtocol.SelectionBudget budget = new BoundedImapProtocol.SelectionBudget();
            assertThat(budget.charge(response("* " + BoundedImapProtocol.MAX_MESSAGES + " EXISTS"))).isNull();

            assertThat(expungesUntilRefused(budget)).isEqualTo(999);
        }

        @Test
        @DisplayName("The folder's largest count prices an EXPUNGE, not its latest")
        void theLargestCountStays() throws Exception {
            BoundedImapProtocol.SelectionBudget budget = new BoundedImapProtocol.SelectionBudget();
            budget.charge(response("* " + BoundedImapProtocol.MAX_MESSAGES + " EXISTS"));
            budget.charge(response("* 1 EXISTS"));

            assertThat(expungesUntilRefused(budget)).isEqualTo(999);
        }

        /**
         * Each EXPUNGE is charged the array Angus may hold: the largest EXISTS count
         * plus the entries earlier EXPUNGEs left in it, so the thousandth at
         * {@code MAX_MESSAGES} passes the budget by the 499,500 those add.
         */
        private static int expungesUntilRefused(BoundedImapProtocol.SelectionBudget budget) throws Exception {
            int admitted = 0;
            String refusal;
            while ((refusal = budget.charge(response("* 1 EXPUNGE"))) == null) {
                admitted++;
            }
            assertThat(refusal).contains("EXPUNGE");
            return admitted;
        }

        @Test
        @DisplayName("A live VANISHED is charged per UID, and the EARLIER form not at all")
        void liveVanishedIsChargedPerUid() throws Exception {
            BoundedImapProtocol.SelectionBudget budget = new BoundedImapProtocol.SelectionBudget();
            budget.charge(response("* " + BoundedImapProtocol.MAX_MESSAGES + " EXISTS"));

            assertThat(budget.charge(response("* VANISHED (EARLIER) 1:1000000"))).isNull();
            assertThat(budget.charge(response("* VANISHED 1:990"))).isNull();
            assertThat(budget.charge(response("* VANISHED 991:1000"))).contains("EXPUNGE");
        }

        @Test
        @DisplayName("Other responses cost the EXPUNGE budget nothing")
        void otherResponsesAreFree() throws Exception {
            BoundedImapProtocol.SelectionBudget budget = new BoundedImapProtocol.SelectionBudget();
            budget.charge(response("* " + BoundedImapProtocol.MAX_MESSAGES + " EXISTS"));

            for (int i = 0; i < 10_000; i++) {
                assertThat(budget.charge(response("* 1 FETCH (UID 1)"))).isNull();
            }
            assertThat(budget.charge(response("* OK still here"))).isNull();
        }
    }

    /**
     * B1-14. Angus's folder keeps what the FETCH responses handed to it give its
     * messages until it closes, across commands, so the selection budget sums it;
     * that the store charges it over the wire is {@code HostileImapResponseIT}'s
     * question.
     */
    @Nested
    @DisplayName("Open-folder budget")
    class OpenFolderBudgetRules {

        private static FetchResponse fetch(String line) throws Exception {
            return BoundedImapProtocol.parseFetch(new IMAPResponse(line), null, mock(Protocol.class), "test");
        }

        @Test
        @DisplayName("What a folder keeps adds up to its budget, and the FETCH that passes it is refused")
        void keptAddsUpToTheBudget() {
            BoundedImapProtocol.SelectionBudget budget = new BoundedImapProtocol.SelectionBudget();

            assertThat(budget.chargeKept(600, 1000)).isNull();
            assertThat(budget.chargeKept(400, 1000)).isNull();
            assertThat(budget.chargeKept(1, 1000)).contains("budget of 1000 bytes")
                    .contains("mail.client.imap.open-folder-budget");
        }

        /**
         * A bare UID costs a FETCH list and nothing parsed into an object, so the
         * charge is its bytes, the one list, and the response.
         */
        @Test
        @DisplayName("A FETCH is charged its bytes, each object its parse made, and the response")
        void aFetchIsChargedItsObjects() throws Exception {
            assertThat(BoundedImapProtocol.keptBytes(fetch("* 1 FETCH (UID 5)"), 17, false)).isEqualTo(
                    17 + BoundedImapProtocol.PARSED_OBJECT_BYTES + BoundedImapProtocol.RESPONSE_OVERHEAD_BYTES);
        }

        /**
         * Measured at 1.49: Angus keeps a user flag a second time, lower-cased, so each
         * flag is two objects; with the FETCH list and the flag list, six for two
         * flags.
         */
        @Test
        @DisplayName("Each flag counts twice, for the lower-cased copy Angus keeps of it")
        void flagsCountTwice() throws Exception {
            assertThat(BoundedImapProtocol.keptBytes(fetch("* 1 FETCH (FLAGS (\\Seen Urgent))"), 33, false)).isEqualTo(
                    33 + 6L * BoundedImapProtocol.PARSED_OBJECT_BYTES + BoundedImapProtocol.RESPONSE_OVERHEAD_BYTES);
        }

        @Test
        @DisplayName("A fetch of content leaves its body data uncharged, every other fetch is charged it")
        void contentDataIsNotCharged() throws Exception {
            String data = "x".repeat(5000);
            FetchResponse body = fetch("* 1 FETCH (BODY[1]<0> {5000}\r\n" + data + ")");
            FetchResponse rfc822 = fetch("* 1 FETCH (RFC822.TEXT {5000}\r\n" + data + ")");

            for (FetchResponse response : new FetchResponse[]{body, rfc822}) {
                long charged = BoundedImapProtocol.keptBytes(response, 5100, false);
                assertThat(BoundedImapProtocol.keptBytes(response, 5100, true)).isEqualTo(charged - 5000);
            }
        }

        /**
         * What Angus 2.0.5's {@code IMAPMessage}, {@code IMAPBodyPart} and
         * {@code IMAPInputStream} ask for: the first group to read content, the second
         * to load headers they keep.
         */
        @Test
        @DisplayName("Content is the whole message, a part or its text; headers and anything unknown are charged")
        void contentSections() {
            for (String section : new String[]{"", "1", "1.2.3", "TEXT", "1.TEXT", "2.text"}) {
                assertThat(BoundedImapProtocol.isContentSection(section)).as(section).isTrue();
            }
            assertThat(BoundedImapProtocol.isContentSection(null)).isTrue();
            for (String section : new String[]{"HEADER", "HEADER.FIELDS (Message-ID)", "HEADER.FIELDS.NOT (X)",
                    "1.MIME", "1.2.MIME", "1.HEADER", "2.HEADER.FIELDS (To)", "1.X", "TEXT.1"}) {
                assertThat(BoundedImapProtocol.isContentSection(section)).as(section).isFalse();
            }
        }

        @Test
        @DisplayName("The configuration's default is the protocol's")
        void theConfigurationDefaultIsTheProtocols() {
            ImapProperties unset = new Binder(new MapConfigurationPropertySource(Map.of()))
                    .bindOrCreate("mail.client.imap", ImapProperties.class);

            assertThat(unset.openFolderBudget().toBytes()).isEqualTo(BoundedImapProtocol.DEFAULT_OPEN_FOLDER_BUDGET);
        }

        @Test
        @DisplayName("A connection takes the budget its session carries, and the default when it carries none")
        void theSessionCarriesTheBudget() {
            Properties props = new Properties();
            assertThat(BoundedImapProtocol.openFolderBudget(props, "imaps"))
                    .isEqualTo(BoundedImapProtocol.DEFAULT_OPEN_FOLDER_BUDGET);

            props.put("mail.imaps." + BoundedImapProtocol.OPEN_FOLDER_BUDGET_PROPERTY, "4096");

            assertThat(BoundedImapProtocol.openFolderBudget(props, "imaps")).isEqualTo(4096);
            assertThat(BoundedImapProtocol.openFolderBudget(props, "imap"))
                    .isEqualTo(BoundedImapProtocol.DEFAULT_OPEN_FOLDER_BUDGET);
        }
    }

    /**
     * B1-8. Every bound above holds one response, and Protocol.command keeps all of
     * a command's responses until the tagged one: at 1.16, 60 VANISHED (EARLIER)
     * lines, each inside its bound, held 499 MB.
     */
    @Nested
    @DisplayName("Command budget")
    class CommandBudgetRules {

        @Test
        @DisplayName("A command's responses may add up to the budget, and the one that passes it is refused")
        void bytesAddUpToTheBudget() {
            BoundedImapProtocol.CommandBudget budget = new BoundedImapProtocol.CommandBudget();
            int perResponse = 1024 * 1024 - BoundedImapProtocol.RESPONSE_OVERHEAD_BYTES;
            long fits = BoundedImapProtocol.MAX_COMMAND_BYTES / (1024 * 1024);

            for (long i = 0; i < fits; i++) {
                assertThat(budget.charge(perResponse, 0)).as("response %d", i).isNull();
            }
            assertThat(budget.charge(0, 0)).contains("passed").contains("bytes");
        }

        @Test
        @DisplayName("Short responses are charged for the objects around them, not just their bytes")
        void shortResponsesCarryTheirOverhead() {
            BoundedImapProtocol.CommandBudget budget = new BoundedImapProtocol.CommandBudget();
            // "* 1 FETCH (UID 1)": cheap on the wire, 308 bytes in the heap.
            long cost = 17 + BoundedImapProtocol.RESPONSE_OVERHEAD_BYTES;
            long fits = BoundedImapProtocol.MAX_COMMAND_BYTES / cost;

            for (long i = 0; i < fits; i++) {
                budget.charge(17, 0);
            }
            assertThat(budget.charge(17, 0)).isNotNull();
        }

        @Test
        @DisplayName("VANISHED responses may name as many UIDs together as a folder holds messages, and no more")
        void vanishedUidsAddUp() {
            BoundedImapProtocol.CommandBudget budget = new BoundedImapProtocol.CommandBudget();

            assertThat(budget.charge(40, BoundedImapProtocol.MAX_EARLIER_VANISHED_UIDS)).isNull();
            assertThat(budget.charge(40, BoundedImapProtocol.MAX_EARLIER_VANISHED_UIDS)).isNull();
            assertThat(budget.charge(40, 1)).contains("VANISHED");
        }

        @Test
        @DisplayName("A VANISHED response is counted by the UIDs it names, in either form")
        void vanishedUidsAreCounted() throws Exception {
            assertThat(BoundedImapProtocol.vanishedUids(response("* VANISHED (EARLIER) 1:1000000")))
                    .isEqualTo(1_000_000);
            assertThat(BoundedImapProtocol.vanishedUids(response("* VANISHED 5,7:9"))).isEqualTo(4);
            assertThat(BoundedImapProtocol.vanishedUids(response("* VANISHED"))).isZero();
        }
    }

    @Test
    @DisplayName("Every other response passes untouched, whatever number it carries")
    void otherResponsesPass() throws Exception {
        for (String line : new String[]{"* 2147483583 RECENT", "* OK [UIDNEXT 4294967295] predicted",
                "* 2147483583 FETCH (FLAGS (\\Seen))", "A7 OK done", "* BYE going away"}) {
            assertThat(BoundedImapProtocol.refusal(response(line), false)).as(line).isNull();
        }
    }
}
