package com.ledgerflow.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

/** Property: the ledger always balances, whatever sequence of entries is posted. */
class LedgerBalanceProperties {

    /** Moves {@code amount} from account {@code from} to account {@code to} (indexes into a pool). */
    record Transfer(int from, int to, long amount) {
    }

    @Provide
    Arbitrary<Transfer> transfer() {
        return Combinators.combine(
                        Arbitraries.integers().between(0, 3),
                        Arbitraries.integers().between(0, 3),
                        Arbitraries.longs().between(1, 1_000_000_000L))
                .as(Transfer::new);
    }

    @Provide
    Arbitrary<List<Transfer>> entry() {
        return transfer().list().ofMinSize(1).ofMaxSize(8);
    }

    @Property
    void everyBalancedEntryIsAccepted(@ForAll("entry") List<Transfer> transfers) {
        List<Account> pool = pool();
        JournalEntryDraft draft = draftOf(transfers, pool, 0);

        long expected = transfers.stream().mapToLong(Transfer::amount).sum();
        assertThat(draft.totalDebits()).isEqualTo(expected);
    }

    @Property
    void everyUnbalancedEntryIsRejected(@ForAll("entry") List<Transfer> transfers,
                                        @ForAll @LongRange(min = 1, max = 1_000_000) long skew) {
        List<Account> pool = pool();

        assertThatThrownBy(() -> draftOf(transfers, pool, skew)).isInstanceOf(UnbalancedEntryException.class);
    }

    @Property
    void postingAnySequenceOfEntriesKeepsTheAccountingEquation(
            @ForAll @Size(max = 30) List<@From("entry") List<Transfer>> entries) {
        List<Account> pool = pool();

        for (List<Transfer> transfers : entries) {
            for (JournalEntryDraft.Line line : draftOf(transfers, pool, 0).lines()) {
                accountById(pool, line).apply(line.direction(), line.amount());
            }
            // Sum of debit-normal balances == sum of credit-normal balances, after every entry.
            long debitSide = pool.stream()
                    .filter(a -> a.getAccountType().normalSide() == Direction.DEBIT)
                    .mapToLong(Account::getBalance).sum();
            long creditSide = pool.stream()
                    .filter(a -> a.getAccountType().normalSide() == Direction.CREDIT)
                    .mapToLong(Account::getBalance).sum();
            assertThat(debitSide).isEqualTo(creditSide);
        }
    }

    @Property
    void customerAccountNeverGoesNegative(
            @ForAll @Size(max = 50) List<@LongRange(min = -1_000_000, max = 1_000_000) Long> movements) {
        Account customer = Account.open(AccountType.CUSTOMER, "cust");
        long expected = 0;

        for (long movement : movements) {
            if (movement == 0) {
                continue;
            }
            Direction direction = movement > 0 ? Direction.CREDIT : Direction.DEBIT;
            try {
                customer.apply(direction, Math.abs(movement));
                expected += movement;
            } catch (InsufficientFundsException rejected) {
                assertThat(expected + movement).isNegative();
            }
            assertThat(customer.getBalance()).isEqualTo(expected).isNotNegative();
        }
    }

    /** Two debit-normal and two credit-normal accounts, all allowed to go negative. */
    private static List<Account> pool() {
        return List.of(
                Account.open(AccountType.BANK_SETTLEMENT, "bank-a"),
                Account.open(AccountType.BANK_SETTLEMENT, "bank-b"),
                Account.open(AccountType.MERCHANT_RECEIVABLE, "merchant"),
                Account.open(AccountType.PLATFORM_FEE, "fees"));
    }

    private static JournalEntryDraft draftOf(List<Transfer> transfers, List<Account> pool, long skew) {
        JournalEntryDraft.Builder builder = JournalEntryDraft.builder(EntryType.TRANSFER, "property test");
        for (int i = 0; i < transfers.size(); i++) {
            Transfer t = transfers.get(i);
            builder.debit(pool.get(t.from()).getId(), t.amount());
            builder.credit(pool.get(t.to()).getId(), i == 0 ? t.amount() + skew : t.amount());
        }
        return builder.build();
    }

    private static Account accountById(List<Account> pool, JournalEntryDraft.Line line) {
        return pool.stream().filter(a -> a.getId().equals(line.accountId())).findFirst().orElseThrow();
    }
}
