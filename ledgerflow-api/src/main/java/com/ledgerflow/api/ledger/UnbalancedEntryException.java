package com.ledgerflow.api.ledger;

/** Thrown when a journal entry is malformed. Always a programming error, never a client error. */
public class UnbalancedEntryException extends RuntimeException {

    public UnbalancedEntryException(String message) {
        super(message);
    }
}
