"""Hermetic model-protocol endpoint fixture, NOT trained inference or a live provider.

Only proposes tools. The unchanged production gateway and isolated Agent runtime must perform
all reads, writes, tests and remediation; this server has no Workspace or Docker access.
"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread

MODEL = "hermetic-model-protocol-fixture"
MAX_REQUEST_BYTES = 262144


def proposal(request):
    if request.get("model") != MODEL:
        raise ValueError("fixture model required")
    messages = request["messages"]
    command = json.loads(messages[1]["content"])["command"]
    completed = [json.loads(message["content"]) for message in messages if message["role"] == "tool"]
    steps = [("read_file", {"path": "hello.py"}), ("read_file", {"path": "test_hello.py"})]
    outcome = {"plan": "answered", "read": "answered", "resume": "completed", "implement": "completed",
               "remediate": "fixed", "handoff": "completed", "review": "changes-requested", "test": "failed"}[command]
    if command == "implement":
        steps.append(("write_file", {"path": "PLAN.md", "content": "Observe failing addition test, repair addition, retest, review.\n"}))
    elif command == "remediate":
        steps.append(("write_file", {"path": "hello.py", "content": "def add(a, b):\n    return a + b\n"}))
    elif command == "test":
        steps = [("test", {})]
        if completed:
            evidence = completed[0]
            if not isinstance(evidence, dict) or not isinstance(evidence.get("exitCode"), int):
                raise ValueError("actual test tool result required")
            outcome = "passed" if evidence["exitCode"] == 0 else "failed"
    elif command in {"review", "handoff"}:
        steps.append(("list_files", {}))
        if len(completed) >= 3 and "PLAN.md" in completed[2]:
            steps.append(("read_file", {"path": "PLAN.md"}))
        if command == "review" and completed and "return a + b" in completed[0]:
            outcome = "approved"
    if len(completed) < len(steps):
        name, arguments = steps[len(completed)]
    else:
        name, arguments = "finish", {"outcome": outcome, "result": json.dumps({"fixture": MODEL, "observedTools": completed})}
    offered = {tool["function"]["name"] for tool in request["tools"]}
    if name not in offered:
        raise ValueError("fixture cannot propose unavailable authority")
    return {"choices": [{"message": {"role": "assistant", "content": None, "tool_calls": [{
        "id": "fixture-" + str(len(completed)), "type": "function",
        "function": {"name": name, "arguments": json.dumps(arguments)}}]}}], "usage": {"total_tokens": 32}}


class ModelProtocolEndpoint:
    def __enter__(self):
        owner = self
        self.requests = 0

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                try:
                    if self.path != "/v1/chat/completions" or self.headers.get("Authorization"):
                        raise ValueError("secretless fixture path required")
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= MAX_REQUEST_BYTES:
                        raise ValueError("bounded request required")
                    self.connection.settimeout(5)
                    result = proposal(json.loads(self.rfile.read(size)))
                    data = json.dumps(result).encode()
                    owner.requests += 1
                    self.send_response(200)
                except (ValueError, KeyError, TypeError, IndexError):
                    data = b'{"error":"invalid hermetic protocol request"}'
                    self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        return self

    def configuration(self):
        return {"agentRuntime": {"modelTurns": 12, "toolCalls": 24, "modelTokens": 20000, "tokensPerTurn": 2048,
                "protocolBytes": MAX_REQUEST_BYTES, "toolOutputBytes": 8192, "listedFiles": 128,
                "testCommand": ["python3", "-m", "unittest", "discover", "-v"], "skills": {}, "connectTimeout": "PT5S",
                "models": {"governed-model": {"endpoint": "http://127.0.0.1:" + str(self.server.server_port) + "/v1/chat/completions",
                    "model": MODEL, "maxConcurrency": 8, "maxRequestBytes": MAX_REQUEST_BYTES, "maxResponseBytes": 65536}}}}

    def __exit__(self, *_):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
