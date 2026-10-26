package com.ledgerflow.api.payment;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Payment lifecycle. The transition table below is the single source of truth: anything
 * not listed is illegal. FAILED and REFUNDED are terminal (no outgoing transitions).
 *
 * <p>Only a CAPTURED payment moves to SETTLED. A payment refunded before settlement keeps its refund
 * status, and reconciliation records the settlement in {@code settledAt} instead.
 *
 * <pre>
 * CREATED ──► AUTHORIZED ──► CAPTURED ──► SETTLED
 *    │            ▲              │  │         │
 *    ├──► PENDING_CONFIRMATION   │  └──► PARTIALLY_REFUNDED ──► REFUNDED
 *    │            │              └──────────────────────────────► REFUNDED
 *    └──► FAILED ◄┘
 * </pre>
 */
public enum PaymentStatus {
    CREATED,
    PENDING_CONFIRMATION,
    AUTHORIZED,
    CAPTURED,
    SETTLED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    FAILED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(AUTHORIZED, PENDING_CONFIRMATION, FAILED),
            PENDING_CONFIRMATION, EnumSet.of(AUTHORIZED, FAILED),
            AUTHORIZED, EnumSet.of(CAPTURED),
            CAPTURED, EnumSet.of(SETTLED, PARTIALLY_REFUNDED, REFUNDED),
            SETTLED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class),
            FAILED, EnumSet.noneOf(PaymentStatus.class));

    public boolean canTransitionTo(PaymentStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public Set<PaymentStatus> allowedTransitions() {
        return Collections.unmodifiableSet(ALLOWED.get(this));
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Waiting for the bank: the status poller follows these up. */
    public boolean isAwaitingBank() {
        return this == CREATED || this == PENDING_CONFIRMATION;
    }

    /** States from which money can be returned to the customer. */
    public boolean isRefundable() {
        return this == CAPTURED || this == SETTLED || this == PARTIALLY_REFUNDED;
    }
}
