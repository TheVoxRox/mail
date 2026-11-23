package org.voxrox.mailbackend.util;

import org.jspecify.annotations.Nullable;

/**
 * The form text the user types is stored in, the rule {@code docs/DECISIONS.md}
 * records under "Single-line text is stored without surrounding whitespace, the
 * rest as written".
 *
 * <p>
 * Surrounding whitespace carries no meaning in a single-line value, and stored
 * it leaks everywhere the value is shown: a doubled space between name and
 * surname in the contact list, a vCard export that carries it, a display name
 * in the From header with it, and a name of spaces alone that reads as present,
 * so the list showed no placeholder and a screen reader announced the row with
 * no name. Null rather than an empty string, so a blank value means the same as
 * a missing one to every query and every check. Multi-line text keeps its
 * layout, which is content: only the blank lines at its start and the
 * whitespace at its end go.
 *
 * <p>
 * Whitespace is the set JavaScript's {@code String.prototype.trim()} removes,
 * so the frontend and the backend agree on every value: every space separator
 * ({@code Zs}, the no-break spaces U+00A0, U+2007 and U+202F included), tab,
 * vertical tab, form feed, U+FEFF and the line terminators LF, CR, U+2028 and
 * U+2029. {@link String#strip()} is not that set: it keeps the no-break spaces
 * and U+FEFF, the characters a paste from a word processor or a web page most
 * often brings along, and removes U+001C to U+001F, which JavaScript keeps. The
 * shared vectors in {@code text/stored-text-vectors.json} (test resources) run
 * against both implementations.
 *
 * <p>
 * Applied at the boundary of the API and again where the value enters the
 * entity, so no write path can skip it; and by
 * {@code V2__Fold_keys_and_strip_text}, which brings the rows stored before it
 * to the same form.
 *
 * <p>
 * So a change to what this class returns ships with a migration, {@code V<n>},
 * that brings every stored value to the new form on every installation. V2
 * strips with this class, so an installation that runs V2 after the change, one
 * updating from a release before V2, would store the new form where the others
 * keep the old one. The methods V2 calls, {@link #strip}, {@link #stripToNull}
 * and {@link #stripBlankLinesToNull}, keep their signatures, as a released
 * migration is never edited to follow a change ({@code docs/DECISIONS.md}, "A
 * released migration is frozen by its tag").
 */
public final class StoredText {

    private StoredText() {
    }

    /**
     * A required single-line value without the whitespace around it. Blank stays
     * blank, for the validation to refuse.
     */
    public static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * An optional single-line value without the whitespace around it, and
     * {@code null} when nothing else is left.
     */
    public static @Nullable String stripToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String stripped = strip(value);
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * A multi-line value without the blank lines it starts with and the whitespace
     * it ends with, and {@code null} when nothing else is left. The indentation of
     * its first line stays: stripping it would put that line out of line with the
     * rest.
     */
    public static @Nullable String stripBlankLinesToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        int end = value.length();
        while (end > 0 && isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        if (end == 0) {
            return null;
        }
        int start = 0;
        for (int i = 0; i < end && isWhitespace(value.charAt(i)); i++) {
            if (isLineTerminator(value.charAt(i))) {
                start = i + 1;
            }
        }
        return value.substring(start, end);
    }

    /**
     * Whitespace by the rule above. Every character in the set is in the Basic
     * Multilingual Plane, so a surrogate is never one, and a {@code char} at a time
     * reads it exactly.
     */
    private static boolean isWhitespace(char c) {
        return switch (c) {
            case '\t', '\u000B', '\f', '\uFEFF' -> true;
            default -> isLineTerminator(c) || Character.getType(c) == Character.SPACE_SEPARATOR;
        };
    }

    private static boolean isLineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
    }
}
