package org.voxrox.mailbackend.core.diagnostic;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.util.LogMasker;

import module java.base;

/**
 * The safety net a log line passes on its way into the diagnostic bundle.
 *
 * <p>
 * The bundle carries the logs as they are on disk, folder names, account names
 * and paths included: what the user's own log holds may go to the developers
 * the user sends it to. What a log must never hold is another matter, and the
 * log hygiene audits hold it to that rule: no unmasked address, no token, not
 * the session API key. Our own log sites keep it ({@link LogMasker}); text a
 * server or a library wrote into an exception does not have to, and a
 * {@code 550 <someone@example.org> unknown} from an SMTP server is logged as it
 * came. This replaces only what breaks that rule, so a line that keeps it goes
 * into the bundle unchanged.
 *
 * <p>
 * The shell's Save logs ZIP, the stand-in for this bundle when the backend does
 * not start, applies the same rules in
 * {@code frontend/src-tauri/src/redact.rs}. Keep the two in step: a rule added
 * to one and not the other makes the two ZIPs answer to different policies.
 *
 * <p>
 * Both read a line by character (code point), not by UTF-16 unit, and in time
 * that grows with its length: the text is a server's, so a line of tens of
 * thousands of combining marks or of characters outside the Basic Multilingual
 * Plane is something to expect, and the user's download waits for it.
 */
final class DiagnosticLogRedactor {

    private static final String TOKEN = "[token]";
    private static final String API_KEY = "[api-key]";

    /*
     * Wider than the pattern in frontend/scripts/lib/log-scan.mjs on purpose. It is
     * Unicode-aware, because with an ASCII local part "jürgen@example.de" would
     * match from the letter after the umlaut and come out as "jür***n@example.de".
     * And the local part takes the characters RFC 5322 allows there, because with
     * fewer "o'brien@firma.cz" would keep "o'" and a Gmail Message-ID such as
     * "<CAFoo=Xy+abc@mail.gmail.com>" its "CAFoo=". Two are left out: '/', which
     * would take a whole path in front of an address, and '*', which keeps an
     * address LogMasker already masked ("j***k@seznam.cz") from matching again — a
     * match may not start right after one either. Besides these, a local part takes
     * any letter or number (\p{L}, \p{N}).
     */
    private static final String LOCAL_PART_PUNCTUATION = "._%+!#$&'=?^`{|}~-";

    /*
     * The token shapes of frontend/scripts/lib/log-scan.mjs, in one pass. A token
     * starts where no ASCII word character comes before it. Not \b, which also
     * takes a non-spacing mark on a letter or digit for part of a word: it walked
     * back through every mark before each position (A5), and it let an accent
     * written as a mark hide the token the accented letter does not.
     */
    private static final Pattern TOKENS = Pattern.compile(String.join("|", "(?<!\\w)ya29\\.[\\w-]{20,}",
            "(?<![\\w/])1//[\\w-]{20,}", "(?<!\\w)eyJ[\\w-]{8,}\\.[\\w-]{8,}\\.[\\w-]{8,}",
            "(?<!\\w)Ew[A-Za-z0-9+/]{40,}={0,2}", "(?<!\\w)Bearer\\s+[\\w.~+/-]{16,}=*",
            "(?<!\\w)dXNlcj[0-3][A-Za-z0-9+/]{16,}={0,2}",
            "(?<!\\w)(?:access_token|refresh_token|id_token|client_secret|code_verifier)[\"']?\\s*[=:]\\s*[\"']?[\\w.~+/-]{8,}"));

    private final @Nullable String apiKey;

    private long addresses;
    private long tokens;
    private long apiKeys;

    /** {@code apiKey} is the session's key, or {@code null} when there is none. */
    DiagnosticLogRedactor(@Nullable String apiKey) {
        this.apiKey = apiKey != null && !apiKey.isBlank() ? apiKey : null;
    }

    /** The line as the bundle carries it. */
    String redact(String line) {
        String result = line;
        if (apiKey != null && result.contains(apiKey)) {
            apiKeys++;
            result = result.replace(apiKey, API_KEY);
        }
        result = replaceAll(TOKENS, result, match -> {
            tokens++;
            return TOKEN;
        });
        return maskAddresses(result);
    }

    /*
     * Every address not masked yet, masked: what the regex [local
     * part]+@[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)*\.\p{L}{2,} finds where it does not
     * run on into [\p{L}\p{N}_-], scanned by hand, as redact.rs reaches the same
     * match by regex and fallback. As a regex it went wrong three ways
     * (API_SURFACE_AUDIT.md A4, A5, A6): a lookbehind reads one UTF-16 unit back,
     * so after a letter outside the Basic Multilingual Plane it saw half of one,
     * started a match there where redact.rs does not, and started again after every
     * such letter along a run, each time scanning the rest of it; and the group
     * over the domain's labels took a stack frame a label, so a domain of two
     * thousand of them threw StackOverflowError and the whole dump failed.
     *
     * A match starts only where the character before it is neither a '*' nor one an
     * address may hold, so a run of such characters is tried once, from its first,
     * and the next one tried is the first after its '@'.
     */
    private String maskAddresses(String text) {
        if (text.indexOf('@') < 0) {
            return text;
        }
        StringBuilder result = new StringBuilder(text.length());
        int copied = 0;
        int previous = -1;
        int index = 0;
        while (index < text.length()) {
            int character = text.codePointAt(index);
            if (previous == '*' || isLocalPartCharacter(previous) || !isLocalPartCharacter(character)) {
                previous = character;
                index += Character.charCount(character);
                continue;
            }
            int at = skip(text, index, DiagnosticLogRedactor::isLocalPartCharacter);
            int end = at < text.length() && text.charAt(at) == '@' ? addressEnd(text, at) : -1;
            if (end < 0) {
                index = at;
            } else {
                addresses++;
                result.append(text, copied, index).append(LogMasker.maskEmail(text.substring(index, end)));
                copied = end;
                index = end;
            }
            previous = text.codePointBefore(index);
        }
        return copied == 0 ? text : result.append(text, copied, text.length()).toString();
    }

    /*
     * Where the address with its '@' at `at` ends, or -1. Its domain is labels of
     * [\p{L}\p{N}-] joined by single dots, and it ends at a top-level domain of two
     * letters or more right after one of those dots that does not run on; the
     * latest such dot wins, which is the order the regex's backtracking gives
     * labels up in. A label is never a dot, so the dots between `at` and the end of
     * the labels are exactly the dots that join them.
     */
    private static int addressEnd(String text, int at) {
        int labelsEnd = skip(text, at + 1, DiagnosticLogRedactor::isLabelCharacter);
        if (labelsEnd == at + 1) {
            return -1;
        }
        while (labelsEnd < text.length() && text.charAt(labelsEnd) == '.') {
            int next = skip(text, labelsEnd + 1, DiagnosticLogRedactor::isLabelCharacter);
            if (next == labelsEnd + 1) {
                break;
            }
            labelsEnd = next;
        }
        // Not lastIndexOf: it would search on past the '@', to the start of the line.
        for (int dot = labelsEnd - 1; dot > at; dot--) {
            if (text.charAt(dot) != '.') {
                continue;
            }
            int end = skip(text, dot + 1, Character::isLetter);
            if (text.codePointCount(dot + 1, end) >= 2 && (end == text.length() || !runsOn(text.codePointAt(end)))) {
                return end;
            }
        }
        return -1;
    }

    /* Past the characters from `index` on that `test` takes. */
    private static int skip(String text, int index, IntPredicate test) {
        int end = index;
        while (end < text.length() && test.test(text.codePointAt(end))) {
            end += Character.charCount(text.codePointAt(end));
        }
        return end;
    }

    private static boolean isLocalPartCharacter(int character) {
        return Character.isLetter(character) || isNumber(character) || LOCAL_PART_PUNCTUATION.indexOf(character) >= 0;
    }

    private static boolean isLabelCharacter(int character) {
        return Character.isLetter(character) || isNumber(character) || character == '-';
    }

    /* What an address may not run on into: [\p{L}\p{N}_-]. */
    private static boolean runsOn(int character) {
        return isLabelCharacter(character) || character == '_';
    }

    /* \p{N}: a number's three categories. */
    private static boolean isNumber(int character) {
        int type = Character.getType(character);
        return type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
    }

    /** How many replacements of each kind this redactor has made so far. */
    Counts counts() {
        return new Counts(addresses, tokens, apiKeys);
    }

    record Counts(long addresses, long tokens, long apiKeys) {
    }

    private static String replaceAll(Pattern pattern, String text, UnaryOperator<String> replacement) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder result = new StringBuilder(text.length());
        do {
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.apply(matcher.group())));
        } while (matcher.find());
        matcher.appendTail(result);
        return result.toString();
    }
}
