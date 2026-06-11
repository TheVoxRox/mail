package org.voxrox.mailbackend.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin facade over a dedicated SLF4J logger for auditing security events.
 * <p>
 * The logger is named {@code AUDIT} and {@code logback-spring.xml} routes it
 * into a separate {@code audit.log} file with longer retention than the
 * application log.
 * <p>
 * The audit log is an append-only record of security-relevant actions (sign in,
 * token revoke, decrypt failure, account change). It is not intended for
 * debugging — that belongs in {@link LogCategory#SECURITY} on the regular
 * logger.
 * <p>
 * Rules for audit entries:
 * <ul>
 * <li>Never log passwords, tokens, or any part of them — not even masked.</li>
 * <li>E-mails only through {@link LogMasker#maskEmail(String)}.</li>
 * <li>Always include actor (who) + action (what) + outcome
 * (success/failure).</li>
 * </ul>
 */
public final class AuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    private AuditLog() {
    }

    public static void success(String action, String actor, String detail) {
        AUDIT.info("SUCCESS action={} actor={} detail={}", action, actor, detail);
    }

    public static void failure(String action, String actor, String reason) {
        AUDIT.warn("FAILURE action={} actor={} reason={}", action, actor, reason);
    }

    /**
     * Critical security event requiring manual intervention (e.g. repeated decrypt
     * failures, suspicious activity).
     */
    public static void critical(String action, String actor, String detail) {
        AUDIT.error("CRITICAL action={} actor={} detail={}", action, actor, detail);
    }
}
