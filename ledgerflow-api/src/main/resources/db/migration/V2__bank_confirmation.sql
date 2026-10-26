-- Phase 3: unreliable bank. Payments can wait for the bank to confirm, and a poller follows up.

ALTER TABLE payment
    ADD COLUMN failure_reason        VARCHAR(255),
    ADD COLUMN status_check_attempts INTEGER NOT NULL DEFAULT 0,
    -- When the status poller should next ask the bank about this payment. NULL once resolved.
    ADD COLUMN next_status_check_at  TIMESTAMPTZ;

-- The poller only ever scans payments still waiting on the bank.
CREATE INDEX payment_status_check_due_idx
    ON payment (next_status_check_at)
    WHERE status IN ('CREATED', 'PENDING_CONFIRMATION');
