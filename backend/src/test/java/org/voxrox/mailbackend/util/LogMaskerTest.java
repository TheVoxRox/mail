package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LogMaskerTest {

    private static final String BOLD_A = "𝐀";
    private static final String BOLD_B = "𝐁";

    @Test
    @DisplayName("An address keeps the first and last character of its local part, one outside the BMP whole")
    void addressKeepsWholeCharacters() {
        assertThat(LogMasker.maskEmail("jan.novak@seznam.cz")).isEqualTo("j***k@seznam.cz");
        assertThat(LogMasker.maskEmail(BOLD_A + "jan" + BOLD_B + "@example.org"))
                .isEqualTo(BOLD_A + "***" + BOLD_B + "@example.org");
        assertThat(LogMasker.maskEmail(BOLD_A + "b@example.org")).isEqualTo(BOLD_A + "***@example.org");
        assertThat(LogMasker.maskEmail(BOLD_A + "@example.org")).isEqualTo(BOLD_A + "***@example.org");
    }

    @Test
    @DisplayName("A login without '@' keeps its first and last character, one outside the BMP whole")
    void loginKeepsWholeCharacters() {
        assertThat(LogMasker.maskEmail("jannovak")).isEqualTo("j***k");
        assertThat(LogMasker.maskEmail(BOLD_A + "an" + BOLD_B)).isEqualTo(BOLD_A + "***" + BOLD_B);
        assertThat(LogMasker.maskEmail(BOLD_A + BOLD_B)).isEqualTo("***");
    }
}
