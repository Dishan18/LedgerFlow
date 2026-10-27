# LedgerFlow demo walkthrough

A script for showing LedgerFlow live on this Windows laptop: how to start it, what to run, what to say,
and what is happening in the code at each step. About 15 minutes. Quick Q&A is at the end.

For deeper material see [HLD.md](HLD.md), [LLD.md](LLD.md) and [guide.html](guide.html).

---

## Part 1: Start it on this laptop

### Once, well before the interview

| Need | Check | Notes |
|---|---|---|
| Docker Desktop | running (whale icon in the tray) | Everything runs in containers |
| Python 3 | `python --version` | Only for the scripts in `tools/` (standard library, nothing to install) |
| JDK 21 | `C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot` | Only for running tests. Some windows still have JDK 17 first on PATH; step 13 fixes that per window |
| Free ports | 5432, 4566, 8080, 8081, 9090 | Postgres, LocalStack, API, bank, merchant |

Build the images once so the demo itself starts fast (the first build takes a few minutes):

```powershell
cd D:\Ledgerflow
docker compose build
```

### Right before the interview (about 1 minute)

Open **PowerShell** in `D:\Ledgerflow`:

```powershell
docker compose down -v          # wipe data from earlier runs
docker compose up -d            # start all 5 containers
docker compose ps               # wait for localstack and postgres to say (healthy)
Invoke-RestMethod http://localhost:8080/actuator/health    # -> status UP
```

Always start with `down -v`. mock-bank keeps its records in memory, so old payments left in Postgres would show up
as false "missing in bank" rows in the reconciliation report.

Then paste this **session setup** (re-run it if you open a new window or after 12 hours):

```powershell
$api   = "http://localhost:8080"
$token = (Invoke-RestMethod -Method Post "$api/dev/token?merchantId=11111111-1111-1111-1111-111111111111").access_token
$auth  = @{ Authorization = "Bearer $token" }
function Post($path, $body, $key = [guid]::NewGuid().ToString()) {
  Invoke-RestMethod -Method Post "$api$path" -Headers ($auth + @{ "Idempotency-Key" = $key }) -ContentType "application/json" -Body $body
}
```

Keep these tabs open in the browser:
- http://localhost:8080/swagger-ui.html
- `docs/guide.html`, for the architecture diagram

---

## Part 2: The demo

Each step lists what to **run**, what to **say**, and what happens **under the hood**, with file references you can
open if they ask for code.

### Step 1: Big picture (1 min)

**Show:** the architecture diagram in `guide.html` (High-level design section), then `docker compose ps`.

**Say:** "Five containers. Postgres; LocalStack, which runs the same SNS topic, SQS queues, S3 bucket and
secrets I'd use on AWS; mock-bank, a deliberately unreliable UPI bank; the LedgerFlow API; and a fake merchant that
receives webhooks. The application code is identical locally and on AWS; only endpoints change."

**Under the hood:**
- `docker-compose.yml` starts the containers.
- `infra/localstack/init-aws.sh` creates the topic, queues (with DLQs), bucket and secret.
- Flyway migrations `ledgerflow-api/src/main/resources/db/migration/V1..V4` create the schema.
- The seed files add the demo merchant "Chai Point Demo" (2% fee) and funded customers.

### Step 2: Create a payment (1 min)

```powershell
$p = Post "/v1/payments" '{"customerId":"cust_alice","amount":25000}' "demo-order-1"
$p
```

**Say:** "Amounts are integer paise, so this is ₹250 with no floating point anywhere. The fee is 500 paise, 2%."

**Under the hood:**
1. `PaymentController` → `IdempotencyService` inserts the key `merchantId:demo-order-1` with `ON CONFLICT DO NOTHING`.
2. `PaymentService.createAndAuthorize` runs transaction 1: insert the payment as `CREATED` plus an outbox event.
3. With **no transaction open**, `HttpBankGateway` calls the bank: 2 s timeout, up to 2 retries.
4. Transaction 2 records the answer in `PaymentConfirmationService`.

**If status is:**
- `AUTHORIZED`: the normal case. Go on.
- `PENDING_CONFIRMATION`: even better. Do Step 4 next.
- `FAILED`: the bank declined it (chaos). Create another payment with key `demo-order-2`.

### Step 3: Idempotency (1 min)

```powershell
# same key + same body -> same payment, flagged as a replay
$r = Invoke-WebRequest -UseBasicParsing -Method Post "$api/v1/payments" -Headers ($auth + @{ "Idempotency-Key" = "demo-order-1" }) -ContentType "application/json" -Body '{"customerId":"cust_alice","amount":25000}'
$r.Headers["Idempotent-Replayed"]; ($r.Content | ConvertFrom-Json).id; $p.id

# same key + different body -> 422
try { Post "/v1/payments" '{"customerId":"cust_alice","amount":999}' "demo-order-1" } catch { $_.Exception.Response.StatusCode.value__ }
```

**Expect:** `True`, two identical ids, then `422`.

**Say:** "Mobile networks drop responses and clients retry. The retry gets the stored response, not a second
charge. Reusing a key for a different request is a client bug, so it's rejected."

**Under the hood:** `IdempotencyService` finds the key `COMPLETED` with the same request hash and replays the stored
status and body. A different hash gives `IDEMPOTENCY_KEY_REUSED`. A request arriving while the first is still running gets 409.
`RetryStormIT` fires 200 concurrent creates with one key and expects exactly one payment.

### Step 4: Unreliable bank and the status poller (1 min, if you got a pending payment)

```powershell
Invoke-RestMethod "$api/v1/payments/$($p.id)" -Headers $auth      # run again after ~20 s
```

**Say:** "The bank said pending, or never answered. A timeout doesn't mean failed: the bank may have taken the money
and lost the response. So the payment waits in `PENDING_CONFIRMATION`, and a background poller asks the bank again
until it gets a final answer."

**Under the hood:**
- `PaymentStatusPoller` runs every 5 s. It claims due payments with `FOR UPDATE SKIP LOCKED` plus a 1-minute lease, so several API instances never check the same payment.
- It calls the bank in parallel on virtual threads and records each answer in its own short transaction.
- Backoff per payment is 5 s × 2ⁿ, up to 5 min.
- It gives up after 30 min; reconciliation catches a late collect.

### Step 5: Capture and refund: money moves (1 min)

```powershell
Post "/v1/payments/$($p.id)/capture" $null
Post "/v1/payments/$($p.id)/refunds" '{"amount":10000}'
(Invoke-RestMethod "$api/v1/payments/$($p.id)" -Headers $auth).status     # PARTIALLY_REFUNDED
```

**Say:** "Capture is one transaction: status change, journal entry and outbox event commit together or not at all."

**Under the hood:**
- `PaymentService.capture` locks the payment row and calls `LedgerService.post`.
- `LedgerService.post` locks the customer account (`SELECT … FOR UPDATE`, in id order), checks the balance, inserts the entry, and then, as the last step, atomically increments the merchant and platform-fee balances.
- The refund posts the mirror-image entry and points back at the capture with `reverses_entry_id`.

### Step 6: Show the ledger in the database (2 min)

```powershell
docker compose exec -T postgres psql -U ledgerflow -d ledgerflow -c "SELECT e.entry_type, a.account_type, l.direction, l.amount FROM journal_line l JOIN journal_entry e ON e.id = l.entry_id JOIN account a ON a.id = l.account_id WHERE e.payment_id = '$($p.id)' ORDER BY e.created_at, l.direction DESC;"
```

**Expect:**

| entry | account | dir | amount |
|---|---|---|---|
| CAPTURE | CUSTOMER | DEBIT | 25000 |
| CAPTURE | MERCHANT_RECEIVABLE | CREDIT | 24500 |
| CAPTURE | PLATFORM_FEE | CREDIT | 500 |
| REFUND | MERCHANT_RECEIVABLE | DEBIT | 9800 |
| REFUND | PLATFORM_FEE | DEBIT | 200 |
| REFUND | CUSTOMER | CREDIT | 10000 |

```powershell
# the whole ledger balances: prints 0
docker compose exec -T postgres psql -U ledgerflow -d ledgerflow -c "SELECT SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) AS imbalance_paise FROM journal_line;"

# try to rewrite history: the database refuses
docker compose exec -T postgres psql -U ledgerflow -d ledgerflow -c "UPDATE journal_line SET amount = 1;"
```

**Say:** "Double-entry: every entry's debits equal its credits, so across the whole ledger the total is always zero.
And it's not just Java enforcing that: the database rejects unbalanced entries at commit, rejects any update
or delete of journal rows, and has a CHECK that customer balances can't go negative. Mistakes are fixed with
reversal entries, like the refund."

**Under the hood:** `V1__core_schema.sql` has the triggers `journal_line_append_only` and `journal_line_balanced`
(deferred, so it runs at COMMIT) and the constraint `account_no_overdraft`. The refund's fee share uses cumulative rounding
(`FeeCalculator.refundFeePortion`), so partial refunds always return exactly the original fee.

### Step 7: Overdraft is impossible (30 s)

```powershell
$big = Post "/v1/payments" '{"customerId":"cust_bob","amount":5000000}'
try { Post "/v1/payments/$($big.id)/capture" $null } catch { $_.Exception.Response.StatusCode.value__ }
```

**Expect:** `422` (`INSUFFICIENT_FUNDS`): ₹50,000 against a ₹10,000 balance, and nothing was written.
If you see 409 instead, the bank declined or left it pending; run it again.

**Say:** "Under concurrency this is the classic bug: two requests both read 'enough money' and both spend it. The row
lock serializes them. My test fires 1,000 concurrent debits at an account funded for exactly 500: 500 succeed, 500
fail, the balance ends at zero. When I removed the lock, the test failed, which proves it actually tests the lock."

### Step 8: Signed webhooks with retries (1 min)

```powershell
docker compose exec -T merchant-receiver cat /tmp/receiver_stats.json
docker compose exec -T postgres psql -U ledgerflow -d ledgerflow -c "SELECT event_type, status, attempts FROM webhook_delivery ORDER BY created_at DESC LIMIT 8;"
```

**Say:**
- "Every state change becomes an event."
- "The fake merchant fails 10% of requests on purpose, yet everything gets delivered; some rows show more than one attempt."
- "`bad_signature` is 0: the merchant verifies an HMAC-SHA256 signature with its own independent Python code."

**Under the hood:**
1. `PaymentEventPublisher` writes an **outbox row in the same transaction** as the change.
2. `OutboxRelay`, every 500 ms, sends batches of 10 to **SNS** with `PublishBatch`.
3. SNS delivers to the **SQS** queue `webhook-delivery`.
4. `WebhookListener` → `WebhookDispatcher` signs `t=…,v1=HMAC(secret, t.body)` and POSTs.
5. On failure it sets the message's **visibility timeout** to 10 s, 20 s, 40 s, 80 s. SQS itself is the retry timer.
6. After 5 receives SQS moves the message to the **DLQ**, and on AWS a CloudWatch alarm emails the team.

Optional, to show retries in the logs:

```powershell
docker compose logs ledgerflow-api | Select-String "will retry" | Select-Object -Last 3
```

### Step 9: Chaos run (2 min)

```powershell
python tools/chaos_run.py 200
```

**Expect:** a status mix right after create (mostly `AUTHORIZED`, some `PENDING_CONFIRMATION` and `FAILED`). After the
"still waiting on the bank..." lines finish: `LedgerFlow vs bank mismatches: 0`.

**Say:** "About 1 in 4 bank calls misbehaves: 503s, hangs, pending answers, declines. After the poller settles
everything, I ask the bank for its own record of every payment and compare. Zero disagreements."

**Under the hood:** the chaos rates come from `mock-bank/src/main/resources/application.yml` (`mockbank.chaos.*`):
5% errors, 5% timeouts, 5% declines, 10% pending. mock-bank deduplicates by payment id, which is why LedgerFlow's
retries can't charge twice.

### Step 10: Reconciliation (2 min)

```powershell
python tools/reconcile_run.py 5 5 5 5
```

**Expect:** `planted discrepancies caught: 20/20 = 100.0%` and `other discrepancies reported (not planted): 0`.

```powershell
# the report through the ops-only API, and the files in (Local)S3
$ops = (Invoke-RestMethod -Method Post "$api/dev/token?scope=ops").access_token
Invoke-RestMethod "$api/v1/reconciliation/$(Get-Date -Format yyyy-MM-dd)" -Headers @{ Authorization = "Bearer $ops" } | Select-Object -ExpandProperty summary
docker compose exec -T localstack awslocal s3 ls s3://ledgerflow-settlements --recursive
```

**Say:** "Every night the bank sends a settlement file. I asked mock-bank to plant 20 mistakes in it: dropped rows,
duplicates, wrong amounts and payments we never saw. Reconciliation found all 20 and nothing else. Matched payments
become SETTLED. The merchant token can't read this endpoint; only an `ops` token can."

**Under the hood:**
1. mock-bank writes `settlements/<date>/settlement.csv` to **S3**. On AWS, **EventBridge Scheduler** triggers this at 00:30 IST.
2. S3 sends an **ObjectCreated** event to the **SQS** queue `settlement-files`.
3. `SettlementFileListener` → `ReconciliationService` loads both sides and calls `Reconciler.reconcile`, a pure function, property-tested with jqwik.
4. The report is saved in Postgres and copied to S3 as `reports/<date>.json`.

### Step 11 (optional): Run a test live (2 min)

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot"; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -pl ledgerflow-api -am verify "-Dit.test=ConcurrentTransfersIT" "-Dtest=none" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"
```

**Expect:** about 1 minute, then three `[proof]` lines and `BUILD SUCCESS`:

```
[proof] unguarded hot account: 1000 transfers ... posted=1000 ... imbalance=0 paise
[proof] single hot account: 1000 transfers ... posted=500, insufficient=500 ... imbalance=0 paise
[proof] bidirectional mesh: 1000 transfers ... other=0, imbalance=0 paise
```

**Say:** "Testcontainers starts a real Postgres, so this is the real locking, not a mock. `other=0` means no
deadlocks: locks are always taken in account-id order."

### Step 12: Close with the numbers (30 s)

"On the k6 load test it sustained 175 payments per second, 347 requests per second, with create p99 66 ms and
capture p99 180 ms, zero errors, and zero ledger imbalance after about 520,000 journal lines. The test suite has 65 unit
tests, 9 property-based tests and 44 integration tests. The AWS setup (ECS Fargate, RDS, SNS, SQS, S3,
EventBridge, Secrets Manager, CloudWatch alarms, GitHub OIDC deploys) is all in Terraform. I run it against
LocalStack instead of paying about $75 a month to keep it up."

### Afterwards

```powershell
docker compose down        # stop, keep data
docker compose down -v     # stop and wipe
```

---

## If something goes wrong mid-demo

| Symptom | Fix |
|---|---|
| `Invoke-RestMethod : Unable to connect` | API still starting: wait 15 s; `docker compose logs ledgerflow-api --tail 30` |
| 401 Unauthorized | Token missing or expired: re-run the session setup |
| Capture returns 409 | The payment isn't `AUTHORIZED` (pending or failed). Check with `GET`, or create a new one |
| `422` on create with `CUSTOMER_ACCOUNT_NOT_FOUND` | Use a seeded customer: `cust_alice`, `cust_bob`, `cust_0001`…`cust_1000` |
| Reconciliation shows lots of `MISSING_IN_BANK` | Old data or a restarted bank: `docker compose down -v`, `up -d`, redo |
| Test fails with "class file version 65" | Run the `$env:JAVA_HOME = …` line in that window first |
| A port is busy | Something else uses 5432/8080/…; stop it, or stop the old stack |

---

## Quick Q&A

Short answers for rapid-fire questions. Longer versions are in `guide.html` → Interview questions.

**What is LedgerFlow in one line?**
A payments backend where merchants take UPI-style payments, with a double-entry ledger that stays correct under
concurrency, retries, bank failures and merchant outages.

**Why double-entry?**
Every movement has equal debits and credits, so money can't appear or vanish, and the whole ledger always sums to zero.

**Where are the balance rules enforced?**
In Java, and also in Postgres: a deferred trigger checks that each entry balances at commit, triggers block
update/delete of journal rows, and a CHECK constraint blocks overdrafts.

**Why `long` paise instead of `double` or `BigDecimal`?**
Exact, fast, and impossible to get rounding drift. Amounts are capped at ₹1 crore so fee maths can't overflow.

**How do you stop double spending?**
`SELECT … FOR UPDATE` on customer accounts. The second transaction waits, then sees the new balance.

**Why not optimistic locking?**
With many writers on one row, almost every optimistic write conflicts and retries, which becomes a retry storm.
Pessimistic locks queue the writers instead.

**How do you avoid deadlocks?**
Always lock accounts in ascending id order. There's no cycle possible.

**What's a hot account?**
A row every transaction touches, like the platform-fee account. I update it with a single atomic increment at the
very end of the transaction, so its lock is held only until commit.

**How does idempotency work?**
The key is inserted before any work with `ON CONFLICT DO NOTHING`. Same request → stored response replayed;
different body → 422; still running → 409.

**What if the bank times out?**
Retry twice (safe because the bank deduplicates by payment id). If it's still unknown, mark it
`PENDING_CONFIRMATION`, and a poller asks the bank later.

**Why not keep the transaction open during the bank call?**
A slow bank would hold DB connections and locks, and the pool would run dry. So it's two short transactions with the
network call between them.

**What is the outbox pattern?**
The event is written in the same DB transaction as the change, and a relay publishes it later. There's no
"saved but not sent" or "sent but rolled back".

**What delivery guarantee do events have?**
At least once. Consumers deduplicate by event id; `webhook_delivery.event_id` is a primary key.

**How do webhook retries work?**
On failure the SQS message's visibility timeout is set to the backoff (10 s → 80 s). After 5 receives, SQS moves it
to the DLQ and an alarm fires.

**How do merchants trust a webhook?**
They check the HMAC-SHA256 signature over a timestamp plus the raw body, with a constant-time compare, and reject
old timestamps to stop replays.

**What does reconciliation find?**
Duplicates, missing in ledger, missing in bank, and amount mismatches. Matched payments become `SETTLED`.

**How do several instances run background jobs safely?**
`FOR UPDATE SKIP LOCKED` (and a lease for the poller), plus competing SQS consumers. There's no leader election.

**How did you test concurrency?**
1,000 concurrent transfers against real Postgres (Testcontainers), followed by an SQL audit. Removing the lock
makes the test fail.

**What is property-based testing here?**
jqwik generates up to 1,000 random cases per property: the ledger always balances, refunds never exceed the capture,
and reconciliation finds every planted mistake.

**What were the load test results?**
175 payments/s sustained (347 req/s), create p99 66 ms, capture p99 180 ms, 0 errors, 0 imbalance.

**What bottlenecks did you fix?**
The hot-account lock (atomic increment), the connection pool (10 → 30), the SNS calls (PublishBatch), and the
sequential poller (now parallel on virtual threads).

**How would you scale it?**
More stateless API tasks, then a bigger DB with RDS Proxy, read replicas, partitioned tables, split hot accounts,
and finally sharding by merchant.

**Which AWS services, and why?**
- ECS Fargate (containers, no servers) and RDS Postgres (ACID, locks, triggers).
- SNS (fan-out) and SQS (durable retries and DLQ).
- S3 (settlement files with events) and EventBridge Scheduler (nightly cron).
- Secrets Manager (keys) and CloudWatch (metrics, alarms).
- GitHub OIDC (deploys without stored keys).

**Is it deployed?**
The infrastructure is fully written in Terraform and the CI pipeline deploys to ECS. I don't keep it running because
it costs about $75 a month; locally LocalStack serves the same AWS APIs.

**Why no NAT gateway?**
It saves about $35 a month. Tasks sit in public subnets but accept traffic only from the load balancer, and RDS has
no internet route.

**How does CI/CD work?**
Every push runs unit, property and integration tests. On `main`, GitHub assumes an AWS role through OIDC, pushes images to
ECR and rolls out to ECS, and a circuit breaker rolls back an unhealthy deploy.

**Are events ordered?**
No, standard SNS/SQS doesn't guarantee order. Each event carries the full status and a timestamp; FIFO queues
grouped by payment id would add ordering if needed.

**What would you add for production?**
HTTPS with a domain, merchant onboarding and API keys, Multi-AZ RDS, remote Terraform state, rate limiting,
OpenTelemetry tracing, and DLQ runbooks.

**Hardest bug you hit?**
Hot-account contention found by the load test, and Spring Data treating entities with pre-assigned UUIDs as
existing rows (merge instead of insert). I fixed that with a `Persistable` base class.
