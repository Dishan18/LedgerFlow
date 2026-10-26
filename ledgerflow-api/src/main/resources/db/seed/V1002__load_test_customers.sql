-- 1,000 demo customers (cust_0001 .. cust_1000) with Rs 1,000 each, so load tests can spread
-- payments across many accounts instead of queueing on one hot row.
-- Funded through one balanced journal entry: debit bank settlement, credit each customer.

INSERT INTO account (id, account_type, owner_id, allow_negative, balance)
SELECT md5('loadtest-customer-' || n)::uuid, 'CUSTOMER', 'cust_' || lpad(n::text, 4, '0'), FALSE, 100000
  FROM generate_series(1, 1000) AS n;

INSERT INTO journal_entry (id, entry_type, description)
VALUES ('66666666-6666-6666-6666-666666666666', 'TRANSFER', 'demo funding for 1,000 load-test customers');

INSERT INTO journal_line (id, entry_id, account_id, direction, amount)
SELECT md5('loadtest-line-' || n)::uuid, '66666666-6666-6666-6666-666666666666',
       md5('loadtest-customer-' || n)::uuid, 'CREDIT', 100000
  FROM generate_series(1, 1000) AS n;

INSERT INTO journal_line (id, entry_id, account_id, direction, amount)
VALUES ('66666666-6666-6666-6666-666666666667', '66666666-6666-6666-6666-666666666666',
        '00000000-0000-0000-0000-000000000002', 'DEBIT', 100000000);

UPDATE account SET balance = balance + 100000000 WHERE id = '00000000-0000-0000-0000-000000000002';
