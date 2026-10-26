package com.ledgerflow.mockbank;

import java.util.Locale;
import java.util.Optional;

/**
 * What the bank does with a new collect. Like a PSP sandbox's magic test VPAs, a tag in the
 * payer VPA forces a scenario: {@code alice+timeout@upi} always times out.
 */
public enum Scenario {
    APPROVE,
    DECLINE,
    /** Pending, later approved. */
    PENDING,
    /** Pending, later declined. */
    PENDING_DECLINE,
    /** Outcome recorded (approved), but the response never arrives in time. */
    TIMEOUT,
    /** 503 every time, nothing recorded. */
    ERROR,
    /** 503 on the first attempt, then approved. Exercises client retries. */
    FLAKY;

    /** Reads a {@code +tag} from the VPA, e.g. {@code cust_1+pending-decline}. */
    public static Optional<Scenario> fromVpaTag(String vpa) {
        int plus = vpa.indexOf('+');
        if (plus < 0) {
            return Optional.empty();
        }
        String tag = vpa.substring(plus + 1);
        int at = tag.indexOf('@');
        if (at >= 0) {
            tag = tag.substring(0, at);
        }
        try {
            return Optional.of(valueOf(tag.toUpperCase(Locale.ROOT).replace('-', '_')));
        } catch (IllegalArgumentException unknownTag) {
            return Optional.empty();
        }
    }
}
