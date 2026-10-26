package com.ledgerflow.api.bank;

import java.util.UUID;

/**
 * Port to the PSP/bank. Phase 1 wires {@link StubBankGateway}; Phase 3 replaces it with an
 * HTTP client for mock-bank that has timeouts, retries and an "unknown" outcome.
 */
public interface BankGateway {

    AuthorizationResult authorize(UUID paymentId, String customerId, long amount);
}
