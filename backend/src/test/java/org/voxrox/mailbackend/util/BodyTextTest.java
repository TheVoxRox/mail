package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BodyTextTest {

    @Test
    @DisplayName("No cached body has no text, so body_text stays null with content")
    void nullBodyHasNullText() {
        assertThat(BodyText.of(null)).isNull();
    }

    @Test
    @DisplayName("A text/plain body, cached escaped, gives back the text the sender wrote")
    void plainTextBodyRoundTrips() {
        String cached = HtmlSanitizer.escapePlainText("a < b & c, viz https://example.com/x?y=1");

        assertThat(BodyText.of(cached)).isEqualTo("a < b & c, viz https://example.com/x?y=1");
    }

    @Test
    @DisplayName("Style data and attribute values are not text")
    void styleAndAttributesAreDropped() {
        assertThat(BodyText.of("<style>p { color: red }</style><p title=\"titulek\">obsah</p>")).isEqualTo("obsah");
    }
}
