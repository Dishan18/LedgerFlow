# LedgerFlow

A payments backend with a double-entry ledger, built with Java 21 and Spring Boot.

Merchants create payments, capture them and refund them. Every money movement is recorded
as a balanced, append-only journal entry, and every POST is idempotent.

> Work in progress, built in phases. Phases 1-4 (core domain, concurrency proof, unreliable
> bank, events and webhooks) are done. Architecture diagram, results table and design write-up come in Phase 7.

## Modules

| Module | What it is |
|---|---|
| `common` | Contracts shared by the services (error codes, bank API, webhook signatures) |
| `ledgerflow-api` | Payments, ledger, idempotency, outbox relay, bank client, status poller, webhooks |
| `mock-bank` | Simulated unreliable PSP/bank with a UPI-style collect API |

## Run it locally

Prerequisites: JDK 21 and Docker Desktop. Maven is not needed; use the wrapper.

```bash
docker compose up -d --wait          # PostgreSQL 17 + LocalStack (SNS, SQS, S3, Secrets Manager)
python tools/merchant_receiver.py 0  # optional: demo merchant on :9090 that verifies webhook signatures
./mvnw -pl mock-bank -am spring-boot:run                                          # port 8081
./mvnw -pl ledgerflow-api -am spring-boot:run -Dspring-boot.run.profiles=local    # port 8080
```

LocalStack is pinned to 4.14.0: newer LocalStack images require an auth token. Its init script
([infra/localstack/init-aws.sh](infra/localstack/init-aws.sh)) creates the SNS topic, the SQS
queue with its DLQ and redrive policy, and the webhook signing secret.

mock-bank misbehaves on purpose (delays, outages, lost responses, pending collects; see
`mockbank.chaos` in its `application.yml`). Add a tag to the customer id to force one
behaviour, like a PSP sandbox's magic test values: `+approve`, `+decline`, `+pending`,
`+pending-decline`, `+timeout`, `+error`, `+flaky` (for example `cust_alice+timeout`).
Tagged customers need their own ledger account, so the untagged demo customers are the easy start.

The `local` profile loads demo data: merchant `11111111-1111-1111-1111-111111111111` (2% fee)
and customers `cust_alice` / `cust_bob` with Rs 10,000 each.

Swagger UI: http://localhost:8080/swagger-ui.html

Get a merchant token (local profile only), then call the API:

```bash
curl -X POST "localhost:8080/dev/token?merchantId=11111111-1111-1111-1111-111111111111"

curl -X POST localhost:8080/v1/payments \
  -H "Authorization: Bearer <token>" \
  -H "Idempotency-Key: order-42" \
  -H "Content-Type: application/json" \
  -d '{"customerId":"cust_alice","amount":25000}'
```

Amounts are integer **paise** (25000 = Rs 250).

## API

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/payments` | Create + authorize. Needs `Idempotency-Key`. |
| GET | `/v1/payments/{id}` | |
| POST | `/v1/payments/{id}/capture` | Full capture, posts the journal entry. Needs `Idempotency-Key`. |
| POST | `/v1/payments/{id}/refunds` | Partial or full. Needs `Idempotency-Key`. |

## Tests

```bash
./mvnw verify      # unit + jqwik property tests, then Testcontainers integration tests (needs Docker)
```

### Concurrency proofs (Phase 2)

The integration tests print `[proof]` lines. Typical results on a laptop:

| Scenario | Result |
|---|---|
| 1,000 concurrent debits of one account funded for 500 | 500 posted, 500 rejected, balance 0, imbalance 0 paise |
| 1,000 concurrent transfers in both directions across 5 accounts | 0 deadlocks, total conserved, imbalance 0 paise |
| 200 concurrent creates with one Idempotency-Key | 1 payment |
| 200 concurrent captures (half retries, half fresh keys) | 1 capture, customer charged once |
| 300 concurrent Rs 1 refunds of a Rs 100 payment | exactly 100 succeed |

Removing the account row lock makes these tests fail: all 1,000 debits "succeed" and
money disappears from cached balances through lost updates.

### Unreliable bank (Phase 3)

300 payments through mock-bank with its default chaos (about 1 in 4 collects misbehaves):

| Measure | Result |
|---|---|
| Final answer right away | 271 (257 authorized, 14 declined) |
| Needed the status poller | 29, all resolved within ~20 s |
| Final state | 275 authorized, 25 failed, 0 stuck |
| Disagreements between LedgerFlow and the bank | 0 |

### Events and webhooks (Phase 4)

Every payment change writes an outbox row in the same transaction. A relay publishes the rows to
SNS `payment-events`, which fans out to SQS `webhook-delivery`. The dispatcher signs each event
(`X-LedgerFlow-Signature: t=...,v1=HMAC-SHA256`) and POSTs it to the merchant. Failed deliveries
come back after an exponential SQS visibility timeout, and after 5 attempts they go to the DLQ.

300 payments (640 events) against a demo merchant that fails 30% of requests on purpose
(`tools/merchant_receiver.py 0.3`, which verifies signatures in Python, independently of the Java code):

| Measure | Result |
|---|---|
| Events published by the outbox relay | 640 / 640 |
| Delivered after retries | 633 (98.9%) |
| Delivered first try / 2nd / 3rd / 4th / 5th | 412 / 139 / 49 / 27 / 6 |
| Dead-lettered after 5 attempts (DLQ count) | 7 |
| Signatures rejected by the merchant | 0 |

Reproduce: start everything as above with `python tools/merchant_receiver.py 0.3`, then run
`python tools/chaos_run.py 300`.
