package com.ledgerflow.common.error;

/**
 * Machine-readable error codes returned in the {@code code} property of every
 * RFC 7807 problem response. Shared so that mock-bank and any client SDK speak
 * the same vocabulary.
 */
public enum ErrorCode {
    VALIDATION_FAILED,
    MISSING_IDEMPOTENCY_KEY,
    IDEMPOTENCY_KEY_IN_USE,
    IDEMPOTENCY_KEY_REUSED,
    MERCHANT_NOT_FOUND,
    CUSTOMER_ACCOUNT_NOT_FOUND,
    PAYMENT_NOT_FOUND,
    INVALID_STATE_TRANSITION,
    INSUFFICIENT_FUNDS,
    REFUND_EXCEEDS_CAPTURED,
    INTERNAL_ERROR
}
