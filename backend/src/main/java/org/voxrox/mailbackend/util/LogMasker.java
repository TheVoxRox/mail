package org.voxrox.mailbackend.util;

import org.jspecify.annotations.Nullable;

/**
 * Masks sensitive identifiers (currently only e-mail addresses) in logs so the
 * full value never leaks. Other variants ({@code maskUsername}, {@code lazy})
 * are package-private helpers used by {@link #maskEmail} and
 * {@link #lazyEmail}; they are not part of the public API.
 */
public final class LogMasker {

    private static final String EMPTY = "[EMPTY]";

    private LogMasker() {
    }

    /** jan.novak@seznam.cz -> j***k@seznam.cz */
    public static String maskEmail(@Nullable String email) {

        if (email == null) {
            return EMPTY;
        }

        email = email.trim();

        if (email.isEmpty()) {
            return EMPTY;
        }

        int atIndex = email.indexOf('@');

        if (atIndex < 0) {
            return maskUsername(email);
        }

        if (atIndex == 0) {
            return "***" + email.substring(atIndex);
        }

        String user = email.substring(0, atIndex);
        String domain = email.substring(atIndex);

        if (user.length() == 1) {
            return user + "***" + domain;
        }

        if (user.length() == 2) {
            return user.charAt(0) + "***" + domain;
        }

        return user.charAt(0) + "***" + user.charAt(user.length() - 1) + domain;
    }

    /*
     * Lazy variant for log calls: SLF4J evaluates arguments eagerly, so
     * maskEmail(email) is called even when the log level is below DEBUG. The
     * wrapper defers the computation to toString(), which SLF4J only invokes when
     * actually writing the log entry.
     */
    public static Object lazyEmail(@Nullable String email) {
        return lazy(email, LogMasker::maskEmail);
    }

    private static String maskUsername(@Nullable String username) {

        if (username == null) {
            return EMPTY;
        }

        username = username.trim();

        if (username.isEmpty()) {
            return EMPTY;
        }

        if (username.length() <= 2) {
            return "***";
        }

        return username.charAt(0) + "***" + username.charAt(username.length() - 1);
    }

    private static Object lazy(@Nullable String value, java.util.function.Function<@Nullable String, String> masker) {
        return new Object() {
            @Override
            public String toString() {
                return masker.apply(value);
            }
        };
    }
}
