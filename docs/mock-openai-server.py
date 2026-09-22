#!/usr/bin/env python3
"""Minimal instrumented OpenAI-compatible server for measuring LM-Droid's send path.

Emulates a self-hosted llama-server with a configurable number of inference slots: every
chat completion must acquire a slot before it is processed, so a second concurrent request
visibly QUEUES (that's the single-slot behaviour the app's auto-title request used to trip over).

Endpoints:
  GET  /v1/models           -> one model advertising all four capability knobs (no /props probe)
  GET  /props               -> 404
  POST /v1/chat/completions -> stream=true: SSE with reasoning deltas then content deltas
                               stream=false: (the auto-title call) a single JSON completion

Everything is logged to stderr with ms timestamps relative to process start.

Run on the host, point the app at it from the emulator (emulator-5554 reaches the host as
10.0.2.2): Settings → API設定 → プロファイル → APIのURL = http://10.0.2.2:8123/v1, any API key,
then 接続テスト to register "mock-reasoning". Measure with:

    adb logcat -c && adb logcat -v usec \
      -s ChatViewModel:V ConversationRepository:V OpenAiApiClient:V OkHttpWire:V

Each request is logged with its arrival time, whether it had to QUEUED for an inference slot,
and when its first chunk went out — see docs/ttfb-latency-analysis.md §6 for the numbers taken
with this harness.

Env knobs: NP slots (default 1), TTFT_MS (default 250), REASONING_CHUNKS (8),
CONTENT_CHUNKS (8), CHUNK_GAP_MS (30), TITLE_DELAY_MS (4000), PORT (8123).
"""
import json
import os
import re
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

T0 = time.monotonic()
NP = int(os.environ.get("NP", "1"))
TTFT_MS = int(os.environ.get("TTFT_MS", "250"))
REASONING_CHUNKS = int(os.environ.get("REASONING_CHUNKS", "8"))
CONTENT_CHUNKS = int(os.environ.get("CONTENT_CHUNKS", "8"))
CHUNK_GAP_MS = int(os.environ.get("CHUNK_GAP_MS", "30"))
TITLE_DELAY_MS = int(os.environ.get("TITLE_DELAY_MS", "4000"))
PORT = int(os.environ.get("PORT", "8123"))

SLOT = threading.Semaphore(NP)
LOCK = threading.Lock()
SEQ = 0


def now_ms() -> int:
    return int((time.monotonic() - T0) * 1000)


def log(msg: str) -> None:
    with LOCK:
        print(f"+{now_ms():>7}ms {msg}", flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # silence the default access log
        pass

    def _send(self, code, body: bytes, ctype: str):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            payload = {
                "object": "list",
                "data": [{
                    "id": "mock-reasoning",
                    "object": "model",
                    "owned_by": "mock",
                    "supported": ["completions", "chat"],
                    "supported_parameters": [
                        "reasoning", "reasoning_effort", "reasoning_budget_tokens",
                        "chat_template_kwargs", "enable_memory",
                    ],
                }],
            }
            log(f"GET  {self.path} -> 200 (1 model)")
            self._send(200, json.dumps(payload).encode(), "application/json")
            return
        log(f"GET  {self.path} -> 404")
        self._send(404, b'{"error":{"message":"nope"}}', "application/json")

    def do_POST(self):
        global SEQ
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        with LOCK:
            SEQ += 1
            rid = SEQ
        arrived = now_ms()
        try:
            body = json.loads(raw)
        except Exception:
            body = {}
        is_title = not body.get("stream", False)
        msgs = body.get("messages", [])
        system = next((m.get("content", "") for m in msgs if m.get("role") == "system"), "")
        if not isinstance(system, str):
            system = json.dumps(system, ensure_ascii=False)
        kind = "TITLE" if ("short conversation title" in system or is_title) else "REPLY"
        last_user = next((m.get("content", "") for m in reversed(msgs) if m.get("role") == "user"), "")
        if not isinstance(last_user, str):
            last_user = "[multipart]"
        kwargs = body.get("chat_template_kwargs") or {}
        log(f"REQ#{rid} {kind} arrive bytes={length} stream={body.get('stream')} "
            f"max_tokens={body.get('max_tokens')} kwargs={json.dumps(kwargs, ensure_ascii=False)} "
            f"msgs={len(msgs)} last_user={str(last_user)[:40]!r}")

        if not SLOT.acquire(timeout=180):
            log(f"REQ#{rid} {kind} slot acquire TIMED OUT")
            self._send(503, b'{"error":{"message":"no slot"}}', "application/json")
            return
        waited = now_ms() - arrived
        note = f"QUEUED {waited}ms" if waited > 5 else f"slot free ({waited}ms)"
        log(f"REQ#{rid} {kind} got slot — {note}")
        try:
            if kind == "TITLE":
                time.sleep(TITLE_DELAY_MS / 1000)
                payload = {
                    "id": f"chatcmpl-mock-{rid}", "object": "chat.completion", "model": body.get("model"),
                    "choices": [{"index": 0, "finish_reason": "stop",
                                 "message": {"role": "assistant", "content": "モック用の会話タイトル"}}],
                    "usage": {"completion_tokens": 6},
                }
                log(f"REQ#{rid} TITLE responded (+{now_ms() - arrived}ms)")
                self._send(200, json.dumps(payload, ensure_ascii=False).encode(), "application/json")
            else:
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()
                time.sleep(TTFT_MS / 1000)

                def emit(obj):
                    chunk = f"data: {json.dumps(obj, ensure_ascii=False)}\n\n".encode()
                    self.wfile.write(b"%x\r\n%s\r\n" % (len(chunk), chunk))
                    self.wfile.flush()

                for i in range(REASONING_CHUNKS):
                    emit({"id": "chatcmpl-mock", "object": "chat.completion.chunk", "model": body.get("model"),
                          "choices": [{"index": 0, "delta": {"reasoning_content": f"({i + 1})"}}]})
                    if i == 0:
                        log(f"REQ#{rid} REPLY first reasoning chunk (+{now_ms() - arrived}ms)")
                    time.sleep(CHUNK_GAP_MS / 1000)
                for i in range(CONTENT_CHUNKS):
                    emit({"id": "chatcmpl-mock", "object": "chat.completion.chunk", "model": body.get("model"),
                          "choices": [{"index": 0, "delta": {"content": "はい "}}]})
                    if i == 0:
                        log(f"REQ#{rid} REPLY first content chunk (+{now_ms() - arrived}ms)")
                    time.sleep(CHUNK_GAP_MS / 1000)
                emit({"id": "chatcmpl-mock", "object": "chat.completion.chunk", "model": body.get("model"),
                      "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]})
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
                log(f"REQ#{rid} REPLY streamed (+{now_ms() - arrived}ms)")
        except (BrokenPipeError, ConnectionResetError):
            log(f"REQ#{rid} {kind} client went away")
        finally:
            SLOT.release()


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def server_bind(self):
        super().server_bind()


if __name__ == "__main__":
    log(f"mock server on 0.0.0.0:{PORT} NP={NP} TTFT_MS={TTFT_MS} "
        f"reasoning={REASONING_CHUNKS}x{CHUNK_GAP_MS}ms content={CONTENT_CHUNKS}x{CHUNK_GAP_MS}ms "
        f"title_delay={TITLE_DELAY_MS}ms")
    Server(("0.0.0.0", PORT), Handler).serve_forever()
