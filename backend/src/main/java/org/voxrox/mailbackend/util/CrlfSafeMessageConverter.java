package org.voxrox.mailbackend.util;

import org.jspecify.annotations.Nullable;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback message converter that neutralises CR and LF in the rendered log
 * message so externally-influenced values can never forge additional log lines
 * (CWE-117 log injection / log forging).
 *
 * <p>
 * It is registered in {@code logback-spring.xml} as the converter for the
 * {@code %m}, {@code %msg} and {@code %message} pattern words, which means
 * every appender (console, main file, audit file) is covered at the output
 * boundary without having to wrap each of the ~270 individual log sites.
 * Because the substitution happens after SLF4J has expanded {@code {}}
 * placeholders, it also covers values that arrive as log arguments.
 *
 * <p>
 * Stack-trace rendering ({@code %wEx}) is intentionally left untouched so
 * multi-line exceptions stay readable; the log-injection sinks flagged by
 * static analysis are all on the message argument, which renders through
 * {@code %m}.
 */
public final class CrlfSafeMessageConverter extends MessageConverter {

    private static final char REPLACEMENT = '_';

    @Override
    public String convert(ILoggingEvent event) {
        return sanitize(event.getFormattedMessage());
    }

    /** Replaces every CR and LF in {@code message} with {@code '_'}. */
    static String sanitize(@Nullable String message) {

        if (message == null) {
            return "";
        }

        // Fast path: the vast majority of log lines contain no line breaks, so
        // scan once and only allocate when a replacement is actually needed.
        int length = message.length();
        for (int i = 0; i < length; i++) {
            char c = message.charAt(i);
            if (c == '\r' || c == '\n') {
                return replaceFrom(message, i);
            }
        }

        return message;
    }

    private static String replaceFrom(String message, int firstBreak) {
        char[] chars = message.toCharArray();
        for (int i = firstBreak; i < chars.length; i++) {
            if (chars[i] == '\r' || chars[i] == '\n') {
                chars[i] = REPLACEMENT;
            }
        }
        return new String(chars);
    }
}
