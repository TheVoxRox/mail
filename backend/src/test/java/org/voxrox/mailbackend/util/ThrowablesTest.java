package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ThrowablesTest {

    @Test
    @DisplayName("returns the exception and its causes, outermost first")
    void returnsChainOutermostFirst() {
        IllegalStateException root = new IllegalStateException("root");
        RuntimeException middle = new RuntimeException("middle", root);
        RuntimeException outer = new RuntimeException("outer", middle);

        assertThat(Throwables.causalChain(outer)).containsExactly(outer, middle, root);
    }

    @Test
    @DisplayName("a single exception without cause yields a one-element chain")
    void singleExceptionYieldsItself() {
        RuntimeException alone = new RuntimeException("alone");

        assertThat(Throwables.causalChain(alone)).containsExactly(alone);
    }

    @Test
    @DisplayName("terminates on a cyclic cause chain instead of looping forever")
    void terminatesOnCycle() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second); // first -> second -> first -> ...

        List<Throwable> chain = Throwables.causalChain(first);

        assertThat(chain).isNotEmpty();
        assertThat(chain).startsWith(first, second);
    }

    @Test
    @DisplayName("cuts a pathologically deep chain at the depth bound")
    void cutsDeepChainAtBound() {
        RuntimeException head = new RuntimeException("depth 0");
        RuntimeException cur = head;
        for (int depth = 1; depth < 40; depth++) {
            RuntimeException next = new RuntimeException("depth " + depth);
            cur.initCause(next);
            cur = next;
        }

        assertThat(Throwables.causalChain(head)).hasSize(32);
    }
}
