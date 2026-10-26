package com.ledgerflow.api.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Schedules {@link PaymentStatusPoller}. Kept separate so tests can drive the poller by hand. */
@Component
@ConditionalOnBooleanProperty(name = "ledgerflow.status-poller.enabled", matchIfMissing = true)
class PaymentStatusPollerJob {

    private final PaymentStatusPoller poller;

    PaymentStatusPollerJob(PaymentStatusPoller poller) {
        this.poller = poller;
    }

    /** One batch per tick keeps each round short and predictable. */
    @Scheduled(fixedDelayString = "${ledgerflow.status-poller.interval}")
    void poll() {
        poller.pollOnce();
    }
}
