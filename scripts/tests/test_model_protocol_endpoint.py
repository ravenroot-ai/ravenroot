"""Actual HTTP and result-driven conformance of the secretless model-protocol fixture."""
import importlib.util
import json
from pathlib import Path
import unittest
import urllib.error
import urllib.request

SPEC = importlib.util.spec_from_file_location("model_protocol_endpoint", Path(__file__).resolve().parents[1] / "fixtures/model_protocol_endpoint.py")
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)


class ModelProtocolEndpointTest(unittest.TestCase):
    def request(self, command, results=()):
        return {"model": fixture.MODEL, "messages": [{"role": "system", "content": "fixture"},
                {"role": "user", "content": json.dumps({"command": command})}]
                + [{"role": "tool", "content": json.dumps(result)} for result in results],
                "tools": [{"function": {"name": name}} for name in ("read_file", "write_file", "test", "finish")]}

    def call(self, request):
        return fixture.proposal(request)["choices"][0]["message"]["tool_calls"][0]["function"]

    def test_native_effects_are_proposals_and_test_outcomes_require_tool_results(self):
        self.assertEqual(self.call(self.request("test"))["name"], "test")
        for code, outcome in ((1, "failed"), (0, "passed")):
            call = self.call(self.request("test", [{"exitCode": code, "output": "actual test output"}]))
            self.assertEqual(call["name"], "finish")
            self.assertEqual(json.loads(call["arguments"])["outcome"], outcome)
        with self.assertRaises(ValueError):
            self.call(self.request("test", ["pretend passed"]))
        write = self.call(self.request("remediate", ["def add(a, b): return a - b", "assert add(2, 3) == 5"]))
        self.assertEqual(write["name"], "write_file")
        self.assertIn("return a + b", json.loads(write["arguments"])["content"])

    def test_endpoint_is_loopback_only_secretless_and_rejects_other_paths_models_and_credentials(self):
        with fixture.ModelProtocolEndpoint() as endpoint:
            config = endpoint.configuration()
            self.assertNotIn("credentialReference", json.dumps(config))
            self.assertEqual(endpoint.server.server_address[0], "127.0.0.1")
            url = config["agentRuntime"]["models"]["governed-model"]["endpoint"]
            data = json.dumps(self.request("read")).encode()
            with urllib.request.urlopen(urllib.request.Request(url, data=data), timeout=5) as response:
                self.assertEqual(response.status, 200)
                self.assertEqual(json.load(response)["usage"]["total_tokens"], 32)
            self.assertEqual(endpoint.requests, 1)
            for target, body, headers in ((url + "/other", data, {}), (url, data, {"Authorization": "Bearer forbidden"}),
                                          (url, data.replace(fixture.MODEL.encode(), b"live-provider"), {})):
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(urllib.request.Request(target, data=body, headers=headers), timeout=5)
                self.assertEqual(error.exception.code, 400)
                error.exception.close()
            self.assertEqual(endpoint.requests, 1)


if __name__ == "__main__":
    unittest.main()
