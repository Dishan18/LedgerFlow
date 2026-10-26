package com.ledgerflow.api.payment;

import com.ledgerflow.api.bank.AuthorizationResult;
import com.ledgerflow.api.outbox.PaymentEventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns bank answers into payment state changes. Used both right after the authorize call and
 * by the status poller, so the two paths can't disagree. Callers hold the payment's row lock.
 */
@Service
public class PaymentConfirmationService {

    /** Caps 2^attempts so the backoff maths can't overflow. */
    private static final int MAX_BACKOFF_EXPONENT = 16;

    private final PaymentEventPublisher events;
    private final StatusPollerProperties properties;
    private final Clock clock;

    public PaymentConfirmationService(PaymentEventPublisher events, StatusPollerProperties properties, Clock clock) {
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /** Applies the answer to the initial authorize call. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyAuthorization(Payment payment, AuthorizationResult result) {
        if (!payment.getStatus().isAwaitingBank()) {
            return; // the status poller resolved it first
        }
        switch (result) {
            case AuthorizationResult.Approved approved -> authorize(payment, approved);
            case AuthorizationResult.Declined declined -> fail(payment, declined.reason());
            case AuthorizationResult.Pending pending ->
                    awaitConfirmation(payment, clock.instant().plus(properties.firstCheckAfter()));
            case AuthorizationResult.NotFound notFound ->
                    throw new IllegalStateException("authorize never answers NotFound");
        }
    }

    /** Applies the answer to a status check made by the poller. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyStatusCheck(Payment payment, AuthorizationResult result) {
        if (!payment.getStatus().isAwaitingBank()) {
            return;
        }
        payment.recordStatusCheck();
        Instant now = clock.instant();
        Duration age = Duration.between(payment.getCreatedAt(), now);
        switch (result) {
            case AuthorizationResult.Approved approved -> authorize(payment, approved);
            case AuthorizationResult.Declined declined -> fail(payment, declined.reason());
            case AuthorizationResult.NotFound notFound -> {
                // A request may still be in flight right after creation, so "no record" only becomes
                // conclusive after a grace period.
                if (age.compareTo(properties.notFoundGrace()) >= 0) {
                    fail(payment, "BANK_HAS_NO_RECORD");
                } else {
                    awaitConfirmation(payment, nextCheck(payment, now));
                }
            }
            case AuthorizationResult.Pending pending -> {
                if (age.compareTo(properties.maxPendingAge()) >= 0) {
                    // If the bank did collect after all, daily reconciliation flags it as missing-in-ledger.
                    fail(payment, "CONFIRMATION_TIMEOUT");
                } else {
                    awaitConfirmation(payment, nextCheck(payment, now));
                }
            }
        }
    }

    private void authorize(Payment payment, AuthorizationResult.Approved approved) {
        payment.markAuthorized(approved.bankReference());
        events.publish("payment.authorized", payment);
    }

    private void fail(Payment payment, String reason) {
        payment.markFailed(reason);
        events.publish("payment.failed", payment);
    }

    private void awaitConfirmation(Payment payment, Instant nextCheckAt) {
        boolean firstTime = payment.getStatus() == PaymentStatus.CREATED;
        payment.markPendingConfirmation(nextCheckAt);
        if (firstTime) {
            events.publish("payment.pending_confirmation", payment);
        }
    }

    /** Exponential backoff per payment: firstCheckAfter x 2^attempts, capped at maxCheckInterval. */
    private Instant nextCheck(Payment payment, Instant now) {
        int exponent = Math.min(payment.getStatusCheckAttempts(), MAX_BACKOFF_EXPONENT);
        Duration delay = properties.firstCheckAfter().multipliedBy(1L << exponent);
        if (delay.compareTo(properties.maxCheckInterval()) > 0) {
            delay = properties.maxCheckInterval();
        }
        return now.plus(delay);
    }
}
