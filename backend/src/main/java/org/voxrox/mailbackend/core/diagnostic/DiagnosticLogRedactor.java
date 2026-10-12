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
     * match may not start right after one either.
     */
    private static final String LOCAL_PART_CHARACTER = "\\p{L}\\p{N}._%+!#$&'=?^`{|}~-";
    // '*' first in the lookbehind's class: after the '-' that ends
    // LOCAL_PART_CHARACTER it would make a range.
    private static final Pattern ADDRESS = Pattern.compile("(?<![*" + LOCAL_PART_CHARACTER + "])["
            + LOCAL_PART_CHARACTER + "]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.\\p{L}{2,}(?![\\p{L}\\p{N}_-])");

    /* The token shapes of frontend/scripts/lib/log-scan.mjs, in one pass. */
    private static final Pattern TOKENS = Pattern.compile(String.join("|", "\\bya29\\.[\\w-]{20,}",
            "(?<![\\w/])1//[\\w-]{20,}", "\\beyJ[\\w-]{8,}\\.[\\w-]{8,}\\.[\\w-]{8,}", "\\bEw[A-Za-z0-9+/]{40,}={0,2}",
            "\\bBearer\\s+[\\w.~+/-]{16,}=*", "\\bdXNlcj[0-3][A-Za-z0-9+/]{16,}={0,2}",
            "\\b(?:access_token|refresh_token|id_token|client_secret|code_verifier)[\"']?\\s*[=:]\\s*[\"']?[\\w.~+/-]{8,}"));

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
        return replaceAll(ADDRESS, result, match -> {
            addresses++;
            return LogMasker.maskEmail(match);
        });
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
