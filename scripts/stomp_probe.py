#!/usr/bin/env python3
"""STOMP 1.2 over WebSocket probe (Step 42; Phase 12 reuses it through :8090).

Why not stomp.py: it speaks STOMP over raw TCP and cannot reach a WebSocket endpoint.
This uses websocket-client (pinned 1.8.0) for the WebSocket and the small STOMP framing below.
The token is read from $STOMP_TOKEN so it never appears in a process list or shell history.
Subcommands: connect, subscribe, listen, send, raw.
"""
import argparse
import json
import os
import sys
import time

import websocket

NUL = "\x00"
DEFAULT_URL = "ws://messages-websocket-service:8083/ws/websocket"


def frame(command, headers=None, body=""):
    lines = [command] + [f"{k}:{v}" for k, v in (headers or {}).items()]
    return "\n".join(lines) + "\n\n" + body + NUL


def parse(text):
    text = text.lstrip("\r\n")
    if not text:
        return ("HEARTBEAT", {}, "")
    head, _, body = text.partition("\n\n")
    lines = head.split("\n")
    headers = {}
    for line in lines[1:]:
        key, _, value = line.partition(":")
        headers.setdefault(key, value)
    return (lines[0], headers, body.split(NUL, 1)[0])


def read(ws, timeout):
    ws.settimeout(timeout)
    try:
        opcode, data = ws.recv_data(control_frame=True)
    except websocket.WebSocketTimeoutException:
        return ("TIMEOUT", None)
    except websocket.WebSocketConnectionClosedException:
        return ("CLOSED", None)
    if opcode == websocket.ABNF.OPCODE_CLOSE:
        return ("CLOSE", int.from_bytes(data[:2], "big") if len(data) >= 2 else None)
    if opcode in (websocket.ABNF.OPCODE_PING, websocket.ABNF.OPCODE_PONG):
        return ("CONTROL", None)
    return ("FRAME", parse(data.decode("utf-8")))


def open_socket(a):
    options = {"timeout": 10, "subprotocols": ["v12.stomp"]}
    if a.origin:
        options["origin"] = a.origin
    try:
        return websocket.create_connection(a.url, **options)
    except Exception as e:  # handshake failures are results, not crashes
        print(f"handshake failed: {e}")
        sys.exit(3)


def connect(a):
    ws = open_socket(a)
    headers = {"accept-version": "1.2", "host": "messages-websocket-service", "heart-beat": "0,0"}
    token = a.token if a.token is not None else os.environ.get("STOMP_TOKEN", "")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    ws.send(frame("CONNECT", headers))
    kind, value = read(ws, 5)
    if kind == "FRAME" and value[0] == "CONNECTED":
        h = value[1]
        print(f"CONNECTED version={h.get('version')} heart-beat={h.get('heart-beat')} user-name={h.get('user-name')}")
        return ws
    if kind == "FRAME" and value[0] == "ERROR":
        print(f"ERROR message={value[1].get('message')} body={value[2]}")
    else:
        print(f"no CONNECTED: {kind} {value}")
    report_until_close(ws, 5)
    sys.exit(1)


def rejected_subscription(value):
    """SD D-1: a MESSAGE on our subscription whose body is SUBSCRIPTION_REJECTED (the session stays open)."""
    if value[0] != "MESSAGE":
        return None
    try:
        body = json.loads(value[2])
    except ValueError:
        return None
    return body if isinstance(body, dict) and body.get("type") == "SUBSCRIPTION_REJECTED" else None


def report_until_close(ws, seconds):
    """Print frames until the server closes; return the ERROR (or SUBSCRIPTION_REJECTED) frame seen, or None."""
    error, deadline = None, time.time() + seconds
    while time.time() < deadline:
        kind, value = read(ws, max(0.1, deadline - time.time()))
        if kind == "FRAME" and value[0] == "ERROR":
            error = value
            print(f"ERROR message={value[1].get('message')} body={value[2]}")
        elif kind == "FRAME" and rejected_subscription(value):
            error = value
            print(f"REJECTED message={value[1].get('message')} subscription={value[1].get('subscription')} body={value[2]}")
        elif kind == "FRAME" and value[0] not in ("HEARTBEAT",):
            print(f"{value[0]} {value[1]} {value[2]}")
        elif kind in ("CLOSE", "CLOSED"):
            print(f"closed code={value}")
            return error
        elif kind == "TIMEOUT":
            break
    return error


def cmd_connect(a):
    ws = connect(a)
    ws.send(frame("DISCONNECT"))
    ws.close()


def cmd_subscribe(a):
    ws = connect(a)
    ws.send(frame("SUBSCRIBE", {"id": "sub-0", "destination": a.dest}))
    seen = report_until_close(ws, a.wait)
    if seen is None:
        print(f"no ERROR within {a.wait}s: subscription to {a.dest} accepted")
        ws.close()
        return
    if seen[0] == "MESSAGE":
        # Rejected alone: prove the session survived by a clean DISCONNECT with a receipt.
        ws.send(frame("DISCONNECT", {"receipt": "bye"}))
        kind, value = read(ws, 3)
        alive = kind == "FRAME" and value[0] == "RECEIPT"
        print("session still open after the rejection" if alive else f"session NOT open after the rejection: {kind} {value}")
        ws.close()
        sys.exit(2 if alive else 1)
    sys.exit(1)


def cmd_send(a):
    ws = connect(a)
    ws.send(frame("SEND", {"destination": a.dest, "content-type": "application/json"}, a.body))
    sys.exit(0 if report_until_close(ws, 5) else 1)


def cmd_raw(a):
    ws = open_socket(a)
    ws.send(a.payload.replace("\\n", "\n").replace("\\0", NUL))
    sys.exit(0 if report_until_close(ws, 5) else 1)


def cmd_listen(a):
    ws = connect(a)
    ws.send(frame("SUBSCRIBE", {"id": "sub-0", "destination": f"/topic/rooms.{a.room}"}))
    kind, value = read(ws, 2)  # a rejected SUBSCRIBE answers within the membership timeout
    if kind == "FRAME" and (value[0] == "ERROR" or rejected_subscription(value)):
        print(f"{value[0]} message={value[1].get('message')} body={value[2]}")
        sys.exit(1)
    open(os.path.join(a.out_dir, "ready"), "w").close()
    got, deadline = 0, time.time() + a.timeout
    while got < a.count and time.time() < deadline:
        kind, value = read(ws, max(0.1, deadline - time.time()))
        if kind == "FRAME" and value[0] == "MESSAGE":
            got += 1
            print(f"MESSAGE destination={value[1].get('destination')} body={json.dumps(json.loads(value[2]), separators=(',', ':'))}")
        elif kind in ("CLOSE", "CLOSED"):
            print(f"closed code={value}")
            break
    ws.close()
    sys.exit(0 if got == a.count else 1)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--url", default=DEFAULT_URL)
    p.add_argument("--origin")
    p.add_argument("--token", help="override $STOMP_TOKEN (use '' for none)")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("connect").set_defaults(fn=cmd_connect)
    s = sub.add_parser("subscribe"); s.add_argument("--dest", required=True); s.add_argument("--wait", type=int, default=3); s.set_defaults(fn=cmd_subscribe)
    s = sub.add_parser("listen"); s.add_argument("--room", required=True); s.add_argument("--count", type=int, default=1)
    s.add_argument("--timeout", type=int, default=30); s.add_argument("--out-dir", default="/out"); s.set_defaults(fn=cmd_listen)
    s = sub.add_parser("send"); s.add_argument("--dest", required=True); s.add_argument("--body", default="{}"); s.set_defaults(fn=cmd_send)
    s = sub.add_parser("raw"); s.add_argument("--payload", required=True); s.set_defaults(fn=cmd_raw)
    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
