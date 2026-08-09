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
}
