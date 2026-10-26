package com.ledgerflow.api.bank;

import java.util.UUID;

/**
 * Port to the PSP/bank. Implementations never throw for bank-side problems: anything that
 * isn't a clear yes or no comes back as {@link AuthorizationResult.Pending}.
 */
public interface BankGateway {

    /** Asks the bank to collect. Safe to retry: the bank deduplicates by {@code paymentId}. */
    AuthorizationResult authorize(UUID paymentId, String payerVpa, long amount);

    /** Asks the bank what happened to an earlier collect. */
    AuthorizationResult checkStatus(UUID paymentId);
}
