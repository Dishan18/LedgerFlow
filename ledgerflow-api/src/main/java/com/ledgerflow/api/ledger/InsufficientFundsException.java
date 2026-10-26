package com.ledgerflow.api.ledger;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {

    private final UUID accountId;

    public InsufficientFundsException(UUID accountId, long balance, long requested) {
        super("account " + accountId + " has balance " + balance + " paise, cannot debit " + requested);
        this.accountId = accountId;
    }

    public UUID getAccountId() {
        return accountId;
    }
}
