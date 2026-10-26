package com.ledgerflow.api.payment;

public class RefundExceedsCapturedException extends RuntimeException {

    public RefundExceedsCapturedException(long requested, long refundable) {
        super("refund of " + requested + " paise exceeds refundable amount " + refundable);
    }
}
