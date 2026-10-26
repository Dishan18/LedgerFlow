package com.ledgerflow.api.payment;

public class CustomerAccountNotFoundException extends RuntimeException {

    public CustomerAccountNotFoundException(String customerId) {
        super("no ledger account for customer " + customerId);
    }
}
