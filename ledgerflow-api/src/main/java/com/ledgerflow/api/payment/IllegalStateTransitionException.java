package com.ledgerflow.api.payment;

public class IllegalStateTransitionException extends RuntimeException {

    private final PaymentStatus from;
    private final PaymentStatus to;

    public IllegalStateTransitionException(PaymentStatus from, PaymentStatus to) {
        super("illegal payment transition " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public PaymentStatus getFrom() {
        return from;
    }

    public PaymentStatus getTo() {
        return to;
    }
}
