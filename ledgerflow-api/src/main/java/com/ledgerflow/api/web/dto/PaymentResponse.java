package com.ledgerflow.api.web.dto;

import com.ledgerflow.api.payment.Payment;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID merchantId,
        String customerId,
        long amount,
        long feeAmount,
        long refundedAmount,
        String currency,
        String status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getMerchantId(),
                payment.getCustomerId(),
                payment.getAmount(),
                payment.getFeeAmount(),
                payment.getRefundedAmount(),
                payment.getCurrency(),
                payment.getStatus().name(),
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
