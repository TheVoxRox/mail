package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TextFoldTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"Čermák, cermak", "ŠŤASTNÝ, stastny", "Říha, riha", "Úlehla, ulehla", "Žák, zak", "novák, novak",
            "Ďuriš, duris", "Ľubomír, lubomir", "Müller, muller", "Novak, novak"})
    @DisplayName("case and diacritics are removed")
    void foldsCaseAndDiacritics(String input, String expected) {
        assertThat(TextFold.fold(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a precomposed and a decomposed spelling fold the same")
    void precomposedAndDecomposedAgree() {
        // "Č" as one code point and as "C" followed by a combining caron.
        assertThat(TextFold.fold("Čermák")).isEqualTo(TextFold.fold("Čermák"));
    }

    @Test
    @DisplayName("the combining dot of the Turkish capital I is dropped, not kept")
    void turkishDottedCapitalI() {
        // Locale.ROOT lower-cases U+0130 to "i" plus U+0307; lower-casing before
        // the mark removal is what drops the dot.
        assertThat(TextFold.fold("İstanbul")).isEqualTo("istanbul");
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed, inner whitespace kept")
    void trims() {
        assertThat(TextFold.fold("  Jan  Novák ")).isEqualTo("jan  novak");
    }

    @Test
    @DisplayName("key is null for a missing or blank name, so it orders with the missing ones")
    void keyOfNothingIsNull() {
        assertThat(TextFold.key(null)).isNull();
        assertThat(TextFold.key("   ")).isNull();
        assertThat(TextFold.key("́")).isNull();
        assertThat(TextFold.key(" Čermák")).isEqualTo("cermak");
    }
}
