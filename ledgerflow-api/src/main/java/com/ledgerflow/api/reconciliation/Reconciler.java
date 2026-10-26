package com.ledgerflow.api.reconciliation;

import com.ledgerflow.api.reconciliation.ReconciliationReport.Discrepancy;
import com.ledgerflow.api.reconciliation.ReconciliationReport.DiscrepancyType;
import com.ledgerflow.common.settlement.SettlementCsv;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The matching rules, with no I/O so they can be property-tested.
 *
 * <p>Both sides are keyed by payment id (the bank echoes it back as the merchant reference):
 * <ul>
 *   <li>a payment id listed more than once in the file is a DUPLICATE (and is still matched once);
 *   <li>a bank row LedgerFlow doesn't consider collected is MISSING_IN_LEDGER;
 *   <li>a bank row with a different amount is an AMOUNT_MISMATCH;
 *   <li>a payment LedgerFlow considers collected on that date but absent from the file is MISSING_IN_BANK.
 * </ul>
 */
public final class Reconciler {

    /**
     * LedgerFlow's view of one payment.
     *
     * @param collected true if LedgerFlow believes the bank took the money (it has the bank's
     *                  reference and isn't FAILED or still waiting)
     */
    public record LedgerPayment(UUID paymentId, long amount, String status, boolean collected) {
    }

    /** The report plus the payments that matched, so the caller can mark them settled. */
    public record Outcome(ReconciliationReport report, List<UUID> matched) {
    }

    private Reconciler() {
    }

    /**
     * @param ledgerById        every LedgerFlow payment referenced by the file or collected on the date
     * @param collectedOnDate   ids of payments LedgerFlow considers collected on {@code businessDate}
     */
    public static Outcome reconcile(UUID reportId,
                                    LocalDate businessDate,
                                    String sourceKey,
                                    Instant generatedAt,
                                    List<SettlementCsv.Row> bankRows,
                                    Map<UUID, LedgerPayment> ledgerById,
                                    Set<UUID> collectedOnDate) {
        Map<UUID, List<SettlementCsv.Row>> byPayment = new LinkedHashMap<>();
        for (SettlementCsv.Row row : bankRows) {
            byPayment.computeIfAbsent(row.paymentId(), id -> new ArrayList<>()).add(row);
        }

        List<Discrepancy> discrepancies = new ArrayList<>();
        List<UUID> matched = new ArrayList<>();
        int duplicates = 0;
        int missingInLedger = 0;
        int amountMismatches = 0;

        for (Map.Entry<UUID, List<SettlementCsv.Row>> entry : byPayment.entrySet()) {
            UUID paymentId = entry.getKey();
            List<SettlementCsv.Row> rows = entry.getValue();
            SettlementCsv.Row row = rows.getFirst();
            LedgerPayment ledger = ledgerById.get(paymentId);

            if (rows.size() > 1) {
                duplicates++;
                discrepancies.add(new Discrepancy(DiscrepancyType.DUPLICATE, paymentId, row.utr(), row.amount(),
                        ledger == null ? null : ledger.amount(), "listed " + rows.size() + " times"));
            }
            if (ledger == null || !ledger.collected()) {
                missingInLedger++;
                discrepancies.add(new Discrepancy(DiscrepancyType.MISSING_IN_LEDGER, paymentId, row.utr(), row.amount(),
                        ledger == null ? null : ledger.amount(),
                        ledger == null ? "unknown payment" : "LedgerFlow status " + ledger.status()));
            } else if (ledger.amount() != row.amount()) {
                amountMismatches++;
                discrepancies.add(new Discrepancy(DiscrepancyType.AMOUNT_MISMATCH, paymentId, row.utr(), row.amount(),
                        ledger.amount(), "differs by " + (row.amount() - ledger.amount()) + " paise"));
            } else {
                matched.add(paymentId);
            }
        }

        int missingInBank = 0;
        for (UUID paymentId : collectedOnDate) {
            if (!byPayment.containsKey(paymentId)) {
                missingInBank++;
                LedgerPayment ledger = ledgerById.get(paymentId);
                discrepancies.add(new Discrepancy(DiscrepancyType.MISSING_IN_BANK, paymentId, null, null,
                        ledger == null ? null : ledger.amount(), "not in the bank's settlement file"));
            }
        }

        ReconciliationReport.Summary summary = new ReconciliationReport.Summary(
                bankRows.size(), collectedOnDate.size(), matched.size(), missingInBank, missingInLedger, duplicates,
                amountMismatches);
        ReconciliationReport report = new ReconciliationReport(reportId, businessDate, sourceKey, generatedAt, summary,
                List.copyOf(discrepancies));
        return new Outcome(report, List.copyOf(matched));
    }
}
