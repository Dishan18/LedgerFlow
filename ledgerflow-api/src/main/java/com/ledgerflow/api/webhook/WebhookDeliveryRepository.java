package com.ledgerflow.api.webhook;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookDeliveryRepository {

    public enum Status { PENDING, DELIVERED, DEAD_LETTERED, SKIPPED }

    public record Delivery(UUID eventId, Status status, int attempts, Integer lastStatusCode) {
    }

    private final JdbcClient jdbc;

    public WebhookDeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the row on first sight of an event; later redeliveries see the existing row. */
    public Delivery findOrCreate(UUID eventId, UUID merchantId, String eventType) {
        jdbc.sql("""
                        INSERT INTO webhook_delivery (event_id, merchant_id, event_type, status)
                        VALUES (:eventId, :merchantId, :eventType, 'PENDING')
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("merchantId", merchantId)
                .param("eventType", eventType)
                .update();
        return find(eventId).orElseThrow();
    }

    public Optional<Delivery> find(UUID eventId) {
        return jdbc.sql("SELECT event_id, status, attempts, last_status_code FROM webhook_delivery WHERE event_id = :id")
                .param("id", eventId)
                .query((rs, n) -> new Delivery(
                        rs.getObject("event_id", UUID.class),
                        Status.valueOf(rs.getString("status")),
                        rs.getInt("attempts"),
                        (Integer) rs.getObject("last_status_code")))
                .optional();
    }

    public void recordAttempt(UUID eventId, Status status, Integer statusCode, String error, Instant now) {
        jdbc.sql("""
                        UPDATE webhook_delivery
                           SET status = :status,
                               attempts = attempts + 1,
                               last_status_code = :code,
                               last_error = :error,
                               updated_at = :now,
                               delivered_at = CASE WHEN :status = 'DELIVERED' THEN :now ELSE delivered_at END
                         WHERE event_id = :eventId
                        """)
                .param("status", status.name())
                .param("code", statusCode)
                .param("error", error == null ? null : truncate(error))
                .param("now", Timestamp.from(now))
                .param("eventId", eventId)
                .update();
    }

    public void markSkipped(UUID eventId, Instant now) {
        jdbc.sql("UPDATE webhook_delivery SET status = 'SKIPPED', updated_at = :now WHERE event_id = :eventId")
                .param("now", Timestamp.from(now))
                .param("eventId", eventId)
                .update();
    }

    private static String truncate(String error) {
        return error.length() <= 255 ? error : error.substring(0, 255);
    }
}
