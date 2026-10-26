package com.ledgerflow.api.idempotency;

/** Another request with the same key is still being processed. Maps to 409. */
public class IdempotencyKeyInUseException extends RuntimeException {

    public IdempotencyKeyInUseException(String key) {
        super("a request with Idempotency-Key '" + key + "' is still in progress");
    }
}
