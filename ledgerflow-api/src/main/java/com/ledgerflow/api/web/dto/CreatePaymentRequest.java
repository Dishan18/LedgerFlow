package com.ledgerflow.api.web.dto;

import com.ledgerflow.api.payment.FeeCalculator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param amount amount in paise (₹1 = 100). Boxed so a missing field fails validation instead of
 *               silently becoming 0.
 */
public record CreatePaymentRequest(
        @NotBlank @Size(max = 64) String customerId,
        @NotNull @Positive @Max(FeeCalculator.MAX_AMOUNT) Long amount) {
}
