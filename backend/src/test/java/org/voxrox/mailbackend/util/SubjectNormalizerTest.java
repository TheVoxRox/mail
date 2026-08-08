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
        // Every iteration consumes at least "re:", so the strip terminates on its
        // own. Within the length cap the result stays independent of how many
        // markers were stacked, so a deeply-nested reply still threads with a
        // shallow one.
        assertThat(SubjectNormalizer.normalize("Re: ".repeat(100) + "X")).isEqualTo("x");
        assertThat(SubjectNormalizer.normalize("Re: ".repeat(2) + "X")).isEqualTo("x");
    }

    @Test
    @DisplayName("A pathological subject cannot burn CPU during sync (audit B1-2)")
    void pathologicalSubjectIsBounded() {
        // The Subject header is attacker-controlled and nothing upstream bounds
        // it: MessageFetcher takes getSubject() verbatim and SQLite ignores the
        // schema's VARCHAR(500). Before the fix this input cost ~108 s on the
        // sync executor (O(n²) substring copying, measured on -Xmx384m); the
        // budget below is ~4 orders of magnitude above the fixed cost and still
        // fails loudly if the quadratic behaviour ever returns.
        String hostile = "Re: ".repeat(1_048_576) + "payload";

        long startNanos = System.nanoTime();
        String normalized = SubjectNormalizer.normalize(hostile);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(elapsedMillis).as("normalizing a 4 MiB marker chain").isLessThan(1_000);
        // Truncated to markers only, so there is no grouping key left — the row
        // simply does not participate in the subject fallback.
        assertThat(normalized).isNull();
    }

    @Test
    @DisplayName("The length cap does not change any realistic subject")
    void capLeavesRealisticSubjectsUntouched() {
        String longButLegitimate = "Re: " + "a".repeat(400);
        assertThat(SubjectNormalizer.normalize(longButLegitimate)).isEqualTo("a".repeat(400));

        // A subject whose payload starts beyond the cap keeps only what fits, which
        // is the point: the key is derived from a bounded prefix, deterministically.
        String beyondCap = "Re: " + "b".repeat(2_000);
        assertThat(SubjectNormalizer.normalize(beyondCap)).isEqualTo("b".repeat(996));
    }
}
