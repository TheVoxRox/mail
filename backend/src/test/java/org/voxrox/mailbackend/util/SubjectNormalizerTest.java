package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubjectNormalizerTest {

    @Test
    @DisplayName("Plain subject lowercases and trims")
    void plainSubjectNormalizes() {
        assertThat(SubjectNormalizer.normalize("  Vylet na Hory  ")).isEqualTo("vylet na hory");
    }

    @Test
    @DisplayName("Reply and forward markers strip, including localized and counted forms")
    void markersStrip() {
        assertThat(SubjectNormalizer.normalize("Re: X")).isEqualTo("x");
        assertThat(SubjectNormalizer.normalize("RE: X")).isEqualTo("x");
        assertThat(SubjectNormalizer.normalize("Odp: X")).isEqualTo("x");
        assertThat(SubjectNormalizer.normalize("FW: X")).isEqualTo("x");
        assertThat(SubjectNormalizer.normalize("Fwd: X")).isEqualTo("x");
        assertThat(SubjectNormalizer.normalize("Re[2]: X")).isEqualTo("x");
        assertThat(SubjectNormalizer.normalize("AW: X")).isEqualTo("x");
        assertThat(SubjectNormalizer.normalize("WG: X")).isEqualTo("x");
    }

    @Test
    @DisplayName("Stacked markers all strip")
    void stackedMarkersStrip() {
        assertThat(SubjectNormalizer.normalize("Re: Odp: FW: Vylet")).isEqualTo("vylet");
    }

    @Test
    @DisplayName("Inner whitespace collapses so client re-wrapping does not split threads")
    void whitespaceCollapses() {
        assertThat(SubjectNormalizer.normalize("Re: Vylet   na\thory")).isEqualTo("vylet na hory");
    }

    @Test
    @DisplayName("A marker-like word inside the subject is untouched")
    void innerMarkerWordSurvives() {
        assertThat(SubjectNormalizer.normalize("Prodam auto: rezervace")).isEqualTo("prodam auto: rezervace");
        // "Report:" is not in the marker set — must not be stripped.
        assertThat(SubjectNormalizer.normalize("Report: Q3")).isEqualTo("report: q3");
    }

    @Test
    @DisplayName("Null, blank and marker-only subjects normalize to null")
    void degenerateSubjectsAreNull() {
        assertThat(SubjectNormalizer.normalize(null)).isNull();
        assertThat(SubjectNormalizer.normalize("   ")).isNull();
        assertThat(SubjectNormalizer.normalize("Re:")).isNull();
        assertThat(SubjectNormalizer.normalize("Re: Fwd:  ")).isNull();
    }

    @Test
    @DisplayName("Non-breaking space is treated as whitespace everywhere in the subject")
    void nonBreakingSpaceNormalizes() {
        // U+00A0 routinely survives RFC 2047 decoding from webmails, and neither
        // plain \\s nor String.trim()/strip() removes it. A subject that keeps one
        // normalizes to a different key than the parent's and the fallback stops
        // matching without any visible symptom.
        String nbsp = "\u00A0";
        assertThat(SubjectNormalizer.normalize("Re:" + nbsp + "Faktura")).isEqualTo("faktura");
        assertThat(SubjectNormalizer.normalize(nbsp + "Re: Faktura" + nbsp)).isEqualTo("faktura");
        assertThat(SubjectNormalizer.normalize("Re: Vylet" + nbsp + nbsp + "na hory")).isEqualTo("vylet na hory");
        assertThat(SubjectNormalizer.hasConversationMarker(nbsp + "Re: Faktura")).isTrue();
        assertThat(SubjectNormalizer.normalize(nbsp)).isNull();
    }

    @Test
    @DisplayName("storedNorm maps a subject with no grouping key to the empty sentinel, never null")
    void storedNormUsesSentinelForDegenerateSubjects() {
        // The backfill selects rows by `subject_norm IS NULL`; a degenerate subject
        // left NULL would re-match that predicate on every startup.
        assertThat(SubjectNormalizer.storedNorm("Re: Faktura")).isEqualTo("faktura");
        assertThat(SubjectNormalizer.storedNorm("Re:")).isEqualTo(SubjectNormalizer.NO_GROUPING_KEY);
        assertThat(SubjectNormalizer.storedNorm("   ")).isEqualTo(SubjectNormalizer.NO_GROUPING_KEY);
        assertThat(SubjectNormalizer.storedNorm(null)).isEqualTo(SubjectNormalizer.NO_GROUPING_KEY);
        // The sentinel must be unreachable as a lookup key, or every subject-less
        // message would group with every other.
        assertThat(SubjectNormalizer.normalize("anything")).isNotEqualTo(SubjectNormalizer.NO_GROUPING_KEY);
    }

    @Test
    @DisplayName("hasConversationMarker detects both families and rejects plain subjects")
    void conversationMarkerDetects() {
        assertThat(SubjectNormalizer.hasConversationMarker("Re: X")).isTrue();
        assertThat(SubjectNormalizer.hasConversationMarker("  odp: X")).isTrue();
        assertThat(SubjectNormalizer.hasConversationMarker("Fwd: X")).isTrue();
        assertThat(SubjectNormalizer.hasConversationMarker("X")).isFalse();
        assertThat(SubjectNormalizer.hasConversationMarker("Rekonstrukce bytu")).isFalse();
        assertThat(SubjectNormalizer.hasConversationMarker(null)).isFalse();
    }

    @Test
    @DisplayName("Reply and forward markers are told apart for subject prefill")
    void replyAndForwardMarkersAreDistinct() {
        // A reply to a forwarded message is "Re: Fwd: X" — a forward marker must
        // not suppress the "Re: ", and vice versa.
        assertThat(SubjectNormalizer.startsWithReplyMarker("Re: X")).isTrue();
        assertThat(SubjectNormalizer.startsWithReplyMarker("Odp: X")).isTrue();
        assertThat(SubjectNormalizer.startsWithReplyMarker("AW: X")).isTrue();
        assertThat(SubjectNormalizer.startsWithReplyMarker(" re[2]: X")).isTrue();
        assertThat(SubjectNormalizer.startsWithReplyMarker("Fwd: X")).isFalse();
        assertThat(SubjectNormalizer.startsWithReplyMarker("WG: X")).isFalse();
        assertThat(SubjectNormalizer.startsWithReplyMarker(null)).isFalse();

        assertThat(SubjectNormalizer.startsWithForwardMarker("Fwd: X")).isTrue();
        assertThat(SubjectNormalizer.startsWithForwardMarker("fw: X")).isTrue();
        assertThat(SubjectNormalizer.startsWithForwardMarker("WG: X")).isTrue();
        assertThat(SubjectNormalizer.startsWithForwardMarker("Re: X")).isFalse();
        assertThat(SubjectNormalizer.startsWithForwardMarker("Odp: X")).isFalse();
        assertThat(SubjectNormalizer.startsWithForwardMarker(null)).isFalse();
    }

    @Test
    @DisplayName("A long Re: chain strips completely — normalization is length-independent")
    void longMarkerChainStripsCompletely() {
        // The strip loop is uncapped: every iteration consumes at least "re:", so
        // it terminates on its own. With a cap, a subject that stacked more markers
        // than the cap would normalize to a different key than the same subject
        // with fewer — the two would stop threading together.
        assertThat(SubjectNormalizer.normalize("Re: ".repeat(100) + "X")).isEqualTo("x");
        assertThat(SubjectNormalizer.normalize("Re: ".repeat(2) + "X")).isEqualTo("x");
    }
}
