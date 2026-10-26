package com.ledgerflow.api.ledger;

/**
 * The four account types the ledger knows about.
 *
 * <p>Each type has a <em>normal side</em>: the direction that increases its balance.
 * From LedgerFlow's point of view, money held for customers and merchants and the
 * fees we earned are things we owe or own on the credit side (liabilities / revenue),
 * while cash sitting at the bank is an asset (debit side).
 *
 * <p>Because every journal entry has equal debits and credits, the sum of balances of
 * debit-normal accounts always equals the sum of balances of credit-normal accounts.
 * That is the accounting equation, and it is what the "zero imbalance" checks verify.
 */
public enum AccountType {
    CUSTOMER(Direction.CREDIT, false),
    MERCHANT_RECEIVABLE(Direction.CREDIT, true),
    PLATFORM_FEE(Direction.CREDIT, true),
    BANK_SETTLEMENT(Direction.DEBIT, true);

    private final Direction normalSide;
    private final boolean allowNegative;

    AccountType(Direction normalSide, boolean allowNegative) {
        this.normalSide = normalSide;
        this.allowNegative = allowNegative;
    }

    public Direction normalSide() {
        return normalSide;
    }

    /** How a line changes this account's balance: + on the normal side, - on the other. */
    public long signedAmount(Direction direction, long amount) {
        return direction == normalSide ? amount : Math.negateExact(amount);
    }

    /** Customer balances must never go below zero; system and merchant accounts may. */
    public boolean allowNegative() {
        return allowNegative;
    }
}
