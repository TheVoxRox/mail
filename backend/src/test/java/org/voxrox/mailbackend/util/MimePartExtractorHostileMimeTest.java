package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.voxrox.mailbackend.feature.mail.dto.AttachmentResponse;

/**
 * {@link MimePartExtractor} over raw MIME that jakarta.mail has to parse,
 * rather than over a part tree the test assembled itself.
 *
 * Its sibling {@code MimePartExtractorTest} covers the traversal logic well —
 * seventeen cases, including oversized bodies and rejected inline images — but
 * every one of them hands the extractor a tree built in the test: a Mockito
 * mock, or a {@code MimeMultipart} put together field by field. Even the
 * "malformed multipart" case is a mock whose {@code getContent()} returns a
 * String, which is the shape a bad parse leaves behind rather than the parse
 * itself. The parser is never asked to do anything.
 *
 * That matters because the claims in §2 of {@code docs/IMAP_SMTP_AUDIT.md}
 * ("Malformed structure fails soft, per message", "MIME parsing is
 * depth-bounded", "Inline images are strictly bounded") are about a pipeline
 * whose first step is jakarta.mail turning attacker-controlled bytes into that
 * tree. Every mail is 100% attacker-controlled input, and the shape of the tree
 * is the library's decision, not ours.
 *
 * The same reasoning produced {@code HtmlSanitizerMalformedInputTest} for
 * Boundary 4. This is Boundary 1's half: a corpus of raw messages, and
 * invariants rather than recorded output, so that a jakarta.mail release which
 * legitimately changes how a broken message is bracketed does not turn the
 * suite red without a safety change behind it.
 */
class MimePartExtractorHostileMimeTest {

    private static final String CRLF = "\r\n";

    /** A 1x1 PNG, base64 — small enough that no size cap is in play. */
    private static final String PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
            + "YPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private static MimeMessage parse(String raw) throws Exception {
        Session session = Session.getInstance(new Properties());
        return new MimeMessage(session, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static String headers(String contentType) {
        return "From: attacker@example.com" + CRLF + "To: victim@example.com" + CRLF + "Subject: hostile" + CRLF
                + "Message-ID: <hostile@example.com>" + CRLF + "Content-Type: " + contentType + CRLF + CRLF;
    }

    /** multipart/mixed nested {@code levels} deep, with a marker at the bottom. */
    private static String nested(int levels) {
        StringBuilder raw = new StringBuilder(headers("multipart/mixed; boundary=\"b0\""));
        for (int i = 0; i < levels; i++) {
            raw.append("--b").append(i).append(CRLF);
            if (i == levels - 1) {
                raw.append("Content-Type: text/plain; charset=UTF-8").append(CRLF).append(CRLF).append("DEEPMARKER")
                        .append(CRLF);
            } else {
                raw.append("Content-Type: multipart/mixed; boundary=\"b").append(i + 1).append("\"").append(CRLF)
                        .append(CRLF);
            }
        }
        for (int i = levels - 1; i >= 0; i--) {
            raw.append("--b").append(i).append("--").append(CRLF);
        }
        return raw.toString();
    }

    /**
     * Raw messages a hostile or merely broken sender can produce. Each names the
     * parse decision it leans on, because that is what a jakarta.mail release can
     * move.
     */
    static Stream<Arguments> hostileMessages() {
        return Stream.of(
                Arguments.of("multipart declared with no parts at all", headers("multipart/mixed; boundary=\"b\"")),
                Arguments.of("body truncated in the middle of a part",
                        headers("multipart/mixed; boundary=\"b\"") + "--b" + CRLF + "Content-Type: text/plain" + CRLF
                                + CRLF + "half a body"),
                Arguments.of("closing boundary missing",
                        headers("multipart/mixed; boundary=\"b\"") + "--b" + CRLF + "Content-Type: text/plain" + CRLF
                                + CRLF + "no terminator" + CRLF),
                Arguments.of("boundary token also appears inside the content",
                        headers("multipart/mixed; boundary=\"b\"") + "--b" + CRLF + "Content-Type: text/plain" + CRLF
                                + CRLF + "text mentioning --b in passing" + CRLF + "--b--" + CRLF),
                Arguments.of("content-type claims multipart but the body is flat",
                        headers("multipart/mixed; boundary=\"b\"") + "just text, no boundaries at all" + CRLF),
                Arguments.of("charset that does not exist",
                        headers("text/plain; charset=\"definitely-not-a-charset\"") + "some text" + CRLF),
                Arguments.of("transfer encoding that does not exist",
                        "From: a@example.com" + CRLF + "Subject: x" + CRLF + "Content-Type: text/plain" + CRLF
                                + "Content-Transfer-Encoding: quoted-bogus" + CRLF + CRLF + "body" + CRLF),
                Arguments.of("base64 body with invalid padding",
                        "From: a@example.com" + CRLF + "Subject: x" + CRLF + "Content-Type: text/plain" + CRLF
                                + "Content-Transfer-Encoding: base64" + CRLF + CRLF + "!!!not!base64!!!" + CRLF),
                Arguments.of("headers only, no body", "From: a@example.com" + CRLF + "Subject: x" + CRLF + CRLF),
                Arguments.of("message/rfc822 wrapping another message",
                        headers("multipart/mixed; boundary=\"b\"") + "--b" + CRLF + "Content-Type: message/rfc822"
                                + CRLF + CRLF + "From: inner@example.com" + CRLF + "Subject: inner" + CRLF
                                + "Content-Type: text/plain" + CRLF + CRLF + "inner body" + CRLF + "--b--" + CRLF),
                Arguments.of("nesting past the depth bound", nested(25)),
                // Deep enough that an unbounded walk would overflow the stack rather
                // than merely take a while: this is the case that gives the Error
                // invariant something to catch. MAX_DEPTH stops us at 20, so the
                // levels below are never even parsed.
                Arguments.of("nesting deep enough to overflow an unbounded walk", nested(3000)),
                Arguments.of("attachment nested inside a message/rfc822",
                        headers("multipart/mixed; boundary=\"b\"") + "--b" + CRLF + "Content-Type: message/rfc822"
                                + CRLF + CRLF + "From: inner@example.com" + CRLF + "Subject: inner" + CRLF
                                + "Content-Type: multipart/mixed; boundary=\"c\"" + CRLF + CRLF + "--c" + CRLF
                                + "Content-Type: application/zip" + CRLF
                                + "Content-Disposition: attachment; filename=\"inner.zip\"" + CRLF + CRLF + "PK" + CRLF
                                + "--c--" + CRLF + "--b--" + CRLF),
                Arguments.of("attachment with a disposition but no filename",
                        headers("multipart/mixed; boundary=\"b\"") + "--b" + CRLF
                                + "Content-Type: application/octet-stream" + CRLF + "Content-Disposition: attachment"
                                + CRLF + CRLF + "bytes" + CRLF + "--b--" + CRLF),
                Arguments.of("attachment inside a multipart whose closing boundary is missing",
                        headers("multipart/mixed; boundary=\"b\"") + "--b" + CRLF + "Content-Type: application/pdf"
                                + CRLF + "Content-Disposition: attachment; filename=\"truncated.pdf\"" + CRLF + CRLF
                                + "%PDF-1.4" + CRLF),
                Arguments.of("attachment whose filename is RFC 2047 encoded",
                        headers("multipart/mixed; boundary=\"b\"") + "--b" + CRLF + "Content-Type: application/pdf"
                                + CRLF + "Content-Disposition: attachment; filename=\"=?UTF-8?B?ZmFrdHVyYS5wZGY=?=\""
                                + CRLF + CRLF + "%PDF-1.4" + CRLF + "--b--" + CRLF));
    }

    /**
     * The two attachment walks must agree.
     *
     * {@code hasAttachments} and {@code extractAttachmentMetadata} are separate
     * recursive walks over the same tree, each with its own use of
     * {@code isAttachment} and its own depth accounting, and the list page uses the
     * first while the detail page uses the second. Nothing but this test stops them
     * drifting into a paperclip icon on a message with no listed attachment, or the
     * reverse.
     *
     * The corpus is raw MIME rather than a hand-built tree because the shape they
     * walk is jakarta.mail's decision. What is deliberately not asserted is which
     * of these inputs the library throws on — four of the thirteen do today, and
     * recording that would pin the parse decisions this file exists not to depend
     * on. Throwing is inside the contract: all four entry points declare
     * {@code MessagingException} and {@code IOException}, the audit says a bad
     * structure "is caught - including RuntimeException - and the message is
     * persisted as an envelope-only stub", and {@code MalformedBodyStructureSyncIT}
     * is what proves that degradation.
     *
     * An earlier version of this test asserted instead that no {@code Error}
     * escapes, on the theory that a {@code StackOverflowError} would pass through
     * {@code catch (Exception)} and take the sync with it. Measured against a copy
     * of the extractor with the depth guard removed, that is not reachable here:
     * the unguarded walk completes at 100, 1000 and 3000 levels and still returns
     * the body. The assertion could not have failed, so it was removed rather than
     * kept for the comfort of a green tick. {@code MAX_DEPTH} bounds work, not
     * stack, and the two tests below are what hold it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileMessages")
    @DisplayName("the two attachment walks agree on hostile MIME")
    void attachmentWalksAgree(String name, String raw) throws Exception {
        MimeMessage message = parse(raw);

        Boolean flag = quietly(() -> MimePartExtractor.hasAttachments(message));
        List<AttachmentResponse> listed = quietly(() -> MimePartExtractor.extractAttachmentMetadata(message, ""));

        if (flag == null || listed == null) {
            // The parse threw for at least one of them; the caller degrades the
            // whole message, so there is no disagreement to have.
            return;
        }
        assertThat(flag).as(name).isEqualTo(!listed.isEmpty());
    }

    /**
     * Runs an entry point, returning null when it throws what its caller catches.
     */
    private static <T> @Nullable T quietly(Callable<T> entryPoint) {
        try {
            return entryPoint.call();
        } catch (Exception expected) {
            return null;
        }
    }

    @Nested
    @DisplayName("Bounds that the audit states")
    class Bounds {

        @Test
        @DisplayName("nesting past MAX_DEPTH does not reach the marker at the bottom")
        void depthBoundStopsTheWalk() throws Exception {
            assertThat(MimePartExtractor.extractBody(parse(nested(25))).text()).doesNotContain("DEEPMARKER");
        }

        @Test
        @DisplayName("nesting within MAX_DEPTH still reaches the marker")
        void withinTheBoundTheWalkCompletes() throws Exception {
            assertThat(MimePartExtractor.extractBody(parse(nested(5))).text()).contains("DEEPMARKER");
        }

        @Test
        @DisplayName("a referenced raster image is inlined")
        void referencedRasterIsInlined() throws Exception {
            String raw = headers("multipart/related; boundary=\"b\"") + "--b" + CRLF + "Content-Type: image/png" + CRLF
                    + "Content-ID: <logo@example.com>" + CRLF + "Content-Transfer-Encoding: base64" + CRLF + CRLF
                    + PNG_BASE64 + CRLF + "--b--" + CRLF;

            Map<String, String> images = MimePartExtractor.collectInlineImages(parse(raw), Set.of("logo@example.com"));

            assertThat(images).containsOnlyKeys("logo@example.com");
            assertThat(images.get("logo@example.com")).startsWith("data:image/png;base64,");
        }

        @Test
        @DisplayName("an SVG offered as an inline image is refused even when referenced")
        void svgIsNeverInlined() throws Exception {
            String raw = headers("multipart/related; boundary=\"b\"") + "--b" + CRLF + "Content-Type: image/svg+xml"
                    + CRLF + "Content-ID: <logo@example.com>" + CRLF + CRLF
                    + "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>" + CRLF + "--b--"
                    + CRLF;

            assertThat(MimePartExtractor.collectInlineImages(parse(raw), Set.of("logo@example.com"))).isEmpty();
        }

        @Test
        @DisplayName("an unreferenced image is not inlined")
        void unreferencedImageIsSkipped() throws Exception {
            String raw = headers("multipart/related; boundary=\"b\"") + "--b" + CRLF + "Content-Type: image/png" + CRLF
                    + "Content-ID: <logo@example.com>" + CRLF + "Content-Transfer-Encoding: base64" + CRLF + CRLF
                    + PNG_BASE64 + CRLF + "--b--" + CRLF;

            assertThat(MimePartExtractor.collectInlineImages(parse(raw), Set.of("other@example.com"))).isEmpty();
        }

        @Test
        @DisplayName("an RFC 2047 encoded attachment filename is decoded for display")
        void encodedFilenameIsDecoded() throws Exception {
            String raw = headers("multipart/mixed; boundary=\"b\"") + "--b" + CRLF + "Content-Type: application/pdf"
                    + CRLF + "Content-Disposition: attachment; filename=\"=?UTF-8?B?ZmFrdHVyYS5wZGY=?=\"" + CRLF + CRLF
                    + "%PDF-1.4" + CRLF + "--b--" + CRLF;

            List<AttachmentResponse> attachments = MimePartExtractor.extractAttachmentMetadata(parse(raw), "");

            assertThat(attachments).hasSize(1);
            assertThat(attachments.get(0).fileName()).isEqualTo("faktura.pdf");
        }
    }
}
