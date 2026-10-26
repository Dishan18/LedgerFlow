package com.ledgerflow.api.web.dto;

import com.ledgerflow.api.payment.Refund;
import java.time.Instant;
import java.util.UUID;

public record RefundResponse(UUID id, UUID paymentId, long amount, Instant createdAt) {

    public static RefundResponse from(Refund refund) {
        return new RefundResponse(refund.getId(), refund.getPaymentId(), refund.getAmount(), refund.getCreatedAt());
    }
}
