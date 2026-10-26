package com.ledgerflow.api.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty(name = "ledgerflow.outbox.enabled", matchIfMissing = true)
class OutboxRelayJob {

    private final OutboxRelay relay;
    private final OutboxProperties properties;

    OutboxRelayJob(OutboxRelay relay, OutboxProperties properties) {
        this.relay = relay;
        this.properties = properties;
    }

    /** Drains the backlog in full batches, then waits for the next tick. */
    @Scheduled(fixedDelayString = "${ledgerflow.outbox.poll-interval}")
    void relay() {
        int published;
        do {
            published = relay.relayOnce();
        } while (published == properties.batchSize());
    }
}
