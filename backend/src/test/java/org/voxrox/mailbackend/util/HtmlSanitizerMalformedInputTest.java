package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What {@link HtmlSanitizer} does with markup no browser would call well
 * formed, which is the input class its sibling {@code HtmlSanitizerTest} does
 * not reach.
 *
 * That file asserts the safelist policy: a well-formed attack goes in, nothing
 * dangerous comes out. Every one of its cases is markup a parser agrees about.
 * But the sanitizer is a clean() over a parse and then a second parse of the
 * serialized result, so what the Cleaner is even shown is decided by how jsoup
 * resolves malformed input: foreign content, unclosed RCDATA, a tag breaking
 * out of a context it should not leave. That behaviour belongs to the library,
 * it moves between releases, and until this file existed nothing here watched
 * it.
 *
 * It stopped being hypothetical on 2026-08-31. jsoup 1.23.2 changed where
 * breakout tags land in malformed SVG and MathML, and made unclosed title and
 * textarea content stay text through EOF. Compiling the real sanitizer against
 * 1.23.1 and 1.23.2 over this corpus showed exactly one output difference, safe
 * under both, and not one of the 33 policy tests moved.
 *
 * The assertion here is an invariant, not a recorded string. A golden output
 * would have gone red on that textarea case, where the rendering changed and
 * the safety did not, and re-approving a golden file is a habit that eventually
 * re-approves a real regression. What is pinned is the claim the audit actually
 * makes: nothing executable survives. Exact output is pinned separately, and
 * only for mail a parser does agree about, in {@code Fidelity} below.
 */
class HtmlSanitizerMalformedInputTest {

    /**
     * Elements that must never reach the client: script carriers, foreign content
     * (the breakout vector), and the interactive elements the relaxed safelist does
     * not allow either.
     */
    private static final Set<String> FORBIDDEN_TAGS = Set.of("script", "iframe", "object", "embed", "applet", "svg",
            "math", "base", "link", "meta", "style", "form", "input", "button", "select", "option", "textarea",
            "noscript", "template", "frame", "frameset");

    /** Attributes whose value is fetched or navigated to, so the scheme matters. */
    private static final Set<String> URL_ATTRIBUTES = Set.of("href", "src", "action", "formaction", "xlink:href");

    /**
     * Every way the sanitized output could still execute something, as a list of
     * readable violations. Empty means safe.
     *
     * A method rather than a chain of assertions so the control below can prove it
     * bites: a checker nothing has ever seen fail is not evidence.
     */
    private static List<String> securityViolations(String sanitized) {
        List<String> violations = new ArrayList<>();
        Element wrapper = Jsoup.parseBodyFragment(sanitized).selectFirst("div.mail-content-wrapper");
        if (wrapper == null) {
            violations.add("output is not wrapped in div.mail-content-wrapper");
            return violations;
        }

        // The wrapper is walked through its children rather than included in the
        // sweep: its own style attribute is ours, not the message's.
        List<Element> content = new ArrayList<>();
        for (Element child : wrapper.children()) {
            content.addAll(child.getAllElements());
        }

        for (Element element : content) {
            String tag = element.tagName().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_TAGS.contains(tag)) {
                violations.add("element <" + tag + "> survived");
            }
            for (Attribute attribute : element.attributes()) {
                String name = attribute.getKey().toLowerCase(Locale.ROOT);
                if (name.startsWith("on")) {
                    violations.add("event handler " + name + " survived on <" + tag + ">");
                }
                if ("style".equals(name)) {
                    violations.add("inline style survived on <" + tag + ">");
                }
                if (URL_ATTRIBUTES.contains(name) && isExecutableUrl(attribute.getValue())) {
                    violations.add(name + "=" + attribute.getValue() + " survived on <" + tag + ">");
                }
            }
        }
        return violations;
    }

    /**
     * Whitespace and control characters go before the scheme is read: a newline
     * inside "javascript:" is one of the oldest ways past a naive prefix check, and
     * a checker that missed it would pass this corpus for the wrong reason.
     */
    private static boolean isExecutableUrl(String rawValue) {
        StringBuilder compact = new StringBuilder(rawValue.length());
        for (int i = 0; i < rawValue.length(); i++) {
            char character = rawValue.charAt(i);
            if (!Character.isWhitespace(character) && !Character.isISOControl(character)) {
                compact.append(character);
            }
        }
        String value = compact.toString().toLowerCase(Locale.ROOT);
        if (value.startsWith("javascript:") || value.startsWith("vbscript:")) {
            return true;
        }
        return value.startsWith("data:") && !value.startsWith("data:image/");
    }

    /**
     * Malformed markup, each case naming the parser behaviour it leans on. These
     * are the payloads the 1.23.1 against 1.23.2 differential ran on.
     */
    static Stream<Arguments> malformedMarkup() {
        return Stream.of(
                Arguments.of("SVG breaking out through a style element",
                        "<svg><p><style><a title=\"</style><img src=x onerror=alert(1)>\">"),
                Arguments.of("SVG breaking out through CDATA",
                        "<svg><desc><![CDATA[</desc><img src=x onerror=alert(2)>]]></desc></svg>"),
                Arguments.of("MathML mtext and mglyph with a commented-out style",
                        "<math><mtext><table><mglyph><style><!--</style>"
                                + "<img title=\"--><img src=x onerror=alert(3)>\">"),
                Arguments.of("MathML annotation-xml declaring text/html",
                        "<math><annotation-xml encoding=\"text/html\"><p>"
                                + "<img src=x onerror=alert(4)></p></annotation-xml></math>"),
                Arguments.of("form, math and mglyph nesting confusion",
                        "<form><math><mtext></form><form><mglyph><style></math>" + "<img src onerror=alert(5)>"),
                Arguments.of("noscript with a tag-like title attribute",
                        "<noscript><p title=\"</noscript><img src=x onerror=alert(6)>\"></noscript>"),
                Arguments.of("title as RCDATA with a tag-like href",
                        "<title><a href=\"</title><img src=x onerror=alert(7)>\"></title>"),
                Arguments.of("textarea left unclosed at EOF", "<textarea><p>still text <img src=x onerror=alert(8)>"),
                Arguments.of("style closed by a malformed end tag", "<style></style x><img src=x onerror=alert(9)>"),
                Arguments.of("script-data escape sequence",
                        "<script><!--<script></script>--><img src=x onerror=alert(10)></script>"),
                Arguments.of("comments after body and after html",
                        "<p>body</p></body><!--after body--></html><!--after html-->"),
                Arguments.of("unmatched template end tag", "<p>one</template><p>two"),
                Arguments.of("entity-encoded tag inside an attribute",
                        "<p title=\"&lt;img src=x onerror=alert(11)&gt;\">t</p>"),
                Arguments.of("mis-nested formatting elements", "<b><i>bold italic</b> only italic</i>"),
                Arguments.of("comment left unclosed", "<p>before<!-- unclosed comment <img src=x onerror=alert(12)>"),
                Arguments.of("newline inside the javascript scheme", "<a href=\"java&#10;script:alert(13)\">click</a>"),
                Arguments.of("nesting past the parser's max depth",
                        "<div>".repeat(600) + "<p>marker</p>" + "</div>".repeat(600)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedMarkup")
    @DisplayName("malformed markup never yields an executable construct")
    void malformedMarkupYieldsNothingExecutable(String name, String payload) {
        assertThat(securityViolations(HtmlSanitizer.sanitize(payload))).as(name).isEmpty();
    }

    @Nested
    @DisplayName("The invariant checker itself")
    class CheckerControl {

        @Test
        @DisplayName("reports every class of violation it claims to cover")
        void bitesOnKnownBadOutput() {
            String unsafe = "<div class='mail-content-wrapper' style='all: revert;'>" + "<script>alert(1)</script>"
                    + "<p onclick=\"alert(2)\">x</p>" + "<p style=\"position:fixed\">y</p>"
                    + "<a href=\"javascript:alert(3)\">z</a>" + "<img src=\"data:text/html;base64,AAAA\">" + "</div>";

            assertThat(securityViolations(unsafe)).anyMatch(v -> v.contains("<script>"))
                    .anyMatch(v -> v.contains("onclick")).anyMatch(v -> v.contains("inline style"))
                    .anyMatch(v -> v.contains("javascript:")).anyMatch(v -> v.contains("data:text/html"));
        }

        @Test
        @DisplayName("does not report the wrapper's own style attribute")
        void ignoresTheWrapperItself() {
            String safe = "<div class='mail-content-wrapper' style='all: revert;'><p>safe</p></div>";
            assertThat(securityViolations(safe)).isEmpty();
        }

        @Test
        @DisplayName("reports output that lost the wrapper entirely")
        void requiresTheWrapper() {
            assertThat(securityViolations("<p>no wrapper</p>"))
                    .containsExactly("output is not wrapped in div.mail-content-wrapper");
        }
    }

    /**
     * Exact output, and only for mail every parser agrees about. This is the
     * rendering-fidelity net, deliberately separate from the security invariant
     * above: attributes kept, links given their rel, an embedded image resolved,
     * table structure intact. Whitespace is pinned too, because for well-formed
     * input it should not move; if a jsoup release changes the pretty-printer these
     * fire, and that is a review worth having once rather than a change worth
     * missing.
     */
    @Nested
    @DisplayName("Rendering fidelity for well-formed mail")
    class Fidelity {

        private static final Map<String, String> INLINE_IMAGE = Map.of("abc123@mail",
                "data:image/png;base64,iVBORw0KGgo=");

        @Test
        @DisplayName("a newsletter table keeps its layout attributes and gains a tbody")
        void newsletterTable() {
            String out = HtmlSanitizer.sanitize("<p>Hello,</p><table align=\"center\" width=\"600\""
                    + " cellpadding=\"8\"><tr><td valign=\"top\" width=\"50%\"><b>Invoice</b>"
                    + " <i>2026-08</i></td><td align=\"right\">42.00</td></tr></table>");

            assertThat(out).isEqualTo("<div class='mail-content-wrapper' style='all: revert;'>" + "<p>Hello,</p>\n"
                    + "<table align=\"center\" width=\"600\" cellpadding=\"8\">\n" + " <tbody>\n" + "  <tr>\n"
                    + "   <td valign=\"top\" width=\"50%\"><b>Invoice</b> <i>2026-08</i></td>\n"
                    + "   <td align=\"right\">42.00</td>\n" + "  </tr>\n" + " </tbody>\n" + "</table></div>");
        }

        @Test
        @DisplayName("an external link gains rel and target, and a cid image resolves to its data URI")
        void linkAndEmbeddedImage() {
            String out = HtmlSanitizer
                    .sanitize("<p>See <a href=\"https://example.com/invoice?id=42&amp;type=pdf\">the invoice</a>.</p>"
                            + "<p><img src=\"cid:abc123@mail\" width=\"120\"></p>", INLINE_IMAGE);

            assertThat(out).isEqualTo("<div class='mail-content-wrapper' style='all: revert;'><p>See <a"
                    + " href=\"https://example.com/invoice?id=42&amp;type=pdf\" target=\"_blank\""
                    + " rel=\"nofollow noopener noreferrer\">the invoice</a>.</p>\n"
                    + "<p><img src=\"data:image/png;base64,iVBORw0KGgo=\" width=\"120\"></p></div>");
        }
    }
}
