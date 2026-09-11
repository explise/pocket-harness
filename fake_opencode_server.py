#!/usr/bin/env python3
"""Fake 'opencode' server for PocketHarness end-to-end testing.
Speaks BOTH transports:
  POST /session                -> {"id": "..."}            (opencode native)
  POST /session/*/message      -> {"parts":[{text: plan}]} (opencode native)
  GET  /config                 -> providers/models tree    (opencode native)
  POST */chat/completions      -> OpenAI-compatible shape
  GET  */models                -> OpenAI-compatible model list
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

PLAN = ["torch off", "time", "battery"]

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, obj):
        b = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path.endswith("/models"):
            self._send({"object": "list", "data": [
                {"id": "fake-mini"}, {"id": "fake-large"}, {"id": "fake-coder"}]})
        elif self.path.endswith("/config"):
            self._send({"providers": {
                "anthropic": {"models": {
                    "claude-fake-opus": {}, "claude-fake-sonnet": {}}},
                "openai": {"models": {"gpt-fake": {}}}}})
        else:
            self._send({"id": "ses_get", "ok": True})

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(n).decode(errors="replace") if n else ""
        print("REQ", self.path, "|", body[:160], flush=True)
        if self.path.rstrip("/") == "/session":
            self._send({"id": "ses_test_1"})
        elif "/message" in self.path:
            self._send({"parts": [{"type": "text",
                                   "text": json.dumps(PLAN)}]})
        elif "/chat/completions" in self.path:
            self._send({"choices": [{"message": {
                "role": "assistant", "content": json.dumps(PLAN)}}]})
        else:
            self._send({"ok": True})

from http.server import ThreadingHTTPServer
ThreadingHTTPServer(("0.0.0.0", 8765), H).serve_forever()
