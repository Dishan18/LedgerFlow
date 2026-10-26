package com.ledgerflow.common.bank;

/** Paths of the mock-bank HTTP API, shared so client and server can't drift apart. */
public final class BankApi {

    public static final String COLLECTS = "/bank/v1/collects";
    public static final String COLLECT_BY_PAYMENT = COLLECTS + "/{paymentId}";

    private BankApi() {
    }
}
