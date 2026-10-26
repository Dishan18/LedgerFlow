package com.ledgerflow.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class JournalEntryDraftTest {

    private final UUID customer = UUID.randomUUID();
    private final UUID merchant = UUID.randomUUID();
    private final UUID fees = UUID.randomUUID();

    @Test
    void acceptsBalancedCaptureWithFee() {
        JournalEntryDraft draft = JournalEntryDraft.builder(EntryType.CAPTURE, "capture")
                .debit(customer, 10_000)
                .credit(merchant, 9_800)
                .credit(fees, 200)
                .build();

        assertThat(draft.lines()).hasSize(3);
        assertThat(draft.totalDebits()).isEqualTo(10_000);
    }

    @Test
    void zeroFeeLineIsDropped() {
        JournalEntryDraft draft = JournalEntryDraft.builder(EntryType.CAPTURE, "capture")
                .debit(customer, 10_000)
                .credit(merchant, 10_000)
                .credit(fees, 0)
                .build();

        assertThat(draft.lines()).hasSize(2);
    }

    @Test
    void rejectsUnbalancedEntry() {
        assertThatThrownBy(() -> JournalEntryDraft.builder(EntryType.TRANSFER, "bad")
                .debit(customer, 100)
                .credit(merchant, 99)
                .build())
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("debits 100 != credits 99");
    }

    @Test
    void rejectsSingleLine() {
        assertThatThrownBy(() -> JournalEntryDraft.builder(EntryType.TRANSFER, "bad").debit(customer, 100).build())
                .isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> JournalEntryDraft.builder(EntryType.TRANSFER, "bad")
                .debit(customer, -5)
                .credit(merchant, -5)
                .build())
                .isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    void customerAccountCannotGoNegative() {
        Account account = Account.open(AccountType.CUSTOMER, "cust");
        account.apply(Direction.CREDIT, 500);

        assertThatThrownBy(() -> account.apply(Direction.DEBIT, 501)).isInstanceOf(InsufficientFundsException.class);
        assertThat(account.getBalance()).isEqualTo(500);
    }
}
