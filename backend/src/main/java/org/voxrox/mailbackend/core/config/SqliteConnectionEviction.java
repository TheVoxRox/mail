package org.voxrox.mailbackend.core.config;

import java.sql.SQLException;
import java.util.Set;

import com.zaxxer.hikari.SQLExceptionOverride;

/**
 * Tells HikariCP to throw away a SQLite connection whose transaction state can
 * no longer be trusted, instead of handing it to the next caller.
 * <p>
 * On {@code SQLITE_FULL}, {@code SQLITE_IOERR} and {@code SQLITE_NOMEM} SQLite
 * may roll the whole transaction back on its own. The JDBC driver does not
 * notice: its connection still believes a transaction is open, the rollback the
 * pool sends when the connection comes back fails ("no transaction is active"),
 * the pool then skips restoring auto-commit, and every later transaction on
 * that connection fails to commit — after the disk has room again, until the
 * process restarts. The accelerated soak found exactly that with a full disk.
 * Evicting the connection on the first such error lets the pool open a clean
 * one, so the application recovers as soon as the cause is gone. A connection
 * that already reports the lost state is evicted too, and so is one on a
 * damaged file, which no later statement on it can use.
 * <p>
 * Every other error keeps HikariCP's own decision: {@code SQLITE_BUSY} in
 * particular is routine under {@code busy_timeout} and evicting on it would
 * only churn the pool.
 */
// @callerless instantiated by HikariCP from
// spring.datasource.hikari.exception-override-class-name
public final class SqliteConnectionEviction implements SQLExceptionOverride {

    /** SQLITE_NOMEM, SQLITE_IOERR, SQLITE_CORRUPT, SQLITE_FULL, SQLITE_NOTADB. */
    private static final Set<Integer> UNTRUSTED_STATE_CODES = Set.of(7, 10, 11, 13, 26);
    private static final int PRIMARY_RESULT_CODE_MASK = 0xFF;
    private static final String LOST_TRANSACTION = "no transaction is active";

    // @callerless HikariCP calls it through SQLExceptionOverride on every
    // SQLException a pooled connection throws.
    @java.lang.Override
    public SQLExceptionOverride.Override adjudicate(SQLException exception) {
        if (UNTRUSTED_STATE_CODES.contains(exception.getErrorCode() & PRIMARY_RESULT_CODE_MASK)) {
            return SQLExceptionOverride.Override.MUST_EVICT;
        }
        String message = exception.getMessage();
        if (message != null && message.contains(LOST_TRANSACTION)) {
            return SQLExceptionOverride.Override.MUST_EVICT;
        }
        return SQLExceptionOverride.Override.CONTINUE_EVICT;
    }
}
