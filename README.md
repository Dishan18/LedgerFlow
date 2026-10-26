# LedgerFlow

A payments backend with a double-entry ledger, built with Java 21 and Spring Boot.

Merchants create payments, capture them and refund them. Every money movement is recorded
as a balanced, append-only journal entry, and every POST is idempotent.

> Work in progress, built in phases. Phase 1 (core domain) is done. Architecture diagram,
> results table and design write-up come in Phase 7.

## Modules

| Module | What it is |
|---|---|
| `common` | Contracts shared by the services (error codes) |
| `ledgerflow-api` | Payments, ledger, idempotency, outbox |
| `mock-bank` | Simulated unreliable bank (Phase 3) |

## Run it locally

Prerequisites: JDK 21 and Docker Desktop. Maven is not needed; use the wrapper.

```bash
docker compose up -d                 # PostgreSQL 17
./mvnw -pl ledgerflow-api -am spring-boot:run -Dspring-boot.run.profiles=local
```

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
