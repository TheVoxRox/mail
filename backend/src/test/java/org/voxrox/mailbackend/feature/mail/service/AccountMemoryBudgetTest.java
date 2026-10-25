package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sum {@link AccountMemoryBudget} keeps over an account's connections
 * (IMAP/SMTP audit B1-14, B1-21). That a real connection reports what it holds
 * and is refused by the sum is {@code AccountMemoryBudgetIT}'s question.
 */
class AccountMemoryBudgetTest {

    /** A connection that holds what the test sets. */
    private static final class Connection implements AccountMemoryBudget.Holder {

        private final AtomicLong held = new AtomicLong();

        Connection holding(long bytes) {
            held.set(bytes);
            return this;
        }

        @Override
        public long heldBytes() {
            return held.get();
        }
    }

    @Test
    @DisplayName("The account's connections count together, and the sum past the budget is refused")
    void connectionsCountTogether() {
        AccountMemoryBudget account = new AccountMemoryBudget(100, 7L);
        Connection sync = new Connection().holding(60);
        Connection interactive = new Connection().holding(30);
        account.opened(sync);
        account.opened(interactive);

        assertThat(account.held()).isEqualTo(90);
        assertThat(account.refusal()).isNull();

        interactive.holding(41);

        assertThat(account.refusal()).contains("hold 101 bytes together").contains("budget of 100 bytes")
                .contains("mail.client.imap.account-budget");
    }

    @Test
    @DisplayName("A connection that closes stops counting, so what it held no longer refuses the others")
    void aClosedConnectionStopsCounting() {
        AccountMemoryBudget account = new AccountMemoryBudget(100, 7L);
        Connection sync = new Connection().holding(80);
        Connection interactive = new Connection().holding(30);
        account.opened(sync);
        account.opened(interactive);
        assertThat(account.refusal()).isNotNull();

        account.closed(sync);

        assertThat(account.held()).isEqualTo(30);
        assertThat(account.refusal()).isNull();
    }

    @Test
    @DisplayName("Each account counts only its own connections")
    void accountsDoNotShare() {
        AccountMemoryBudget first = new AccountMemoryBudget(100, 7L);
        AccountMemoryBudget second = new AccountMemoryBudget(100, 8L);
        Connection ofFirst = new Connection().holding(90);
        Connection ofSecond = new Connection().holding(90);
        first.opened(ofFirst);
        second.opened(ofSecond);

        assertThat(first.held()).isEqualTo(90);
        assertThat(second.held()).isEqualTo(90);
        assertThat(first.refusal()).isNull();
        assertThat(second.refusal()).isNull();
        // Held weakly by the budgets: used here, so they are counted to the end.
        assertThat(ofFirst.heldBytes() + ofSecond.heldBytes()).isEqualTo(180);
    }

    @Test
    @DisplayName("A budget that is not positive is refused")
    void aBudgetMustBePositive() {
        assertThatThrownBy(() -> new AccountMemoryBudget(0, 7L)).isInstanceOf(IllegalArgumentException.class);
    }
}
