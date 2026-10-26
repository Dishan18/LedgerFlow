package com.ledgerflow.api.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * Moves committed outbox rows to SNS.
 *
 * <p>Each round locks a batch of unpublished rows with {@code FOR UPDATE SKIP LOCKED}, so several
 * app instances can relay in parallel without publishing the same row at the same time. A row is
 * marked published only after SNS accepted it. If we crash between the two, the row is published
 * again later: delivery is <em>at least once</em>, and consumers deduplicate by event id.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    record PendingEvent(UUID id, String eventType, String payload) {
    }

    private final JdbcClient jdbc;
    private final SnsClient sns;
    private final OutboxProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    public OutboxRelay(JdbcClient jdbc, SnsClient sns, OutboxProperties properties, TransactionTemplate tx,
                       Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.sns = sns;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
    }

    /** Publishes one batch. Returns how many rows were published. */
    public int relayOnce() {
        Integer published = tx.execute(status -> {
            List<PendingEvent> batch = jdbc.sql("""
                            SELECT id, event_type, payload::text AS payload
                              FROM outbox_event
                             WHERE published_at IS NULL
                             ORDER BY created_at
                             LIMIT :batch
                               FOR UPDATE SKIP LOCKED
                            """)
                    .param("batch", properties.batchSize())
                    .query((rs, n) -> new PendingEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
                            rs.getString("payload")))
                    .list();

            int count = 0;
            for (PendingEvent event : batch) {
                try {
                    publish(event);
                } catch (RuntimeException e) {
                    // Keep what already went out and stop; the rest stays unpublished for the next round.
                    log.warn("Publishing outbox event {} failed, will retry: {}", event.id(), e.toString());
                    meters.counter("ledgerflow.outbox.publish.failures").increment();
                    break;
                }
                jdbc.sql("UPDATE outbox_event SET published_at = :now WHERE id = :id")
                        .param("now", Timestamp.from(clock.instant()))
                        .param("id", event.id())
                        .update();
                count++;
            }
            return count;
        });
        if (published != null && published > 0) {
            meters.counter("ledgerflow.outbox.published").increment(published);
        }
        return published == null ? 0 : published;
    }

    private void publish(PendingEvent event) {
        sns.publish(PublishRequest.builder()
                .topicArn(properties.topicArn())
                .message(event.payload())
                .messageAttributes(Map.of(
                        "eventType", MessageAttributeValue.builder().dataType("String").stringValue(event.eventType()).build(),
                        "eventId", MessageAttributeValue.builder().dataType("String").stringValue(event.id().toString()).build()))
                .build());
    }
}
