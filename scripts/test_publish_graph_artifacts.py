import importlib.util
import http.server
import io
import json
import pathlib
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
from unittest import mock

SCRIPT = pathlib.Path(__file__).with_name("publish-graph-artifacts.py")
SPEC = importlib.util.spec_from_file_location("publisher", SCRIPT)
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class PublisherTest(unittest.TestCase):
    def release(self, version=2, graph_hash="a" * 64):
        return {
            "tenantId": "tenant-a", "graphId": "orders", "releaseVersion": version,
            "sourceCommit": "b" * 40, "graphMlSha256": graph_hash,
            "graphMlPath": f"graph-{version}", "manifestSha256": "c" * 64,
            "manifestPath": f"manifest-{version}",
        }

    def test_catalog_retains_history_and_requires_increasing_version(self):
        first = self.release(1)
        second = self.release(2)
        self.assertEqual([first, second], publisher.append_release([first], second))
        with self.assertRaisesRegex(SystemExit, "increase monotonically"):
            publisher.append_release([second], first)

    def test_version_cannot_be_reused_for_different_bytes(self):
        current = self.release()
        changed = self.release(graph_hash="d" * 64)
        self.assertEqual([current], publisher.append_release([current], current))
        with self.assertRaisesRegex(SystemExit, "different immutable evidence"):
            publisher.append_release([current], changed)

    def test_conditional_http_retry_compares_existing_bytes(self):
        conflict = urllib.error.HTTPError("https://store/object", 412, "exists", {}, io.BytesIO())
        with mock.patch.object(publisher, "open_http",
                               side_effect=[conflict, (200, b"same")]):
            publisher.http_create("https://store", "object", b"same", "text/plain", "")
        with mock.patch.object(publisher, "open_http",
                               side_effect=[conflict, (200, b"other")]):
            with self.assertRaisesRegex(SystemExit, "different bytes|exceeds"):
                publisher.http_create("https://store", "object", b"same", "text/plain", "")

    def test_authorization_is_not_forwarded_across_redirect(self):
        received_authorization = []

        class Target(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                received_authorization.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"target")

            def log_message(self, _format, *_args):
                pass

        target = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Target)
        target.daemon_threads = True
        target_thread = threading.Thread(target=target.serve_forever, daemon=True)
        target_thread.start()

        target_url = f"http://127.0.0.1:{target.server_address[1]}/capture"

        class Redirect(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(302)
                self.send_header("Location", target_url)
                self.end_headers()

            def log_message(self, _format, *_args):
                pass

        redirect = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Redirect)
        redirect.daemon_threads = True
        redirect_thread = threading.Thread(target=redirect.serve_forever, daemon=True)
        redirect_thread.start()
        try:
            url = f"http://127.0.0.1:{redirect.server_address[1]}/redirect"
            with self.assertRaises(urllib.error.HTTPError) as raised:
                publisher.http_get(url, "TEST_ONLY_SENTINEL")
            self.assertEqual(302, raised.exception.code)
            raised.exception.close()
            self.assertEqual([], received_authorization)
        finally:
            redirect.shutdown()
            target.shutdown()
            redirect.server_close()
            target.server_close()

    def test_stalled_response_body_obeys_absolute_deadline(self):
        class Stalled(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Length", "4")
                self.end_headers()
                self.wfile.flush()
                time.sleep(0.8)

            def log_message(self, _format, *_args):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Stalled)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        started = time.monotonic()
        try:
            url = f"http://127.0.0.1:{server.server_address[1]}/stall"
            with mock.patch.object(publisher, "HTTP_TIMEOUT_SECONDS", 0.15):
                with self.assertRaisesRegex(SystemExit, "time limit|network boundary"):
                    publisher.http_get(url, "")
            self.assertLess(time.monotonic() - started, 0.7)
        finally:
            server.shutdown()
            server.server_close()

    def test_slow_drip_cannot_extend_absolute_body_deadline(self):
        class SlowDrip(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Length", "4")
                self.end_headers()
                for value in b"slow":
                    time.sleep(0.08)
                    try:
                        self.wfile.write(bytes([value]))
                        self.wfile.flush()
                    except (BrokenPipeError, ConnectionResetError):
                        return

            def log_message(self, _format, *_args):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), SlowDrip)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        started = time.monotonic()
        try:
            url = f"http://127.0.0.1:{server.server_address[1]}/slow"
            with mock.patch.object(publisher, "HTTP_TIMEOUT_SECONDS", 0.15):
                with self.assertRaisesRegex(SystemExit, "time limit"):
                    publisher.http_get(url, "")
            self.assertLess(time.monotonic() - started, 0.7)
        finally:
            server.shutdown()
            server.server_close()

    def test_aws_cli_calls_have_retry_network_and_process_bounds(self):
        completed = subprocess.CompletedProcess(["aws"], 0)
        with mock.patch.object(publisher.subprocess, "run", return_value=completed) as run:
            self.assertIs(completed, publisher.aws_run(["aws", "s3api", "head-object"]))
        command = run.call_args.args[0]
        self.assertEqual("aws", command[0])
        self.assertIn("--cli-connect-timeout", command)
        self.assertIn("--cli-read-timeout", command)
        self.assertEqual(publisher.AWS_CLI_TIMEOUT_SECONDS, run.call_args.kwargs["timeout"])
        self.assertEqual(publisher.AWS_MAX_ATTEMPTS,
                         run.call_args.kwargs["env"]["AWS_MAX_ATTEMPTS"])
        with mock.patch.object(publisher.subprocess, "run",
                               side_effect=subprocess.TimeoutExpired(["aws"], 1)):
            with self.assertRaisesRegex(SystemExit, "time limit"):
                publisher.aws_run(["aws", "s3api", "head-object"])

    def test_full_http_publication_binds_admission_source_and_immutable_objects(self):
        graph = b'''<?xml version="1.0" encoding="UTF-8"?>
<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
  <key id="graph-id" for="graph" attr.name="ravenroot.authoring.graphId" attr.type="string"/>
  <key id="release" for="graph" attr.name="ravenroot.authoring.releaseVersion" attr.type="int"/>
  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
  <graph id="orders" edgedefault="directed">
    <data key="graph-id">orders</data><data key="release">1</data>
    <node id="start"><data key="behavior">builtin.start</data></node>
    <node id="end"><data key="behavior">builtin.end</data></node>
    <edge source="start" target="end"/>
  </graph>
</graphml>'''
        accepted = {"valid": True, "violations": [], "findings": [],
                    "nodes": 2, "edges": 1, "startNodes": 1, "endNodes": 1}
        uploaded = {}

        def capture(_base, path, data, content_type, _token):
            self.assertNotIn(path, uploaded)
            uploaded[path] = (data, content_type)

        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory, "orders.graphml")
            source.write_bytes(graph)
            argv = [str(SCRIPT), str(source), "--tenant", "tenant-a",
                    "--source-commit", "b" * 40, "--admission-url", "https://runtime",
                    "--catalog-path", "catalog.json", "--http-base", "https://store"]
            with mock.patch.object(sys, "argv", argv), \
                    mock.patch.object(publisher, "admission", return_value=accepted) as admission, \
                    mock.patch.object(publisher, "http_create", side_effect=capture):
                publisher.main()

        admission.assert_called_once_with(graph, "https://runtime", "")
        self.assertEqual(3, len(uploaded))
        catalog = json.loads(uploaded["catalog.json"][0])
        release = catalog["artifacts"][0]
        manifest = json.loads(uploaded[release["manifestPath"]][0])
        self.assertEqual("b" * 40, release["sourceCommit"])
        self.assertEqual(release["sourceCommit"], manifest["sourceCommit"])
        self.assertEqual(publisher.digest(graph), manifest["graphMlSha256"])
        self.assertEqual(accepted["nodes"], manifest["compatibility"]["nodes"])
        self.assertEqual([{"id": "builtin.end", "kind": "behavior"},
                          {"id": "builtin.start", "kind": "behavior"}], manifest["dependencies"])
        self.assertEqual(graph, uploaded[release["graphMlPath"]][0])
        self.assertEqual("application/graphml+xml", uploaded[release["graphMlPath"]][1])
        self.assertEqual(publisher.digest(uploaded[release["manifestPath"]][0]),
                         release["manifestSha256"])


if __name__ == "__main__":
    unittest.main()
