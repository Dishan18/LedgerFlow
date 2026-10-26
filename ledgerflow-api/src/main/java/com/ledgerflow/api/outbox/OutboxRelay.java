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
import software.amazon.awssdk.services.sns.model.PublishBatchRequest;
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry;
import software.amazon.awssdk.services.sns.model.PublishBatchResponse;

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

    private static final int SNS_BATCH_LIMIT = 10;

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

            // SNS takes up to 10 messages per call: 10x fewer round trips while we hold the row locks.
            int count = 0;
            for (int from = 0; from < batch.size(); from += SNS_BATCH_LIMIT) {
                List<PendingEvent> chunk = batch.subList(from, Math.min(from + SNS_BATCH_LIMIT, batch.size()));
                List<UUID> accepted;
                try {
                    accepted = publish(chunk);
                } catch (RuntimeException e) {
                    log.warn("Publishing outbox batch failed, will retry: {}", e.toString());
                    meters.counter("ledgerflow.outbox.publish.failures").increment();
                    break;
                }
                for (UUID id : accepted) {
                    jdbc.sql("UPDATE outbox_event SET published_at = :now WHERE id = :id")
                            .param("now", Timestamp.from(clock.instant()))
                            .param("id", id)
                            .update();
                }
                count += accepted.size();
                if (accepted.size() < chunk.size()) {
                    // Keep what went out and stop; the rest stays unpublished for the next round.
                    meters.counter("ledgerflow.outbox.publish.failures").increment(chunk.size() - accepted.size());
                    break;
                }
            }
            return count;
        });
        if (published != null && published > 0) {
            meters.counter("ledgerflow.outbox.published").increment(published);
        }
        return published == null ? 0 : published;
    }

    /** Publishes up to 10 events in one call. Returns the ids SNS accepted. */
    private List<UUID> publish(List<PendingEvent> events) {
        List<PublishBatchRequestEntry> entries = events.stream()
                .map(event -> PublishBatchRequestEntry.builder()
                        .id(event.id().toString())
                        .message(event.payload())
                        .messageAttributes(Map.of(
                                "eventType", MessageAttributeValue.builder().dataType("String")
                                        .stringValue(event.eventType()).build(),
                                "eventId", MessageAttributeValue.builder().dataType("String")
                                        .stringValue(event.id().toString()).build()))
                        .build())
                .toList();
        PublishBatchResponse response = sns.publishBatch(PublishBatchRequest.builder()
                .topicArn(properties.topicArn())
                .publishBatchRequestEntries(entries)
                .build());
        if (!response.failed().isEmpty()) {
            log.warn("SNS rejected {} of {} outbox events: {}", response.failed().size(), events.size(),
                    response.failed().getFirst().message());
        }
        return response.successful().stream().map(ok -> UUID.fromString(ok.id())).toList();
    }
}
