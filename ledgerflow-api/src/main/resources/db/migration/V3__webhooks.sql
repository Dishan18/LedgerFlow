-- Phase 4: events and webhooks.

ALTER TABLE merchant ADD COLUMN webhook_url VARCHAR(2048);

-- One row per event we were asked to deliver. The primary key on event_id is what makes the
-- consumer idempotent: SNS/SQS deliver at least once, and the outbox relay may publish twice.
CREATE TABLE webhook_delivery (
    event_id         UUID        PRIMARY KEY,
    merchant_id      UUID        NOT NULL,
    event_type       VARCHAR(64) NOT NULL,
    status           VARCHAR(16) NOT NULL
        CHECK (status IN ('PENDING', 'DELIVERED', 'DEAD_LETTERED', 'SKIPPED')),
    attempts         INTEGER     NOT NULL DEFAULT 0,
    last_status_code INTEGER,
    last_error       VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ
);

CREATE INDEX webhook_delivery_merchant_idx ON webhook_delivery (merchant_id, created_at);
