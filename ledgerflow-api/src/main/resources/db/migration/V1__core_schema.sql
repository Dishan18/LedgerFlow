-- LedgerFlow core schema.
-- All money columns are BIGINT paise (minor units of INR). No floating point anywhere.

CREATE TABLE merchant (
    id         UUID         PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    fee_bps    INTEGER      NOT NULL CHECK (fee_bps BETWEEN 0 AND 10000),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One row per ledger account. balance is a cached projection of journal_line,
-- expressed on the account's normal side (see AccountType in Java).
CREATE TABLE account (
    id             UUID        PRIMARY KEY,
    account_type   VARCHAR(32) NOT NULL
        CHECK (account_type IN ('CUSTOMER', 'MERCHANT_RECEIVABLE', 'PLATFORM_FEE', 'BANK_SETTLEMENT')),
    owner_id       VARCHAR(64) NOT NULL,
    currency       VARCHAR(3)  NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    balance        BIGINT      NOT NULL DEFAULT 0,
    allow_negative BOOLEAN     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Last line of defence against overdraft, even if application code is wrong.
    CONSTRAINT account_no_overdraft CHECK (allow_negative OR balance >= 0),
    CONSTRAINT account_type_owner_uq UNIQUE (account_type, owner_id)
);

CREATE TABLE payment (
    id              UUID        PRIMARY KEY,
    merchant_id     UUID        NOT NULL REFERENCES merchant (id),
    customer_id     VARCHAR(64) NOT NULL,
    amount          BIGINT      NOT NULL CHECK (amount > 0),
    fee_amount      BIGINT      NOT NULL CHECK (fee_amount >= 0),
    refunded_amount BIGINT      NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    currency        VARCHAR(3)  NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    status          VARCHAR(32) NOT NULL
        CHECK (status IN ('CREATED', 'PENDING_CONFIRMATION', 'AUTHORIZED', 'CAPTURED', 'SETTLED',
                          'FAILED', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    bank_reference  VARCHAR(64),
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payment_fee_le_amount CHECK (fee_amount <= amount),
    -- Capture is always for the full amount, so "captured minus earlier refunds" is amount - refunded_amount.
    CONSTRAINT payment_refund_le_amount CHECK (refunded_amount <= amount)
);

CREATE INDEX payment_merchant_created_idx ON payment (merchant_id, created_at);

CREATE TABLE journal_entry (
    id                UUID         PRIMARY KEY,
    entry_type        VARCHAR(32)  NOT NULL CHECK (entry_type IN ('CAPTURE', 'REFUND', 'TRANSFER')),
    payment_id        UUID         REFERENCES payment (id),
    reverses_entry_id UUID         REFERENCES journal_entry (id),
    description       VARCHAR(255) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX journal_entry_payment_idx ON journal_entry (payment_id);

CREATE TABLE journal_line (
    id         UUID       PRIMARY KEY,
    entry_id   UUID       NOT NULL REFERENCES journal_entry (id),
    account_id UUID       NOT NULL REFERENCES account (id),
    direction  VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount     BIGINT     NOT NULL CHECK (amount > 0)
);

CREATE INDEX journal_line_entry_idx ON journal_line (entry_id);
CREATE INDEX journal_line_account_idx ON journal_line (account_id);

CREATE TABLE refund (
    id               UUID        PRIMARY KEY,
    payment_id       UUID        NOT NULL REFERENCES payment (id),
    amount           BIGINT      NOT NULL CHECK (amount > 0),
    journal_entry_id UUID        NOT NULL REFERENCES journal_entry (id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX refund_payment_idx ON refund (payment_id);

CREATE TABLE idempotency_key (
    key           VARCHAR(320) PRIMARY KEY,
    request_hash  VARCHAR(64)  NOT NULL,
    status        VARCHAR(16)  NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_code INTEGER,
    response_body JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idempotency_key_created_idx ON idempotency_key (created_at);

CREATE TABLE outbox_event (
    id             UUID        PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR(64) NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

-- The relay (Phase 4) only ever scans unpublished rows, oldest first.
CREATE INDEX outbox_event_unpublished_idx ON outbox_event (created_at) WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------
-- Ledger invariants enforced by the database itself.
-- ---------------------------------------------------------------------------

-- 1. Append-only: journal rows can never be updated or deleted. Mistakes are fixed with reversal entries.
CREATE FUNCTION reject_journal_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only (% rejected)', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER journal_entry_append_only
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION reject_journal_modification();

CREATE TRIGGER journal_entry_no_truncate
    BEFORE TRUNCATE ON journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION reject_journal_modification();

CREATE TRIGGER journal_line_append_only
    BEFORE UPDATE OR DELETE ON journal_line
    FOR EACH ROW EXECUTE FUNCTION reject_journal_modification();

CREATE TRIGGER journal_line_no_truncate
    BEFORE TRUNCATE ON journal_line
    FOR EACH STATEMENT EXECUTE FUNCTION reject_journal_modification();

-- 2. Balanced: at COMMIT time, every entry touched in the transaction must have debits = credits.
--    DEFERRABLE INITIALLY DEFERRED lets us insert the lines one by one inside the transaction.
CREATE FUNCTION assert_journal_entry_balanced() RETURNS trigger AS $$
DECLARE
    total_debits  BIGINT;
    total_credits BIGINT;
    line_count    INTEGER;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0),
           COUNT(*)
      INTO total_debits, total_credits, line_count
      FROM journal_line
     WHERE entry_id = NEW.entry_id;

    IF line_count < 2 OR total_debits <> total_credits THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: debits=% credits=% lines=%',
            NEW.entry_id, total_debits, total_credits, line_count;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER journal_line_balanced
    AFTER INSERT ON journal_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_journal_entry_balanced();

-- ---------------------------------------------------------------------------
-- System accounts (exactly one of each).
-- ---------------------------------------------------------------------------
INSERT INTO account (id, account_type, owner_id, allow_negative) VALUES
    ('00000000-0000-0000-0000-000000000001', 'PLATFORM_FEE', 'SYSTEM', TRUE),
    ('00000000-0000-0000-0000-000000000002', 'BANK_SETTLEMENT', 'SYSTEM', TRUE);
