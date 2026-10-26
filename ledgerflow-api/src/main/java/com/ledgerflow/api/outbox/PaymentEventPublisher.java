package com.ledgerflow.api.outbox;

import com.ledgerflow.api.payment.Payment;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Writes payment events to the outbox table. Nothing leaves the database here. */
@Component
public class PaymentEventPublisher {

    /** Event body. {@code eventId} lets consumers deduplicate redeliveries. */
    public record PaymentEvent(
            UUID eventId,
            String eventType,
            UUID paymentId,
            UUID merchantId,
            long amount,
            long refundedAmount,
            String currency,
            String status,
            Instant occurredAt) {
    }

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public PaymentEventPublisher(OutboxRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String eventType, Payment payment) {
        UUID eventId = UUID.randomUUID();
        PaymentEvent event = new PaymentEvent(
                eventId,
                eventType,
                payment.getId(),
                payment.getMerchantId(),
                payment.getAmount(),
                payment.getRefundedAmount(),
                payment.getCurrency(),
                payment.getStatus().name(),
                Instant.now());
        outbox.save(new OutboxEvent(eventId, "payment", payment.getId(), eventType, objectMapper.writeValueAsString(event)));
    }
}
