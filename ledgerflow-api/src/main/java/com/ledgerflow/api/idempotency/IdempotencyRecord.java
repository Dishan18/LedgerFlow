package com.ledgerflow.api.idempotency;

import java.time.Instant;

/** One row of {@code idempotency_key}. */
public record IdempotencyRecord(
        String key,
        String requestHash,
        Status status,
        Integer responseCode,
        String responseBody,
        Instant createdAt) {

    public enum Status {
        IN_PROGRESS,
        COMPLETED
    }
}
