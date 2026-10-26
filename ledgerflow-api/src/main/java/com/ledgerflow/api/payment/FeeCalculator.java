package com.ledgerflow.api.payment;

/**
 * Integer-only fee maths. Basis points: 1 bps = 0.01%, so 200 bps = 2%.
 *
 * <p>Rounding is half-up to the nearest paisa. Overflow is impossible within the API's
 * amount limit ({@link #MAX_AMOUNT}), and {@link Math#multiplyExact} guards it anyway.
 */
public final class FeeCalculator {

    /** ₹1 crore in paise. Keeps every product below {@code Long.MAX_VALUE}. */
    public static final long MAX_AMOUNT = 1_000_000_000L;

    private static final long BPS_DENOMINATOR = 10_000L;

    private FeeCalculator() {
    }

    public static long fee(long amount, int feeBps) {
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("amount out of range: " + amount);
        }
        if (feeBps < 0 || feeBps > BPS_DENOMINATOR) {
            throw new IllegalArgumentException("feeBps out of range: " + feeBps);
        }
        return roundHalfUp(Math.multiplyExact(amount, (long) feeBps), BPS_DENOMINATOR);
    }

    /**
     * The part of the original fee to give back for this refund, proportional to the refund.
     *
     * <p>We compute the cumulative fee refunded after this refund and subtract what was already
     * returned, instead of rounding each refund on its own. That way rounding errors never pile up,
     * and refunding the whole amount (in any number of pieces) returns exactly {@code totalFee}.
     */
    public static long refundFeePortion(long totalFee, long amount, long alreadyRefunded, long refundAmount) {
        long before = roundHalfUp(Math.multiplyExact(totalFee, alreadyRefunded), amount);
        long after = roundHalfUp(Math.multiplyExact(totalFee, Math.addExact(alreadyRefunded, refundAmount)), amount);
        return after - before;
    }

    private static long roundHalfUp(long numerator, long denominator) {
        return (numerator + denominator / 2) / denominator;
    }
}
