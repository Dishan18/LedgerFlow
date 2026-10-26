"""Measures reconciliation: asks mock-bank for today's settlement file with planted discrepancies,
waits for LedgerFlow's report, and checks how many of the planted mistakes it caught.

Run after tools/chaos_run.py so there are payments to settle. Standard library only.
usage: python tools/reconcile_run.py [drop duplicate wrongAmount phantom]
"""
import datetime
import json
import sys
import time
import urllib.error
import urllib.request

API = "http://localhost:8080"
BANK = "http://localhost:8081"
IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
drop, duplicate, wrong, phantom = (int(x) for x in (sys.argv[1:5] if len(sys.argv) >= 5 else (10, 10, 10, 10)))


def call(method, url, headers=None):
    req = urllib.request.Request(url, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, None


today = datetime.datetime.now(IST).date().isoformat()
_, tok = call("POST", f"{API}/dev/token?scope=ops")
ops = {"Authorization": "Bearer " + tok["access_token"]}
_, before = call("GET", f"{API}/v1/reconciliation/{today}", ops)
before_id = before["id"] if before else None

_, gen = call("POST", f"{BANK}/bank/v1/settlements/{today}?drop={drop}&duplicate={duplicate}"
                      f"&wrongAmount={wrong}&phantom={phantom}")
injected = gen["injected"]
print(f"settlement file {gen['key']}: {gen['rows']} rows, planted {sum(len(v) for v in injected.values())} discrepancies")

for _ in range(60):
    code, report = call("GET", f"{API}/v1/reconciliation/{today}", ops)
    if code == 200 and report["id"] != before_id:
        break
    time.sleep(1)
else:
    raise SystemExit("no report")

print("summary:", json.dumps(report["summary"]))
found = {(d["type"], d["paymentId"]) for d in report["discrepancies"]}
expected = {("MISSING_IN_BANK", i) for i in injected["dropped"]} | \
           {("DUPLICATE", i) for i in injected["duplicated"]} | \
           {("AMOUNT_MISMATCH", i) for i in injected["wrongAmount"]} | \
           {("MISSING_IN_LEDGER", i) for i in injected["phantom"]}
caught = expected & found
print(f"planted discrepancies caught: {len(caught)}/{len(expected)} = {100.0 * len(caught) / max(1, len(expected)):.1f}%")
extra = found - expected
print(f"other discrepancies reported (not planted): {len(extra)}")
for e in sorted(extra)[:10]:
    print("  ", e)
