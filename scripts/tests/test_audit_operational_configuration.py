from __future__ import annotations

import json
import io
import copy
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest import mock


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

    def test_explicit_time_unit_calls_discover_only_timeout_argument_atoms(self) -> None:
        source = """
            final class RuntimePolicy {
              void waitForWork() throws Exception {
                future.get(17, TimeUnit.SECONDS);
                latch.await(18, java.util.concurrent.TimeUnit.MILLISECONDS);
                semaphore.tryAcquire(19, TimeUnit.MINUTES);
                semaphore.tryAcquire(3, 20, TimeUnit.SECONDS);
                lock.tryLock(21, TimeUnit.SECONDS);
                child.waitFor(22, TimeUnit.SECONDS);
                executor.awaitTermination(23, TimeUnit.SECONDS);
                future.get(
                    Math.max(1, TimeUnit.MILLISECONDS.toNanos(100)),
                    TimeUnit.NANOSECONDS);
                consume(98, future.get(26, TimeUnit.SECONDS), 99);
                int timeout = future.get(29, TimeUnit.SECONDS);
              }
            }
            """
        rows = audit.code_candidates(
            Path("ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"), source, "java")
        timed = [(row[3], row[4]) for row in rows if row[3].startswith("timeunit-")]
        self.assertEqual([
            ("timeunit-get", "17"),
            ("timeunit-await", "18"),
            ("timeunit-tryAcquire", "19"),
            ("timeunit-tryAcquire", "20"),
            ("timeunit-tryLock", "21"),
            ("timeunit-waitFor", "22"),
            ("timeunit-awaitTermination", "23"),
            ("timeunit-get", "1"),
            ("timeunit-get", "100"),
            ("timeunit-get", "26"),
        ], timed)
        self.assertEqual(1, sum(row[4] == "29" for row in rows), rows)
        self.assertFalse(any(row[4] in {"3", "98", "99"} for row in rows), rows)

    def test_time_unit_call_coverage_is_explicit_and_fail_closed(self) -> None:
        source = '''
            final class RuntimePolicy {
              void ignored() throws Exception {
                future.get(24);
                future.get(25, unit);
                future.get(26, SECONDS);
                future.get(27, ChronoUnit.SECONDS);
                latch.await();
                semaphore.tryAcquire();
                semaphore.tryAcquire(28);
                semaphore.tryAcquire(1, 29, 30, TimeUnit.SECONDS);
                // child.waitFor(31, TimeUnit.SECONDS);
                String quoted = "child.waitFor(32, TimeUnit.SECONDS)";
                String block = """
                    child.waitFor(33, TimeUnit.SECONDS);
                    """;
              }
            }
            '''
        rows = audit.code_candidates(
            Path("ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"), source, "java")
        self.assertFalse(any(row[3].startswith("timeunit-") for row in rows), rows)
        malformed = """
            final class BrokenRuntimePolicy {
              void ignored() throws Exception {
                future.get(34, TimeUnit.SECONDS;
              }
            }
            """
        malformed_rows = audit.code_candidates(
            Path("ravenroot/example/src/main/java/dev/example/BrokenRuntimePolicy.java"),
            malformed, "java")
        self.assertFalse(any(row[3].startswith("timeunit-") for row in malformed_rows),
                         malformed_rows)

    def test_cumulative_assignment_discovers_fixed_atom_without_path_exception(self) -> None:
        source = """
            final class RuntimePolicy {
              void check(long responseBytes) {
                long requiredCumulative = responseBytes + 256L;
                long total = responseBytes + 257L;
              }
            }
            """
        rows = audit.code_candidates(
            Path("ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"), source, "java")
        self.assertTrue(any(row[3] == "requiredCumulative" and row[4] == "256L"
                            for row in rows), rows)
        self.assertFalse(any(row[4] == "257L" for row in rows), rows)

    def test_new_timed_call_is_pending_and_literal_mutation_changes_identity(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            source = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
            source.write_text(source.read_text(encoding="utf-8").replace(
                "}\n", "  Object value() throws Exception { "
                "return future.get(37, TimeUnit.SECONDS); }\n}\n"), encoding="utf-8")
            first = next(candidate for candidate in audit.discover(root)
                         if candidate.role == "timeunit-get")
            errors = audit.check(root, root / "scripts/operational-configuration-inventory.json",
                                 root / "docs/architecture/operational-configuration-audit.md",
                                 require_complete=False)
            source.write_text(source.read_text(encoding="utf-8").replace(
                "future.get(37,", "future.get(38,"), encoding="utf-8")
            second = next(candidate for candidate in audit.discover(root)
                          if candidate.role == "timeunit-get")
        self.assertTrue(any("unclassified operational candidate" in error
                            and "timeunit-get" in error for error in errors), errors)
        self.assertEqual("37", first.expression)
        self.assertEqual("38", second.expression)
        self.assertNotEqual(first.id, second.id)

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
            source = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
            source.write_text(source.read_text(encoding="utf-8").replace(
                "package dev.example;",
                'package dev.example;\n// final class CommentOwner {}\n'
                'final class Holder { String text = "final class StringOwner {}";\n'
                'String block = """\nfinal class TextBlockOwner {}\n"""; }',
            ), encoding="utf-8")
            comment_owner = audit.current_source_owner(
                root, "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java#CommentOwner",
            )
            string_owner = audit.current_source_owner(
                root, "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java#StringOwner",
            )
            text_block_owner = audit.current_source_owner(
                root, "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java#TextBlockOwner",
            )
        self.assertIsNone(escaped)
        self.assertIsNone(untracked_owner)
        self.assertIsNone(keyword_owner)
        self.assertIsNone(comment_owner)
        self.assertIsNone(string_owner)
        self.assertIsNone(text_block_owner)
        self.assertIsNotNone(tracked_owner)

    def test_non_java_owner_does_not_claim_unverified_field_membership(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            source = root / "scripts/runtime_config.py"
            source.parent.mkdir(exist_ok=True)
            source.write_text("class RuntimeConfig:\n    retries = 7\n", encoding="utf-8")
            subprocess.run(["git", "add", str(source.relative_to(root))], cwd=root, check=True)
            self.assertIsNotNone(audit.current_source_owner(root, "scripts/runtime_config.py#RuntimeConfig"))
            self.assertFalse(audit.current_source_field(root, "scripts/runtime_config.py#RuntimeConfig", "retries"))

    def test_operator_field_must_be_declared_by_its_typed_owner(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            inventory = root / "scripts/operational-configuration-inventory.json"
            document = json.loads(inventory.read_text(encoding="utf-8"))
            reviewed = next(entry for entry in document["entries"] if entry["status"] == "pending-review")
            reviewed.update(
                status="already-centralized", classification="operator-configurable",
                setting="example.runtime.policy",
                owner="ravenroot/example/src/main/java/dev/example/RuntimePolicy.java#RuntimePolicy",
                field="fieldThatDoesNotExist", bindings=[], default="16",
                defaultEvidence=[reviewed["id"]], validation="positive integer", scope="process",
                pinning="read once at startup", coverage="synthetic test owner only",
                rationale="Synthetic missing-field owner test.",
            )
            errors = audit.inventory_errors(root, document, audit.discover(root))
        self.assertTrue(any("field is not declared by its typed owner" in error for error in errors), errors)

    def test_java_field_owner_excludes_locals_nested_fields_and_record_non_components(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            source = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
            source.write_text(
                "package dev.example;\n"
                "final class Real { void f() { int fake = 1; } class Inner { int nested; } }\n"
                "record Limits(int maxDepth) {}\n",
                encoding="utf-8",
            )
            owner = "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java#Real"
            record_owner = "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java#Limits"
            self.assertFalse(audit.current_source_field(root, owner, "fake"))
            self.assertFalse(audit.current_source_field(root, owner, "nested"))
            self.assertTrue(audit.current_source_field(root, record_owner, "maxDepth"))
            self.assertFalse(audit.current_source_field(root, record_owner, "limits.maxDepth"))
            self.assertFalse(audit.current_source_field(root, record_owner, "Limits"))
            self.assertFalse(audit.current_source_field(root, record_owner, "int"))
            self.assertFalse(audit.current_source_field(root, record_owner, "Bogus.maxDepth"))

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

    def test_revision_helpers_reject_option_shaped_or_abbreviated_ids_before_git(self) -> None:
        with mock.patch.object(audit.subprocess, "run") as run:
            self.assertFalse(audit.commit_exists(ROOT, "--output=/tmp/never-write"))
            self.assertFalse(audit.commit_exists(ROOT, "deadbeef"))
            self.assertIsNone(audit.committed_source(
                ROOT, "--output=/tmp/never-write", "tracked.java"))
            self.assertIsNone(audit.committed_source(ROOT, "deadbeef", "tracked.java"))
            self.assertFalse(audit.revision_is_ancestor(
                ROOT, "--output=/tmp/never-write", "0" * 40))
            self.assertFalse(audit.revision_is_ancestor(ROOT, "0" * 40, "deadbeef"))
        run.assert_not_called()

    def test_migration_history_rejects_unsafe_revision_or_source_path_before_git(self) -> None:
        base = {
            "schemaVersion": audit.SCHEMA_VERSION,
            "entries": [],
            "retiredEntries": [],
            "evidenceRecords": {},
            "resolverAuthorities": {},
        }
        cases = (
            ("--output=/tmp/never-write", "inventory.json"),
            ("deadbeef", "inventory.json"),
            ("0" * 40, "../inventory.json"),
            ("0" * 40, "/tmp/inventory.json"),
        )
        for revision, source_path in cases:
            with self.subTest(revision=revision, source_path=source_path):
                document = copy.deepcopy(base)
                document["migrationHistory"] = [{
                    "fromSchema": 1,
                    "toSchema": audit.SCHEMA_VERSION,
                    "sourceRevision": revision,
                    "sourcePath": source_path,
                    "sourceFileDigest": "0" * 64,
                    "candidateCount": 0,
                    "statusCounts": {},
                    "rationale": "Synthetic invalid migration source.",
                }]
                with mock.patch.object(audit, "tracked_files", return_value=()), \
                        mock.patch.object(audit.subprocess, "run") as run:
                    errors = audit.inventory_errors(Path("/unused"), document, ())
                self.assertTrue(any("migration source is not locally resolvable" in error
                                    for error in errors), errors)
                run.assert_not_called()

    def test_migration_history_accepts_full_revision_and_tracked_json_source(self) -> None:
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            source = root / "inventory.json"
            source_document = {
                "entries": [{"status": "pending-review"}, {"status": "retained"}],
            }
            source.write_text(json.dumps(source_document) + "\n", encoding="utf-8")
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "add", "inventory.json"], cwd=root, check=True)
            subprocess.run([
                "git", "-c", "user.name=Audit Test", "-c", "user.email=audit@example.invalid",
                "commit", "-qm", "migration source",
            ], cwd=root, check=True)
            revision = subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=root, check=True,
                capture_output=True, text=True,
            ).stdout.strip()
            document = {
                "schemaVersion": audit.SCHEMA_VERSION,
                "entries": [],
                "retiredEntries": [],
                "evidenceRecords": {},
                "resolverAuthorities": {},
                "migrationHistory": [{
                    "fromSchema": 1,
                    "toSchema": audit.SCHEMA_VERSION,
                    "sourceRevision": revision,
                    "sourcePath": "inventory.json",
                    "sourceFileDigest": audit.hashlib.sha256(source.read_bytes()).hexdigest(),
                    "candidateCount": 2,
                    "statusCounts": {"pending-review": 1, "retained": 1},
                    "rationale": "Synthetic valid migration source.",
                }],
            }
            errors = audit.inventory_errors(root, document, ())
        self.assertEqual([], errors)

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

    def test_converted_setting_rejects_executable_evidence_inside_java_string(self) -> None:
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
                '  String fake = "read(RUNTIME_LIMIT_VARIABLE, DERIVED_MASK)";\n}\n',
            ), encoding="utf-8")
            subprocess.run(["git", "add", "ravenroot"], cwd=root, check=True)
            subprocess.run(["git", "-c", "user.name=Audit Test", "-c", "user.email=audit@example.invalid",
                            "commit", "-qm", "string evidence"], cwd=root, check=True)
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
                coverage="synthetic test owner only", rationale="Synthetic string transition test.",
                conversion={
                    "issue": "#225", "beforeRevision": before, "afterRevision": after,
                    "path": "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java",
                    "symbol": "RuntimePolicy", "binding": "RAVENROOT_RUNTIME_LIMIT",
                    "bindingSymbol": "RUNTIME_LIMIT_VARIABLE", "field": "DERIVED_MASK",
                    "beforeExpression": "static final int DERIVED_MASK = 1 << 4;",
                    "afterExpression": "read(RUNTIME_LIMIT_VARIABLE, DERIVED_MASK)",
                },
            )
            errors = audit.inventory_errors(root, document, audit.discover(root))
        self.assertTrue(any("afterExpression does not identify the added source" in error
                            for error in errors), errors)

    def test_java_text_block_cannot_supply_conversion_evidence(self) -> None:
        source = '''record Limits(int maxDepth) {
  static final Limits DEFAULTS = new Limits(7);
  String fake = """
      read(LIMIT_VARIABLE, maxDepth)
      """;
}
'''
        executable = audit.normalized(audit.strip_c_comments_and_literals(source))
        self.assertNotIn("read(LIMIT_VARIABLE, maxDepth)", executable)

        escaped = '''record Limits(int maxDepth) {
  String fake = """
      escaped quote: \" and fake terminator: \\"""
      read(LIMIT_VARIABLE, maxDepth)
      """;
  int real() { return read(REAL_LIMIT_VARIABLE, maxDepth); }
}
'''
        executable = audit.normalized(audit.strip_c_comments_and_literals(escaped))
        self.assertNotIn("read(LIMIT_VARIABLE, maxDepth)", executable)
        self.assertIn("read(REAL_LIMIT_VARIABLE, maxDepth)", executable)

    def test_json_schema_reference_edge_carries_resolved_constraint_and_required_state(self) -> None:
        schema = {
            "type": "object", "required": ["retries"], "properties": {
                "retries": {"oneOf": [
                    {"type": "integer", "minimum": 1},
                    {"$ref": "#/definitions/positive"},
                ]},
                "external": {"$ref": "other.json#/definitions/value"},
            },
            "definitions": {"positive": {"oneOf": [
                {"type": "integer", "minimum": 1, "maximum": 9},
                {"$ref": "#/definitions/blank"},
            ]}, "blank": {"type": "string", "maxLength": 0}},
        }
        rows = audit.json_schema_reference_candidates(json.dumps(schema, indent=2))
        retry = next(row for row in rows if row[3] == "/properties/retries/oneOf/1/$ref")
        evidence = json.loads(retry[5])
        external = next(row for row in rows if row[3] == "/properties/external/$ref")
        self.assertEqual("schema-reference-binding", retry[2])
        self.assertEqual("#/definitions/positive", retry[4])
        self.assertTrue(evidence["required"])
        self.assertEqual(9, evidence["resolved"]["oneOf"][0]["maximum"])
        self.assertIn("same-document", json.loads(external[5])["resolutionError"])

        schema["properties"]["retries"]["oneOf"][1]["description"] = "unsupported sibling"
        siblings = audit.json_schema_reference_candidates(json.dumps(schema, indent=2))
        retry_sibling = next(row for row in siblings if row[3] == "/properties/retries/oneOf/1/$ref")
        self.assertIn("unsupported sibling", json.loads(retry_sibling[5])["resolutionError"])
        del schema["properties"]["retries"]["oneOf"][1]["description"]

        schema["definitions"]["blank"] = {"$ref": "#/definitions/positive"}
        cyclic = audit.json_schema_reference_candidates(json.dumps(schema, indent=2))
        retry_cycle = next(row for row in cyclic if row[3] == "/properties/retries/oneOf/1/$ref")
        self.assertIn("cyclic", json.loads(retry_cycle[5])["resolutionError"])

        schema["definitions"]["blank"] = {"type": "string", "maxLength": 0}
        schema["definitions"]["positive"] = {
            "$ref": "#/definitions/blank", "description": "unsupported transitive sibling",
        }
        transitive_sibling = audit.json_schema_reference_candidates(json.dumps(schema, indent=2))
        retry_transitive = next(
            row for row in transitive_sibling if row[3] == "/properties/retries/oneOf/1/$ref")
        self.assertIn("unsupported sibling", json.loads(retry_transitive[5])["resolutionError"])

    def test_java_resolver_methods_are_direct_members_of_the_named_type(self) -> None:
        nested_only = '''final class Outer {
  static final class Inner {
    long whole(Object value) { return helper(value); }
    long helper(Object value) { return 7; }
  }
}
'''
        self.assertIsNone(audit.java_method_span(nested_only, "Outer", "whole"))
        self.assertEqual(set(), audit.java_declared_method_names(nested_only, "Outer"))
        self.assertIsNone(audit.java_reachable_helper_methods(nested_only, "Outer", ("whole",)))

        direct_and_nested = '''final class Outer {
  long whole(Object value) { return helper(value); }
  long helper(Object value) { return 9; }
  static final class Inner {
    long whole(Object value) { return helper(value); }
    long helper(Object value) { return 7; }
  }
}
'''
        span = audit.java_method_span(direct_and_nested, "Outer", "whole")
        self.assertIsNotNone(span)
        self.assertIn("return helper(value)", direct_and_nested[slice(*span)])
        self.assertEqual({"whole", "helper"}, audit.java_declared_method_names(
            direct_and_nested, "Outer"))
        self.assertEqual({"helper"}, audit.java_reachable_helper_methods(
            direct_and_nested, "Outer", ("whole",)))

        overloaded = direct_and_nested.replace(
            "  long whole(Object value) { return helper(value); }",
            "  long whole(Object value) { return helper(value); }\n"
            "  long whole(String value) { return helper(value); }",
        )
        self.assertIsNone(audit.java_method_span(overloaded, "Outer", "whole"))
        self.assertNotIn("whole", audit.java_declared_method_names(overloaded, "Outer"))
        self.assertIsNone(audit.java_reachable_helper_methods(overloaded, "Outer", ("whole",)))

    def test_nested_record_binding_and_default_authorities_use_component_positions(self) -> None:
        policy = '''record Policy(Confirmation confirmation) {
  record Confirmation(long timeoutMillis, int responseBytes) {
    static final Confirmation DEFAULTS = new Confirmation(
        Duration.ofSeconds(5).toMillis(),
        16 * 1024);
  }
}
'''
        configuration = '''final class Configuration {
  Policy.Confirmation confirmation(Object properties, Object environment,
      Policy.Confirmation defaults) {
    return new Policy.Confirmation(
        whole(properties, environment, "ravenroot.timeout", "RAVENROOT_TIMEOUT",
            defaults.timeoutMillis()),
        integer(properties, environment, "ravenroot.response-bytes", "RAVENROOT_RESPONSE_BYTES",
            defaults.responseBytes()));
  }
}
'''
        components = audit.java_record_components(policy, "Confirmation")
        self.assertEqual(("timeoutMillis", "responseBytes"), components)
        timeout = audit.java_constructor_component_call(
            configuration, "Configuration", "confirmation", "Policy.Confirmation",
            components, "timeoutMillis",
        )
        response = audit.java_constructor_component_call(
            configuration, "Configuration", "confirmation", "Policy.Confirmation",
            components, "responseBytes",
        )
        self.assertEqual(
            'whole(properties, environment, "ravenroot.timeout", "RAVENROOT_TIMEOUT", '
            'defaults.timeoutMillis())', timeout[0],
        )
        self.assertEqual(
            'integer(properties, environment, "ravenroot.response-bytes", '
            '"RAVENROOT_RESPONSE_BYTES", defaults.responseBytes())', response[0],
        )
        self.assertEqual(
            "Duration.ofSeconds(5).toMillis()",
            audit.java_record_default_expression(policy, "Confirmation", "DEFAULTS", "timeoutMillis"),
        )
        self.assertEqual(
            "16 * 1024",
            audit.java_record_default_expression(policy, "Confirmation", "DEFAULTS", "responseBytes"),
        )

    def test_constant_backed_default_is_tied_to_ordered_static_final_chain(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            policy_path = root / "ravenroot/example/src/main/java/dev/example/Limits.java"
            policy_path.write_text(
                "package dev.example;\n"
                "record Limits(int maxDepth) {\n"
                "  static final int LIMIT = Shared.MAX_DEPTH;\n"
                "  static final Limits DEFAULTS = new Limits(LIMIT);\n"
                "}\n",
                encoding="utf-8",
            )
            shared_path = root / "ravenroot/example/src/main/java/dev/example/Shared.java"
            shared_path.write_text(
                "package dev.example;\n"
                "final class Shared {\n"
                "  static final int MAX_DEPTH = 7;\n"
                "  static final int UNRELATED = 7;\n"
                "  static final class Nested { static final int MAX_DEPTH = 7; }\n"
                "  void local() { final int MAX_DEPTH = 7; }\n"
                "}\n",
                encoding="utf-8",
            )
            subprocess.run(["git", "add", "ravenroot"], cwd=root, check=True)
            candidates = audit.discover(root)
            discovered = {candidate.id: candidate for candidate in candidates}
            terminal = next(candidate for candidate in candidates
                            if candidate.path.endswith("Shared.java")
                            and candidate.role == "MAX_DEPTH" and candidate.expression == "7")
            unrelated = next(candidate for candidate in candidates
                             if candidate.path.endswith("Shared.java")
                             and candidate.role == "UNRELATED" and candidate.expression == "7")
            setting = "example.max-depth"
            entries = {
                terminal.id: {"id": terminal.id, "setting": setting},
                unrelated.id: {"id": unrelated.id, "setting": setting},
            }

            def hop(path: Path, symbol: str, field: str) -> dict[str, object]:
                source = path.read_text(encoding="utf-8")
                initializer = audit.java_static_final_initializer(source, symbol, field)
                self.assertIsNotNone(initializer)
                expression, start, end = initializer
                ids = audit.candidate_ids_in_source_span(
                    path.relative_to(root), source, start, end, "fixed-declaration", field, discovered)
                return {
                    "owner": f"{path.relative_to(root).as_posix()}#{symbol}", "field": field,
                    "sourceExpression": expression,
                    "initializerDigest": audit.hashlib.sha256(
                        audit.normalized(expression).encode("utf-8")).hexdigest(),
                    "candidateIds": ids,
                }

            authority = {
                "owner": f"{policy_path.relative_to(root).as_posix()}#Limits",
                "instanceSymbol": "DEFAULTS", "field": "maxDepth", "sourceExpression": "LIMIT",
                "candidateIds": [],
                "constantReferenceAuthority": {
                    "kind": "java-static-final-chain-v1",
                    "hops": [hop(policy_path, "Limits", "LIMIT"),
                             hop(shared_path, "Shared", "MAX_DEPTH")],
                },
            }
            contract = {
                "owner": authority["owner"], "field": "maxDepth",
                "defaultEvidence": [terminal.id], "defaultAuthority": authority,
            }
            valid = audit.constant_reference_authority_errors(
                root, setting, contract, authority,
                policy_path.read_text(encoding="utf-8"), discovered, entries)
            self.assertEqual([], valid)

            wrong_atom = copy.deepcopy(contract)
            wrong_atom["defaultEvidence"] = [unrelated.id]
            wrong_atom["defaultAuthority"]["constantReferenceAuthority"]["hops"][-1][
                "candidateIds"] = [unrelated.id]
            atom_errors = audit.constant_reference_authority_errors(
                root, setting, wrong_atom, wrong_atom["defaultAuthority"],
                policy_path.read_text(encoding="utf-8"), discovered, entries)
            self.assertTrue(any("atom multiset" in error for error in atom_errors), atom_errors)

            missing_hop = copy.deepcopy(contract)
            missing_hop["defaultAuthority"]["constantReferenceAuthority"]["hops"] = [
                missing_hop["defaultAuthority"]["constantReferenceAuthority"]["hops"][-1]]
            hop_errors = audit.constant_reference_authority_errors(
                root, setting, missing_hop, missing_hop["defaultAuthority"],
                policy_path.read_text(encoding="utf-8"), discovered, entries)
            self.assertTrue(any("preceding initializer" in error for error in hop_errors), hop_errors)

            wrong_owner = copy.deepcopy(contract)
            wrong_owner["defaultAuthority"]["constantReferenceAuthority"]["hops"][-1]["owner"] = \
                f"{policy_path.relative_to(root).as_posix()}#Limits"
            owner_errors = audit.constant_reference_authority_errors(
                root, setting, wrong_owner, wrong_owner["defaultAuthority"],
                policy_path.read_text(encoding="utf-8"), discovered, entries)
            self.assertTrue(any("preceding initializer" in error for error in owner_errors), owner_errors)

            duplicated = shared_path.read_text(encoding="utf-8").replace(
                "  static final int UNRELATED = 7;",
                "  static final int MAX_DEPTH = 7;\n  static final int UNRELATED = 7;",
            )
            self.assertIsNone(audit.java_static_final_initializer(duplicated, "Shared", "MAX_DEPTH"))

    def test_graph_platform_coverage_is_tied_to_carrier_candidates_and_test_bodies(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            environment = "RAVENROOT_GRAPH_SYNTHETIC_MAX_RETRIES"
            files = {
                "compose.yaml": f"services:\n  app:\n    environment:\n      {environment}: ${{{environment}:-}}\n",
                "deploy/helm/ravenroot/values.yaml": "graph:\n  synthetic:\n    maxRetries: \"\"\n",
                "deploy/helm/ravenroot/templates/deployment.yaml":
                    f"env:\n  - name: {environment}\n    value: {{{{ .Values.graph.synthetic.maxRetries | quote }}}}\n",
                "deploy/helm/ravenroot/values.schema.json": json.dumps({
                    "type": "object", "required": ["graph"], "properties": {
                        "graph": {"type": "object", "required": ["synthetic"], "properties": {
                            "synthetic": {"type": "object", "required": ["maxRetries"], "properties": {
                                "maxRetries": {"x-ravenroot-environment": environment, "oneOf": [
                                    {"type": "integer", "minimum": 1, "maximum": 9},
                                    {"$ref": "#/definitions/graphBlank"},
                                ]},
                            }},
                        }},
                    }, "definitions": {"graphBlank": {"type": "string", "maxLength": 0}},
                }, indent=2),
                "deploy/kubernetes/ravenroot.yaml": f"env:\n  - name: {environment}\n    value: \"\"\n",
                "ravenroot/example/src/test/java/dev/example/GraphCarrierContractTest.java":
                    "final class GraphCarrierContractTest { void carriersMatchJavaAuthority() {} }\n",
                "scripts/tests/test_graph_carriers.sh": f"#!/bin/sh\n# checks {environment}\n",
            }
            for relative, text in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            candidates = audit.discover(root)
            discovered = {candidate.id: candidate for candidate in candidates}

            def ids(path: str, kind: str, expression: str) -> list[str]:
                return [candidate.id for candidate in candidates if candidate.path == path
                        and candidate.kind == kind and candidate.expression == expression]

            coverage = {
                "kind": "graph-platform-carriers-v1", "environment": environment,
                "helmPath": "graph.synthetic.maxRetries",
                "composeCandidateIds": ids("compose.yaml", "environment-binding", environment),
                "helmValueCandidateIds": ids(
                    "deploy/helm/ravenroot/values.yaml", "configuration-scalar", '""'),
                "helmTemplateCandidateIds": ids(
                    "deploy/helm/ravenroot/templates/deployment.yaml", "environment-binding", environment),
                "helmSchemaEnvironmentCandidateIds": ids(
                    "deploy/helm/ravenroot/values.schema.json", "environment-binding", environment),
                "helmSchemaReferenceCandidateIds": ids(
                    "deploy/helm/ravenroot/values.schema.json", "schema-reference-binding",
                    "#/definitions/graphBlank"),
                "rawKubernetesCandidateIds": ids(
                    "deploy/kubernetes/ravenroot.yaml", "environment-binding", environment),
                "contractTestPath": "ravenroot/example/src/test/java/dev/example/GraphCarrierContractTest.java",
                "shellTestPath": "scripts/tests/test_graph_carriers.sh",
            }
            for path_field, digest_field in (("contractTestPath", "contractTestDigest"),
                                             ("shellTestPath", "shellTestDigest")):
                coverage[digest_field] = audit.hashlib.sha256(
                    (root / coverage[path_field]).read_bytes()).hexdigest()
            identifiers = {identifier for field, value in coverage.items()
                           if field.endswith("CandidateIds") for identifier in value}
            entries = {identifier: {"id": identifier, "setting": "graph.synthetic.max-retries"}
                       for identifier in identifiers}
            records = {candidate.evidence_digest: candidate.evidence for candidate in candidates}
            errors = audit.graph_platform_coverage_errors(
                root, "graph.synthetic.max-retries",
                {"bindings": [environment], "coverageEvidence": coverage},
                entries, discovered, records, set(audit.tracked_files(root)),
            )
            self.assertEqual([], errors)

            (root / coverage["contractTestPath"]).write_text(
                "final class GraphCarrierContractTest {}\n", encoding="utf-8")
            drift = audit.graph_platform_coverage_errors(
                root, "graph.synthetic.max-retries",
                {"bindings": [environment], "coverageEvidence": coverage},
                entries, discovered, records, set(audit.tracked_files(root)),
            )
            self.assertTrue(any("body digest has drifted" in error for error in drift), drift)

    def test_paired_binding_default_and_schema_evidence_are_source_verified(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            limits = root / "ravenroot/example/src/main/java/dev/example/RuntimeLimits.java"
            limits.write_text(
                "package dev.example;\n"
                "record RuntimeLimits(int maxRetries, int maxBatch) {\n"
                "  static final RuntimeLimits DEFAULTS = new RuntimeLimits(7, 9);\n"
                "}\n",
                encoding="utf-8",
            )
            config = root / "ravenroot/example/src/main/java/dev/example/RuntimeLimitConfiguration.java"
            authority_call = ('integer(properties, environment, "ravenroot.example.max-retries", '
                              '"RAVENROOT_EXAMPLE_MAX_RETRIES", defaults.maxRetries())')
            second_call = ('integer(properties, environment, "ravenroot.example.max-batch", '
                           '"RAVENROOT_EXAMPLE_MAX_BATCH", defaults.maxBatch())')
            config.write_text(
                "package dev.example;\n"
                "final class RuntimeLimitConfiguration {\n"
                "  Object fromSources(Object properties, Object environment) {\n"
                "    RuntimeLimits defaults = RuntimeLimits.DEFAULTS;\n"
                "    confirmation(properties, environment, defaults);\n"
                "    String diagnostic = \"RAVENROOT_EXAMPLE_MAX_RETRIES\"; "
                f"return new RuntimeLimits({authority_call}, {second_call});\n"
                "  }\n"
                "  RuntimeLimits confirmation(Object properties, Object environment, RuntimeLimits defaults) {\n"
                "    return defaults;\n"
                "  }\n"
                "  int integer(Object properties, Object environment, String property, String variable, int fallback) {\n"
                "    return (int) whole(properties, environment, property, variable, fallback);\n"
                "  }\n"
                "  long whole(Object properties, Object environment, String property, String variable, long fallback) {\n"
                "    String raw = nonBlank(property == null ? variable : property);\n"
                "    if (raw == null) return fallback;\n"
                "    try { return Long.parseLong(raw); } catch (NumberFormatException failure) { throw invalid(); }\n"
                "  }\n"
                "  String nonBlank(String raw) { return raw == null || blank(raw) ? null : raw.strip(); }\n"
                "  boolean blank(String raw) { return raw.isBlank(); }\n"
                "  IllegalArgumentException invalid() { return new IllegalArgumentException(); }\n"
                "}\n",
                encoding="utf-8",
            )
            tests = root / "ravenroot/example/src/test/java/dev/example/RuntimeLimitConfigurationTest.java"
            tests.parent.mkdir(parents=True)
            tests.write_text(
                "package dev.example;\n"
                "class RuntimeLimitConfigurationTest {\n"
                "  void propertyPrecedesEnvironment() {}\n"
                "  void blankPropertyUsesEnvironment() {}\n"
                "  void blankBothUseDefault() {}\n"
                "  void malformedValueRefuses() {}\n"
                "}\n", encoding="utf-8",
            )
            schema_path = root / "deploy/example/values.schema.json"
            schema_path.parent.mkdir(parents=True)
            schema_path.write_text(json.dumps({
                "type": "object", "required": ["maxRetries"],
                "properties": {"maxRetries": {"$ref": "#/definitions/positive"}},
                "definitions": {"positive": {"type": "integer", "minimum": 1, "maximum": 32}},
            }, indent=2), encoding="utf-8")
            subprocess.run(["git", "add", "ravenroot", "deploy"], cwd=root, check=True)
            errors, _summary = audit.refresh_inventory(
                root, root / "scripts/operational-configuration-inventory.json",
                root / "docs/architecture/operational-configuration-audit.md",
            )
            self.assertEqual([], errors)
            inventory = root / "scripts/operational-configuration-inventory.json"
            document = json.loads(inventory.read_text(encoding="utf-8"))
            discovered = audit.discover(root)
            candidates = {candidate.id: candidate for candidate in discovered}

            def one(path_suffix: str, kind: str, expression: str):
                return next(candidate for candidate in discovered
                            if candidate.path.endswith(path_suffix) and candidate.kind == kind
                            and candidate.expression == expression)

            default = one("RuntimeLimits.java", "fixed-declaration", "7")
            prop = one("RuntimeLimitConfiguration.java", "property-binding",
                       "ravenroot.example.max-retries")
            retry_environments = [candidate for candidate in discovered
                                  if candidate.path.endswith("RuntimeLimitConfiguration.java")
                                  and candidate.kind == "environment-binding"
                                  and candidate.expression == "RAVENROOT_EXAMPLE_MAX_RETRIES"]
            env = next(candidate for candidate in retry_environments
                       if "return new RuntimeLimits" in candidate.evidence)
            diagnostic_env = next(candidate for candidate in retry_environments if candidate is not env)
            schema = next(candidate for candidate in discovered
                          if candidate.path.endswith("values.schema.json")
                          and candidate.role == "/properties/maxRetries/$ref")
            setting = "example.runtime.max-retries"
            resolver_id = "synthetic-number-resolver-v1"
            document["resolverAuthorities"] = {resolver_id: {
                "path": str(config.relative_to(root)), "type": "RuntimeLimitConfiguration",
                "integerMethod": "integer", "wholeMethod": "whole",
                "integerBodyDigest": audit.java_method_digest(
                    config.read_text(encoding="utf-8"), "RuntimeLimitConfiguration", "integer"),
                "wholeBodyDigest": audit.java_method_digest(
                    config.read_text(encoding="utf-8"), "RuntimeLimitConfiguration", "whole"),
                "dependencyBodyDigests": {
                    method: audit.java_method_digest(
                        config.read_text(encoding="utf-8"), "RuntimeLimitConfiguration", method)
                    for method in ("nonBlank", "blank", "invalid")
                },
                "testPath": str(tests.relative_to(root)), "testType": "RuntimeLimitConfigurationTest",
                "testMethods": {
                    "propertyPrecedence": "propertyPrecedesEnvironment",
                    "blankPropertyEnvironmentFallback": "blankPropertyUsesEnvironment",
                    "blankSourcesTypedDefault": "blankBothUseDefault",
                    "malformedNonblankRefusal": "malformedValueRefuses",
                },
                "testMethodDigests": {
                    method: audit.java_method_digest(
                        tests.read_text(encoding="utf-8"), "RuntimeLimitConfigurationTest", method)
                    for method in ("propertyPrecedesEnvironment", "blankPropertyUsesEnvironment",
                                   "blankBothUseDefault", "malformedValueRefuses")
                },
                "compositionMethods": {
                    "typedDefaultsFactory": "fromSources", "nestedDefaultsFactory": "confirmation",
                },
                "compositionMethodDigests": {
                    method: audit.java_method_digest(
                        config.read_text(encoding="utf-8"), "RuntimeLimitConfiguration", method)
                    for method in ("fromSources", "confirmation")
                },
                "compositionLinks": {
                    "typedDefaultsInitializer": {
                        "method": "fromSources",
                        "expression": "RuntimeLimits defaults = RuntimeLimits.DEFAULTS",
                    },
                    "nestedDefaultsAccessor": {
                        "method": "fromSources",
                        "expression": "confirmation(properties, environment, defaults)",
                    },
                },
            }}
            binding_authority = {
                "kind": "java-dual-source-constructor-v1",
                "sourceOwner": str(config.relative_to(root)) + "#RuntimeLimitConfiguration",
                "method": "fromSources", "constructorType": "RuntimeLimits",
                "component": "maxRetries", "helper": "integer",
                "propertyCandidateId": prop.id, "property": prop.expression,
                "environmentCandidateId": env.id, "environment": env.expression,
                "defaultAccessor": "defaults.maxRetries()",
                "callDigest": audit.hashlib.sha256(audit.normalized(authority_call).encode()).hexdigest(),
                "resolverAuthority": resolver_id,
            }
            contract = {
                "status": "already-centralized", "classification": "operator-configurable",
                "setting": setting,
                "owner": "ravenroot/example/src/main/java/dev/example/RuntimeLimits.java#RuntimeLimits",
                "field": "maxRetries", "bindings": [prop.expression, env.expression],
                "bindingAuthority": binding_authority, "default": "7 attempts",
                "defaultEvidence": [default.id],
                "defaultAuthority": {
                    "owner": "ravenroot/example/src/main/java/dev/example/RuntimeLimits.java#RuntimeLimits",
                    "instanceSymbol": "DEFAULTS", "field": "maxRetries", "sourceExpression": "7",
                    "candidateIds": [default.id],
                },
                "schemaEvidence": {
                    "candidateId": schema.id, "path": schema.path,
                    "pointer": schema.role, "reference": schema.expression, "required": True,
                },
                "validation": "1..32", "scope": "process", "pinning": "read once at startup",
                "coverage": "synthetic authority and schema", "rationale": "Synthetic paired proof.",
            }
            by_id = {entry["id"]: entry for entry in document["entries"]}
            for candidate in (default, prop, env, schema):
                by_id[candidate.id].update(contract)
            valid = audit.inventory_errors(root, document, discovered)
            self.assertFalse([error for error in valid if setting in error], valid)

            missing_property = copy.deepcopy(document)
            for entry in missing_property["entries"]:
                if entry.get("setting") == setting:
                    entry["bindingAuthority"]["propertyCandidateId"] = "oc-missing"
            missing_errors = audit.inventory_errors(root, missing_property, discovered)
            self.assertTrue(any("authority candidate is absent" in error for error in missing_errors), missing_errors)

            wrong_occurrence = copy.deepcopy(document)
            wrong_rows = {entry["id"]: entry for entry in wrong_occurrence["entries"]}
            source_metadata = {key: wrong_rows[diagnostic_env.id][key]
                               for key in diagnostic_env.source_fields()}
            wrong_rows[diagnostic_env.id].update(copy.deepcopy(contract))
            wrong_rows[diagnostic_env.id].update(source_metadata)
            for entry in wrong_occurrence["entries"]:
                if entry.get("setting") == setting:
                    entry["bindingAuthority"]["environmentCandidateId"] = diagnostic_env.id
            occurrence_errors = audit.inventory_errors(root, wrong_occurrence, discovered)
            self.assertTrue(any("not the literal in its constructor component" in error
                                for error in occurrence_errors), occurrence_errors)

            original_config = config.read_text(encoding="utf-8")
            config.write_text(original_config.replace(
                f"new RuntimeLimits({authority_call}, {second_call})",
                f"new RuntimeLimits({second_call}, {authority_call})",
            ), encoding="utf-8")
            swapped_errors = audit.inventory_errors(root, document, audit.discover(root))
            self.assertTrue(any("not a supported direct integer/whole authority call" in error
                                or "literals/default accessor" in error for error in swapped_errors),
                            swapped_errors)
            config.write_text(original_config, encoding="utf-8")

            missing_default_atom = copy.deepcopy(document)
            for entry in missing_default_atom["entries"]:
                if entry.get("setting") == setting:
                    entry["defaultAuthority"]["candidateIds"] = []
            default_atom_errors = audit.inventory_errors(root, missing_default_atom, discovered)
            self.assertTrue(any("exact initializer atom multiset" in error
                                for error in default_atom_errors), default_atom_errors)

            wrong_resolver = copy.deepcopy(document)
            wrong_resolver["resolverAuthorities"][resolver_id]["wholeBodyDigest"] = "0" * 64
            resolver_errors = audit.inventory_errors(root, wrong_resolver, discovered)
            self.assertTrue(any("whole body digest has drifted" in error for error in resolver_errors),
                            resolver_errors)

            wrong_resolver_test = copy.deepcopy(document)
            wrong_resolver_test["resolverAuthorities"][resolver_id]["testMethodDigests"][
                "malformedValueRefuses"] = "0" * 64
            resolver_test_errors = audit.inventory_errors(root, wrong_resolver_test, discovered)
            self.assertTrue(any("missing precedence/refusal test evidence" in error
                                for error in resolver_test_errors), resolver_test_errors)

            changed_blank_source = config.read_text(encoding="utf-8")
            config.write_text(changed_blank_source.replace("return raw.isBlank();", "return raw.isEmpty();"),
                              encoding="utf-8")
            blank_drift_errors = audit.inventory_errors(root, document, discovered)
            self.assertTrue(any("incomplete or drifted helper dependencies" in error
                                for error in blank_drift_errors), blank_drift_errors)
            config.write_text(changed_blank_source, encoding="utf-8")

            changed_composition_source = config.read_text(encoding="utf-8")
            config.write_text(changed_composition_source.replace(
                "RuntimeLimits defaults = RuntimeLimits.DEFAULTS",
                "RuntimeLimits defaults = RuntimeLimits.ALTERNATE",
            ), encoding="utf-8")
            initializer_errors = audit.inventory_errors(root, document, audit.discover(root))
            self.assertTrue(any("composition method fromSources has drifted" in error
                                for error in initializer_errors), initializer_errors)
            self.assertTrue(any("fallback-composition link typedDefaultsInitializer has drifted" in error
                                for error in initializer_errors), initializer_errors)
            config.write_text(changed_composition_source.replace(
                "confirmation(properties, environment, defaults)",
                "confirmation(properties, environment, RuntimeLimits.DEFAULTS)",
            ), encoding="utf-8")
            delegation_errors = audit.inventory_errors(root, document, audit.discover(root))
            self.assertTrue(any("fallback-composition link nestedDefaultsAccessor has drifted" in error
                                for error in delegation_errors), delegation_errors)
            config.write_text(changed_composition_source, encoding="utf-8")

            for field in ("compositionMethods", "compositionMethodDigests", "compositionLinks"):
                with self.subTest(missing_composition_map=field):
                    missing_map = copy.deepcopy(document)
                    del missing_map["resolverAuthorities"][resolver_id][field]
                    missing_map_errors = audit.resolver_authority_errors(
                        root, missing_map["resolverAuthorities"])
                    self.assertTrue(any("requires" in error for error in missing_map_errors),
                                    missing_map_errors)

            for role in ("typedDefaultsFactory", "nestedDefaultsFactory"):
                with self.subTest(missing_composition_method=role):
                    missing_method = copy.deepcopy(document)
                    authority = missing_method["resolverAuthorities"][resolver_id]
                    method = authority["compositionMethods"].pop(role)
                    authority["compositionMethodDigests"].pop(method)
                    missing_method_errors = audit.resolver_authority_errors(
                        root, missing_method["resolverAuthorities"])
                    self.assertTrue(any("incomplete fallback-composition evidence" in error
                                        for error in missing_method_errors), missing_method_errors)

            for role in ("typedDefaultsInitializer", "nestedDefaultsAccessor"):
                with self.subTest(missing_composition_link=role):
                    missing_link = copy.deepcopy(document)
                    del missing_link["resolverAuthorities"][resolver_id]["compositionLinks"][role]
                    missing_link_errors = audit.resolver_authority_errors(
                        root, missing_link["resolverAuthorities"])
                    self.assertTrue(any("incomplete fallback-composition evidence" in error
                                        for error in missing_link_errors), missing_link_errors)

            aliased_method = copy.deepcopy(document)
            authority = aliased_method["resolverAuthorities"][resolver_id]
            nested_method = authority["compositionMethods"]["nestedDefaultsFactory"]
            authority["compositionMethods"]["nestedDefaultsFactory"] = \
                authority["compositionMethods"]["typedDefaultsFactory"]
            authority["compositionMethodDigests"].pop(nested_method)
            aliased_errors = audit.resolver_authority_errors(
                root, aliased_method["resolverAuthorities"])
            self.assertTrue(any("incomplete fallback-composition evidence" in error
                                for error in aliased_errors), aliased_errors)

            blank_method = copy.deepcopy(document)
            authority = blank_method["resolverAuthorities"][resolver_id]
            method = authority["compositionMethods"]["nestedDefaultsFactory"]
            digest = authority["compositionMethodDigests"].pop(method)
            authority["compositionMethods"]["nestedDefaultsFactory"] = " "
            authority["compositionMethodDigests"][" "] = digest
            blank_method_errors = audit.resolver_authority_errors(
                root, blank_method["resolverAuthorities"])
            self.assertTrue(any("incomplete fallback-composition evidence" in error
                                for error in blank_method_errors), blank_method_errors)

            other_resolver = root / "ravenroot/example/src/main/java/dev/example/OtherResolver.java"
            other_resolver.write_text(config.read_text(encoding="utf-8").replace(
                "RuntimeLimitConfiguration", "OtherResolver"), encoding="utf-8")
            subprocess.run(["git", "add", str(other_resolver.relative_to(root))], cwd=root, check=True)
            wrong_source = copy.deepcopy(document)
            wrong_source["resolverAuthorities"][resolver_id]["path"] = str(other_resolver.relative_to(root))
            wrong_source["resolverAuthorities"][resolver_id]["type"] = "OtherResolver"
            source_errors = audit.inventory_errors(root, wrong_source, discovered)
            self.assertTrue(any("resolver authority must be the binding source type" in error
                                for error in source_errors), source_errors)

            wrong_default = copy.deepcopy(document)
            for entry in wrong_default["entries"]:
                if entry.get("setting") == setting:
                    entry["defaultAuthority"]["sourceExpression"] = "9"
            default_errors = audit.inventory_errors(root, wrong_default, discovered)
            self.assertTrue(any("sourceExpression does not match" in error for error in default_errors),
                            default_errors)

            wrong_schema = copy.deepcopy(document)
            for entry in wrong_schema["entries"]:
                if entry.get("setting") == setting:
                    entry["schemaEvidence"]["required"] = False
            schema_errors = audit.inventory_errors(root, wrong_schema, discovered)
            self.assertTrue(any("metadata has drifted" in error for error in schema_errors), schema_errors)

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

    def test_yaml_duplicate_default_removal_is_tied_to_exact_path_and_typed_default(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            policy = root / "ravenroot/example/src/main/java/dev/example/RuntimeLimits.java"
            policy.write_text(
                "package dev.example;\n"
                "record RuntimeLimits(int maxRetries) {\n"
                "  static final RuntimeLimits DEFAULTS = new RuntimeLimits(7);\n"
                "}\n",
                encoding="utf-8",
            )
            values = root / "deploy/example/values.yaml"
            values.parent.mkdir(parents=True)
            values.write_text(
                "humanTask:\n  maxRetries: 7\ngraph:\n  maxRetries: 7\n",
                encoding="utf-8",
            )
            subprocess.run(["git", "add", "ravenroot", "deploy"], cwd=root, check=True)
            subprocess.run(["git", "-c", "user.name=Audit Test", "-c",
                            "user.email=audit@example.invalid", "commit", "-qm", "numeric default"],
                           cwd=root, check=True)
            before = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                                    capture_output=True, text=True).stdout.strip()
            values.write_text(
                'humanTask:\n  maxRetries: ""\ngraph:\n  maxRetries: ""\n',
                encoding="utf-8",
            )
            subprocess.run(["git", "add", "deploy"], cwd=root, check=True)
            subprocess.run(["git", "-c", "user.name=Audit Test", "-c",
                            "user.email=audit@example.invalid", "commit", "-qm", "blank carrier"],
                           cwd=root, check=True)
            after = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                                   capture_output=True, text=True).stdout.strip()
            owner = "ravenroot/example/src/main/java/dev/example/RuntimeLimits.java#RuntimeLimits"
            setting = "example.runtime.max-retries"
            active = {"oc-active": {
                "id": "oc-active", "status": "already-centralized",
                "classification": "operator-configurable", "setting": setting,
                "owner": owner, "field": "maxRetries",
                "defaultAuthority": {
                    "owner": owner, "instanceSymbol": "DEFAULTS", "field": "maxRetries",
                    "sourceExpression": "7", "candidateIds": [],
                },
            }}
            retired = {
                "id": "oc-retired", "path": "deploy/example/values.yaml", "line": 2,
                "symbol": "module", "kind": "configuration-scalar", "role": "maxRetries",
                "expression": "7", "setting": setting,
            }
            removal = {
                "kind": "yaml-default-authority-v1", "issue": "#225",
                "beforeRevision": before, "afterRevision": after,
                "yamlPath": "humanTask.maxRetries", "beforeValue": "7", "afterValue": '\"\"',
                "replacementOwner": owner, "replacementField": "maxRetries",
                "replacementInstanceSymbol": "DEFAULTS", "replacementDefaultExpression": "7",
            }
            valid = audit.yaml_default_removal_errors(root, retired["id"], retired, removal, active)
            self.assertEqual([], valid)

            wrong_path = copy.deepcopy(removal)
            wrong_path["yamlPath"] = "graph.maxRetries"
            path_errors = audit.yaml_default_removal_errors(
                root, retired["id"], retired, wrong_path, active)
            self.assertTrue(any("retired source line" in error for error in path_errors), path_errors)

            wrong_field = copy.deepcopy(removal)
            wrong_field["replacementField"] = "otherRetries"
            field_errors = audit.yaml_default_removal_errors(
                root, retired["id"], retired, wrong_field, active)
            self.assertTrue(any("no active typed replacement" in error for error in field_errors), field_errors)

            wrong_before = copy.deepcopy(removal)
            wrong_before["beforeValue"] = "9"
            value_errors = audit.yaml_default_removal_errors(
                root, retired["id"], retired, wrong_before, active)
            self.assertTrue(any("beforeValue" in error for error in value_errors), value_errors)

    def test_accept_retired_pending_is_only_valid_for_refresh(self) -> None:
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
            audit.main(["--accept-retired-pending"])
        self.assertEqual(2, failure.exception.code)


if __name__ == "__main__":
    unittest.main()
