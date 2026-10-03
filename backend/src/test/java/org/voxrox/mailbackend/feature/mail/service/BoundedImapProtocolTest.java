package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.atIndex;

import java.util.Properties;

import org.eclipse.angus.mail.imap.protocol.IMAPResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
     * B1-10: how deep a response nests, measured before Angus parses it, because
     * its {@code BODYSTRUCTURE} parser recurses once per level. That the check sits
     * in front of that parse on a real connection is
     * {@code HostileImapResponseIT}'s question.
     */
    @Nested
    @DisplayName("Nesting")
    class Nesting {

        private static final int BOUND = BoundedImapProtocol.MAX_NESTING;

        private static boolean nestsTooDeep(String line) throws Exception {
            return new BoundedImapProtocol.NestingCheckedResponse(line).nestsDeeperThan(BOUND);
        }

        /**
         * The FETCH list itself is one level, so the parentheses inside get the rest.
         */
        private static String fetchNested(int levels) {
            return "* 1 FETCH " + "(".repeat(levels) + ")".repeat(levels);
        }

        @Test
        @DisplayName("Nesting up to the bound passes, one level more is refused")
        void nestingAtTheBound() throws Exception {
            assertThat(nestsTooDeep(fetchNested(BOUND))).isFalse();
            assertThat(nestsTooDeep(fetchNested(BOUND + 1))).isTrue();
        }

        @Test
        @DisplayName("Depth is the deepest point, not the number of lists")
        void siblingListsDoNotAddUp() throws Exception {
            String siblings = "* 1 FETCH (" + "(x) ".repeat(BOUND * 4) + ")";

            assertThat(nestsTooDeep(siblings)).isFalse();
        }

        @Test
        @DisplayName("Parentheses inside a quoted string or a literal are data, not depth")
        void stringsAndLiteralsDoNotCount() throws Exception {
            String deep = "(".repeat(BOUND + 1);

            assertThat(nestsTooDeep("* 1 FETCH (BODY[HEADER] \"" + deep + "\")")).isFalse();
            assertThat(nestsTooDeep("* 1 FETCH (BODY[HEADER] \"a \\\" " + deep + "\")")).isFalse();
            assertThat(nestsTooDeep("* 1 FETCH (BODY[] {" + deep.length() + "}\r\n" + deep + ")")).isFalse();
        }

        /**
         * B1-10, reopened at 1.24. Angus reads a {@code BODY[...]} section up to its
         * {@code ]} as raw text, so a quote or a literal marker inside it opens
         * nothing, and the structure after it is parsed.
         */
        @Test
        @DisplayName("A quote or a literal marker inside a BODY section opens nothing, as in Angus")
        void aBodySectionIsReadRaw() throws Exception {
            String deep = "(".repeat(BOUND + 1) + "\"text\" \"plain\" NIL NIL NIL \"7bit\" 1 1" + ")".repeat(BOUND + 1);
            String rest = "] NIL BODYSTRUCTURE " + deep + ")";

            assertThat(nestsTooDeep("* 1 FETCH (BODY[x\"" + rest)).isTrue();
            assertThat(nestsTooDeep("* 1 FETCH (body[x\"" + rest)).isTrue();
            assertThat(nestsTooDeep("* 1 FETCH (UID 5 BODY[{" + rest.length() + "}\r\n" + rest)).isTrue();
        }

        @Test
        @DisplayName("A BODY section without its closing bracket ends the scan, where Angus fails the parse")
        void anUnclosedSectionEndsTheScan() throws Exception {
            assertThat(nestsTooDeep("* 1 FETCH (BODY[x " + "(".repeat(BOUND + 1))).isFalse();
        }

        @Test
        @DisplayName("A brace that starts no literal leaves what follows it counted")
        void aBraceThatIsNoLiteralIsCounted() throws Exception {
            String deep = "(".repeat(BOUND + 1);

            assertThat(nestsTooDeep("* OK [ALERT] {3} " + deep)).isTrue();
            assertThat(nestsTooDeep("* OK [ALERT] {} " + deep)).isTrue();
        }

        @Test
        @DisplayName("A literal longer than the response ends the scan instead of running past it")
        void aLiteralPastTheEndIsNotFollowed() throws Exception {
            assertThat(nestsTooDeep("* 1 FETCH (BODY[] {999}\r\n(((")).isFalse();
        }

        @Test
        @DisplayName("An ordinary structure passes: a multipart with a text part and an attachment")
        void anOrdinaryStructurePasses() throws Exception {
            String structure = "* 3 FETCH (UID 7 BODYSTRUCTURE ((\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL"
                    + " \"quoted-printable\" 120 4 NIL NIL NIL NIL)(\"application\" \"pdf\" (\"name\" \"a (1).pdf\")"
                    + " NIL NIL \"base64\" 5000 NIL (\"attachment\" (\"filename\" \"a (1).pdf\")) NIL NIL)"
                    + " \"mixed\" (\"boundary\" \"b1\") NIL NIL NIL))";

            assertThat(nestsTooDeep(structure)).isFalse();
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
        @DisplayName("Other responses cost nothing")
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
