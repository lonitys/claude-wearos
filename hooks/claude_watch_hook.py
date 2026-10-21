#!/usr/bin/env python3
"""Claude Code hook that forwards permission prompts and chat to the watch.

Register it for the PermissionRequest, Notification, Stop and UserPromptSubmit
events (see hooks/settings.example.json). It reads the hook payload from stdin
and talks to the relay server.

The hook never breaks Claude Code: if the relay is unreachable, no watch is
connected or nobody answers in time, it exits silently and the normal terminal
prompt appears.

Configuration (environment variables, or ~/.claude/claude-watch.json with the
same keys in lower case without the prefix, e.g. {"url": ..., "token": ...}):

    CLAUDE_WATCH_URL                 relay base URL (default http://127.0.0.1:8787)
    CLAUDE_WATCH_TOKEN               shared secret, same as the relay's --token
    CLAUDE_WATCH_PERMISSION_TIMEOUT  seconds to wait for the watch (default 300)
    CLAUDE_WATCH_REPLY_WAIT          seconds a Stop waits for a watch reply (default 0)
    CLAUDE_WATCH_NO_SHAKE            regex; matching requests need a button tap
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote
from pathlib import Path

DEFAULT_NO_SHAKE = (
    r"rm\s+-[a-zA-Z]*[rf]|git\s+push\s+.*(-f\b|--force)|git\s+reset\s+--hard|"
    r"\bsudo\b|\bmkfs\b|\bdd\s+if=|drop\s+(table|database)|chmod\s+-R|"
    r"curl[^|]*\|\s*(ba|z)?sh"
)
CONFIG_FILE = Path.home() / ".claude" / "claude-watch.json"


def load_config() -> dict:
    cfg = {
        "url": "http://127.0.0.1:8787",
        "token": "",
        "permission_timeout": "300",
        "reply_wait": "0",
        "no_shake": DEFAULT_NO_SHAKE,
    }
    try:
        cfg.update({k: str(v) for k, v in json.loads(CONFIG_FILE.read_text()).items()})
    except (OSError, ValueError):
        pass
    for key in list(cfg):
        env = os.environ.get("CLAUDE_WATCH_" + key.upper())
        if env:
            cfg[key] = env
    cfg["url"] = cfg["url"].rstrip("/")
    return cfg


class Relay:
    def __init__(self, cfg: dict) -> None:
        self.url = cfg["url"]
        self.token = cfg["token"]

    def call(self, method: str, path: str, body: dict | None = None, timeout: float = 10):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.url + path, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self.token)
        req.add_header("Content-Type", "application/json")
        # The relay is usually on localhost/LAN; never route it through a proxy.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(req, timeout=timeout) as resp:
            return json.loads(resp.read() or b"{}")


# -- formatting ----------------------------------------------------------------


def _clip(text: str, limit: int) -> str:
    text = str(text)
    return text if len(text) <= limit else text[: limit - 1] + "…"


def describe_tool(tool: str, inp: dict) -> tuple[str, str]:
    """Return (summary, detail) for a tool call, short enough for a watch."""
    inp = inp if isinstance(inp, dict) else {}
    if tool == "Bash":
        summary = inp.get("command", "")
        detail = inp.get("description", "")
    elif tool in ("Edit", "MultiEdit"):
        summary = inp.get("file_path", "")
        edits = inp.get("edits") or [inp]
        detail = "\n".join(
            f"- {_clip(e.get('old_string', ''), 300)}\n+ {_clip(e.get('new_string', ''), 300)}"
            for e in edits[:3]
        )
    elif tool == "Write":
        summary = inp.get("file_path", "")
        detail = _clip(inp.get("content", ""), 600)
    elif tool == "NotebookEdit":
        summary = inp.get("notebook_path", "")
        detail = _clip(inp.get("new_source", ""), 600)
    elif tool == "WebFetch":
        summary = inp.get("url", "")
        detail = inp.get("prompt", "")
    elif tool == "WebSearch":
        summary = inp.get("query", "")
        detail = ""
    else:
        summary = ""
        detail = json.dumps(inp, ensure_ascii=False, indent=1)
    return _clip(summary, 1500), _clip(detail, 2000)


def last_assistant_text(payload: dict) -> str:
    """Text of Claude's last turn, from the payload or the transcript file."""
    if payload.get("last_assistant_message"):
        return str(payload["last_assistant_message"])
    path = payload.get("transcript_path")
    if not path:
        return ""
    try:
        lines = Path(path).expanduser().read_text(encoding="utf-8").splitlines()
    except OSError:
        return ""
    chunks: list[str] = []
    for line in reversed(lines):
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        msg = entry.get("message") or {}
        content = msg.get("content")
        if entry.get("type") == "user":
            # A real prompt (not a tool result) marks the start of this turn.
            if isinstance(content, str) or any(
                isinstance(c, dict) and c.get("type") == "text" for c in content or []
            ):
                break
            continue
        if entry.get("type") != "assistant" or not isinstance(content, list):
            continue
        texts = [c.get("text", "") for c in content if isinstance(c, dict) and c.get("type") == "text"]
        if any(t.strip() for t in texts):
            chunks.append("\n".join(texts).strip())
    return "\n\n".join(reversed(chunks))


# -- event handlers ------------------------------------------------------------


def on_permission(payload: dict, relay: Relay, cfg: dict) -> dict | None:
    tool = payload.get("tool_name", "")
    summary, detail = describe_tool(tool, payload.get("tool_input") or {})
    no_shake = cfg.get("no_shake") or ""
    risky = bool(no_shake) and re.search(no_shake, f"{summary}\n{detail}", re.IGNORECASE) is not None
    try:
        req = relay.call(
            "POST",
            "/api/permission",
            {
                "session_id": payload.get("session_id", ""),
                "project": Path(payload.get("cwd") or ".").name,
                "tool_name": tool,
                "summary": summary,
                "detail": detail,
                "shake_allowed": not risky,
            },
        )
    except urllib.error.HTTPError:
        return None  # 409: no watch online -> regular terminal prompt
    pid = req["id"]
    deadline = time.monotonic() + float(cfg["permission_timeout"])
    try:
        while (remaining := deadline - time.monotonic()) > 0:
            wait = min(50.0, remaining)
            state = relay.call("GET", f"/api/permission/{pid}?wait={wait:.0f}", timeout=wait + 15)
            status = state.get("status")
            if status == "allow":
                return {"behavior": "allow"}
            if status == "deny":
                return {
                    "behavior": "deny",
                    "message": state.get("message") or "Denied from the watch.",
                }
            if status != "pending":
                return None
    except (OSError, ValueError):
        pass
    try:
        relay.call("DELETE", f"/api/permission/{pid}")
    except (OSError, ValueError):
        pass
    return None


def on_stop(payload: dict, relay: Relay, cfg: dict) -> dict | None:
    session = payload.get("session_id", "")
    wait = float(cfg["reply_wait"] or 0)
    text = last_assistant_text(payload) or "Claude ist fertig."
    relay.call(
        "POST",
        "/api/message",
        {
            "session_id": session,
            "project": Path(payload.get("cwd") or ".").name,
            "role": "assistant",
            "text": text,
            "awaiting_reply": wait > 0,
        },
    )
    # Pick up a reply sent from the watch (queued earlier or arriving now).
    deadline = time.monotonic() + wait
    while True:
        chunk = max(0.0, min(50.0, deadline - time.monotonic()))
        reply = relay.call("GET", f"/api/reply?session_id={quote(session)}&wait={chunk:.0f}", timeout=chunk + 15)
        if reply.get("text"):
            return {"decision": "block", "reason": f"Antwort von der Watch: {reply['text']}"}
        if time.monotonic() >= deadline:
            return None


def on_notification(payload: dict, relay: Relay) -> None:
    # Permission prompts are already handled by the PermissionRequest hook.
    if payload.get("notification_type") == "permission_prompt":
        return
    message = payload.get("message") or ""
    if message.startswith("Claude needs your permission"):
        return
    relay.call(
        "POST",
        "/api/message",
        {
            "session_id": payload.get("session_id", ""),
            "project": Path(payload.get("cwd") or ".").name,
            "role": "notification",
            "text": message,
        },
    )


def on_prompt(payload: dict, relay: Relay) -> None:
    prompt = payload.get("prompt") or ""
    if prompt.startswith("Antwort von der Watch:"):
        return
    relay.call(
        "POST",
        "/api/message",
        {
            "session_id": payload.get("session_id", ""),
            "project": Path(payload.get("cwd") or ".").name,
            "role": "user",
            "text": _clip(prompt, 2000),
        },
    )


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except ValueError:
        return 0
    cfg = load_config()
    if not cfg["token"]:
        return 0
    relay = Relay(cfg)
    event = payload.get("hook_event_name", "")
    try:
        if event == "PermissionRequest":
            decision = on_permission(payload, relay, cfg)
            if decision:
                out = {"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": decision}}
                print(json.dumps(out))
        elif event == "Stop":
            result = on_stop(payload, relay, cfg)
            if result:
                print(json.dumps(result))
        elif event == "Notification":
            on_notification(payload, relay)
        elif event == "UserPromptSubmit":
            on_prompt(payload, relay)
    except (OSError, ValueError, KeyError):
        pass  # relay down or garbled answer: stay out of Claude's way
    return 0


if __name__ == "__main__":
    sys.exit(main())
