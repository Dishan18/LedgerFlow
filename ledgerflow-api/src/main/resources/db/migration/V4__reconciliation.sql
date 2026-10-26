-- Phase 5: daily reconciliation against the bank's settlement file.

-- When the bank's settlement file confirmed this payment. Kept separate from status because a
-- settled payment can still be refunded, and the status then says REFUNDED / PARTIALLY_REFUNDED.
ALTER TABLE payment ADD COLUMN settled_at TIMESTAMPTZ;

-- Reconciliation scans one business day of payments.
CREATE INDEX payment_created_idx ON payment (created_at);

-- Every run is kept (a file can be re-delivered or re-uploaded); the API serves the latest per date.
CREATE TABLE reconciliation_report (
    id                UUID          PRIMARY KEY,
    business_date     DATE          NOT NULL,
    source_key        VARCHAR(1024) NOT NULL,
    bank_rows         INTEGER       NOT NULL,
    ledger_payments   INTEGER       NOT NULL,
    matched           INTEGER       NOT NULL,
    missing_in_bank   INTEGER       NOT NULL,
    missing_in_ledger INTEGER       NOT NULL,
    duplicates        INTEGER       NOT NULL,
    amount_mismatches INTEGER       NOT NULL,
    report            JSONB         NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX reconciliation_report_date_idx ON reconciliation_report (business_date, created_at DESC);
