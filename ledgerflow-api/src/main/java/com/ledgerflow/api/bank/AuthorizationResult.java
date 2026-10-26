package com.ledgerflow.api.bank;

/** What we learned from the bank about one collect. */
public sealed interface AuthorizationResult {

    /** Money collected. */
    record Approved(String bankReference) implements AuthorizationResult {
    }

    /** Definitely not collected. */
    record Declined(String reason) implements AuthorizationResult {
    }

    /**
     * Outcome not known yet: the bank said PENDING, or we never got an answer (timeout, outage).
     * Either way the money may or may not move, so the payment must wait for confirmation.
     */
    record Pending(String reason) implements AuthorizationResult {
    }

    /** Status check only: the bank has no record of this payment. */
    record NotFound() implements AuthorizationResult {
    }
}
