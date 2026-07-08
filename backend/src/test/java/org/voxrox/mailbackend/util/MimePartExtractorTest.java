package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import jakarta.activation.DataHandler;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Robustness tests for {@link MimePartExtractor} against malformed MIME — the
 * input comes straight from arbitrary IMAP servers, so a part whose
 * Content-Type claims {@code multipart/*} while the body is not actually a
 * {@link jakarta.mail.Multipart} (JavaMail then hands back the raw String) must
 * degrade gracefully, not throw a ClassCastException that breaks the message
 * list.
 */
class MimePartExtractorTest {

    private static Part malformedMultipart() throws Exception {
        Part part = mock(Part.class);
        when(part.isMimeType("multipart/*")).thenReturn(true);
        lenient().when(part.isMimeType("text/plain")).thenReturn(false);
        lenient().when(part.isMimeType("text/html")).thenReturn(false);
        lenient().when(part.isMimeType("message/rfc822")).thenReturn(false);
        when(part.getContent()).thenReturn("--b\r\nthis is not a real multipart body");
        return part;
    }

    @Test
    @DisplayName("extractText degrades to empty when a 'multipart' body is not a Multipart")
    void extractTextHandlesMalformedMultipart() throws Exception {
        assertThat(MimePartExtractor.extractText(malformedMultipart())).isEmpty();
    }

    @Test
    @DisplayName("extractAttachmentMetadata returns empty for a malformed multipart")
    void extractAttachmentMetadataHandlesMalformedMultipart() throws Exception {
        assertThat(MimePartExtractor.extractAttachmentMetadata(malformedMultipart(), "")).isEmpty();
    }

    @Test
    @DisplayName("hasAttachments returns false for a malformed multipart")
    void hasAttachmentsHandlesMalformedMultipart() throws Exception {
        assertThat(MimePartExtractor.hasAttachments(malformedMultipart())).isFalse();
    }

    @Test
    @DisplayName("extractText reads the body of a plain-text part")
    void extractTextReadsPlainText() throws Exception {
        Part part = mock(Part.class);
        when(part.isMimeType("text/plain")).thenReturn(true);
        when(part.getFileName()).thenReturn(null);
        when(part.getContent()).thenReturn("hello world");

        assertThat(MimePartExtractor.extractText(part)).isEqualTo("hello world");
    }

    @Test
    @DisplayName("extractBody reports isHtml=false for a text/plain part")
    void extractBodyFlagsPlainText() throws Exception {
        Part part = mock(Part.class);
        when(part.isMimeType("text/plain")).thenReturn(true);
        when(part.getFileName()).thenReturn(null);
        when(part.getContent()).thenReturn("a<b and c>d");

        MimePartExtractor.ExtractedBody body = MimePartExtractor.extractBody(part);
        assertThat(body.isHtml()).isFalse();
        assertThat(body.text()).isEqualTo("a<b and c>d");
    }

    @Test
    @DisplayName("extractBody reports isHtml=true for a text/html part")
    void extractBodyFlagsHtml() throws Exception {
        Part part = mock(Part.class);
        lenient().when(part.isMimeType("text/plain")).thenReturn(false);
        when(part.isMimeType("text/html")).thenReturn(true);
        when(part.getFileName()).thenReturn(null);
        when(part.getContent()).thenReturn("<p>hi</p>");

        MimePartExtractor.ExtractedBody body = MimePartExtractor.extractBody(part);
        assertThat(body.isHtml()).isTrue();
        assertThat(body.text()).isEqualTo("<p>hi</p>");
    }

    @Test
    @DisplayName("extractBody prefers the text/html alternative and flags it as HTML")
    void extractBodyPrefersHtmlAlternative() throws Exception {
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("plain body", "utf-8");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>html body</p>", "text/html; charset=utf-8");

        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(plain);
        alternative.addBodyPart(html);

        MimeMessage message = new MimeMessage((Session) null);
        message.setContent(alternative);
        message.saveChanges();

        MimePartExtractor.ExtractedBody body = MimePartExtractor.extractBody(message);
        assertThat(body.isHtml()).isTrue();
        assertThat(body.text()).contains("<p>html body</p>");
    }

    private static MimeMessage relatedMessage(byte[] imageBytes, String imageType, String contentId) throws Exception {
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<p>hi</p><img src=\"cid:logo\">", "text/html; charset=utf-8");

        MimeBodyPart imagePart = new MimeBodyPart();
        imagePart.setDataHandler(new DataHandler(new ByteArrayDataSource(imageBytes, imageType)));
        imagePart.setHeader("Content-ID", contentId);

        MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(htmlPart);
        related.addBodyPart(imagePart);

        MimeMessage message = new MimeMessage((Session) null);
        message.setContent(related);
        // Finalize part headers (Content-Type from the DataHandler) so isMimeType /
        // getContentType see them, exactly as a message parsed off the wire would.
        message.saveChanges();
        return message;
    }

    @Test
    @DisplayName("collectInlineImages inlines a cid: raster image as a data: URI")
    void collectInlineImagesInlinesRaster() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        Map<String, String> images = MimePartExtractor.collectInlineImages(relatedMessage(png, "image/png", "<logo>"),
                Set.of("logo"));

        assertThat(images).containsKey("logo");
        String uri = images.get("logo");
        assertThat(uri).startsWith("data:image/png;base64,");
        byte[] decoded = Base64.getDecoder().decode(uri.substring("data:image/png;base64,".length()));
        assertThat(decoded).isEqualTo(png);
    }

    @Test
    @DisplayName("collectInlineImages never inlines SVG (it can carry script)")
    void collectInlineImagesRejectsSvg() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);

        assertThat(
                MimePartExtractor.collectInlineImages(relatedMessage(svg, "image/svg+xml", "<logo>"), Set.of("logo")))
                .isEmpty();
    }

    @Test
    @DisplayName("collectInlineImages skips an image over the per-image size cap")
    void collectInlineImagesSkipsOversized() throws Exception {
        byte[] tooBig = new byte[2 * 1024 * 1024 + 1]; // just over the 2 MiB per-image cap

        assertThat(MimePartExtractor.collectInlineImages(relatedMessage(tooBig, "image/png", "<logo>"), Set.of("logo")))
                .isEmpty();
    }

    @Test
    @DisplayName("collectInlineImages ignores an inline image the body does not reference")
    void collectInlineImagesSkipsUnreferenced() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        // The part's Content-ID is <logo>, but the body references only cid:other.
        assertThat(MimePartExtractor.collectInlineImages(relatedMessage(png, "image/png", "<logo>"), Set.of("other")))
                .isEmpty();
    }
}
