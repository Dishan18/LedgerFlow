"""Flaky demo merchant: verifies LedgerFlow webhook signatures (independent Python implementation)
and fails a configurable share of requests on purpose. Prints a summary on Ctrl+C / kill."""
import hashlib
import hmac
import json
import random
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SECRET = b"whsec_local_dev_secret_change_me"
FAIL_RATE = float(sys.argv[1]) if len(sys.argv) > 1 else 0.3
STATS_FILE = sys.argv[2] if len(sys.argv) > 2 else "receiver_stats.json"
lock = threading.Lock()
stats = {"requests": 0, "failed_on_purpose": 0, "accepted": 0, "bad_signature": 0, "event_ids": set()}


def verify(header, body):
    parts = dict(p.split("=", 1) for p in header.split(",") if "=" in p)
    t, v1 = parts.get("t"), parts.get("v1")
    if not t or not v1 or abs(time.time() - int(t)) > 300:
        return False
    expected = hmac.new(SECRET, f"{t}.".encode() + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, v1)


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        with lock:
            stats["requests"] += 1
            if random.random() < FAIL_RATE:
                stats["failed_on_purpose"] += 1
                code = 503
            elif not verify(self.headers.get("X-LedgerFlow-Signature", ""), body):
                stats["bad_signature"] += 1
                code = 401
            else:
                stats["accepted"] += 1
                stats["event_ids"].add(self.headers["X-LedgerFlow-Event-Id"])
                code = 200
            snapshot = dict(stats, event_ids=len(stats["event_ids"]))
        with open(STATS_FILE, "w") as f:
            json.dump(snapshot, f)
        self.send_response(code)
        self.end_headers()

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("localhost", 9090), Handler).serve_forever()
