#!/usr/bin/env python3
"""Signed CLI client for the Moonbear K4 voice bridge.

Every request is HMAC-signed with a per-device secret, exactly as the Android
app does. Two modes:

  seed            provision a local admin device directly (root only, no pairing)
  health|talk|tts|devices|pair-new   signed calls against the bridge

Usage:
  mb_client.py seed [name]
  mb_client.py health
  mb_client.py talk "what time is it"
  mb_client.py tts "hello"
  mb_client.py devices
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import urllib.request

BRIDGE = os.environ.get("MB_BRIDGE", "https://127.0.0.1:9443")
CREDS_FILE = "/root/.moonbear-cli.json"
STATE_DIR = "/root/.hermes/state/k4_voice"


def _load_creds() -> dict:
    if not os.path.exists(CREDS_FILE):
        sys.exit("no credentials — run: mb_client.py seed")
    with open(CREDS_FILE) as fh:
        return json.load(fh)


def _sign(secret_hex: str, device_id: str, ts: str, body: bytes) -> str:
    msg = f"{device_id}\n{ts}\n{hashlib.sha256(body).hexdigest()}".encode()
    return hmac.new(bytes.fromhex(secret_hex), msg, hashlib.sha256).hexdigest()


def call(path: str, body: dict | None = None, method: str = None) -> dict:
    creds = _load_creds()
    raw = json.dumps(body).encode() if body is not None else b""
    ts = str(int(time.time() * 1000))
    sig = _sign(creds["secret"], creds["device_id"], ts, raw)
    req = urllib.request.Request(
        BRIDGE.rstrip("/") + path,
        data=raw if body is not None else None,
        method=method or ("POST" if body is not None else "GET"),
        headers={
            "Content-Type": "application/json",
            "X-Mb-Device": creds["device_id"],
            "X-Mb-Ts": ts,
            "X-Mb-Sig": sig,
        },
    )
    ctx = None
    if BRIDGE.startswith("https://"):
        import ssl
        ctx = ssl.create_default_context(cafile="/opt/0ad-web/certs/moonbear.crt")
    try:
        with urllib.request.urlopen(req, timeout=180, context=ctx) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        return {"http_error": exc.code, "detail": exc.read().decode()[:400]}


def seed(name: str = "moonbear-admin-cli") -> None:
    """Provision an admin device straight into the store (root-only bootstrap)."""
    if os.path.exists(CREDS_FILE):
        print("credentials already exist at", CREDS_FILE)
        return
    device_id = secrets.token_hex(16)
    secret = secrets.token_hex(32)
    path = os.path.join(STATE_DIR, "devices.json")
    devices = {}
    if os.path.exists(path):
        with open(path) as fh:
            devices = json.load(fh)
    devices[device_id] = {
        "secret": secret,
        "name": name,
        "created": int(time.time()),
        "last_seen": int(time.time()),
    }
    os.makedirs(STATE_DIR, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as fh:
        json.dump(devices, fh)
    fd = os.open(CREDS_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as fh:
        json.dump({"device_id": device_id, "secret": secret, "name": name}, fh)
    print(f"seeded device {device_id[:12]}… ({name})")


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd = sys.argv[1]
    if cmd == "seed":
        seed(sys.argv[2] if len(sys.argv) > 2 else "moonbear-admin-cli")
    elif cmd == "health":
        print(json.dumps(call("/api/moonbear-voice/health"), indent=1))
    elif cmd == "devices":
        print(json.dumps(call("/api/moonbear-voice/devices"), indent=1))
    elif cmd == "talk":
        print(json.dumps(call("/api/moonbear-voice/talk", {"text": sys.argv[2]}), indent=1)[:1200])
    elif cmd == "tts":
        r = call(f"/api/moonbear-voice/tts?text={urllib.parse.quote(sys.argv[2])}")
        print(json.dumps(r, indent=1)[:600])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
