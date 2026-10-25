package org.voxrox.mailbackend.feature.mail.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

/**
 * One bound on what all of an account's IMAP connections hold together: what
 * their open folders keep of the FETCH responses handed to them, and the
 * responses of the commands they are running (IMAP/SMTP audit B1-14, B1-21).
 * <p>
 * Each connection is bounded on its own as well — a folder by the open-folder
 * budget, a command by {@link BoundedImapProtocol#MAX_COMMAND_BYTES} — but an
 * account has several at once: a store per lane, a connection per open folder,
 * and a move's destination open beside its source. Bounded one by one, a
 * hostile server driving them all at the same time made the account hold about
 * the whole packaged heap, and each independent pass of the audit found a
 * response shape whose kept heap the estimate behind the per-folder budget had
 * missed, which multiplied with every connection. One budget across the account
 * takes the number of connections out of that product, so the bound no longer
 * depends on how many the account happens to have open, and its margin under
 * the heap absorbs an estimate that is off.
 * <p>
 * What a connection holds is what it reports, read when another connection of
 * the account charges something. The connections are held weakly: one that
 * Angus drops without disconnecting, and that nothing reaches any more, takes
 * its folder's messages with it to the collector, so it stops counting at the
 * moment what it held can be freed. One that disconnects stops counting at
 * once.
 * <p>
 * Shared by an account's sessions through their properties (see
 * {@link BoundedImapStore#install}); the connection manager keeps one per
 * account for as long as the account exists.
 */
final class AccountMemoryBudget {

    private static final Logger log = LoggerFactory.getLogger(AccountMemoryBudget.class);

    /** A connection whose holdings count against the budget. */
    interface Holder {

        /** What the connection holds now, in the bytes its budgets charge. */
        long heldBytes();
    }

    private final long limit;
    private final @Nullable Long accountId;
    private final Set<Holder> connections = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean overHalf;

    /**
     * {@code accountId} names the account in the log, or is null for a connection
     * that belongs to none yet (the credential probe).
     */
    AccountMemoryBudget(long limit, @Nullable Long accountId) {
        if (limit <= 0) {
            throw new IllegalArgumentException("An account's memory budget must be positive, was " + limit);
        }
        this.limit = limit;
        this.accountId = accountId;
    }

    long limit() {
        return limit;
    }

    synchronized void opened(Holder connection) {
        connections.add(connection);
    }

    synchronized void closed(Holder connection) {
        connections.remove(connection);
    }

    /** What the account's connections hold together. */
    synchronized long held() {
        long held = 0;
        for (Holder connection : connections) {
            held += connection.heldBytes();
        }
        return held;
    }

    /**
     * Checks the account after one of its connections charged something: the reason
     * to refuse what it charged, or null. Logs once when the account passes half of
     * its budget, so a tester's log shows an account nearing it before anything is
     * refused.
     */
    synchronized @Nullable String refusal() {
        long held = held();
        if (held > limit / 2 && !overHalf) {
            log.warn(
                    "{} The IMAP connections of account {} hold over half of their {}-byte budget "
                            + "(mail.client.imap.account-budget); past it a connection is closed.",
                    LogCategory.IMAP, accountId, limit);
        }
        overHalf = held > limit / 2;
        return held > limit
                ? "the account's IMAP connections hold " + held + " bytes together, past its budget of " + limit
                        + " bytes (mail.client.imap.account-budget)"
                : null;
    }
}
