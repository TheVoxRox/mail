package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.angus.mail.imap.protocol.IMAPResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which empty lists {@link EmptyListsAsNil} writes as NIL, read off the
 * response it hands back. Whether Angus then parses what it hands back is
 * {@code BoundedImapProtocolTest}'s question.
 */
class EmptyListsAsNilTest {

    /**
     * The response as rewritten, or "unchanged" when there was nothing to rewrite.
     */
    private static String rewritten(String line) throws Exception {
        IMAPResponse reread = EmptyListsAsNil.of(new IMAPResponse(line));
        return reread == null ? "unchanged" : reread.toString();
    }

    @Test
    @DisplayName("A disposition with an empty parameter list, as Seznam writes it, gets NIL")
    void anEmptyDispositionParameterListBecomesNil() throws Exception {
        String part = "(\"text\" \"html\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 10 1 NIL (\"inline\" %s) NIL NIL)";
        String line = "* 93 FETCH (BODYSTRUCTURE (" + part + " \"mixed\" (\"boundary\" \"b\") NIL NIL NIL))";

        assertThat(rewritten(line.formatted("()"))).isEqualTo(line.formatted("NIL"));
        assertThat(rewritten(line.formatted("( )"))).as("with a space inside").isEqualTo(line.formatted("NIL"));
    }

    @Test
    @DisplayName("An empty parameter list after a subtype gets NIL, a part's and a multipart's")
    void anEmptyContentTypeParameterListBecomesNil() throws Exception {
        assertThat(rewritten("* 1 FETCH (BODYSTRUCTURE (\"text\" \"plain\" () NIL NIL \"7bit\" 1 1))"))
                .isEqualTo("* 1 FETCH (BODYSTRUCTURE (\"text\" \"plain\" NIL NIL NIL \"7bit\" 1 1))");
        assertThat(rewritten("* 1 FETCH (BODYSTRUCTURE ((\"text\" \"plain\" NIL NIL NIL \"7bit\" 1 1) \"mixed\" ()))"))
                .isEqualTo("* 1 FETCH (BODYSTRUCTURE ((\"text\" \"plain\" NIL NIL NIL \"7bit\" 1 1) \"mixed\" NIL))");
    }

    @Test
    @DisplayName("An empty list after a bracket stays: where a part stands, and as a language list Angus accepts")
    void anEmptyListAfterABracketStays() throws Exception {
        assertThat(rewritten("* 1 FETCH (BODYSTRUCTURE ())")).as("the structure's own list").isEqualTo("unchanged");
        assertThat(rewritten("* 1 FETCH (BODYSTRUCTURE (()(\"text\" \"plain\" () NIL NIL \"7bit\" 1 1) \"mixed\"))"))
                .as("an empty part beside a part with an empty parameter list")
                .isEqualTo("* 1 FETCH (BODYSTRUCTURE (()(\"text\" \"plain\" NIL NIL NIL \"7bit\" 1 1) \"mixed\"))");
        assertThat(rewritten("* 1 FETCH (BODYSTRUCTURE (\"text\" \"plain\" NIL NIL NIL \"7bit\" 1 1 NIL"
                + " (\"inline\" NIL) () NIL))")).as("a language list after the disposition").isEqualTo("unchanged");
    }

    @Test
    @DisplayName("An empty list outside the structure stays, FLAGS () among them")
    void anEmptyListOutsideTheStructureStays() throws Exception {
        assertThat(rewritten("* 1 FETCH (FLAGS () UID 5)")).isEqualTo("unchanged");
        assertThat(rewritten("* 1 FETCH (FLAGS () UID 5 BODYSTRUCTURE (\"text\" \"plain\" () NIL NIL \"7bit\" 1 1)"
                + " MODSEQ (12))"))
                .isEqualTo("* 1 FETCH (FLAGS () UID 5 BODYSTRUCTURE (\"text\" \"plain\" NIL NIL NIL"
                        + " \"7bit\" 1 1) MODSEQ (12))");
    }

    @Test
    @DisplayName("A () inside a quoted string, a literal or a section in brackets stays where it is")
    void anEmptyListInsideDataStays() throws Exception {
        String attachment = "(\"application\" \"pdf\" (\"name\" %s) NIL NIL \"base64\" 10 NIL (\"attachment\" %s) NIL NIL)";

        assertThat(rewritten("* 1 FETCH (BODYSTRUCTURE " + attachment.formatted("\"a ().pdf\"", "()") + ")"))
                .isEqualTo("* 1 FETCH (BODYSTRUCTURE " + attachment.formatted("\"a ().pdf\"", "NIL") + ")");
        assertThat(rewritten("* 1 FETCH (BODYSTRUCTURE " + attachment.formatted("\"a \\\" ().pdf\"", "()") + ")"))
                .as("after an escaped quote")
                .isEqualTo("* 1 FETCH (BODYSTRUCTURE " + attachment.formatted("\"a \\\" ().pdf\"", "NIL") + ")");
        assertThat(rewritten("* 1 FETCH (BODYSTRUCTURE " + attachment.formatted("{8}\r\na ().pdf", "()") + ")"))
                .as("in a literal")
                .isEqualTo("* 1 FETCH (BODYSTRUCTURE " + attachment.formatted("{8}\r\na ().pdf", "NIL") + ")");
        assertThat(rewritten("* 1 FETCH (BODY[HEADER.FIELDS (X-A ())] {4}\r\na: b BODYSTRUCTURE"
                + " (\"text\" \"plain\" () NIL NIL \"7bit\" 1 1))")).as("in a section name")
                .isEqualTo("* 1 FETCH (BODY[HEADER.FIELDS (X-A ())] {4}\r\na: b BODYSTRUCTURE"
                        + " (\"text\" \"plain\" NIL NIL NIL \"7bit\" 1 1))");
    }

    @Test
    @DisplayName("A response with nothing to rewrite, or past the length bound, comes back null")
    void nothingToRewriteComesBackNull() throws Exception {
        assertThat(rewritten("* 1 FETCH (BODYSTRUCTURE (\"text\" \"plain\" NIL NIL NIL \"7bit\" 1 1))"))
                .isEqualTo("unchanged");
        String padding = "x".repeat(EmptyListsAsNil.MAX_REREAD_BYTES);
        assertThat(rewritten("* 1 FETCH (BODYSTRUCTURE (\"text\" \"plain\" () \"" + padding + "\" NIL \"7bit\" 1 1))"))
                .isEqualTo("unchanged");
    }
}
