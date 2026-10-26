package com.ledgerflow.api.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerflow.api.reconciliation.ReconciliationReport.DiscrepancyType;
import com.ledgerflow.common.settlement.SettlementCsv;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Property: whatever mix of mistakes is planted in a settlement file, reconciliation reports every
 * one of them with the right type (100% caught) and reports nothing else (no false alarms).
 */
class ReconcilerProperties {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 27);

    record Finding(DiscrepancyType type, UUID paymentId) {
    }

    @Property
    void everyPlantedDiscrepancyIsCaughtAndNothingElse(
            @ForAll @IntRange(min = 0, max = 60) int collected,
            @ForAll @IntRange(min = 0, max = 10) int drop,
            @ForAll @IntRange(min = 0, max = 10) int duplicate,
            @ForAll @IntRange(min = 0, max = 10) int wrongAmount,
            @ForAll @IntRange(min = 0, max = 10) int phantom,
            @ForAll @IntRange(min = 0, max = 10) int failedButCollectedByBank,
            @ForAll long seed) {
        Random random = new Random(seed);
        Map<UUID, Reconciler.LedgerPayment> ledger = new HashMap<>();
        Set<UUID> collectedOnDate = new LinkedHashSet<>();
        List<SettlementCsv.Row> rows = new ArrayList<>();
        Set<Finding> expected = new HashSet<>();

        for (int i = 0; i < collected; i++) {
            UUID id = UUID.randomUUID();
            long amount = 1 + random.nextInt(1_000_000);
            ledger.put(id, new Reconciler.LedgerPayment(id, amount, "CAPTURED", true));
            collectedOnDate.add(id);
            rows.add(row(id, amount));
        }

        // Plant mistakes on distinct victims, as mock-bank does.
        List<Integer> victims = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            victims.add(i);
        }
        Collections.shuffle(victims, random);
        int next = 0;
        List<Integer> toDrop = new ArrayList<>();
        for (int i = 0; i < drop && next < victims.size(); i++) {
            int idx = victims.get(next++);
            toDrop.add(idx);
            expected.add(new Finding(DiscrepancyType.MISSING_IN_BANK, rows.get(idx).paymentId()));
        }
        for (int i = 0; i < wrongAmount && next < victims.size(); i++) {
            int idx = victims.get(next++);
            SettlementCsv.Row r = rows.get(idx);
            rows.set(idx, row(r.paymentId(), r.amount() + 1 + random.nextInt(1_000)));
            expected.add(new Finding(DiscrepancyType.AMOUNT_MISMATCH, r.paymentId()));
        }
        List<SettlementCsv.Row> duplicates = new ArrayList<>();
        for (int i = 0; i < duplicate && next < victims.size(); i++) {
            SettlementCsv.Row r = rows.get(victims.get(next++));
            duplicates.add(r);
            expected.add(new Finding(DiscrepancyType.DUPLICATE, r.paymentId()));
        }
        toDrop.sort(Collections.reverseOrder());
        for (int idx : toDrop) {
            rows.remove(idx);
        }
        rows.addAll(duplicates);
        for (int i = 0; i < phantom; i++) {
            UUID id = UUID.randomUUID();
            rows.add(row(id, 1 + random.nextInt(1_000)));
            expected.add(new Finding(DiscrepancyType.MISSING_IN_LEDGER, id));
        }
        for (int i = 0; i < failedButCollectedByBank; i++) {
            UUID id = UUID.randomUUID();
            ledger.put(id, new Reconciler.LedgerPayment(id, 500, "FAILED", false));
            rows.add(row(id, 500));
            expected.add(new Finding(DiscrepancyType.MISSING_IN_LEDGER, id));
        }
        // Noise that must never be reported: failed payments the bank rightly never collected.
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            ledger.put(id, new Reconciler.LedgerPayment(id, 900, "FAILED", false));
        }
        Collections.shuffle(rows, random); // row order must not matter

        Reconciler.Outcome outcome = Reconciler.reconcile(UUID.randomUUID(), DATE, "k", Instant.now(), rows, ledger,
                collectedOnDate);

        Set<Finding> reported = new HashSet<>();
        outcome.report().discrepancies().forEach(d -> reported.add(new Finding(d.type(), d.paymentId())));
        assertThat(reported).isEqualTo(expected);
        assertThat(outcome.report().discrepancies()).hasSameSizeAs(expected);
        int realDrops = Math.min(drop, collected);
        int realWrong = Math.min(wrongAmount, Math.max(0, collected - realDrops));
        assertThat(outcome.matched()).hasSize(collected - realDrops - realWrong);
    }

    private static SettlementCsv.Row row(UUID paymentId, long amount) {
        return new SettlementCsv.Row(DATE, "000000000000", paymentId, "x@upi", amount);
    }
}
