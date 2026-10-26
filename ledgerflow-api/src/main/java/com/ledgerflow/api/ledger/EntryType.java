package com.ledgerflow.api.ledger;

public enum EntryType {
    /** Customer pays merchant on capture (merchant receivable + platform fee). */
    CAPTURE,
    /** Reversal of (part of) a capture. */
    REFUND,
    /** Plain movement between two accounts, e.g. funding a customer from the bank. */
    TRANSFER
}
