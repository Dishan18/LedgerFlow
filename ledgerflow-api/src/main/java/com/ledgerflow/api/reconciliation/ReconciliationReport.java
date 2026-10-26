package com.ledgerflow.api.reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Result of matching one settlement file against LedgerFlow. Serialized as-is to the API and S3. */
public record ReconciliationReport(
        UUID id,
        LocalDate businessDate,
        String sourceKey,
        Instant generatedAt,
        Summary summary,
        List<Discrepancy> discrepancies) {

    public record Summary(
            int bankRows,
            int ledgerPayments,
            int matched,
            int missingInBank,
            int missingInLedger,
            int duplicates,
            int amountMismatches) {
    }

    public enum DiscrepancyType {
        /** LedgerFlow thinks the bank collected it; the bank's file doesn't list it. */
        MISSING_IN_BANK,
        /** The bank settled it; LedgerFlow has no collected payment for it. */
        MISSING_IN_LEDGER,
        /** The bank listed the same payment more than once. */
        DUPLICATE,
        /** Both sides know the payment, with different amounts. */
        AMOUNT_MISMATCH
    }

    /**
     * @param bankAmount   amount in the bank file, null for MISSING_IN_BANK
     * @param ledgerAmount amount in LedgerFlow, null when LedgerFlow doesn't know the payment
     */
    public record Discrepancy(
            DiscrepancyType type,
            UUID paymentId,
            String utr,
            Long bankAmount,
            Long ledgerAmount,
            String detail) {
    }
}
