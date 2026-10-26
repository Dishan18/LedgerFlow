package com.ledgerflow.mockbank;

/** Simulated outage: the request is rejected before anything is recorded. */
public class BankUnavailableException extends RuntimeException {

    public BankUnavailableException() {
        super("bank temporarily unavailable");
    }
}
