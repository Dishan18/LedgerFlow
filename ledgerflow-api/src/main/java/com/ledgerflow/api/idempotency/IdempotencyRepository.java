package com.ledgerflow.api.idempotency;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL instead of JPA: the whole point of this table is the exact
 * {@code INSERT ... ON CONFLICT DO NOTHING} statement, so we write it ourselves.
 *
 * <p>Each method runs in its own auto-committed statement (callers are not transactional),
 * so a claim is visible to other requests the moment it is made.
 */
@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically claims a key. Exactly one of any number of concurrent callers gets {@code true};
     * the database's primary-key uniqueness is the lock.
     */
    public boolean claim(String key, String requestHash) {
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_key (key, request_hash, status, created_at)
                        VALUES (:key, :hash, 'IN_PROGRESS', now())
                        ON CONFLICT (key) DO NOTHING
                        """)
                .param("key", key)
                .param("hash", requestHash)
                .update();
        return inserted == 1;
    }

    public Optional<IdempotencyRecord> find(String key) {
        return jdbc.sql("""
                        SELECT key, request_hash, status, response_code, response_body::text AS response_body, created_at
                          FROM idempotency_key
                         WHERE key = :key
                        """)
                .param("key", key)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("key"),
                        rs.getString("request_hash"),
                        IdempotencyRecord.Status.valueOf(rs.getString("status")),
                        (Integer) rs.getObject("response_code"),
                        rs.getString("response_body"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    public void complete(String key, int responseCode, String responseBody) {
        jdbc.sql("""
                        UPDATE idempotency_key
                           SET status = 'COMPLETED', response_code = :code, response_body = CAST(:body AS jsonb)
                         WHERE key = :key AND status = 'IN_PROGRESS'
                        """)
                .param("key", key)
                .param("code", responseCode)
                .param("body", responseBody)
                .update();
    }

    /** Gives the key back so the client can retry; only an unfinished claim can be released. */
    public void release(String key) {
        jdbc.sql("DELETE FROM idempotency_key WHERE key = :key AND status = 'IN_PROGRESS'")
                .param("key", key)
                .update();
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM idempotency_key WHERE created_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }
}
