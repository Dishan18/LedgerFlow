package com.ledgerflow.mockbank.settlement;

/**
 * Mistakes to plant in a settlement file, so reconciliation can be measured against known truth.
 *
 * @param drop        leave out real settled collects (LedgerFlow should report missing-in-bank)
 * @param duplicate   list real collects twice (duplicate)
 * @param wrongAmount change the amount of real collects (amount mismatch)
 * @param phantom     add collects LedgerFlow never saw (missing-in-ledger)
 */
public record Discrepancies(int drop, int duplicate, int wrongAmount, int phantom) {

    public static Discrepancies none() {
        return new Discrepancies(0, 0, 0, 0);
    }

    public Discrepancies {
        if (drop < 0 || duplicate < 0 || wrongAmount < 0 || phantom < 0) {
            throw new IllegalArgumentException("discrepancy counts must not be negative");
        }
    }

    public int total() {
        return drop + duplicate + wrongAmount + phantom;
    }
}
