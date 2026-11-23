package org.voxrox.mailbackend.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The form names are compared in when the user searches or sorts the address
 * book: lower case, without diacritics, trimmed. A Czech surname and the same
 * surname typed without its carons and accents fold to one key.
 *
 * <p>
 * It exists because SQLite cannot do this on its own. Its {@code lower()} and
 * {@code LIKE} fold ASCII letters only, so a stored surname starting with a
 * capital C with a caron was never found by its own first letters typed in
 * lower case (the query side is folded in Java, the stored side was not), and
 * {@code ORDER BY} compares code points, which put every name starting with a
 * letter outside ASCII, and every name written in lower case, after Z. The
 * folded form is computed here and stored beside the name, so both searching
 * and ordering compare like with like.
 *
 * <p>
 * The same rule the mail search already follows: its FTS5 index uses the
 * {@code unicode61} tokenizer, which ignores case and diacritics too.
 *
 * <p>
 * Deliberately not a Czech collation. A folded key puts a C with a caron among
 * the C names rather than after all of them, and "Chalupa" among the C names
 * rather than after H. A real Czech order depends on the language the list is
 * read in ("Chapman" belongs after "Hughes" only in Czech), and a stored key
 * cannot follow the user switching languages; why this was accepted is in
 * docs/DECISIONS.md.
 *
 * <p>
 * Letters with no decomposition in Unicode (the Polish l with a stroke, the
 * Danish o with a stroke) keep their form; every Czech and Slovak letter
 * decomposes.
 *
 * <p>
 * A stored key is only as good as its agreement with the query side, so a
 * change to what this class returns ships with a migration, {@code V<n>}, that
 * recomputes every stored key on every installation. Without it the rows
 * written before the change stop matching the queries written after it. The
 * same migration keeps the installations alike:
 * {@code V2__Fold_keys_and_strip_text} computes its keys with this class, so an
 * installation that runs V2 after the change, one updating from a release
 * before V2, would store the new keys where the others keep the old ones. The
 * methods V2 calls, {@link #fold} and {@link #key}, keep their signatures, as a
 * released migration is never edited to follow a change
 * ({@code docs/DECISIONS.md}, "A released migration is frozen by its tag").
 */
public final class TextFold {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private TextFold() {
    }

    /**
     * Folds {@code text}: lower case first, then decomposed (NFD) with the
     * combining marks removed, then trimmed. Lower-casing comes first because it
     * can itself produce a combining mark — Locale.ROOT turns the Turkish capital I
     * with a dot above (U+0130) into "i" plus a combining dot, which the removal
     * then drops. Locale.ROOT rather than the default locale for the reason given
     * on {@code ContactLabelService.toNameKey}: the result is stored.
     */
    public static String fold(String text) {
        String decomposed = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("").strip();
    }

    /**
     * The stored key of a nullable name: its {@link #fold}, or {@code null} when
     * there is no name or nothing is left of it. Null rather than an empty string
     * so that a blank name orders with the missing ones ({@code NULLS LAST})
     * instead of ahead of every real name.
     */
    public static @Nullable String key(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String folded = fold(text);
        return folded.isEmpty() ? null : folded;
    }
}
