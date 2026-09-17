package org.voxrox.mailbackend.core.lifecycle;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.ExitCodeGenerator;

/**
 * A startup failure the desktop client can name. The process exits with the
 * code of its {@link Reason}, and the client turns that code into a message
 * that says what to do next ({@code frontend/src/lib/backend/sidecar.ts});
 * every other failed start exits 1 and reads as "failed to start, try again",
 * which for a damaged database is the wrong advice.
 * <p>
 * The codes follow {@code sysexits.h}, like {@code EXIT_CONFIG} (78) in
 * {@code MailBackendApplication}. It extends {@link IllegalStateException} so a
 * caller that already expected one still matches.
 */
public final class StartupFailure extends IllegalStateException implements ExitCodeGenerator {

    private static final long serialVersionUID = 1L;

    /** Why the backend cannot start, and the exit code that carries it. */
    public enum Reason {
        /**
         * The database file is damaged or is not a database. Restarting does not help;
         * restoring a backup does ({@code EX_DATAERR}).
         */
        DATABASE_DAMAGED(65),
        /**
         * A migration that is already applied no longer matches this build. The
         * database is intact and a backup does not help; a build whose migrations match
         * does ({@code EX_SOFTWARE}).
         */
        SCHEMA_MISMATCH(70),
        /**
         * The database cannot be opened or written: a full disk, missing permissions,
         * or a file another process holds ({@code EX_IOERR}).
         */
        STORAGE_UNAVAILABLE(74);

        private final int exitCode;

        Reason(int exitCode) {
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    private static final int GENERIC_FAILURE = 1;
    private static final int MAX_CAUSE_DEPTH = 32;

    private final Reason reason;

    public StartupFailure(Reason reason, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    @Override
    public int getExitCode() {
        return reason.exitCode();
    }

    /**
     * The exit code for a failed start: the code of the first exit-code carrier in
     * the cause chain, which Spring wraps in its own bean-creation exceptions, and
     * 1 when there is none.
     */
    public static int exitCodeOf(Throwable failure) {
        // Bounded: a cause chain can be made to loop through initCause.
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof ExitCodeGenerator generator) {
                return generator.getExitCode();
            }
        }
        return GENERIC_FAILURE;
    }
}
