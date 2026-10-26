package com.ledgerflow.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FeeCalculatorTest {

    @Test
    void twoPercentOfHundredRupees() {
        assertThat(FeeCalculator.fee(10_000, 200)).isEqualTo(200);
    }

    @Test
    void roundsHalfUpToNearestPaisa() {
        // 2% of 125 paise = 2.5 -> 3
        assertThat(FeeCalculator.fee(125, 200)).isEqualTo(3);
        // 2% of 124 paise = 2.48 -> 2
        assertThat(FeeCalculator.fee(124, 200)).isEqualTo(2);
    }

    @Test
    void zeroAndFullFee() {
        assertThat(FeeCalculator.fee(999, 0)).isZero();
        assertThat(FeeCalculator.fee(999, 10_000)).isEqualTo(999);
    }

    @Test
    void largestAllowedAmountDoesNotOverflow() {
        assertThat(FeeCalculator.fee(FeeCalculator.MAX_AMOUNT, 10_000)).isEqualTo(FeeCalculator.MAX_AMOUNT);
    }

    @Test
    void rejectsOutOfRangeInputs() {
        assertThatThrownBy(() -> FeeCalculator.fee(0, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeeCalculator.fee(FeeCalculator.MAX_AMOUNT + 1, 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeeCalculator.fee(100, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void piecewiseRefundsReturnExactlyTheWholeFee() {
        long amount = 1_000;
        long fee = 23;
        long returned = 0;
        long refunded = 0;
        for (long piece : new long[] {333, 333, 334}) {
            returned += FeeCalculator.refundFeePortion(fee, amount, refunded, piece);
            refunded += piece;
        }
        assertThat(returned).isEqualTo(fee);
    }
}
