package com.ledgerflow.mockbank;

import java.util.UUID;

/** Same payment id sent again with a different payer or amount. */
public class ConflictingCollectException extends RuntimeException {

    public ConflictingCollectException(UUID paymentId) {
        super("collect " + paymentId + " already exists with a different payer or amount");
    }
}
