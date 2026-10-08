import importlib.util
import io
import json
import pathlib
import sys
import tempfile
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
        with mock.patch.object(publisher.urllib.request, "urlopen",
                               side_effect=[conflict, io.BytesIO(b"same")]):
            publisher.http_create("https://store", "object", b"same", "text/plain", "")
        with mock.patch.object(publisher.urllib.request, "urlopen",
                               side_effect=[conflict, io.BytesIO(b"other")]):
            with self.assertRaisesRegex(SystemExit, "different bytes|exceeds"):
                publisher.http_create("https://store", "object", b"same", "text/plain", "")

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
