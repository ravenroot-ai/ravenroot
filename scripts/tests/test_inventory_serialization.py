"""Inventory representation preserves evidence, including through guarded writers."""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest
from unittest import mock

from scripts.tests.test_audit_operational_configuration import audit, synthetic_repository


class InventorySerializationTest(unittest.TestCase):
    def assert_canonical(self, path):
        raw = path.read_text(encoding="utf-8")
        document = json.loads(raw)
        self.assertEqual(json.dumps(document, indent=1, ensure_ascii=False) + "\n", raw)
        return document

    def test_encoding_preserves_types_order_strings_and_readability(self):
        document = {"history": [{"rationale": "à\n\\literal", "approved": True, "absent": None}],
                    "integer": 1, "fraction": 1.0, "last": [False, 0, "001"]}
        rendered = audit.render_inventory(document)
        self.assertEqual(json.dumps(document, indent=1, ensure_ascii=False) + "\n", rendered)
        self.assertEqual(json.loads(json.dumps(document), object_pairs_hook=list),
                         json.loads(rendered, object_pairs_hook=list))
        restored = json.loads(rendered)
        self.assertIs(type(restored["integer"]), int)
        self.assertIs(type(restored["fraction"]), float)
        self.assertIs(restored["history"][0]["approved"], True)
        self.assertIsNone(restored["history"][0]["absent"])
        self.assertLess(len(rendered.encode()), len((json.dumps(document, indent=2, ensure_ascii=False) + "\n").encode()))

    def test_bootstrap_refresh_and_guarded_reconciliation_share_encoding(self):
        with synthetic_repository() as location:
            root = Path(location)
            inventory = root / "scripts/operational-configuration-inventory.json"
            report = root / "docs/architecture/operational-configuration-audit.md"
            before = self.assert_canonical(inventory)
            errors, _ = audit.refresh_inventory(root, inventory, report)
            self.assertEqual([], errors)
            self.assertEqual(before["entries"], self.assert_canonical(inventory)["entries"])
            def commit(message):
                subprocess.run(["git", "add", "."], cwd=root, check=True)
                subprocess.run(["git", "-c", "user.name=Audit Test", "-c", "user.email=audit@example.invalid",
                                "commit", "-qm", message], cwd=root, check=True)
                return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
            source = commit("inventory source")
            prior = inventory.read_bytes()
            file = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
            file.write_text("\n" + file.read_text())
            target = commit("source line change")
            candidates = audit.discover(root)
            plan = {"id": "serialization-guarded-test", "issue": "#606", "sourceRevision": source,
                    "targetRevision": target, "sourceInventoryPath": "scripts/operational-configuration-inventory.json",
                    "sourceInventoryDigest": hashlib.sha256(prior).hexdigest(),
                    "targetCandidateDigest": audit.candidate_set_digest(c.id for c in candidates),
                    "mappings": [], "retirements": [], "additions": []}
            # This small repository has no RouteTable; retain the real revision,
            # source inventory and candidate validation around the writer.
            with mock.patch.object(audit, "current_route_table_authority", return_value={}), \
                    mock.patch.object(audit, "route_table_authority_errors", return_value=[]):
                errors, _ = audit.refresh_inventory(root, inventory, report, reconciliation_plan=plan)
            self.assertEqual([], errors)
            after = self.assert_canonical(inventory)
            self.assertEqual("serialization-guarded-test", after["reconciliationHistory"][-1]["id"])
            # The real check retains its refusal of the deliberately absent route family.
            self.assertEqual(["RouteTable authority exists without reviewed rows"],
                             audit.check(root, inventory, report, require_complete=False))
