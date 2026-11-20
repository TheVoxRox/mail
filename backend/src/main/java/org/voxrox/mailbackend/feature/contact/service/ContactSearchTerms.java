package org.voxrox.mailbackend.feature.contact.service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.util.TextFold;

/**
 * A contact query as the words it is matched by: folded ({@link TextFold}),
 * split on spaces and on the commas and semicolons a pasted "Surname, Name"
 * carries, at most {@link #MAX_WORDS} of them.
 *
 * <p>
 * Every word has to match one of the contact's fields — given name, surname or
 * an address — and the fields need not be the same one. That is what lets "Jan
 * Novak", "novak jan" and "jan no" find Jan Novak: matched as one string, a
 * query with a space in it could only ever hit a field that held the space, and
 * no field holds a whole name. The compose typeahead was the visible victim:
 * the history row of the same person, whose display name is one string, still
 * matched, so the suggestion said the person was not in the address book.
 *
 * <p>
 * The words are LIKE patterns with the wildcards escaped
 * ({@link CorrespondentService#escapeLikeWildcards}), so "_" and "%" match
 * themselves: unescaped, a query of "_" listed the whole address book, and an
 * address such as {@code jan_novak@} also matched {@code jan.novak@}.
 *
 * <p>
 * Capped at four because the repository takes the words as four parameters (a
 * JPQL query cannot take a variable number of conditions). A fifth word and
 * later ones are dropped, which can only widen the result, never lose a match.
 * No name in an address book needs more than four words to be told apart.
 */
final class ContactSearchTerms {

    /** How many words the repository's queries take; see the class comment. */
    static final int MAX_WORDS = 4;

    private static final Pattern SEPARATORS = Pattern.compile("[\\s,;]+");

    private final List<String> words;

    private ContactSearchTerms(List<String> words) {
        this.words = words;
    }

    static ContactSearchTerms of(String query) {
        List<String> words = Arrays.stream(SEPARATORS.split(TextFold.fold(query))).filter(word -> !word.isEmpty())
                .limit(MAX_WORDS).toList();
        return new ContactSearchTerms(words);
    }

    /**
     * True for a query with nothing left to match, such as a lone comma or a
     * combining mark: it matches nothing rather than everything.
     */
    boolean isEmpty() {
        return words.isEmpty();
    }

    /**
     * The contains-pattern of word {@code index} (0-based), or {@code null} past
     * the last word, which the query reads as "no condition".
     */
    @Nullable
    String word(int index) {
        return index < words.size() ? "%" + CorrespondentService.escapeLikeWildcards(words.get(index)) + "%" : null;
    }

    /**
     * The words joined by single spaces: the query as a name prefix, the way a
     * folded "name surname" or "surname name" starts. "Novak, Jan" becomes
     * {@code novak jan}.
     */
    String phrase() {
        return String.join(" ", words);
    }

    /** {@link #phrase()} as an escaped LIKE prefix pattern. */
    String phrasePrefix() {
        return CorrespondentService.escapeLikeWildcards(phrase()) + "%";
    }
}
