package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MarkdownBodyRenderer}. Two properties matter and both
 * are load-bearing for the send path:
 *
 * <ol>
 * <li>a body that uses no Markdown produces <em>no</em> HTML alternative, so
 * messages from users who never type Markdown go out exactly as they did before
 * the renderer existed;</li>
 * <li>the renderer cannot emit markup the user did not ask for, which is what
 * lets {@code MimeMessageBuilder} attach its output without a sanitizer pass.
 * </li>
 * </ol>
 */
class MarkdownBodyRendererTest {

    private final MarkdownBodyRenderer renderer = new MarkdownBodyRenderer();

    private String render(String markdown) {
        return renderer.renderAlternative(markdown)
                .orElseThrow(() -> new AssertionError("expected an HTML alternative for: " + markdown));
    }

    @Nested
    @DisplayName("Bodies that produce no HTML alternative")
    class Inert {

        @Test
        @DisplayName("Prose with single newlines — the common message, untouched")
        void plainProse() {
            assertThat(renderer.renderAlternative("Hello,\nsee you at 10:00.\n\nAlice")).isEmpty();
        }

        @Test
        @DisplayName("Null, empty and whitespace-only bodies")
        void emptyBodies() {
            assertThat(renderer.renderAlternative(null)).isEmpty();
            assertThat(renderer.renderAlternative("")).isEmpty();
            assertThat(renderer.renderAlternative("   \n\t ")).isEmpty();
        }

        /**
         * Four-space indentation is how people paste log output and lay out signatures,
         * not a request for a code block — so IndentedCodeBlock is off in the parser
         * and such a body stays plain text.
         */
        @Test
        @DisplayName("Four-space indented text is indentation, not a code block")
        void indentedTextIsNotCode() {
            assertThat(renderer.renderAlternative("Report:\n\n    total 12\n    done  9")).isEmpty();
        }

        /**
         * With inline HTML escaped, a body mentioning tags reads identically in both
         * parts — so generating a second part would add bytes and no information.
         */
        @Test
        @DisplayName("Text that merely mentions HTML tags")
        void inlineHtmlIsInert() {
            assertThat(renderer.renderAlternative("Use <b>bold</b> in the template.")).isEmpty();
            assertThat(renderer.renderAlternative("<div>\nblock\n</div>")).isEmpty();
        }

        @Test
        @DisplayName("Body past the render cap stays plain rather than parsing megabytes")
        void oversizedBodyIsSkipped() {
            String huge = "# heading\n".repeat(60_000);

            assertThat(huge.length()).isGreaterThan(512 * 1024);
            assertThat(renderer.renderAlternative(huge)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Bodies that render")
    class Formatted {

        @Test
        @DisplayName("Emphasis, headings, lists, blockquote and fenced code")
        void coreConstructs() {
            assertThat(render("**bold**")).contains("<strong>bold</strong>");
            assertThat(render("# Title")).contains("<h1>Title</h1>");
            assertThat(render("- one\n- two")).contains("<ul>").contains("<li>one</li>");
            assertThat(render("1. one\n2. two")).contains("<ol>");
            assertThat(render("> quoted")).contains("<blockquote>");
            assertThat(render("```\ncode()\n```")).contains("<pre><code>code()");
        }

        /**
         * CommonMark folds a single newline into a space. In a message that would
         * reflow addresses, times and signature lines into one paragraph, so soft
         * breaks are rendered as <code>&lt;br&gt;</code> — the line structure the
         * sender typed is part of the message.
         */
        @Test
        @DisplayName("Single newlines become <br>, not spaces")
        void softBreaksArePreserved() {
            String html = render("**Where:** Main street 1\nPrague\nsee you there");

            assertThat(html).contains("Main street 1<br />").contains("Prague<br />");
        }

        @Test
        @DisplayName("Output is a complete HTML document with a UTF-8 meta charset")
        void wrapsInADocument() {
            String html = render("# Title");

            assertThat(html).startsWith("<!DOCTYPE html>").contains("<meta charset=\"utf-8\">").endsWith("</html>\n");
        }
    }

    /**
     * The renderer's output goes onto the wire without a sanitizer pass, so these
     * are the tests that justify that. The threat is not a remote attacker — the
     * Markdown source is the user's own composition — but the two parts of the
     * message must agree, and a body must never gain markup its plain-text
     * counterpart does not show.
     */
    @Nested
    @DisplayName("The renderer emits no markup the user did not type")
    class NoMarkupInjection {

        @Test
        @DisplayName("Inline HTML in a formatted body is escaped, not passed through")
        void inlineHtmlIsEscaped() {
            String html = render("# Title\n\n<script>alert('x')</script>");

            assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
        }

        @Test
        @DisplayName("An img tag typed by hand stays literal text")
        void imageTagIsEscaped() {
            String html = render("## Heading\n\n<img src=x onerror=alert(1)>");

            assertThat(html).doesNotContain("<img").contains("&lt;img");
        }

        @Test
        @DisplayName("A javascript: link target is dropped by URL sanitization")
        void javascriptUrlIsSanitized() {
            String html = render("# Title\n\n[click](javascript:alert(1))");

            assertThat(html).doesNotContain("javascript:");
        }

        @Test
        @DisplayName("Ordinary http/https/mailto links survive")
        void safeUrlsSurvive() {
            String html = render("# Title\n\n[site](https://example.com) [mail](mailto:bob@example.com)");

            assertThat(html).contains("href=\"https://example.com\"").contains("href=\"mailto:bob@example.com\"");
        }
    }

    /**
     * The account signature is not a separate thing on the way out: the composer
     * appends it to the body as {@code "\n\n-- \n" + text} (frontend
     * {@code lib/compose/signature.ts}), so it is parsed as part of the message and
     * its format is whatever the body's is — plain text that may contain Markdown.
     * That is the reason {@code accounts.signature} is a single untyped
     * {@code TEXT} column and needs no {@code signature_html} sibling or
     * {@code signature_format} discriminator (decision recorded in
     * {@code todo.md}).
     *
     * <p>
     * These tests hold the half of that reasoning which could break silently: that
     * an ordinary signature does not, by itself, turn a plain-text message into a
     * {@code multipart/alternative} one. Nothing else enforces it — it rests on the
     * RFC 3676 separator being {@code "-- "} and on the enabled block set, and a
     * change to either lives far from this consequence.
     */
    @Nested
    @DisplayName("The RFC 3676 signature block the composer appends")
    class SignatureBlock {

        private static final String SEPARATOR = "\n\n-- \n";

        @Test
        @DisplayName("An ordinary signature leaves a plain body plain")
        void plainSignatureStaysPlain() {
            assertThat(
                    renderer.renderAlternative("Hello,\n\nsee you at 10:00." + SEPARATOR + "Alice Smith\nExample Ltd"))
                    .isEmpty();
        }

        @Test
        @DisplayName("The separator alone is inert — two hyphens are not a thematic break")
        void separatorAloneIsInert() {
            assertThat(renderer.renderAlternative("Hello." + SEPARATOR + "Alice")).isEmpty();
        }

        @Test
        @DisplayName("A bare URL in the signature does not trigger rendering")
        void signatureWithUrlStaysPlain() {
            assertThat(renderer.renderAlternative("Hello." + SEPARATOR + "Alice Smith\nhttps://example.com")).isEmpty();
        }

        @Test
        @DisplayName("An indented block inside the signature is indentation, not code")
        void indentedSignatureStaysPlain() {
            // The blank line matters. Without it the indented line is a lazy
            // continuation of the paragraph above and could never be a code block,
            // so the assertion would hold whatever the enabled block set says —
            // passing for a reason that has nothing to do with what it claims.
            assertThat(renderer.renderAlternative("Hello." + SEPARATOR + "Alice Smith\n\n    Example Ltd")).isEmpty();
        }

        @Test
        @DisplayName("Markdown typed into the signature renders, like anywhere else in the body")
        void markdownInSignatureRenders() {
            String html = render("Hello." + SEPARATOR + "*Alice Smith*");

            assertThat(html).contains("<em>Alice Smith</em>");
        }

        @Test
        @DisplayName("A formatted body keeps the separator as text, not as a heading")
        void separatorSurvivesAFormattedBody() {
            String html = render("Hello,\n\n- first\n- second" + SEPARATOR + "Alice Smith");

            assertThat(html).contains("<p>--<br />\nAlice Smith</p>");
        }

        @Test
        @DisplayName("A signature with a --- rule stays plain, name and separator intact")
        void signatureWithHorizontalRuleStaysPlain() {
            assertThat(renderer.renderAlternative("Hello." + SEPARATOR + "Alice Smith\n---\nExample Ltd")).isEmpty();
        }

        @Test
        @DisplayName("With a formatted body, the rule in the signature renders as text on its own line")
        void signatureRuleIsTextInAFormattedBody() {
            String html = render("Hello,\n\n- first\n- second" + SEPARATOR + "Alice Smith\n---\nExample Ltd");

            // The rule ends the block, so what followed it is its own paragraph —
            // the dashes are kept, which is what the plain-text part shows too.
            assertThat(html).doesNotContain("<h2>").contains("Alice Smith<br />\n---</p>")
                    .contains("<p>Example Ltd</p>");
        }
    }

    /**
     * A rule under a line of text is a setext heading in CommonMark, and in a mail
     * that is almost never what the writer meant — a signature separated by dashes
     * would turn its own name into an {@code
     *
    <h2>}. These demote back to paragraphs, on the same principle that keeps
     * indented code blocks off: formatting in a message should be an explicit
     * gesture, and {@code # Title} is one while a line of dashes is not.
     */
    @Nested
    @DisplayName("Setext headings demote to paragraphs")
    class SetextHeadings {

        @Test
        @DisplayName("A --- rule under text does not create a heading")
        void dashRuleIsNotAHeading() {
            assertThat(renderer.renderAlternative("Release notes\n---\nnothing to report")).isEmpty();
        }

        @Test
        @DisplayName("An === rule under text does not create a heading either")
        void equalsRuleIsNotAHeading() {
            assertThat(renderer.renderAlternative("Release notes\n===\nnothing to report")).isEmpty();
        }

        @Test
        @DisplayName("The underline survives as text — both message parts say the same thing")
        void underlineIsKeptAsText() {
            String html = render("# Title\n\nSummary\n---\nrest");

            assertThat(html).doesNotContain("<h2>").contains("Summary<br />\n---</p>").contains("<p>rest</p>");
        }

        @Test
        @DisplayName("ATX headings are untouched — writing # is deliberate")
        void atxHeadingsStillRender() {
            assertThat(render("# Title")).contains("<h1>Title</h1>");
            assertThat(render("## Sub")).contains("<h2>Sub</h2>");
            assertThat(render("###### Six")).contains("<h6>Six</h6>");
        }

        @Test
        @DisplayName("A rule on its own is still a thematic break, not an underline")
        void standaloneRuleIsUnaffected() {
            String html = render("before\n\n---\n\nafter");

            assertThat(html).contains("<hr />");
        }
    }
}
