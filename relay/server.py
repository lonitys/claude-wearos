#!/usr/bin/env python3
"""Claude Watch relay.

A tiny HTTP server (stdlib only) that sits between Claude Code hooks and the
Wear OS app. Hooks push permission requests and chat messages in; the watch
long-polls for events and posts decisions/replies back.

State lives in memory only: restart the relay and pending requests are gone
(the hooks then fall back to the normal terminal prompt).

Usage:
    python3 relay/server.py --token SECRET [--host 0.0.0.0] [--port 8787]
"""

from __future__ import annotations

import argparse
import hmac
import json
import os
import threading
import time
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

MAX_EVENTS = 500
MAX_MESSAGES = 200
MAX_WAIT = 60.0
# A client (watch or web UI) counts as online if it polled within this window.
CLIENT_ONLINE_WINDOW = 90.0
MAX_BODY = 1 << 20

WEB_UI = Path(__file__).with_name("web.html")


class Relay:
    def __init__(self) -> None:
        self.cond = threading.Condition()
        self.seq = 0
        self.events: list[dict] = []
        self.permissions: dict[str, dict] = {}
        self.messages: list[dict] = []
        self.replies: dict[str, list[str]] = {}
        self.last_client_seen = 0.0

    # -- helpers (call with self.cond held) ---------------------------------

    def _emit(self, kind: str, data: dict) -> None:
        self.seq += 1
        self.events.append({"seq": self.seq, "type": kind, "data": data})
        del self.events[:-MAX_EVENTS]
        self.cond.notify_all()

    def _wait_for(self, predicate, timeout: float):
        deadline = time.monotonic() + min(max(timeout, 0.0), MAX_WAIT)
        while True:
            result = predicate()
            remaining = deadline - time.monotonic()
            if result or remaining <= 0:
                return result
            self.cond.wait(remaining)

    # -- permission requests ------------------------------------------------

    def client_online(self) -> bool:
        return time.time() - self.last_client_seen < CLIENT_ONLINE_WINDOW

    def create_permission(self, body: dict) -> dict:
        req = {
            "id": uuid.uuid4().hex[:12],
            "session_id": str(body.get("session_id", "")),
            "project": str(body.get("project", "")),
            "tool_name": str(body.get("tool_name", "")),
            "summary": str(body.get("summary", ""))[:4000],
            "detail": str(body.get("detail", ""))[:8000],
            "shake_allowed": bool(body.get("shake_allowed", True)),
            "created": time.time(),
            "status": "pending",
            "message": "",
        }
        with self.cond:
            self.permissions[req["id"]] = req
            self._emit("permission", req)
        return req

    def wait_permission(self, pid: str, timeout: float) -> dict | None:
        with self.cond:
            if pid not in self.permissions:
                return None
            self._wait_for(
                lambda: self.permissions.get(pid, {}).get("status", "expired") != "pending", timeout
            )
            req = self.permissions.get(pid)
            return dict(req) if req else {"id": pid, "status": "expired", "message": ""}

    def decide(self, pid: str, decision: str, message: str = "") -> dict | None:
        if decision not in ("allow", "deny", "expired"):
            raise ValueError("decision must be allow, deny or expired")
        with self.cond:
            req = self.permissions.get(pid)
            if req is None:
                return None
            if req["status"] == "pending":
                req["status"] = decision
                req["message"] = message[:1000]
                self._emit("resolved", {"id": pid, "status": decision})
            return dict(req)

    def pending_permissions(self) -> list[dict]:
        return [p for p in self.permissions.values() if p["status"] == "pending"]

    def prune(self, max_age: float = 3600.0) -> None:
        cutoff = time.time() - max_age
        with self.cond:
            for pid in [k for k, v in self.permissions.items() if v["created"] < cutoff]:
                if self.permissions[pid]["status"] == "pending":
                    self.permissions[pid]["status"] = "expired"
                    self._emit("resolved", {"id": pid, "status": "expired"})
                del self.permissions[pid]

    # -- chat ---------------------------------------------------------------

    def add_message(self, body: dict) -> dict:
        msg = {
            "id": uuid.uuid4().hex[:12],
            "session_id": str(body.get("session_id", "")),
            "project": str(body.get("project", "")),
            "role": str(body.get("role", "assistant")),
            "text": str(body.get("text", ""))[:8000],
            "awaiting_reply": bool(body.get("awaiting_reply", False)),
            "created": time.time(),
        }
        with self.cond:
            self.messages.append(msg)
            del self.messages[:-MAX_MESSAGES]
            self._emit("message", msg)
        return msg

    def add_reply(self, session_id: str, text: str) -> dict:
        msg = self.add_message({"session_id": session_id, "role": "user", "text": text})
        with self.cond:
            self.replies.setdefault(session_id, []).append(text)
            self.cond.notify_all()
        return msg

    def take_reply(self, session_id: str, timeout: float) -> str | None:
        with self.cond:
            self._wait_for(lambda: bool(self.replies.get(session_id)), timeout)
            queue = self.replies.get(session_id)
            return queue.pop(0) if queue else None

    def clear_replies(self, session_id: str) -> None:
        with self.cond:
            self.replies.pop(session_id, None)

    # -- watch side ---------------------------------------------------------

    def events_since(self, since: int, timeout: float) -> dict:
        with self.cond:
            self.last_client_seen = time.time()
            if since > self.seq:  # relay restarted; tell the client to resync
                since = 0
            self._wait_for(lambda: self.seq > since, timeout)
            self.last_client_seen = time.time()
            return {"events": [e for e in self.events if e["seq"] > since], "last": self.seq}

    def state(self) -> dict:
        with self.cond:
            self.last_client_seen = time.time()
            return {
                "last": self.seq,
                "permissions": self.pending_permissions(),
                "messages": list(self.messages[-50:]),
            }


class Handler(BaseHTTPRequestHandler):
    relay: Relay
    token: str
    server_version = "ClaudeWatchRelay/1.0"

    def log_message(self, fmt, *args):  # quieter default logging
        if os.environ.get("CLAUDE_WATCH_RELAY_VERBOSE"):
            super().log_message(fmt, *args)

    # -- plumbing -----------------------------------------------------------

    def _send(self, status: int, payload, content_type="application/json") -> None:
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        supplied = header[7:] if header.startswith("Bearer ") else ""
        if not supplied:
            supplied = parse_qs(urlparse(self.path).query).get("token", [""])[0]
        return hmac.compare_digest(supplied.encode(), self.token.encode())

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY:
            raise ValueError("body too large")
        raw = self.rfile.read(length) if length else b"{}"
        data = json.loads(raw or b"{}")
        if not isinstance(data, dict):
            raise ValueError("body must be a JSON object")
        return data

    def _route(self, method: str) -> None:
        url = urlparse(self.path)
        parts = [p for p in url.path.split("/") if p]
        query = {k: v[0] for k, v in parse_qs(url.query).items()}

        if method == "GET" and parts in ([], ["web"]):
            return self._send(200, WEB_UI.read_bytes(), "text/html; charset=utf-8")
        if parts == ["health"]:
            return self._send(200, {"ok": True})
        if not parts or parts[0] != "api":
            return self._send(404, {"error": "not found"})
        if not self._authorized():
            return self._send(401, {"error": "unauthorized"})

        wait = float(query.get("wait", 0) or 0)
        relay = self.relay
        route = parts[1:]

        if method == "POST" and route == ["permission"]:
            body = self._body()
            relay.prune()
            if not body.get("force") and not relay.client_online():
                return self._send(409, {"error": "no watch connected"})
            return self._send(201, relay.create_permission(body))

        if len(route) == 2 and route[0] == "permission":
            if method == "GET":
                req = relay.wait_permission(route[1], wait)
                return self._send(200, req) if req else self._send(404, {"error": "unknown id"})
            if method == "DELETE":
                req = relay.decide(route[1], "expired")
                return self._send(200, req) if req else self._send(404, {"error": "unknown id"})

        if method == "POST" and len(route) == 3 and route[0] == "permission" and route[2] == "decision":
            body = self._body()
            req = relay.decide(route[1], str(body.get("decision", "")), str(body.get("message", "")))
            return self._send(200, req) if req else self._send(404, {"error": "unknown id"})

        if method == "POST" and route == ["message"]:
            return self._send(201, relay.add_message(self._body()))

        if route == ["reply"]:
            if method == "POST":
                body = self._body()
                text = str(body.get("text", "")).strip()
                if not text:
                    return self._send(400, {"error": "text required"})
                return self._send(201, relay.add_reply(str(body.get("session_id", "")), text))
            if method == "GET":
                reply = relay.take_reply(query.get("session_id", ""), wait)
                return self._send(200, {"text": reply})
            if method == "DELETE":
                relay.clear_replies(query.get("session_id", ""))
                return self._send(200, {"ok": True})

        if method == "GET" and route == ["events"]:
            return self._send(200, relay.events_since(int(query.get("since", 0) or 0), wait))

        if method == "GET" and route == ["state"]:
            return self._send(200, relay.state())

        return self._send(404, {"error": "not found"})

    def _handle(self, method: str) -> None:
        try:
            self._route(method)
        except (ValueError, json.JSONDecodeError) as exc:
            self._send(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def do_DELETE(self):
        self._handle("DELETE")


def make_server(host: str, port: int, token: str, relay: Relay | None = None) -> ThreadingHTTPServer:
    handler = type("BoundHandler", (Handler,), {"relay": relay or Relay(), "token": token})
    server = ThreadingHTTPServer((host, port), handler)
    server.daemon_threads = True
    return server


def main() -> None:
    parser = argparse.ArgumentParser(description="Claude Watch relay server")
    parser.add_argument("--host", default=os.environ.get("CLAUDE_WATCH_HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("CLAUDE_WATCH_PORT", "8787")))
    parser.add_argument("--token", default=os.environ.get("CLAUDE_WATCH_TOKEN", ""))
    args = parser.parse_args()
    if len(args.token) < 8:
        parser.error("set --token (or CLAUDE_WATCH_TOKEN) to a secret of at least 8 characters")

    server = make_server(args.host, args.port, args.token)
    print(f"Claude Watch relay listening on http://{args.host}:{args.port}  (web UI: /web)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
