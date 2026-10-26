"""Mints an HS256 JWT for LedgerFlow (the deployed API has no /dev/token endpoint).

usage:
  python tools/mint_token.py --secret <jwt secret> --merchant 11111111-1111-1111-1111-111111111111
  python tools/mint_token.py --secret <jwt secret> --ops

In AWS, read the secret first (needs your own AWS credentials):
  aws secretsmanager get-secret-value --secret-id ledgerflow/jwt-signing-secret --query SecretString --output text
Standard library only.
"""
import argparse
import base64
import hashlib
import hmac
import json
import time


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


parser = argparse.ArgumentParser()
parser.add_argument("--secret", required=True)
who = parser.add_mutually_exclusive_group(required=True)
who.add_argument("--merchant", help="merchant id (UUID) for payment endpoints")
who.add_argument("--ops", action="store_true", help="operations token for /v1/reconciliation")
parser.add_argument("--hours", type=int, default=12)
args = parser.parse_args()

now = int(time.time())
claims = {"sub": args.merchant or "ops-user", "iat": now, "exp": now + args.hours * 3600}
if args.ops:
    claims["scope"] = "ops"
header = {"alg": "HS256", "typ": "JWT"}
signing_input = f"{b64url(json.dumps(header).encode())}.{b64url(json.dumps(claims).encode())}"
signature = hmac.new(args.secret.encode(), signing_input.encode(), hashlib.sha256).digest()
print(f"{signing_input}.{b64url(signature)}")
