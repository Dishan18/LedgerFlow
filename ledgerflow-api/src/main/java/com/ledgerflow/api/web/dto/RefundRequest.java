package com.ledgerflow.api.web.dto;

import com.ledgerflow.api.payment.FeeCalculator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** @param amount amount to refund in paise */
public record RefundRequest(@NotNull @Positive @Max(FeeCalculator.MAX_AMOUNT) Long amount) {
}
