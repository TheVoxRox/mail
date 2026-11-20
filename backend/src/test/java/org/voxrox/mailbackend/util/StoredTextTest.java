package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoredTextTest {

    @Test
    @DisplayName("surrounding whitespace is removed, Unicode spaces included, inner whitespace kept")
    void stripsTheEnds() {
        assertThat(StoredText.stripToNull("  Jan  Novak \t")).isEqualTo("Jan  Novak");
        assertThat(StoredText.stripToNull(" Jan ")).isEqualTo("Jan");
        assertThat(StoredText.stripToNull("\nline one\nline two\n")).isEqualTo("line one\nline two");
    }

    @Test
    @DisplayName("nothing left, or nothing given, is null")
    void blankIsNull() {
        assertThat(StoredText.stripToNull(null)).isNull();
        assertThat(StoredText.stripToNull("")).isNull();
        assertThat(StoredText.stripToNull(" \t\n ")).isNull();
    }
}
