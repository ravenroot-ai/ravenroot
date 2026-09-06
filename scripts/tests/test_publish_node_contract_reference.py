"""Tests for the descriptor-backed node contract publisher."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("publisher", ROOT / "scripts/publish_node_contract_reference.py")
assert SPEC and SPEC.loader
PUBLISHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISHER)


class PublishNodeContractReferenceTest(unittest.TestCase):
    def test_runtime_snapshot_covers_every_node_and_sensitive_variant(self) -> None:
        rendered = PUBLISHER.render()
        self.assertEqual(53, rendered.count("## `"))
        self.assertIn("## `openapi.request-reply`", rendered)
        self.assertIn("`maxResponseBytes`", rendered)
        self.assertIn("## `telegram.delete.message`", rendered)
        delete = rendered.split("## `telegram.delete.message`", 1)[1].split("## `", 1)[0]
        self.assertNotIn("`maxButtons`", delete)
        self.assertNotIn("`maxMediaBytes`", delete)

    def test_each_node_links_a_complete_graph(self) -> None:
        rendered = PUBLISHER.render()
        self.assertEqual(53, rendered.count("Complete GraphML example"))


if __name__ == "__main__":
    unittest.main()
