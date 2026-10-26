package com.ledgerflow.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.api.support.AbstractIntegrationTest;
import com.ledgerflow.api.support.ConcurrentRunner;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 2 concurrency proof: many transactions hammering the same account at once must never
 * lose an update, overdraw the account, unbalance the ledger or deadlock.
 */
class ConcurrentTransfersIT extends AbstractIntegrationTest {

    private static final int TRANSFERS = 1_000;

    enum Outcome { POSTED, INSUFFICIENT_FUNDS, OTHER_ERROR }

    @Autowired
    LedgerService ledger;

    @Autowired
    TransactionTemplate tx;

    private final ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

    @Test
    void thousandConcurrentDebitsOfOneAccountNeverOverdrawIt() throws Exception {
        long transferAmount = 100;
        int affordable = 500;
        String hotCustomer = data.customer(affordable * transferAmount); // funds exactly 500 of the 1,000
        UUID hot = data.accountId(AccountType.CUSTOMER, hotCustomer);
        List<String> recipients = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            recipients.add(data.customer(0));
        }

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            UUID to = data.accountId(AccountType.CUSTOMER, recipients.get(i % recipients.size()));
            tasks.add(() -> transfer(hot, to, transferAmount));
        }

        ConcurrentRunner.Result<Outcome> result = ConcurrentRunner.runAll(tasks);
        Map<Outcome, Long> counts = count(result.outcomes());

        report("single hot account", result, counts);
        assertThat(unexpected).as("unexpected errors (deadlocks, lock timeouts...)").isEmpty();
        assertThat(counts.get(Outcome.POSTED)).isEqualTo(affordable);
        assertThat(counts.get(Outcome.INSUFFICIENT_FUNDS)).isEqualTo(TRANSFERS - affordable);
        assertThat(data.balance(AccountType.CUSTOMER, hotCustomer)).isZero();
        assertThat(recipients.stream().mapToLong(r -> data.balance(AccountType.CUSTOMER, r)).sum())
                .isEqualTo(affordable * transferAmount);
        assertLedgerIsSound();
    }

    @Test
    void thousandConcurrentTransfersInBothDirectionsNeitherDeadlockNorLoseMoney() throws Exception {
        int accounts = 5;
        long fundingEach = 10_000;
        List<UUID> ids = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        for (int i = 0; i < accounts; i++) {
            String owner = data.customer(fundingEach);
            owners.add(owner);
            ids.add(data.accountId(AccountType.CUSTOMER, owner));
        }

        // A->B and B->A at the same time is the classic deadlock recipe if locks are taken in
        // request order. Fixed seed so a failure can be replayed exactly.
        Random random = new Random(42);
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            int from = random.nextInt(accounts);
            int to = (from + 1 + random.nextInt(accounts - 1)) % accounts;
            long amount = 1 + random.nextInt(5_000);
            UUID fromId = ids.get(from);
            UUID toId = ids.get(to);
            tasks.add(() -> transfer(fromId, toId, amount));
        }

        ConcurrentRunner.Result<Outcome> result = ConcurrentRunner.runAll(tasks);
        Map<Outcome, Long> counts = count(result.outcomes());

        report("bidirectional mesh", result, counts);
        assertThat(unexpected).as("unexpected errors (deadlocks, lock timeouts...)").isEmpty();
        assertThat(counts.get(Outcome.POSTED)).isPositive();
        // Closed system: money only moves between these accounts, so the total can't change.
        assertThat(owners.stream().mapToLong(o -> data.balance(AccountType.CUSTOMER, o)).sum())
                .isEqualTo(accounts * fundingEach);
        assertThat(owners).allSatisfy(o -> assertThat(data.balance(AccountType.CUSTOMER, o)).isNotNegative());
        assertLedgerIsSound();
    }

    private Outcome transfer(UUID from, UUID to, long amount) {
        try {
            tx.executeWithoutResult(status -> ledger.post(
                    JournalEntryDraft.builder(EntryType.TRANSFER, "concurrency test")
                            .debit(from, amount)
                            .credit(to, amount)
                            .build()));
            return Outcome.POSTED;
        } catch (InsufficientFundsException e) {
            return Outcome.INSUFFICIENT_FUNDS;
        } catch (RuntimeException e) {
            unexpected.add(e);
            return Outcome.OTHER_ERROR;
        }
    }

    private void assertLedgerIsSound() {
        assertThat(audit.globalImbalance()).as("ledger imbalance in paise").isZero();
        assertThat(audit.accountsWithDriftedBalance()).as("accounts whose balance != sum of lines").isEmpty();
        assertThat(audit.overdrawnAccounts()).as("overdrawn accounts").isZero();
    }

    private static Map<Outcome, Long> count(List<Outcome> outcomes) {
        Map<Outcome, Long> counts = new EnumMap<>(Outcome.class);
        for (Outcome o : Outcome.values()) {
            counts.put(o, outcomes.stream().filter(o::equals).count());
        }
        return counts;
    }

    private void report(String scenario, ConcurrentRunner.Result<Outcome> result, Map<Outcome, Long> counts) {
        long millis = Math.max(1, result.elapsed().toMillis());
        System.out.printf("[proof] %s: %d transfers in %d ms (%.0f tx/s), posted=%d, insufficient=%d, other=%d, "
                        + "imbalance=%d paise%n",
                scenario, result.outcomes().size(), millis, result.outcomes().size() * 1000.0 / millis,
                counts.get(Outcome.POSTED), counts.get(Outcome.INSUFFICIENT_FUNDS), counts.get(Outcome.OTHER_ERROR),
                audit.globalImbalance());
    }
}
