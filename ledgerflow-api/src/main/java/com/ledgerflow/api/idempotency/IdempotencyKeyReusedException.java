package com.ledgerflow.api.idempotency;

/** The key was already used for a different request. Maps to 422. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request");
    }
}
