package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.mail.Part;

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
}
