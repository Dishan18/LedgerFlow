package com.ledgerflow.api.bank;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Temporary in-process bank that approves everything. Removed in Phase 3. */
@Component
public class StubBankGateway implements BankGateway {

    @Override
    public AuthorizationResult authorize(UUID paymentId, String customerId, long amount) {
        return new AuthorizationResult.Approved("STUB-" + paymentId);
    }
}
