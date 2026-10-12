package org.voxrox.mailbackend.core.diagnostic;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.util.LogMasker;

import module java.base;

/**
 * Rewrites a log line for the copy of the logs that goes into the diagnostic
 * bundle.
 *
 * <p>
 * {@code mail.log} keeps what the user would recognise: folder names in full
 * and paths under the home directory, because it stays on the machine (see
 * {@link DiagnosticDumpService}). The bundle leaves it, so its copy of each log
 * follows the bundle's rule instead:
 * <ul>
 * <li>an address anywhere in a line is masked the way {@link LogMasker} masks
 * one, which also catches a Message-ID and an address a server wrote into an
 * error;</li>
 * <li>a token shape, the ones {@code npm run release:scan-logs} looks for,
 * becomes {@code [token]};</li>
 * <li>a known value (a folder name, an account's name and display name, a
 * login, the session API key) becomes the label the rest of the bundle uses for
 * it, also in the percent-encoded form a request path carries it in;</li>
 * <li>the home directory becomes {@code ~} and the Windows account name
 * {@code [user]}.</li>
 * </ul>
 *
 * <p>
 * Known values are replaced as whole words only, case-sensitively, and only in
 * a line's message: the timestamp, level, thread and logger in front of it stay
 * as written, so a folder called "main" does not rename the main thread and
 * {@code npm run release:scan-logs} still parses the copy. A value shorter than
 * three characters or without a letter is left alone: a folder called "2025"
 * would otherwise rewrite every number that happens to match, and says no more
 * about the user than the year does.
 *
 * <p>
 * What this cannot reach is a value it was not told about. A folder the app
 * never listed or synchronised in this run, or one deleted before the bundle
 * was made, keeps its name in older lines.
 */
final class DiagnosticLogRedactor {

    /** A replacement the bundle writes for a known value of each kind. */
    private static final String TOKEN = "[token]";
    private static final String USER = "[user]";

    private static final int MIN_KNOWN_LENGTH = 3;

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

    /*
     * The three line shapes the logs folder holds, up to where the message starts:
     * Spring Boot's file line (mail.log), the AUDIT appender of logback-spring.xml
     * (audit.log) and tauri-plugin-log's default (mail-frontend.log). A line that
     * matches none, a stack frame for one, is all message.
     */
    private static final Pattern HEADER = Pattern.compile(String.join("|",
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?\\s+[A-Z]+\\s+\\S+\\s+---\\s+\\[[^\\]]*\\]\\s+\\[[^\\]]*\\]\\s+\\S+\\s*:\\s?",
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[[^\\]]*\\] [A-Z]+\\s+",
            "^\\[[^\\]]*\\]\\[[^\\]]*\\]\\[[^\\]]*\\]\\[[A-Z]+\\] "));

    private static final String NOT_A_WORD_CHARACTER_BEFORE = "(?<![\\p{L}\\p{N}])";
    private static final String NOT_A_WORD_CHARACTER_AFTER = "(?![\\p{L}\\p{N}])";

    private final Map<String, String> known;
    /*
     * The known values by their first character, longest first. Not one regex
     * alternation of them all: Java tries an alternation value by value at every
     * position, so a mailbox with a few hundred folders made the cost the size of
     * the logs times the number of values. Looking up the character at a word's
     * start leaves the few values that can start there.
     */
    private final Map<Character, List<String>> knownByFirstCharacter;
    private final @Nullable Pattern homePattern;
    private final @Nullable Pattern userPattern;

    private long addresses;
    private long tokens;
    private long knownValues;
    private long homeDirectories;
    private long userNames;

    private DiagnosticLogRedactor(Map<String, String> known, @Nullable String home, @Nullable String userName) {
        this.known = Map.copyOf(known);
        this.knownByFirstCharacter = known.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .collect(Collectors.groupingBy(value -> value.charAt(0), HashMap::new, Collectors.toList()));
        this.homePattern = homePattern(home);
        this.userPattern = userName != null && eligible(userName) ? wordIgnoringCase(userName) : null;
    }

    static Builder builder() {
        return new Builder();
    }

    /** The line as the bundle carries it. */
    String redact(String line) {
        Matcher header = HEADER.matcher(line);
        int messageStart = header.lookingAt() ? header.end() : 0;
        String message = line.substring(messageStart);

        message = replaceAll(homePattern, message, match -> {
            homeDirectories++;
            return "~";
        });
        message = replaceAll(TOKENS, message, match -> {
            tokens++;
            return TOKEN;
        });
        message = replaceAll(ADDRESS, message, match -> {
            addresses++;
            return LogMasker.maskEmail(match);
        });
        message = replaceKnown(message);
        message = replaceAll(userPattern, message, match -> {
            userNames++;
            return USER;
        });
        return line.substring(0, messageStart) + message;
    }

    /** How many replacements of each kind this redactor has made so far. */
    Counts counts() {
        return new Counts(addresses, tokens, knownValues, homeDirectories, userNames);
    }

    record Counts(long addresses, long tokens, long knownValues, long homeDirectories, long userNames) {
    }

    /**
     * Every known value that stands as a whole word, the longest one where two
     * start at the same place, replaced in one pass from the left.
     */
    private String replaceKnown(String text) {
        if (knownByFirstCharacter.isEmpty()) {
            return text;
        }
        StringBuilder result = null;
        int copiedUpTo = 0;
        int index = 0;
        while (index < text.length()) {
            String value = knownValueAt(text, index);
            if (value == null) {
                index++;
                continue;
            }
            if (result == null) {
                result = new StringBuilder(text.length());
            }
            result.append(text, copiedUpTo, index).append(known.get(value));
            knownValues++;
            index += value.length();
            copiedUpTo = index;
        }
        if (result == null) {
            return text;
        }
        return result.append(text, copiedUpTo, text.length()).toString();
    }

    private @Nullable String knownValueAt(String text, int index) {
        List<String> candidates = knownByFirstCharacter.get(text.charAt(index));
        if (candidates == null || (index > 0 && isWordCharacter(text.codePointBefore(index)))) {
            return null;
        }
        for (String candidate : candidates) {
            int end = index + candidate.length();
            if (text.startsWith(candidate, index)
                    && (end == text.length() || !isWordCharacter(text.codePointAt(end)))) {
                return candidate;
            }
        }
        return null;
    }

    /** {@code [\p{L}\p{N}]}, the word character of the boundaries here. */
    private static boolean isWordCharacter(int codePoint) {
        return Character.isLetter(codePoint) || switch (Character.getType(codePoint)) {
            case Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true;
            default -> false;
        };
    }

    private static String replaceAll(@Nullable Pattern pattern, String text, UnaryOperator<String> replacement) {
        if (pattern == null) {
            return text;
        }
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

    /** One value as a whole word, case-insensitively. */
    private static Pattern wordIgnoringCase(String value) {
        return Pattern.compile(NOT_A_WORD_CHARACTER_BEFORE + Pattern.quote(value) + NOT_A_WORD_CHARACTER_AFTER,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * The home directory with either separator, ignoring case as Windows paths do,
     * and only where a separator or the end of the path follows: that is what keeps
     * {@code C:\Users\Lukas2} from reading as the home {@code C:\Users\Lukas}.
     */
    private static @Nullable Pattern homePattern(@Nullable String home) {
        if (home == null) {
            return null;
        }
        String root = home.strip();
        while (root.endsWith("/") || root.endsWith("\\")) {
            root = root.substring(0, root.length() - 1);
        }
        if (root.isEmpty()) {
            return null;
        }
        Set<String> forms = new LinkedHashSet<>();
        forms.add(root);
        forms.add(root.replace('\\', '/'));
        forms.add(root.replace('/', '\\'));
        String alternation = forms.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        return Pattern.compile("(?:" + alternation + ")(?=[\\\\/]|$|[^\\p{L}\\p{N}._-])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static boolean eligible(String value) {
        return value.length() >= MIN_KNOWN_LENGTH && value.codePoints().anyMatch(Character::isLetter);
    }

    /**
     * The form {@code encodeURIComponent} gives a value, which is how the client
     * puts a folder into a request path and how that path reaches the log.
     */
    private static String percentEncoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%21", "!")
                .replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~");
    }

    static final class Builder {

        private final Map<String, String> known = new LinkedHashMap<>();
        private @Nullable String home;
        private @Nullable String userName;

        private Builder() {
        }

        /**
         * A value to replace with {@code replacement}, as written and percent-encoded.
         * The first replacement given for a value wins.
         */
        Builder known(@Nullable String value, String replacement) {
            if (value == null) {
                return this;
            }
            String trimmed = value.strip();
            if (!eligible(trimmed) || trimmed.equals(replacement)) {
                return this;
            }
            known.putIfAbsent(trimmed, replacement);
            String encoded = percentEncoded(trimmed);
            if (!encoded.equals(trimmed)) {
                known.putIfAbsent(encoded, replacement);
            }
            return this;
        }

        Builder home(@Nullable String home) {
            this.home = home;
            return this;
        }

        Builder userName(@Nullable String userName) {
            this.userName = userName;
            return this;
        }

        DiagnosticLogRedactor build() {
            return new DiagnosticLogRedactor(known, home, userName);
        }
    }
}
