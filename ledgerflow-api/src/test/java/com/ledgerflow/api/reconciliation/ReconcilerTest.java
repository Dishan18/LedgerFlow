package com.ledgerflow.api.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.ledgerflow.api.reconciliation.ReconciliationReport.Discrepancy;
import com.ledgerflow.api.reconciliation.ReconciliationReport.DiscrepancyType;
import com.ledgerflow.common.settlement.SettlementCsv;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconcilerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 27);

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID failed = UUID.randomUUID();

    @Test
    void cleanFileMatchesEverything() {
        Reconciler.Outcome outcome = reconcile(List.of(row(a, 100), row(b, 200)));

        assertThat(outcome.report().discrepancies()).isEmpty();
        assertThat(outcome.matched()).containsExactlyInAnyOrder(a, b);
        assertThat(outcome.report().summary().matched()).isEqualTo(2);
    }

    @Test
    void eachKindOfDiscrepancyIsReported() {
        UUID phantom = UUID.randomUUID();
        Reconciler.Outcome outcome = reconcile(List.of(
                row(a, 100), row(a, 100),   // duplicate
                row(phantom, 50),           // bank collected something we never saw
                row(failed, 70)));          // bank collected something we marked FAILED
        // b (200) is missing from the file

        assertThat(outcome.report().discrepancies())
                .extracting(Discrepancy::type, Discrepancy::paymentId)
                .containsExactlyInAnyOrder(
                        tuple(DiscrepancyType.DUPLICATE, a),
                        tuple(DiscrepancyType.MISSING_IN_LEDGER, phantom),
                        tuple(DiscrepancyType.MISSING_IN_LEDGER, failed),
                        tuple(DiscrepancyType.MISSING_IN_BANK, b));
        assertThat(outcome.matched()).containsExactly(a);
        assertThat(outcome.report().discrepancies())
                .filteredOn(d -> d.paymentId().equals(failed))
                .extracting(Discrepancy::detail).containsExactly("LedgerFlow status FAILED");
    }

    @Test
    void amountMismatchIsNotAMatch() {
        Reconciler.Outcome outcome = reconcile(List.of(row(a, 101), row(b, 200)));

        assertThat(outcome.report().discrepancies()).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
            assertThat(d.bankAmount()).isEqualTo(101);
            assertThat(d.ledgerAmount()).isEqualTo(100);
        });
        assertThat(outcome.matched()).containsExactly(b);
    }

    private Reconciler.Outcome reconcile(List<SettlementCsv.Row> rows) {
        Map<UUID, Reconciler.LedgerPayment> ledger = Map.of(
                a, new Reconciler.LedgerPayment(a, 100, "CAPTURED", true),
                b, new Reconciler.LedgerPayment(b, 200, "AUTHORIZED", true),
                failed, new Reconciler.LedgerPayment(failed, 70, "FAILED", false));
        return Reconciler.reconcile(UUID.randomUUID(), DATE, "settlements/" + DATE + "/settlement.csv",
                Instant.now(), rows, ledger, Set.of(a, b));
    }

    private static SettlementCsv.Row row(UUID paymentId, long amount) {
        return new SettlementCsv.Row(DATE, "123456789012", paymentId, "x@upi", amount);
    }
}
