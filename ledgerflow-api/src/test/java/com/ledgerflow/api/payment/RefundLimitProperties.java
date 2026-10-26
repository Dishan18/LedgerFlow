package com.ledgerflow.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Property: refunds never exceed the captured amount, and fee reversals add up exactly. */
class RefundLimitProperties {

    record Scenario(long amount, int feeBps, List<Long> refundRequests) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Arbitraries.longs().between(1, FeeCalculator.MAX_AMOUNT).flatMap(amount -> Combinators.combine(
                        Arbitraries.integers().between(0, 10_000),
                        // Mostly requests within the amount (so full refunds actually happen), some above it.
                        Arbitraries.longs().between(1, amount + amount / 4 + 1).list().ofMaxSize(15))
                .as((bps, refunds) -> new Scenario(amount, bps, refunds)));
    }

    @Property
    void refundsNeverExceedCapturedAmount(@ForAll("scenarios") Scenario scenario) {
        long fee = FeeCalculator.fee(scenario.amount(), scenario.feeBps());
        Payment payment = capturedPayment(scenario.amount(), fee);
        long accepted = 0;
        long feeReturned = 0;

        for (long request : scenario.refundRequests()) {
            long refundableBefore = payment.refundableAmount();
            try {
                long feePortion = payment.applyRefund(request);
                accepted += request;
                feeReturned += feePortion;
                assertThat(request).isLessThanOrEqualTo(refundableBefore);
                assertThat(feePortion).isBetween(0L, request);
            } catch (RefundExceedsCapturedException | IllegalStateTransitionException rejected) {
                assertThat(request).isGreaterThan(refundableBefore);
            }
            assertThat(payment.getRefundedAmount()).isEqualTo(accepted).isLessThanOrEqualTo(scenario.amount());
            assertThat(feeReturned).isLessThanOrEqualTo(fee);
        }

        if (payment.getRefundedAmount() == scenario.amount()) {
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(feeReturned).isEqualTo(fee);
        } else if (payment.getRefundedAmount() > 0) {
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        }
    }

    private static Payment capturedPayment(long amount, long fee) {
        Payment payment = Payment.create(UUID.randomUUID(), "cust", amount, fee);
        payment.markAuthorized("ref");
        payment.capture();
        return payment;
    }
}
