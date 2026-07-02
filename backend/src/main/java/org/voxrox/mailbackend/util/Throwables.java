package org.voxrox.mailbackend.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for inspecting exception cause chains.
 *
 * <p>
 * Every {@code getCause()} walk in the codebase must go through
 * {@link #causalChain(Throwable)} instead of a hand-rolled
 * {@code while (cur != null) cur = cur.getCause()} loop: a cyclic cause chain
 * ({@code initCause} cycles are legal on custom exceptions) turns the naive
 * loop into a hang on whatever thread happens to classify the failure.
 */
public final class Throwables {

    /**
     * Upper bound on how many links of a cause chain {@link #causalChain} walks.
     * Well-formed chains are a handful of links deep; the bound only exists so a
     * pathological or cyclic chain can never spin the walker. Links past the bound
     * are simply not inspected — a classifier misses that cause and degrades to its
     * conservative default, never to a hang.
     */
    private static final int MAX_CAUSE_DEPTH = 32;

    private Throwables() {
    }

    /**
     * The exception followed by its causes, outermost first, cut off at
     * {@link #MAX_CAUSE_DEPTH} so a cyclic cause chain terminates instead of
     * looping forever.
     */
    public static List<Throwable> causalChain(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cur = error; cur != null && chain.size() < MAX_CAUSE_DEPTH; cur = cur.getCause()) {
            chain.add(cur);
        }
        return chain;
    }
}
