package com.ledgerflow.api.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Schedules {@link PaymentStatusPoller}. Kept separate so tests can drive the poller by hand. */
@Component
@ConditionalOnBooleanProperty(name = "ledgerflow.status-poller.enabled", matchIfMissing = true)
class PaymentStatusPollerJob {

    private final PaymentStatusPoller poller;
    private final StatusPollerProperties properties;

    PaymentStatusPollerJob(PaymentStatusPoller poller, StatusPollerProperties properties) {
        this.poller = poller;
        this.properties = properties;
    }

    /** Drains the backlog in full batches, so a burst of pending payments doesn't wait many ticks. */
    @Scheduled(fixedDelayString = "${ledgerflow.status-poller.interval}")
    void poll() {
        int checked;
        do {
            checked = poller.pollOnce();
        } while (checked == properties.batchSize());
    }
}
