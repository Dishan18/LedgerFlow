"""Fire N payments at a local LedgerFlow backed by a chaotic mock-bank, wait for the status
poller, then compare every payment's final state with what the bank says."""
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

API = "http://localhost:8080"
BANK = "http://localhost:8081"
MERCHANT = "11111111-1111-1111-1111-111111111111"
N = int(sys.argv[1]) if len(sys.argv) > 1 else 300


def call(method, url, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null")


def wait_up(url):
    for _ in range(60):
        try:
            urllib.request.urlopen(url, timeout=2)
            return
        except Exception:
            time.sleep(1)
    raise SystemExit("not up: " + url)


wait_up(API + "/actuator/health")
wait_up(BANK + "/actuator/health")
_, tok = call("POST", f"{API}/dev/token?merchantId={MERCHANT}")
auth = {"Authorization": "Bearer " + tok["access_token"]}


def create(i):
    headers = dict(auth, **{"Idempotency-Key": str(uuid.uuid4())})
    t = time.time()
    status, body = call("POST", API + "/v1/payments", {"customerId": "cust_alice", "amount": 100 + i}, headers)
    return status, body, time.time() - t


start = time.time()
with ThreadPoolExecutor(32) as pool:
    results = list(pool.map(create, range(N)))
print(f"created {N} payments in {time.time() - start:.1f}s")
print("HTTP statuses:", Counter(s for s, _, _ in results))
print("status right after create:", Counter(b["status"] for s, b, _ in results if s == 201))
lat = sorted(d for _, _, d in results)
print(f"create latency p50={lat[len(lat) // 2] * 1000:.0f}ms p99={lat[int(len(lat) * .99)] * 1000:.0f}ms max={lat[-1] * 1000:.0f}ms")

ids = [b["id"] for s, b, _ in results if s == 201]
deadline = time.time() + 240
while True:
    finals = {i: call("GET", f"{API}/v1/payments/{i}", headers=auth)[1] for i in ids}
    waiting = [i for i, p in finals.items() if p["status"] in ("CREATED", "PENDING_CONFIRMATION")]
    if not waiting or time.time() > deadline:
        break
    print(f"  {len(waiting)} still waiting on the bank...")
    time.sleep(10)

print("final status:", Counter(p["status"] for p in finals.values()))
print("failure reasons:", Counter(p["failureReason"] for p in finals.values() if p["status"] == "FAILED"))

mismatches = []
for i, p in finals.items():
    code, bank = call("GET", f"{BANK}/bank/v1/collects/{i}")
    bank_status = bank["status"] if code == 200 else "NOT_FOUND"
    ok = (p["status"] == "AUTHORIZED" and bank_status == "APPROVED") or \
         (p["status"] == "FAILED" and bank_status in ("DECLINED", "NOT_FOUND"))
    if not ok:
        mismatches.append((i, p["status"], p.get("failureReason"), bank_status))
print(f"LedgerFlow vs bank mismatches: {len(mismatches)}")
for m in mismatches[:10]:
    print("  ", m)
