package com.ledgerflow.api.payment;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID paymentId) {
        super("payment " + paymentId + " not found");
    }
}
