"""End-to-end tests: hook script <-> relay server, with a fake watch client."""

import json
import os
import subprocess
import sys
import threading
import time
import unittest
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "relay"))
sys.path.insert(0, str(ROOT / "hooks"))

import claude_watch_hook  # noqa: E402
import server  # noqa: E402

TOKEN = "test-token-123"


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.relay = server.Relay()
        self.srv = server.make_server("127.0.0.1", 0, TOKEN, self.relay)
        self.url = f"http://127.0.0.1:{self.srv.server_address[1]}"
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()

    def api(self, method, path, body=None, token=TOKEN):
        req = urllib.request.Request(
            self.url + path,
            data=json.dumps(body).encode() if body is not None else None,
            method=method,
            headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"},
        )
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(req, timeout=30) as resp:
            return json.loads(resp.read())

    def run_hook(self, payload, **env):
        full_env = {
            **os.environ,
            "CLAUDE_WATCH_URL": self.url,
            "CLAUDE_WATCH_TOKEN": TOKEN,
            "HOME": "/nonexistent",
            **env,
        }
        proc = subprocess.run(
            [sys.executable, str(ROOT / "hooks" / "claude_watch_hook.py")],
            input=json.dumps(payload),
            capture_output=True,
            text=True,
            env=full_env,
            timeout=60,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return json.loads(proc.stdout) if proc.stdout.strip() else None

    def watch_decides(self, decision, message=""):
        """Fake watch: wait for a permission event and answer it."""

        def run():
            since = 0
            while True:
                res = self.api("GET", f"/api/events?since={since}&wait=10")
                since = res["last"]
                for ev in res["events"]:
                    if ev["type"] == "permission":
                        self.seen = ev["data"]
                        self.api(
                            "POST",
                            f"/api/permission/{ev['data']['id']}/decision",
                            {"decision": decision, "message": message},
                        )
                        return

        t = threading.Thread(target=run, daemon=True)
        t.start()
        time.sleep(0.2)  # let the watch register as online
        return t

    def test_unauthorized(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self.api("GET", "/api/state", token="wrong")
        self.assertEqual(ctx.exception.code, 401)

    def test_permission_allowed_from_watch(self):
        t = self.watch_decides("allow")
        out = self.run_hook(
            {
                "hook_event_name": "PermissionRequest",
                "session_id": "s1",
                "cwd": "/home/me/myproj",
                "tool_name": "Bash",
                "tool_input": {"command": "npm test", "description": "Run tests"},
            }
        )
        t.join(5)
        self.assertEqual(
            out,
            {"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": {"behavior": "allow"}}},
        )
        self.assertEqual(self.seen["summary"], "npm test")
        self.assertEqual(self.seen["project"], "myproj")
        self.assertTrue(self.seen["shake_allowed"])

    def test_permission_denied_with_message(self):
        t = self.watch_decides("deny", "nope")
        out = self.run_hook(
            {
                "hook_event_name": "PermissionRequest",
                "tool_name": "Bash",
                "tool_input": {"command": "rm -rf build"},
            }
        )
        t.join(5)
        decision = out["hookSpecificOutput"]["decision"]
        self.assertEqual(decision, {"behavior": "deny", "message": "nope"})
        self.assertFalse(self.seen["shake_allowed"])

    def test_no_watch_falls_back_to_terminal(self):
        start = time.monotonic()
        out = self.run_hook({"hook_event_name": "PermissionRequest", "tool_name": "Bash", "tool_input": {}})
        self.assertIsNone(out)
        self.assertLess(time.monotonic() - start, 5)

    def test_relay_down_falls_back(self):
        out = self.run_hook(
            {"hook_event_name": "PermissionRequest", "tool_name": "Bash", "tool_input": {}},
            CLAUDE_WATCH_URL="http://127.0.0.1:1",
        )
        self.assertIsNone(out)

    def test_timeout_expires_request(self):
        self.api("GET", "/api/state")  # watch online, but never answers
        events = []
        out = self.run_hook(
            {"hook_event_name": "PermissionRequest", "tool_name": "Write", "tool_input": {"file_path": "a"}},
            CLAUDE_WATCH_PERMISSION_TIMEOUT="1",
        )
        self.assertIsNone(out)
        events = self.api("GET", "/api/events?since=0")["events"]
        self.assertEqual([e["type"] for e in events], ["permission", "resolved"])
        self.assertEqual(events[1]["data"]["status"], "expired")

    def test_stop_sends_last_message_and_takes_reply(self):
        transcript = Path(self.id().replace(".", "_") + ".jsonl")
        transcript = Path("/tmp") / transcript
        lines = [
            {"type": "user", "message": {"role": "user", "content": "do it"}},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Working on it"}]}},
            {"type": "user", "message": {"content": [{"type": "tool_result", "content": "ok"}]}},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Done!"}]}},
        ]
        transcript.write_text("\n".join(json.dumps(x) for x in lines))
        self.addCleanup(transcript.unlink)

        def reply_later():
            time.sleep(0.5)
            self.api("POST", "/api/reply", {"session_id": "s9", "text": "now add docs"})

        threading.Thread(target=reply_later, daemon=True).start()
        out = self.run_hook(
            {
                "hook_event_name": "Stop",
                "session_id": "s9",
                "cwd": "/x/proj",
                "transcript_path": str(transcript),
            },
            CLAUDE_WATCH_REPLY_WAIT="10",
        )
        self.assertEqual(out, {"decision": "block", "reason": "Antwort von der Watch: now add docs"})
        msgs = self.api("GET", "/api/state")["messages"]
        self.assertEqual(msgs[0]["text"], "Working on it\n\nDone!")
        self.assertTrue(msgs[0]["awaiting_reply"])
        self.assertEqual(msgs[1]["role"], "user")

    def test_stop_without_reply(self):
        out = self.run_hook({"hook_event_name": "Stop", "session_id": "s2", "last_assistant_message": "hi"})
        self.assertIsNone(out)
        self.assertEqual(self.api("GET", "/api/state")["messages"][0]["text"], "hi")

    def test_notification_skips_permission_prompts(self):
        self.run_hook({"hook_event_name": "Notification", "message": "Claude needs your permission to use Bash"})
        self.run_hook({"hook_event_name": "Notification", "message": "Claude is waiting for your input"})
        msgs = self.api("GET", "/api/state")["messages"]
        self.assertEqual([m["text"] for m in msgs], ["Claude is waiting for your input"])

    def test_web_ui_served(self):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(self.url + "/web", timeout=5) as resp:
            self.assertIn(b"Claude Watch", resp.read())


class DescribeTest(unittest.TestCase):
    def test_edit(self):
        summary, detail = claude_watch_hook.describe_tool(
            "Edit", {"file_path": "/a.py", "old_string": "x", "new_string": "y"}
        )
        self.assertEqual(summary, "/a.py")
        self.assertEqual(detail, "- x\n+ y")


if __name__ == "__main__":
    unittest.main()
