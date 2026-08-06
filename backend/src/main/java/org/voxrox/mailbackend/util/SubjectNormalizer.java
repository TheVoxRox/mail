package org.voxrox.mailbackend.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Normalizes RFC 5322 subjects for the threading subject-fallback (see
 * {@code ThreadingService}). Two clients replying to the same conversation may
 * render the marker differently ({@code "Re:"}, {@code "RE:"}, Seznam's
 * {@code "Odp:"}, Outlook's counted {@code "Re[2]:"}), so equality on the raw
 * subject would miss most real-world pairs — the normal form strips every
 * leading reply/forward marker, collapses whitespace and lowercases.
 */
public final class SubjectNormalizer {

    /**
     * Reply markers our target clients emit: the universal {@code Re:}, Czech
     * Seznam's {@code Odp:} and German Outlook's {@code AW:} (Antwort).
     */
    private static final String REPLY_MARKERS = "re|odp|aw";

    /**
     * Forward markers: {@code Fw:}/{@code Fwd:} and German Outlook's {@code WG:}
     * (Weitergeleitet). Kept apart from {@link #REPLY_MARKERS} because prefilling a
     * subject has to tell the two families apart — a reply to {@code "Fwd: X"} is
     * {@code "Re: Fwd: X"}, not {@code "Fwd: X"}.
     */
    private static final String FORWARD_MARKERS = "fw|fwd|wg";

    /**
     * One leading reply/forward marker, with an optional bracketed counter
     * ({@code Re[2]:}). Applied repeatedly, so stacked markers
     * ({@code "Re: Odp: X"}) all strip.
     */
    private static final Pattern LEADING_MARKER = leadingMarker(REPLY_MARKERS + "|" + FORWARD_MARKERS);

    private static final Pattern LEADING_REPLY_MARKER = leadingMarker(REPLY_MARKERS);

    private static final Pattern LEADING_FORWARD_MARKER = leadingMarker(FORWARD_MARKERS);

    private static Pattern leadingMarker(String alternation) {
        return Pattern.compile("^\\s*(" + alternation + ")(\\[\\d+\\])?\\s*:",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    }

    /**
     * A run of whitespace, Unicode-aware. {@link Pattern#UNICODE_CHARACTER_CLASS}
     * matters here: plain {@code \s} matches ASCII whitespace only, and neither
     * {@link String#trim()} nor {@link String#strip()} removes U+00A0 NO-BREAK
     * SPACE — which webmails routinely leave in a subject after RFC 2047 decoding.
     * Without it {@code "Re: Faktura"} normalizes to {@code " faktura"}, the
     * byte-comparing SQLite lookup misses the {@code "faktura"} stored for the
     * parent and the fallback silently stops working. Precompiled — normalization
     * runs once per message during sync and once per row during the backfill.
     */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Stored form of a subject that carries no grouping key — see
     * {@link #storedNorm}. Never equal to any value {@link #normalize} returns, so
     * a row holding it can never match a fallback lookup.
     */
    public static final String NO_GROUPING_KEY = "";

    private SubjectNormalizer() {
    }

    /**
     * Returns the normal form of {@code subject}: leading reply/forward markers
     * stripped, inner whitespace collapsed to single spaces, trimmed, lowercased
     * with {@link Locale#ROOT}. Returns {@code null} when the input is null or
     * nothing remains (an empty subject must never become a grouping key — it would
     * merge every subject-less message into one giant thread).
     */
    public static @Nullable String normalize(@Nullable String subject) {
        if (subject == null) {
            return null;
        }
        String stripped = stripMarkers(subject);
        // Collapse first, trim second: the collapse rewrites every whitespace run
        // (including U+00A0, which trim() would not touch) to a single ASCII space,
        // so the trailing trim() then reaches a leading/trailing no-break space too.
        String collapsed = WHITESPACE.matcher(stripped).replaceAll(" ").trim();
        if (collapsed.isEmpty()) {
            return null;
        }
        return collapsed.toLowerCase(Locale.ROOT);
    }

    /**
     * The value to persist in {@code messages.subject_norm}: {@link #normalize} or
     * {@link #NO_GROUPING_KEY} when the subject normalizes to nothing.
     *
     * <p>
     * The sentinel exists so that "processed" is distinguishable from "not
     * processed yet" in SQL. The backfill selects rows by {@code subject_norm IS
     * NULL}; leaving a degenerate subject ({@code "Re:"}, whitespace only) NULL
     * would re-select the same rows on every startup, so the pass could never
     * settle into a no-op. An empty norm is inert on the read side — the fallback
     * lookup is only ever called with a non-empty key.
     */
    public static String storedNorm(@Nullable String subject) {
        String norm = normalize(subject);
        return norm == null ? NO_GROUPING_KEY : norm;
    }

    /**
     * Whether the subject starts with at least one reply <em>or</em> forward
     * marker. The subject-fallback attaches only marked messages — an unmarked
     * subject carries no evidence of being part of an existing conversation, and
     * matching it would merge independent messages that merely share a name (two
     * "Faktura" notifications). Both families count: a forwarded message continues
     * a conversation just as much as a reply does.
     */
    public static boolean hasConversationMarker(@Nullable String subject) {
        return subject != null && LEADING_MARKER.matcher(subject).find();
    }

    /**
     * Whether the subject already starts with a reply marker — the guard against
     * prefilling {@code "Re: Re: X"} when composing a reply. Narrower than
     * {@link #hasConversationMarker} on purpose: replying to a forwarded message
     * yields {@code "Re: Fwd: X"}, so a forward marker must not suppress the
     * {@code "Re: "}.
     */
    public static boolean startsWithReplyMarker(@Nullable String subject) {
        return subject != null && LEADING_REPLY_MARKER.matcher(subject).find();
    }

    /** Forward-side counterpart of {@link #startsWithReplyMarker}. */
    public static boolean startsWithForwardMarker(@Nullable String subject) {
        return subject != null && LEADING_FORWARD_MARKER.matcher(subject).find();
    }

    /**
     * Strips every leading marker. Uncapped: each iteration consumes a match that
     * is at least three characters long ({@code "re:"}), so {@code current} shrinks
     * strictly and the loop is finite by construction — a cap would only make a
     * long {@code "Re: Re: …"} chain normalize to something different from a short
     * one, which is worse than the linear work it saves.
     */
    private static String stripMarkers(String subject) {
        String current = subject;
        while (true) {
            Matcher matcher = LEADING_MARKER.matcher(current);
            if (!matcher.find()) {
                return current;
            }
            current = current.substring(matcher.end());
        }
    }
}
