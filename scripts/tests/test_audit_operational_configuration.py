from __future__ import annotations

import json
import io
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import audit_operational_configuration as audit  # noqa: E402


ROOT = SCRIPTS.parent


def synthetic_repository() -> tempfile.TemporaryDirectory[str]:
    temporary = tempfile.TemporaryDirectory()
    root = Path(temporary.name)
    source = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
    source.parent.mkdir(parents=True)
    source.write_text(
        "package dev.example;\n"
        "final class RuntimePolicy {\n"
        "  static final String WIRE_VERSION = \"v1\";\n"
        "  static final int DERIVED_MASK = 1 << 4;\n"
        "}\n",
        encoding="utf-8",
    )
    fixture = root / "ravenroot/ravenroot-engine-testkit/src/main/java/dev/example/Fixture.java"
    fixture.parent.mkdir(parents=True)
    fixture.write_text("final class Fixture { static final int TIMEOUT_SECONDS = 3; }\n", encoding="utf-8")
    subprocess.run(["git", "init", "-q"], cwd=root, check=True)
    subprocess.run(["git", "add", "ravenroot"], cwd=root, check=True)
    audit.bootstrap(root, root / "scripts/operational-configuration-inventory.json",
                    root / "docs/architecture/operational-configuration-audit.md")
    return temporary


def classify_non_pending(root: Path) -> None:
    path = root / "scripts/operational-configuration-inventory.json"
    document = json.loads(path.read_text(encoding="utf-8"))
    for entry in document["entries"]:
        if entry["status"] == "pending-review":
            if entry["role"] == "WIRE_VERSION":
                entry.update(status="retained", classification="protocol-or-format-invariant",
                             rationale="Wire schema version parsed by every peer.")
            else:
                entry.update(status="retained", classification="derived",
                             rationale="Bit-mask expression derived from the representation width.")
    path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    (root / "docs/architecture/operational-configuration-audit.md").write_text(
        audit.render_report(document), encoding="utf-8")


class OperationalConfigurationAuditTest(unittest.TestCase):
    def test_real_repository_incremental_inventory_and_generated_report_are_current(self) -> None:
        errors = audit.check(ROOT, require_complete=False)
        self.assertEqual([], errors, "\n".join(errors[:20]))

    def test_default_completion_gate_rejects_pending_review(self) -> None:
        errors = audit.check(ROOT)
        self.assertTrue(any("audit is incomplete" in error and "pending-review" in error
                            for error in errors), errors[:20])

    def test_default_completion_gate_rejects_deferred_work(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            classify_non_pending(root)
            inventory = root / "scripts/operational-configuration-inventory.json"
            document = json.loads(inventory.read_text(encoding="utf-8"))
            reviewed = next(entry for entry in document["entries"]
                            if entry["classification"] != "test-fixture")
            reviewed.update(status="deferred", followUp="#87",
                            rationale="Tracked by the linked deployment lifecycle follow-up.")
            inventory.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            (root / "docs/architecture/operational-configuration-audit.md").write_text(
                audit.render_report(document), encoding="utf-8")
            errors = audit.check(root, inventory,
                                 root / "docs/architecture/operational-configuration-audit.md")
        self.assertTrue(any("audit is incomplete" in error and "1 deferred" in error
                            for error in errors), errors)

    def test_new_named_operational_constant_is_rejected(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            source = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
            source.write_text(source.read_text(encoding="utf-8").replace(
                "}\n", "  static final int WORKER_CAPACITY = 37;\n}\n"), encoding="utf-8")
            errors = audit.check(root, root / "scripts/operational-configuration-inventory.json",
                                 root / "docs/architecture/operational-configuration-audit.md",
                                 require_complete=False)
        self.assertTrue(any("unclassified operational candidate" in error and "WORKER_CAPACITY" in error
                            for error in errors), errors)

    def test_new_inline_duration_with_an_uninformative_local_name_is_rejected(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            source = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
            source.write_text(source.read_text(encoding="utf-8").replace(
                "}\n", "  Object value() { return java.time.Duration.ofSeconds(37); }\n}\n"), encoding="utf-8")
            errors = audit.check(root, root / "scripts/operational-configuration-inventory.json",
                                 root / "docs/architecture/operational-configuration-audit.md",
                                 require_complete=False)
        self.assertTrue(any("unclassified operational candidate" in error and "ofSeconds(37)" in error
                            for error in errors), errors)

    def test_root_runtime_script_default_is_rejected(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            (root / "service.sh").write_text(
                "WAIT_TIMEOUT=${RAVENROOT_WAIT_TIMEOUT:-37}\nsleep 37\n", encoding="utf-8")
            errors = audit.check(root, root / "scripts/operational-configuration-inventory.json",
                                 root / "docs/architecture/operational-configuration-audit.md",
                                 require_complete=False)
        self.assertTrue(any("service.sh" in error and "RAVENROOT_WAIT_TIMEOUT" in error
                            for error in errors), errors)

    def test_docker_identity_and_port_directives_are_rejected(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            (root / "Dockerfile").write_text(
                "FROM scratch\nUSER 12345:12345\nEXPOSE 31337\n", encoding="utf-8")
            errors = audit.check(root, root / "scripts/operational-configuration-inventory.json",
                                 root / "docs/architecture/operational-configuration-audit.md",
                                 require_complete=False)
        self.assertTrue(any("container-directive" in error and "12345" in error for error in errors), errors)
        self.assertTrue(any("container-directive" in error and "31337" in error for error in errors), errors)

    def test_known_policy_constructor_argument_is_rejected(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            source = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
            source.write_text(source.read_text(encoding="utf-8").replace(
                "}\n", "  Object value() { return new RetryPolicy(7); }\n}\n"), encoding="utf-8")
            errors = audit.check(root, root / "scripts/operational-configuration-inventory.json",
                                 root / "docs/architecture/operational-configuration-audit.md",
                                 require_complete=False)
        self.assertTrue(any("inline-operational-call" in error and "7" in error for error in errors), errors)

    def test_environment_binding_is_discovered_without_default_or_operational_name(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            source = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
            source.write_text(source.read_text(encoding="utf-8").replace(
                "}\n", '  Object value() { return text(environment, "RAVENROOT_RUNTIME_INSTANCE", fallback); }\n}\n'),
                encoding="utf-8")
            errors = audit.check(root, root / "scripts/operational-configuration-inventory.json",
                                 root / "docs/architecture/operational-configuration-audit.md",
                                 require_complete=False)
        self.assertTrue(any("environment-binding" in error and "RAVENROOT_RUNTIME_INSTANCE" in error
                            for error in errors), errors)

    def test_aggregate_constructor_yields_atomic_values_with_full_evidence(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            source = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
            padding = "x" * 360
            source.write_text(source.read_text(encoding="utf-8").replace(
                "}\n", f'  Object value() {{ return new AgentBudgetVector("{padding}", 7, 11); }}\n}}\n'),
                encoding="utf-8")
            candidates = [candidate for candidate in audit.discover(root)
                          if candidate.kind == "inline-operational-call"
                          and "AgentBudgetVector" in candidate.evidence]
        self.assertEqual({'"' + padding + '"', "7", "11"}, {candidate.expression for candidate in candidates})
        self.assertTrue(all(len(candidate.evidence) > 320 for candidate in candidates))

    def test_changed_operator_with_the_same_atoms_invalidates_the_stable_review_record(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            source = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
            source.write_text(source.read_text(encoding="utf-8").replace("1 << 4", "1 + 4"), encoding="utf-8")
            errors = audit.check(root, root / "scripts/operational-configuration-inventory.json",
                                 root / "docs/architecture/operational-configuration-audit.md",
                                 require_complete=False)
        self.assertTrue(any("unclassified operational candidate" in error for error in errors), errors)
        self.assertTrue(any("stale inventory entry" in error for error in errors), errors)

    def test_system_property_literal_and_dynamic_family_are_discovered(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            source = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
            source.write_text(source.read_text(encoding="utf-8").replace(
                "}\n",
                '  Object direct() { return System.getProperty("ravenroot.example.timeout"); }\n'
                '  Object dynamic() { return System.getProperty(PROPERTY_PREFIX + name); }\n}\n'),
                encoding="utf-8")
            candidates = [candidate for candidate in audit.discover(root)
                          if candidate.kind == "property-binding"]
        self.assertIn("ravenroot.example.timeout", {candidate.expression for candidate in candidates})
        self.assertIn("PROPERTY_PREFIX + name", {candidate.expression for candidate in candidates})

    def test_protocol_derived_and_test_fixture_classifications_are_accepted(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            classify_non_pending(root)
            errors = audit.check(root, root / "scripts/operational-configuration-inventory.json",
                                 root / "docs/architecture/operational-configuration-audit.md")
            document = json.loads((root / "scripts/operational-configuration-inventory.json")
                                  .read_text(encoding="utf-8"))
        self.assertEqual([], errors)
        self.assertIn("protocol-or-format-invariant", {entry["classification"] for entry in document["entries"]})
        self.assertIn("derived", {entry["classification"] for entry in document["entries"]})
        self.assertIn("test-fixture", {entry["classification"] for entry in document["entries"]})

    def test_operator_candidates_share_one_explicit_logical_setting_contract(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            inventory = root / "scripts/operational-configuration-inventory.json"
            document = json.loads(inventory.read_text(encoding="utf-8"))
            reviewed = [entry for entry in document["entries"] if entry["status"] == "pending-review"]
            contract = {
                "status": "already-centralized",
                "classification": "operator-configurable",
                "setting": "example.runtime.policy",
                "owner": "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java#RuntimePolicy",
                "field": "DERIVED_MASK",
                "bindings": [],
                "default": "16",
                "defaultEvidence": [reviewed[0]["id"]],
                "validation": "positive integer",
                "scope": "process",
                "pinning": "read once at startup",
                "coverage": "synthetic test owner only",
                "rationale": "Both expressions implement one logical operator setting.",
            }
            reviewed[0].update(contract)
            reviewed[1].update(contract)
            reviewed[1]["default"] = "32"
            errors = audit.inventory_errors(root, document, audit.discover(root))
            reviewed[1]["default"] = "16"
            report = audit.render_report(document)
        self.assertTrue(any("inconsistent configuration authority metadata" in error for error in errors), errors)
        self.assertIn("| Unique confirmed operator-configurable parameters | 1 |", report)
        self.assertEqual(1, report.count("| example.runtime.policy |"))

    def test_invalid_status_classification_pair_and_fake_owner_are_rejected(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            inventory = root / "scripts/operational-configuration-inventory.json"
            document = json.loads(inventory.read_text(encoding="utf-8"))
            reviewed = next(entry for entry in document["entries"] if entry["status"] == "pending-review")
            reviewed.update(
                status="converted", classification="protocol-or-format-invariant",
                rationale="Invalid test state.",
            )
            errors = audit.inventory_errors(root, document, audit.discover(root))
            reviewed.update(
                status="already-centralized", classification="operator-configurable",
                setting="example.runtime.policy", owner="missing.java#MissingOwner",
                field="DERIVED_MASK",
                bindings=["RAVENROOT_MISSING"], default="16", defaultEvidence=[reviewed["id"]],
                validation="positive integer", scope="process", pinning="read once at startup",
                coverage="synthetic test owner only",
            )
            owner_errors = audit.inventory_errors(root, document, audit.discover(root))
        self.assertTrue(any("invalid for classification" in error for error in errors), errors)
        self.assertTrue(any("owner is not a tracked in-repository" in error for error in owner_errors), owner_errors)
        self.assertTrue(any("has no same-setting environment-binding" in error
                            for error in owner_errors), owner_errors)

    def test_owner_evidence_rejects_escape_and_untracked_sources(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            untracked = root / "Untracked.java"
            untracked.write_text("final class Untracked {}\n", encoding="utf-8")
            escaped = audit.current_source_owner(root, "../outside.java#Outside")
            untracked_owner = audit.current_source_owner(root, "Untracked.java#Untracked")
            tracked_owner = audit.current_source_owner(
                root, "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java#RuntimePolicy",
            )
            keyword_owner = audit.current_source_owner(
                root, "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java#package",
            )
        self.assertIsNone(escaped)
        self.assertIsNone(untracked_owner)
        self.assertIsNone(keyword_owner)
        self.assertIsNotNone(tracked_owner)

    def test_confirmed_hardcoded_setting_fails_the_completion_gate(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            inventory = root / "scripts/operational-configuration-inventory.json"
            document = json.loads(inventory.read_text(encoding="utf-8"))
            reviewed = next(entry for entry in document["entries"] if entry["status"] == "pending-review")
            reviewed.update(
                status="confirmed-hardcoded", classification="operator-configurable",
                setting="example.runtime.policy",
                owner="ravenroot/example/src/main/java/dev/example/RuntimePolicy.java#RuntimePolicy",
                field="DERIVED_MASK",
                bindings=[], default="16", defaultEvidence=[reviewed["id"]],
                validation="positive integer", scope="process", pinning="read once at startup",
                coverage="synthetic test owner only",
                rationale="Confirmed but intentionally unresolved in this test.",
            )
            for entry in document["entries"]:
                if entry["status"] == "pending-review":
                    entry.update(status="retained", classification="derived",
                                 rationale="Synthetic derived expression.")
            inventory.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            report = root / "docs/architecture/operational-configuration-audit.md"
            report.write_text(audit.render_report(document), encoding="utf-8")
            errors = audit.check(root, inventory, report)
        self.assertTrue(any("confirmed-hardcoded" in error for error in errors), errors)

    def test_converted_setting_requires_resolvable_source_revisions(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            inventory = root / "scripts/operational-configuration-inventory.json"
            document = json.loads(inventory.read_text(encoding="utf-8"))
            reviewed = next(entry for entry in document["entries"] if entry["status"] == "pending-review")
            reviewed.update(
                status="converted", classification="operator-configurable",
                setting="example.runtime.policy",
                owner="ravenroot/example/src/main/java/dev/example/RuntimePolicy.java#RuntimePolicy",
                field="DERIVED_MASK",
                bindings=[], default="16", defaultEvidence=[reviewed["id"]],
                validation="positive integer", scope="process", pinning="read once at startup",
                coverage="synthetic test owner only",
                rationale="Synthetic conversion provenance test.",
                conversion={
                    "issue": "#225", "beforeRevision": "0" * 40, "afterRevision": "1" * 40,
                    "path": "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java",
                    "symbol": "RuntimePolicy",
                    "binding": "RAVENROOT_RUNTIME_LIMIT", "bindingSymbol": "RUNTIME_LIMIT_VARIABLE",
                    "field": "DERIVED_MASK", "beforeExpression": "DERIVED_MASK = 1 << 4",
                    "afterExpression": "RUNTIME_LIMIT_VARIABLE, DERIVED_MASK",
                },
            )
            errors = audit.inventory_errors(root, document, audit.discover(root))
        self.assertTrue(any("conversion beforeRevision is not a local commit" in error for error in errors), errors)
        self.assertTrue(any("conversion afterRevision is not a local commit" in error for error in errors), errors)

    def test_converted_setting_rejects_no_op_revision_provenance(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            subprocess.run(["git", "-c", "user.name=Audit Test", "-c", "user.email=audit@example.invalid",
                            "commit", "-qm", "fixture"], cwd=root, check=True)
            revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                                      capture_output=True, text=True).stdout.strip()
            inventory = root / "scripts/operational-configuration-inventory.json"
            document = json.loads(inventory.read_text(encoding="utf-8"))
            reviewed = next(entry for entry in document["entries"] if entry["status"] == "pending-review")
            reviewed.update(
                status="converted", classification="operator-configurable",
                setting="example.runtime.policy",
                owner="ravenroot/example/src/main/java/dev/example/RuntimePolicy.java#RuntimePolicy",
                field="DERIVED_MASK",
                bindings=[], default="16", defaultEvidence=[reviewed["id"]],
                validation="positive integer", scope="process", pinning="read once at startup",
                coverage="synthetic test owner only",
                rationale="Synthetic no-op conversion provenance test.",
                conversion={
                    "issue": "#225", "beforeRevision": revision, "afterRevision": revision,
                    "path": "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java",
                    "symbol": "RuntimePolicy",
                    "binding": "RAVENROOT_RUNTIME_LIMIT", "bindingSymbol": "RUNTIME_LIMIT_VARIABLE",
                    "field": "DERIVED_MASK", "beforeExpression": "DERIVED_MASK = 1 << 4",
                    "afterExpression": "RUNTIME_LIMIT_VARIABLE, DERIVED_MASK",
                },
            )
            errors = audit.inventory_errors(root, document, audit.discover(root))
        self.assertTrue(any("revisions must be distinct" in error for error in errors), errors)
        self.assertTrue(any("source is unchanged" in error for error in errors), errors)

    def test_converted_setting_rejects_comment_only_or_unrelated_source_changes(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            subprocess.run(["git", "-c", "user.name=Audit Test", "-c", "user.email=audit@example.invalid",
                            "commit", "-qm", "fixture"], cwd=root, check=True)
            before = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                                    capture_output=True, text=True).stdout.strip()
            source = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
            source.write_text(source.read_text(encoding="utf-8").replace(
                "}\n",
                '  static final String RUNTIME_LIMIT_VARIABLE = "RAVENROOT_RUNTIME_LIMIT";\n'
                '  int unrelated() { return read(RUNTIME_LIMIT_VARIABLE, OTHER_FIELD); }\n'
                "  // DERIVED_MASK review did not change.\n}\n",
            ), encoding="utf-8")
            subprocess.run(["git", "add", "ravenroot"], cwd=root, check=True)
            subprocess.run(["git", "-c", "user.name=Audit Test", "-c", "user.email=audit@example.invalid",
                            "commit", "-qm", "unrelated binding"], cwd=root, check=True)
            after = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                                   capture_output=True, text=True).stdout.strip()
            inventory = root / "scripts/operational-configuration-inventory.json"
            document = json.loads(inventory.read_text(encoding="utf-8"))
            reviewed = next(entry for entry in document["entries"] if entry["role"] == "DERIVED_MASK")
            reviewed.update(
                status="converted", classification="operator-configurable",
                setting="example.runtime.policy", field="DERIVED_MASK",
                owner="ravenroot/example/src/main/java/dev/example/RuntimePolicy.java#RuntimePolicy",
                bindings=["RAVENROOT_RUNTIME_LIMIT"], default="16", defaultEvidence=[reviewed["id"]],
                validation="positive integer", scope="process", pinning="read once at startup",
                coverage="synthetic test owner only", rationale="Synthetic unrelated transition test.",
                conversion={
                    "issue": "#225", "beforeRevision": before, "afterRevision": after,
                    "path": "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java",
                    "symbol": "RuntimePolicy", "binding": "RAVENROOT_RUNTIME_LIMIT",
                    "bindingSymbol": "RUNTIME_LIMIT_VARIABLE", "field": "DERIVED_MASK",
                    "beforeExpression": "static final int DERIVED_MASK = 1 << 4;",
                    "afterExpression": "read(RUNTIME_LIMIT_VARIABLE, OTHER_FIELD)",
                },
            )
            errors = audit.inventory_errors(root, document, audit.discover(root))
        self.assertTrue(any("expressions must both identify the setting field" in error
                            for error in errors), errors)

    def test_refresh_preserves_review_metadata_and_updates_source_line(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            classify_non_pending(root)
            inventory = root / "scripts/operational-configuration-inventory.json"
            before = json.loads(inventory.read_text(encoding="utf-8"))
            reviewed = next(entry for entry in before["entries"] if entry["role"] == "WIRE_VERSION")
            source = root / reviewed["path"]
            source.write_text("\n" + source.read_text(encoding="utf-8"), encoding="utf-8")
            errors, summary = audit.refresh_inventory(
                root, inventory, root / "docs/architecture/operational-configuration-audit.md",
            )
            after = json.loads(inventory.read_text(encoding="utf-8"))
            preserved = next(entry for entry in after["entries"] if entry["id"] == reviewed["id"])
        self.assertEqual([], errors)
        self.assertGreaterEqual(summary["metadataUpdated"], 1)
        self.assertEqual("protocol-or-format-invariant", preserved["classification"])
        self.assertEqual(reviewed["line"] + 1, preserved["line"])

    def test_refresh_requires_explicit_acceptance_and_archives_retired_pending(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            inventory = root / "scripts/operational-configuration-inventory.json"
            source = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
            source.write_text(source.read_text(encoding="utf-8").replace("1 << 4", "1 << 5"), encoding="utf-8")
            refused, plan = audit.refresh_inventory(
                root, inventory, root / "docs/architecture/operational-configuration-audit.md",
            )
            self.assertTrue(any("--accept-retired-pending" in error for error in refused), refused)
            self.assertGreaterEqual(plan["added"], 1)
            accepted, summary = audit.refresh_inventory(
                root, inventory, root / "docs/architecture/operational-configuration-audit.md",
                accept_retired_pending=True,
            )
            document = json.loads(inventory.read_text(encoding="utf-8"))
        self.assertEqual([], accepted)
        self.assertEqual(plan["added"], summary["added"])
        self.assertEqual(plan["retired"], summary["retired"])
        self.assertEqual(summary["retired"], len(document["retiredEntries"]))

    def test_refresh_requires_per_entry_rationale_before_retiring_reviewed_candidate(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            classify_non_pending(root)
            inventory = root / "scripts/operational-configuration-inventory.json"
            document = json.loads(inventory.read_text(encoding="utf-8"))
            reviewed = next(entry for entry in document["entries"] if entry["role"] == "WIRE_VERSION")
            source = root / reviewed["path"]
            source.write_text(source.read_text(encoding="utf-8").replace('"v1"', '"v2"'), encoding="utf-8")
            refused, _plan = audit.refresh_inventory(
                root, inventory, root / "docs/architecture/operational-configuration-audit.md",
                accept_retired_pending=True,
            )
            self.assertTrue(any("retirement.approved=true" in error for error in refused), refused)
            reviewed["retirement"] = {"approved": True, "rationale": "Wire version advanced by reviewed protocol change."}
            inventory.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            accepted, summary = audit.refresh_inventory(
                root, inventory, root / "docs/architecture/operational-configuration-audit.md",
                accept_retired_pending=True,
            )
            refreshed = json.loads(inventory.read_text(encoding="utf-8"))
        self.assertEqual([], accepted)
        self.assertEqual(1, summary["retired"])
        self.assertEqual("Wire version advanced by reviewed protocol change.",
                         refreshed["retiredEntries"][0]["retirementRationale"])

    def test_accept_retired_pending_is_only_valid_for_refresh(self) -> None:
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
            audit.main(["--accept-retired-pending"])
        self.assertEqual(2, failure.exception.code)


if __name__ == "__main__":
    unittest.main()
