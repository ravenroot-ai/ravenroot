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

    def test_render_publishes_the_full_catalog_metadata_shape(self) -> None:
        rendered = PUBLISHER.render()
        self.assertEqual(53, rendered.count("| Catalog field | Runtime descriptor value |"))
        for label in (
            "Display name",
            "Category",
            "Description",
            "Visual type",
            "Agentic",
            "Capabilities",
            "Declared default nature",
            "Declared allowed natures",
            "Application command allowlist",
            "Runtime concurrency",
            "Outcomes",
        ):
            self.assertEqual(53, rendered.count(f"| {label} |"), label)

    def test_each_node_links_a_complete_graph(self) -> None:
        rendered = PUBLISHER.render()
        self.assertEqual(53, rendered.count("Complete GraphML example"))

    def test_regeneration_uses_the_isolated_non_reactor_fixture(self) -> None:
        rendered = PUBLISHER.render()
        self.assertIn("-DskipTests install", rendered)
        self.assertIn("-f ../scripts/fixtures/node-contracts/pom.xml", rendered)

    def test_compact_index_links_every_node_anchor(self) -> None:
        rendered = PUBLISHER.render()
        self.assertEqual(53, rendered.count("](#"))
        self.assertIn("may scroll horizontally", rendered)


if __name__ == "__main__":
    unittest.main()
