from __future__ import annotations

import json
import hashlib
import io
import copy
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from collections import Counter
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


REAPPEARANCE_PRIOR_REVISION = "0596c618ac2cb7c55851c6891f7321b407e9686c"
REAPPEARANCE_TARGET_REVISION = "2ab60f03cb1563a69d245e2b81b5f7cb3b056f95"
REAPPEARANCE_CHECKPOINT_REVISION = "c2317c6643378e3f82a4f46d638ac03a157b0461"


def committed_inventory(revision: str) -> tuple[dict[str, object], bytes]:
    relative = audit.INVENTORY.relative_to(audit.ROOT).as_posix()
    result = subprocess.run(
        ["git", "show", f"{revision}:{relative}"], cwd=ROOT, check=True,
        capture_output=True,
    )
    return json.loads(result.stdout), result.stdout


def production_reappearance_fixture() -> tuple[
        dict[str, object], tuple[audit.Candidate, ...], list[dict[str, object]],
        dict[str, object], bytes, dict[str, object], bytes]:
    document = json.loads(audit.INVENTORY.read_text(encoding="utf-8"))
    candidates = audit.discover(ROOT)
    prior, prior_raw = committed_inventory(REAPPEARANCE_PRIOR_REVISION)
    checkpoint, checkpoint_raw = committed_inventory(REAPPEARANCE_CHECKPOINT_REVISION)
    active_ids = {entry["id"] for entry in document["entries"]}
    retired = {entry["id"]: entry for entry in document["retiredEntries"]}
    collisions = sorted(active_ids & set(retired))
    candidate_by_id = {candidate.id: candidate for candidate in candidates}
    records = [{
        "kind": audit.REAPPEARANCE_KIND,
        "issue": "#317",
        "candidateId": identifier,
        "approved": True,
        "rationale": (
            f"The pending schema atom {retired[identifier]['expression']} reappeared with the same "
            "normalized identity after the closed Helm schema moved its source line; this approval "
            "does not inherit semantic review from the retired row."
        ),
        "priorInventoryRevision": REAPPEARANCE_PRIOR_REVISION,
        "priorInventoryPath": audit.INVENTORY.relative_to(audit.ROOT).as_posix(),
        "priorInventoryDigest": audit.hashlib.sha256(prior_raw).hexdigest(),
        "targetSourceRevision": REAPPEARANCE_TARGET_REVISION,
        "identityCheckpointRevision": REAPPEARANCE_CHECKPOINT_REVISION,
        "identityCheckpointInventoryDigest": audit.hashlib.sha256(checkpoint_raw).hexdigest(),
        "reconciliationId": "issue-317-closed-helm-contract-v1",
        "retiredPayloadDigest": audit.canonical_json_digest(retired[identifier]),
        "currentEvidenceDigest": candidate_by_id[identifier].evidence_digest,
    } for identifier in collisions]
    return document, candidates, records, prior, prior_raw, checkpoint, checkpoint_raw


def external_io_reviewed_entries(
        candidates: tuple[audit.Candidate, ...] | list[audit.Candidate],
        authority: dict[str, object]) -> dict[str, dict[str, object]]:
    """Build truthful reviewed metadata for the closed source-derived #319 cohort."""
    discovered = {candidate.id: candidate for candidate in candidates}
    contracts = {identifier: contract for contract in authority["contracts"]
                 for identifier in contract["candidateIds"]}
    retained = {identifier: partition for partition in authority["semanticPartitions"]
                for identifier in partition["candidateIds"]}
    entries: dict[str, dict[str, object]] = {}
    for identifier, contract in contracts.items():
        entry = discovered[identifier].inventory_entry()
        entry.update(
            status="already-centralized", classification="operator-configurable",
            setting=contract["setting"], owner=contract["owner"], field=contract["field"],
            bindings=contract["bindings"], default=contract["defaultExpression"],
            defaultEvidence=contract["defaultCandidateIds"],
            validation="The typed policy validates the value before external I/O is admitted.",
            scope=contract["scope"], pinning=contract["pinning"],
            coverage="Source-derived owner, default, binding, consumer and test evidence.",
            rationale="The closed platform external-I/O policy is the source authority.",
            externalIoPolicyAuthority=audit.EXTERNAL_IO_POLICY_AUTHORITY_ID,
        )
        entries[identifier] = entry
    for identifier, partition in retained.items():
        entry = discovered[identifier].inventory_entry()
        entry.update(
            status=partition["status"], classification=partition["classification"],
            rationale=("The closed external-I/O source proof assigns this exact candidate to "
                       f"the {partition['semanticPartition']} partition."),
            externalIoPolicyAuthority=audit.EXTERNAL_IO_POLICY_AUTHORITY_ID,
        )
        if partition["classification"] == "published-contract-description":
            entry["retainedAuthority"] = audit.ENVIRONMENT_REFERENCE_AUTHORITY_ID
        entries[identifier] = entry
    return entries


class OperationalConfigurationAuditTest(unittest.TestCase):
    def test_final_review_authority_applies_one_exact_source_anchored_partition(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            proof = root / "runtime-policy.txt"
            proof.write_text("bounded consumer\n", encoding="utf-8")
            source_entry = {
                "id": "oc-final", "path": "runtime-policy.txt", "line": 1,
                "symbol": "LIMIT", "kind": "fixed-declaration", "role": "LIMIT",
                "expression": "8", "expressionDigest": "expression",
                "evidenceDigest": "evidence", "surface": "script",
                "status": "pending-review", "classification": None,
            }
            source_document = {"entries": [source_entry]}
            source_raw = (json.dumps(source_document) + "\n").encode()
            authority = {
                "schemaVersion": 1,
                "id": audit.FINAL_REVIEW_AUTHORITY_ID,
                "issue": "#321",
                "sourceRevision": "a" * 40,
                "sourceInventoryPath": "scripts/operational-configuration-inventory.json",
                "sourceInventoryDigest": hashlib.sha256(source_raw).hexdigest(),
                "candidateCount": 1,
                "candidateSetDigest": audit.candidate_set_digest(["oc-final"]),
                "groups": [{
                    "id": "bounded-runtime", "title": "Bounded runtime", "owner": "#321",
                    "metadata": {
                        "status": "retained", "classification": "security-ceiling-or-default",
                        "rationale": "The consumer bounds retained state before admitting another item.",
                    },
                    "semanticDecision": "Eight is a fixed memory-safety ceiling at this local boundary.",
                    "sourceAndConsumerProof": [{
                        "path": "runtime-policy.txt",
                        "digest": hashlib.sha256(proof.read_bytes()).hexdigest(),
                        "assertion": "The source contains the bound and its consumer.",
                    }],
                    "candidateCount": 1,
                    "candidateSetDigest": audit.candidate_set_digest(["oc-final"]),
                    "candidateIds": ["oc-final"],
                }],
            }
            authority_path = root / audit.FINAL_REVIEW.relative_to(audit.ROOT)
            authority_path.parent.mkdir(parents=True)
            authority_path.write_text(json.dumps(authority) + "\n", encoding="utf-8")
            approved = {
                "status": "retained", "classification": "security-ceiling-or-default",
                "rationale": "The consumer bounds retained state before admitting another item.",
                "finalReviewAuthority": audit.FINAL_REVIEW_AUTHORITY_ID,
                "finalReviewGroup": "bounded-runtime", "remediationOwner": "#321",
            }
            document = {"entries": [{**source_entry, **approved}], "finalReviewAuthority": {
                "id": audit.FINAL_REVIEW_AUTHORITY_ID,
                "path": audit.FINAL_REVIEW.relative_to(audit.ROOT).as_posix(),
                "digest": hashlib.sha256(authority_path.read_bytes()).hexdigest(),
            }}
            expected = {"oc-final": {"status": "pending-review", "classification": None}}
            with mock.patch.object(audit, "committed_json", return_value=(source_document, source_raw)), \
                    mock.patch.object(audit, "revision_is_ancestor", return_value=True), \
                    mock.patch.object(audit, "tracked_files", return_value=(Path("runtime-policy.txt"),)):
                self.assertEqual([], audit.final_review_authority_errors(root, document, expected))
            self.assertEqual(approved, expected["oc-final"])

            missing_reference = copy.deepcopy(document)
            missing_reference.pop("finalReviewAuthority")
            self.assertEqual(
                ["final review authority reference is missing while inventory rows claim it"],
                audit.final_review_authority_errors(root, missing_reference, {}),
            )
            for field, value in (
                    ("classification", "derived-or-calculated"),
                    ("finalReviewAuthority", "issue-321-deleted-marker")):
                mutated = copy.deepcopy(document)
                mutated["entries"][0][field] = value
                candidate_expected = {
                    "oc-final": {"status": "pending-review", "classification": None}}
                with mock.patch.object(
                        audit, "committed_json", return_value=(source_document, source_raw)), \
                        mock.patch.object(audit, "revision_is_ancestor", return_value=True), \
                        mock.patch.object(
                            audit, "tracked_files", return_value=(Path("runtime-policy.txt"),)):
                    errors = audit.final_review_authority_errors(
                        root, mutated, candidate_expected)
                self.assertIn(
                    "final review candidate oc-final lost its marker or approved classification",
                    errors,
                )

            retired = copy.deepcopy(document)
            retired["entries"] = []
            retired["retiredEntries"] = [{
                **source_entry,
                "retirementRationale": "The reviewed source atom was removed by typed centralization.",
            }]
            retired_expected = {
                "oc-final": {"status": "pending-review", "classification": None}}
            with mock.patch.object(audit, "committed_json", return_value=(source_document, source_raw)), \
                    mock.patch.object(audit, "revision_is_ancestor", return_value=True), \
                    mock.patch.object(
                        audit, "tracked_files", return_value=(Path("runtime-policy.txt"),)):
                self.assertEqual([], audit.final_review_authority_errors(
                    root, retired, retired_expected))

            fake_retirement = copy.deepcopy(document)
            fake_retirement["entries"][0]["classification"] = "derived"
            fake_retirement["retiredEntries"] = retired["retiredEntries"]
            fake_expected = {"oc-final": {"status": "pending-review", "classification": None}}
            with mock.patch.object(audit, "committed_json", return_value=(source_document, source_raw)), \
                    mock.patch.object(audit, "revision_is_ancestor", return_value=True), \
                    mock.patch.object(
                        audit, "tracked_files", return_value=(Path("runtime-policy.txt"),)):
                errors = audit.final_review_authority_errors(root, fake_retirement, fake_expected)
            self.assertIn(
                "final review candidate oc-final lost its marker or approved classification", errors)

    def test_final_review_authority_rejects_duplicate_membership_and_proof_drift(self) -> None:
        document = json.loads(audit.INVENTORY.read_text(encoding="utf-8"))
        reference = document.get("finalReviewAuthority")
        if not isinstance(reference, dict):
            self.skipTest("production final review authority is not installed yet")
        authority = json.loads(audit.FINAL_REVIEW.read_text(encoding="utf-8"))
        duplicate = copy.deepcopy(authority["groups"][0])
        duplicate["id"] += "-duplicate"
        authority["groups"].append(duplicate)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / audit.FINAL_REVIEW.relative_to(audit.ROOT)
            target.parent.mkdir(parents=True)
            target.write_text(json.dumps(authority) + "\n", encoding="utf-8")
            bad_document = copy.deepcopy(document)
            bad_document["finalReviewAuthority"]["digest"] = hashlib.sha256(target.read_bytes()).hexdigest()
            expected = {str(entry["id"]): audit.candidate_semantic_payload(entry)
                        for entry in document["entries"]}
            source_document, source_raw = committed_inventory(authority["sourceRevision"])
            with mock.patch.object(audit, "committed_json", return_value=(source_document, source_raw)), \
                    mock.patch.object(audit, "revision_is_ancestor", return_value=True), \
                    mock.patch.object(audit, "tracked_files", return_value=tuple(
                        Path(item["path"]) for group in authority["groups"]
                        for item in group["sourceAndConsumerProof"])):
                errors = audit.final_review_authority_errors(root, bad_document, expected)
            self.assertTrue(any("overlaps another semantic authority" in error for error in errors))

    def test_helm_authority_closes_values_schema_templates_runtime_tests_and_candidates(self) -> None:
        candidates = audit.discover(ROOT)
        authority = audit.helm_authority_from_source(ROOT, candidates)
        self.assertIsNotNone(authority)
        assert authority is not None
        self.assertEqual({
            "apiVersion": "v2", "name": "ravenroot", "type": "application",
        }, {field: authority["chartMetadata"][field]
            for field in ("apiVersion", "name", "type")})
        self.assertEqual(authority["chartMetadata"]["version"],
                         authority["chartMetadata"]["appVersion"])
        chart_candidate_ids = {
            candidate.id for candidate in candidates if candidate.path == audit.HELM_CHART_PATH}
        contract_candidate_ids = {
            identifier for contract in authority["contracts"]
            for identifier in contract["candidateIds"]}
        self.assertTrue(chart_candidate_ids)
        self.assertTrue(chart_candidate_ids <= set(authority["candidateIds"]))
        self.assertTrue(chart_candidate_ids.isdisjoint(contract_candidate_ids))
        entries = {
            identifier: {
                "id": identifier, "setting": contract["setting"],
                "owner": contract["owner"], "field": contract["field"],
                "bindings": contract["bindings"], "default": contract["defaultDisplay"],
                "validation": contract["validation"], "scope": contract["scope"],
                "pinning": contract["pinning"], "coverage": contract["coverage"],
                "helmAuthority": audit.HELM_AUTHORITY_ID,
            }
            for contract in authority["contracts"] for identifier in contract["candidateIds"]
        }
        self.assertEqual([], audit.helm_authority_errors(
            ROOT, {audit.HELM_AUTHORITY_ID: authority}, entries, candidates))
        altered = copy.deepcopy(authority)
        altered["candidateIds"] = altered["candidateIds"][:-1]
        self.assertTrue(audit.helm_authority_errors(
            ROOT, {audit.HELM_AUTHORITY_ID: altered}, entries, candidates))
        altered = copy.deepcopy(authority)
        altered["candidateIds"].append("oc-foreign")
        self.assertTrue(audit.helm_authority_errors(
            ROOT, {audit.HELM_AUTHORITY_ID: altered}, entries, candidates))
        altered = copy.deepcopy(authority)
        altered["candidateIds"].append(altered["candidateIds"][0])
        self.assertTrue(audit.helm_authority_errors(
            ROOT, {audit.HELM_AUTHORITY_ID: altered}, entries, candidates))
        wrong_entries = copy.deepcopy(entries)
        next(iter(wrong_entries.values()))["owner"] = "Example.java#Example"
        self.assertTrue(audit.helm_authority_errors(
            ROOT, {audit.HELM_AUTHORITY_ID: authority}, wrong_entries, candidates))
        wrong_entries = copy.deepcopy(entries)
        next(iter(wrong_entries.values()))["setting"] = "deployment.unsupported"
        self.assertTrue(audit.helm_authority_errors(
            ROOT, {audit.HELM_AUTHORITY_ID: authority}, wrong_entries, candidates))

        self.assertTrue(audit.helm_authority_errors(ROOT, None, {}, candidates))

    def test_helm_authority_distinguishes_absent_partial_and_invalid_charts(self) -> None:
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            self.assertEqual([], audit.helm_authority_errors(root, None, {}, ()))
            self.assertTrue(audit.helm_authority_errors(
                root, {audit.HELM_AUTHORITY_ID: {}}, {}, ()))
            self.assertTrue(audit.helm_authority_errors(
                root, None, {"oc-owned": {"helmAuthority": audit.HELM_AUTHORITY_ID}}, ()))

        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            target = root / audit.HELM_VALUES_PATH
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / audit.HELM_VALUES_PATH, target)
            errors = audit.helm_authority_errors(root, None, {}, ())
            self.assertTrue(any("violate the closed authority" in error for error in errors), errors)

        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            target = root / audit.HELM_CHART_PATH
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("apiVersion: v2\n", encoding="utf-8")
            errors = audit.helm_authority_errors(root, None, {}, ())
            self.assertTrue(any("violate the closed authority" in error for error in errors), errors)

        candidates = audit.discover(ROOT)
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            authority = audit.helm_authority_from_source(ROOT, candidates)
            self.assertIsNotNone(authority)
            assert authority is not None
            for path in {audit.HELM_CHART_PATH, audit.HELM_RELEASE_CONTRACT_PATH,
                         audit.HELM_VALUES_PATH, audit.HELM_SCHEMA_PATH,
                         *audit.HELM_TEMPLATE_PATHS, *audit.HELM_TEST_ROLES,
                         *authority["timeoutRuntime"].get("sourcePaths", [authority["timeoutRuntime"]["path"]])}:
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / path, target)
            schema = root / audit.HELM_SCHEMA_PATH
            schema.write_text(schema.read_text(encoding="utf-8").replace(
                '"const": true', '"const": false', 1), encoding="utf-8")
            errors = audit.helm_authority_errors(root, None, {}, candidates)
            self.assertTrue(any("violate the closed authority" in error for error in errors), errors)

        chart_mutations = (
            ("apiVersion: v2", "apiVersion: v1"),
            ("name: ravenroot", "name: another-chart"),
            ("type: application", "type: library"),
            ("description: Optional, single-replica Ravenroot deployment for Kubernetes and Minikube.\n", ""),
            ("version: 0.1.0-alpha.1\n", ""),
            ('appVersion: "0.1.0-alpha.1"\n', ""),
            ('kubeVersion: ">=1.25.0-0"\n', ""),
            ("apiVersion: v2", "apiVersion: ["),
            ("description: Optional, single-replica Ravenroot deployment for Kubernetes and Minikube.",
             "description: broken: metadata"),
            ("description: Optional, single-replica Ravenroot deployment for Kubernetes and Minikube.",
             "description: 'broken' metadata'"),
        )
        authority = audit.helm_authority_from_source(ROOT, candidates)
        self.assertIsNotNone(authority)
        assert authority is not None
        required = {audit.HELM_CHART_PATH, audit.HELM_RELEASE_CONTRACT_PATH,
                    audit.HELM_VALUES_PATH, audit.HELM_SCHEMA_PATH,
                    *audit.HELM_TEMPLATE_PATHS, *audit.HELM_TEST_ROLES,
                    *authority["timeoutRuntime"].get("sourcePaths", [authority["timeoutRuntime"]["path"]])}
        for before, after in chart_mutations:
            with self.subTest(chart_mutation=before):
                with tempfile.TemporaryDirectory() as location:
                    root = Path(location)
                    for path in required:
                        target = root / path
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(ROOT / path, target)
                    self.assertEqual(authority, audit.helm_authority_from_source(root, candidates))
                    chart = root / audit.HELM_CHART_PATH
                    source = chart.read_text(encoding="utf-8")
                    self.assertIn(before, source)
                    chart.write_text(source.replace(before, after, 1), encoding="utf-8")
                    errors = audit.helm_authority_errors(root, None, {}, candidates)
                    self.assertTrue(any("violate the closed authority" in error
                                        for error in errors), errors)

        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            for path in required:
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / path, target)
            chart = root / audit.HELM_CHART_PATH
            source = chart.read_text(encoding="utf-8")
            source = source.replace("version: 0.1.0-alpha.1",
                                    "version: 0.1.0-alpha.1+build.7", 1)
            source = source.replace('appVersion: "0.1.0-alpha.1"',
                                    'appVersion: "0.1.0-alpha.1+build.7"', 1)
            chart.write_text(source, encoding="utf-8")
            self.assertIsNotNone(audit.helm_authority_from_source(root, candidates))

    def test_helm_authority_rejects_source_contract_and_executable_evidence_drift(self) -> None:
        candidates = audit.discover(ROOT)
        authority = audit.helm_authority_from_source(ROOT, candidates)
        self.assertIsNotNone(authority)
        assert authority is not None
        entries = {
            identifier: {
                "id": identifier, "setting": contract["setting"],
                "owner": contract["owner"], "field": contract["field"],
                "bindings": contract["bindings"], "default": contract["defaultDisplay"],
                "validation": contract["validation"], "scope": contract["scope"],
                "pinning": contract["pinning"], "coverage": contract["coverage"],
                "helmAuthority": audit.HELM_AUTHORITY_ID,
            }
            for contract in authority["contracts"] for identifier in contract["candidateIds"]
        }
        mutations = (
            (audit.HELM_VALUES_PATH, "programTimeoutMs: 15000", "programTimeoutMs: 15001"),
            (audit.HELM_VALUES_PATH, "runAsNonRoot: true", "runAsNonRoot: false"),
            (audit.HELM_VALUES_PATH, "allowPrivilegeEscalation: false", "allowPrivilegeEscalation: true"),
            (audit.HELM_VALUES_PATH, "readOnlyRootFilesystem: true", "readOnlyRootFilesystem: false"),
            (audit.HELM_SCHEMA_PATH, '"maximum": 300000', '"maximum": 300001'),
            (audit.HELM_SCHEMA_PATH,
             '"pattern": "^[\\u0009-\\u000D\\u001C-\\u0020\\u1680',
             '"pattern": "^[\\u0009-\\u000D\\u0020'),
            (audit.HELM_SCHEMA_PATH, '"additionalProperties": false', '"additionalProperties": true'),
            (audit.HELM_SCHEMA_PATH, '"required": ["replicaCount"', '"required": ["image"'),
            (audit.HELM_SCHEMA_PATH, '"enum": ["RuntimeDefault"]',
             '"enum": ["RuntimeDefault", "Unconfined"]'),
            (audit.HELM_SCHEMA_PATH, '"runAsNonRoot": { "type": "boolean", "const": true }',
             '"runAsNonRoot": { "type": "boolean" }'),
            (audit.HELM_SCHEMA_PATH,
             '"allowPrivilegeEscalation": { "type": "boolean", "const": false }',
             '"allowPrivilegeEscalation": { "type": "boolean" }'),
            (audit.HELM_SCHEMA_PATH,
             '"readOnlyRootFilesystem": { "type": "boolean", "const": true }',
             '"readOnlyRootFilesystem": { "type": "boolean" }'),
            (audit.HELM_TEMPLATE_PATHS[1],
             '          resources:\n            {{- toYaml .Values.resources | nindent 12 }}',
             '      resources:\n        {{- toYaml .Values.resources | nindent 8 }}'),
            (audit.HELM_TEMPLATE_PATHS[1], '- name: RAVENROOT_PROGRAM_TIMEOUT_MS',
             '# - name: RAVENROOT_PROGRAM_TIMEOUT_MS'),
            ("scripts/tests/test_program_timeout_helm_contract.sh",
             "for invalid in 99 300001; do", "for invalid in 99; do"),
            ("scripts/tests/test_helm_values_contract.sh",
             '--set-string image.tag=release-test \\\n  --set-string image.digest= \\\n  >"$TEMP_DIR/tag-only.yaml"',
             '--set-string image.digest= \\\n  >"$TEMP_DIR/tag-only.yaml"'),
            ("scripts/tests/test_helm_values_contract.sh",
             '--set-string image.digest= \\\n  >"$TEMP_DIR/tag-only.yaml"',
             '--set-string image.digest=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \\\n  >"$TEMP_DIR/tag-only.yaml"'),
            ("scripts/tests/test_helm_values_contract.sh",
             'if tag_image != "registry.example.test/ravenroot:release-test":',
             'if False:'),
            ("scripts/tests/test_helm_values_contract.sh",
             "securityContext.readOnlyRootFilesystem=false \\",
             "securityContext.readOnlyRootFilesystem=not-a-boolean \\",),
            (authority["timeoutRuntime"]["resolverPath"],
             'int timeout = integer(', '// int timeout = integer('),
            (authority["timeoutRuntime"]["path"],
             'static GraalVmProgramRuntime fromEnvironment(java.util.Map<String, String> environment)',
             'static GraalVmProgramRuntime fromChangedEnvironment(java.util.Map<String, String> environment)'),
        )
        for relative, before, after in mutations:
            with self.subTest(relative=relative, before=before):
                with tempfile.TemporaryDirectory() as location:
                    root = Path(location)
                    required = {audit.HELM_CHART_PATH, audit.HELM_RELEASE_CONTRACT_PATH,
                                audit.HELM_VALUES_PATH,
                                audit.HELM_SCHEMA_PATH,
                                *audit.HELM_TEMPLATE_PATHS, *audit.HELM_TEST_ROLES,
                                *authority["timeoutRuntime"].get("sourcePaths", [authority["timeoutRuntime"]["path"]])}
                    for path in required:
                        target = root / path
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(ROOT / path, target)
                    self.assertEqual(authority, audit.helm_authority_from_source(root, candidates))
                    target = root / relative
                    source = target.read_text(encoding="utf-8")
                    self.assertIn(before, source)
                    target.write_text(source.replace(before, after, 1), encoding="utf-8")
                    self.assertTrue(audit.helm_authority_errors(
                        root, {audit.HELM_AUTHORITY_ID: authority}, entries, candidates))

        for removed in (audit.HELM_CHART_PATH, audit.HELM_TEMPLATE_PATHS[0],
                        "scripts/tests/test_helm_values_contract.sh",
                        authority["timeoutRuntime"]["path"]):
            with self.subTest(removed=removed):
                with tempfile.TemporaryDirectory() as location:
                    root = Path(location)
                    required = {audit.HELM_CHART_PATH, audit.HELM_RELEASE_CONTRACT_PATH,
                                audit.HELM_VALUES_PATH,
                                audit.HELM_SCHEMA_PATH,
                                *audit.HELM_TEMPLATE_PATHS, *audit.HELM_TEST_ROLES,
                                *authority["timeoutRuntime"].get("sourcePaths", [authority["timeoutRuntime"]["path"]])}
                    for path in required:
                        target = root / path
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(ROOT / path, target)
                    self.assertEqual(authority, audit.helm_authority_from_source(root, candidates))
                    (root / removed).unlink()
                    self.assertTrue(audit.helm_authority_errors(
                        root, {audit.HELM_AUTHORITY_ID: authority}, entries, candidates))

        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            for path in {audit.HELM_CHART_PATH, audit.HELM_RELEASE_CONTRACT_PATH,
                         audit.HELM_VALUES_PATH,
                         *audit.HELM_TEMPLATE_PATHS,
                         *audit.HELM_TEST_ROLES, *authority["timeoutRuntime"].get("sourcePaths", [authority["timeoutRuntime"]["path"]])}:
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / path, target)
            target = root / audit.HELM_SCHEMA_PATH
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text('{"type":"object","properties":[]}', encoding="utf-8")
            self.assertTrue(audit.helm_authority_errors(
                root, {audit.HELM_AUTHORITY_ID: authority}, entries, candidates))

    def test_inventory_errors_routes_only_exact_helm_owned_rows_through_helm_proof(self) -> None:
        candidates = audit.discover(ROOT)
        authority = audit.helm_authority_from_source(ROOT, candidates)
        self.assertIsNotNone(authority)
        assert authority is not None
        document = copy.deepcopy(audit.load_inventory(allow_previous_schema=True))
        document["entries"] = []
        document["evidenceRecords"] = {
            candidate.evidence_digest: candidate.evidence for candidate in candidates}
        for candidate in candidates:
            entry = {**candidate.source_fields(), "status": "pending-review", "classification": None}
            if candidate.surface == "test-fixture":
                entry.update(status="retained", classification="test-fixture")
            document["entries"].append(entry)
        by_id = {entry["id"]: entry for entry in document["entries"]}
        for contract in authority["contracts"]:
            for identifier in contract["candidateIds"]:
                by_id[identifier].update(
                    status="already-centralized", classification="operator-configurable",
                    setting=contract["setting"], owner=contract["owner"], field=contract["field"],
                    bindings=contract["bindings"], default=contract["defaultDisplay"],
                    defaultEvidence=contract["candidateIds"], validation=contract["validation"],
                    scope=contract["scope"], pinning=contract["pinning"],
                    coverage=contract["coverage"], helmAuthority=audit.HELM_AUTHORITY_ID,
                    rationale="The closed Helm values authority proves this deployment setting.",
                )
        document["helmAuthorities"] = {audit.HELM_AUTHORITY_ID: authority}
        errors = audit.inventory_errors(ROOT, document, candidates)
        self.assertFalse(any("Helm" in error for error in errors), errors)

        removed = copy.deepcopy(document)
        removed.pop("helmAuthorities")
        helm_fields = {
            "setting", "owner", "field", "bindings", "default", "defaultEvidence",
            "validation", "scope", "pinning", "coverage", "helmAuthority",
        }
        for entry in removed["entries"]:
            if entry.get("helmAuthority") == audit.HELM_AUTHORITY_ID:
                for field in helm_fields:
                    entry.pop(field, None)
                entry.update(status="retained", classification="protocol-or-format-invariant",
                             rationale="Incorrectly relabelled as retained.")
        errors = audit.inventory_errors(ROOT, removed, candidates)
        self.assertTrue(any("Helm settings require" in error for error in errors), errors)

        altered = copy.deepcopy(document)
        marked = next(entry for entry in altered["entries"] if entry.get("helmAuthority"))
        marked.pop("helmAuthority")
        errors = audit.inventory_errors(ROOT, altered, candidates)
        self.assertTrue(any("Helm candidate coverage" in error for error in errors), errors)

        altered = copy.deepcopy(document)
        marked = next(entry for entry in altered["entries"] if entry.get("helmAuthority"))
        marked["owner"] = (
            "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/"
            "GraphExecutionLimits.java#GraphExecutionLimits")
        errors = audit.inventory_errors(ROOT, altered, candidates)
        self.assertTrue(any("unsupported Helm authority owner" in error for error in errors), errors)

    def test_helm_live_candidate_references_remap_without_touching_history_or_prose(self) -> None:
        document = {
            "helmAuthorities": {audit.HELM_AUTHORITY_ID: {
                "candidateIds": ["oc-old"],
                "contracts": [{"candidateIds": ["oc-old"]}],
                "evidence": "oc-old remains historical prose",
            }},
            "semanticReviewHistory": [{"candidateIds": ["oc-old"]}],
            "normalizedIdentityReappearanceHistory": [{
                "candidateId": "oc-old", "rationale": "Immutable historical identity evidence.",
            }],
        }
        locations = audit.candidate_reference_locations(document, {"oc-old"})
        self.assertTrue(all(audit.allowed_migrated_reference(path)
                            or audit.immutable_historical_reference(path)
                            or path[-1] == "evidence" for path in locations))
        audit.remap_declared_candidate_references(document, {"oc-old": "oc-new"})
        authority = document["helmAuthorities"][audit.HELM_AUTHORITY_ID]
        self.assertEqual(["oc-new"], authority["candidateIds"])
        self.assertEqual(["oc-new"], authority["contracts"][0]["candidateIds"])
        self.assertEqual(["oc-old"], document["semanticReviewHistory"][0]["candidateIds"])
        self.assertEqual(
            "oc-old", document["normalizedIdentityReappearanceHistory"][0]["candidateId"])
        self.assertEqual("oc-old remains historical prose", authority["evidence"])

    def test_normalized_identity_reappearance_is_exact_source_anchored_and_nonsemantic(self) -> None:
        document, candidates, records, prior, prior_raw, checkpoint, checkpoint_raw = \
            production_reappearance_fixture()
        self.assertEqual(21, len(records))
        document["normalizedIdentityReappearanceHistory"] = copy.deepcopy(records)

        errors, allowed = audit.normalized_identity_reappearance_errors(
            ROOT, document, candidates)
        self.assertEqual([], errors)
        self.assertEqual({record["candidateId"] for record in records}, allowed)

        malformed_collection = copy.deepcopy(document)
        malformed_collection["normalizedIdentityReappearanceHistory"] = {}
        self.assertEqual(
            ["normalizedIdentityReappearanceHistory must be an array"],
            audit.normalized_identity_reappearance_errors(
                ROOT, malformed_collection, candidates)[0])

        # Exercise the production routing without paying the unrelated append-only semantic-review
        # validation cost. The source-anchored reappearance validator itself remains unmocked.
        with mock.patch.object(audit, "reconciliation_history_errors", return_value=[]), \
                mock.patch.object(audit, "remediation_domain_errors", return_value=[]):
            routed = audit.inventory_errors(ROOT, document, candidates)
        self.assertFalse(any("duplicate active/retired inventory id" in error for error in routed), routed)

        inventory_path = audit.INVENTORY.relative_to(audit.ROOT).as_posix()

        def anchored_errors(value, discovered=candidates, prior_document=prior,
                            prior_bytes=prior_raw, checkpoint_document=checkpoint,
                            checkpoint_bytes=checkpoint_raw):
            def committed(_root, revision, path):
                if path != inventory_path:
                    return None, None
                if revision == REAPPEARANCE_PRIOR_REVISION:
                    return prior_document, prior_bytes
                if revision == REAPPEARANCE_CHECKPOINT_REVISION:
                    return checkpoint_document, checkpoint_bytes
                return None, None
            with mock.patch.object(audit, "committed_json", side_effect=committed):
                return audit.normalized_identity_reappearance_errors(
                    ROOT, value, discovered)[0]

        absent = copy.deepcopy(document)
        absent["normalizedIdentityReappearanceHistory"].pop()
        self.assertTrue(any("missing active/retired collisions" in error
                            for error in anchored_errors(absent)))
        self.assertEqual(set(), audit.normalized_identity_reappearance_errors(
            ROOT, absent, candidates)[1], "a partial record set must grant no duplicate exception")
        collision_id = records[0]["candidateId"]
        collision_candidate = next(item for item in candidates if item.id == collision_id)
        collision_document = {
            "schemaVersion": audit.SCHEMA_VERSION, "reconciliationRequired": False,
            "entries": [copy.deepcopy(next(
                entry for entry in document["entries"] if entry["id"] == collision_id))],
            "retiredEntries": [copy.deepcopy(next(
                entry for entry in document["retiredEntries"] if entry["id"] == collision_id))],
            "migrationHistory": [],
            "evidenceRecords": {collision_candidate.evidence_digest: collision_candidate.evidence},
        }
        with mock.patch.object(audit, "route_table_authority_errors", return_value=[]), \
                mock.patch.object(audit, "assistant_limit_authority_errors", return_value=[]), \
                mock.patch.object(audit, "graph_limit_authority_errors", return_value=[]), \
                mock.patch.object(audit, "helm_authority_errors", return_value=[]), \
                mock.patch.object(audit, "environment_resolver_group_errors", return_value=[]), \
                mock.patch.object(audit, "reconciliation_history_errors", return_value=[]), \
                mock.patch.object(audit, "remediation_domain_errors", return_value=[]):
            collision_errors = audit.inventory_errors(
                ROOT, collision_document, (collision_candidate,))
        self.assertIn(f"duplicate active/retired inventory id: {collision_id}", collision_errors)

        duplicate = copy.deepcopy(document)
        duplicate["normalizedIdentityReappearanceHistory"].append(
            copy.deepcopy(duplicate["normalizedIdentityReappearanceHistory"][0]))
        self.assertTrue(any("duplicate normalized identity" in error
                            for error in anchored_errors(duplicate)))

        foreign = copy.deepcopy(document)
        foreign["normalizedIdentityReappearanceHistory"][0]["candidateId"] = "oc-foreign"
        self.assertTrue(any("non-colliding candidates" in error
                            for error in anchored_errors(foreign)))

        for field, value in (
                ("approved", False), ("rationale", " "), ("issue", "317"),
                ("priorInventoryPath", "../inventory.json"),
                ("priorInventoryRevision", "deadbeef"),
                ("priorInventoryDigest", "0" * 64),
                ("identityCheckpointInventoryDigest", "0" * 64),
                ("reconciliationId", "missing-reconciliation"),
                ("retiredPayloadDigest", "0" * 64),
                ("currentEvidenceDigest", "0" * 64)):
            with self.subTest(record_field=field):
                changed = copy.deepcopy(document)
                changed["normalizedIdentityReappearanceHistory"][0][field] = value
                self.assertTrue(anchored_errors(changed))

        extra_field = copy.deepcopy(document)
        extra_field["normalizedIdentityReappearanceHistory"][0]["owner"] = "forbidden"
        self.assertTrue(any("unsupported or incomplete shape" in error
                            for error in anchored_errors(extra_field)))

        with mock.patch.object(audit, "revision_is_ancestor", return_value=False):
            self.assertTrue(any("ordered ancestry" in error for error in anchored_errors(document)))

        identifier = records[0]["candidateId"]
        checkpoint_entry = next(entry for entry in checkpoint["entries"]
                                if entry["id"] == identifier)

        prior_active = copy.deepcopy(prior)
        prior_active["entries"].append(copy.deepcopy(checkpoint_entry))
        prior_active_raw = json.dumps(prior_active).encode("utf-8")
        changed = copy.deepcopy(document)
        for record in changed["normalizedIdentityReappearanceHistory"]:
            record["priorInventoryDigest"] = audit.hashlib.sha256(prior_active_raw).hexdigest()
        self.assertTrue(any("absent from active prior inventory" in error for error in
                            anchored_errors(changed, prior_document=prior_active,
                                            prior_bytes=prior_active_raw)))

        prior_without_retirement = copy.deepcopy(prior)
        prior_without_retirement["retiredEntries"] = [
            entry for entry in prior_without_retirement["retiredEntries"]
            if entry.get("id") != identifier]
        prior_without_raw = json.dumps(prior_without_retirement).encode("utf-8")
        changed = copy.deepcopy(document)
        for record in changed["normalizedIdentityReappearanceHistory"]:
            record["priorInventoryDigest"] = audit.hashlib.sha256(prior_without_raw).hexdigest()
        self.assertTrue(any("one retirement" in error for error in
                            anchored_errors(changed, prior_document=prior_without_retirement,
                                            prior_bytes=prior_without_raw)))

        current_retired_tamper = copy.deepcopy(document)
        next(entry for entry in current_retired_tamper["retiredEntries"]
             if entry["id"] == identifier)["retirementRationale"] = "Rewritten history."
        self.assertTrue(any("immutable retired payload has drifted" in error for error in
                            anchored_errors(current_retired_tamper)))

        def eligibility_errors(mutator):
            changed = copy.deepcopy(document)
            prior_changed = copy.deepcopy(prior)
            checkpoint_changed = copy.deepcopy(checkpoint)
            retired_rows = [
                next(entry for entry in changed["retiredEntries"] if entry["id"] == identifier),
                next(entry for entry in prior_changed["retiredEntries"] if entry["id"] == identifier),
                next(entry for entry in checkpoint_changed["retiredEntries"]
                     if entry["id"] == identifier),
            ]
            for row in retired_rows:
                mutator(row)
            prior_changed_raw = json.dumps(prior_changed).encode("utf-8")
            checkpoint_changed_raw = json.dumps(checkpoint_changed).encode("utf-8")
            for record in changed["normalizedIdentityReappearanceHistory"]:
                record["priorInventoryDigest"] = audit.hashlib.sha256(prior_changed_raw).hexdigest()
                record["identityCheckpointInventoryDigest"] = audit.hashlib.sha256(
                    checkpoint_changed_raw).hexdigest()
                if record["candidateId"] == identifier:
                    record["retiredPayloadDigest"] = audit.canonical_json_digest(retired_rows[0])
            return anchored_errors(
                changed, prior_document=prior_changed, prior_bytes=prior_changed_raw,
                checkpoint_document=checkpoint_changed, checkpoint_bytes=checkpoint_changed_raw)

        eligibility_mutations = (
            lambda row: row.update(status="retained"),
            lambda row: row.update(classification="protocol-or-format-invariant"),
            lambda row: row.update(removal={"kind": "duplicate-removed"}),
            lambda row: row["sourceRefresh"].update(semanticRetirement=True),
            lambda row: row["sourceRefresh"].update(duplicateAuthorityCredit=1),
            lambda row: row["sourceRefresh"].update(
                kind="semantic-candidate-retirement-v1"),
        )
        for index, mutation in enumerate(eligibility_mutations):
            with self.subTest(eligibility=index):
                self.assertTrue(any("not an eligible mechanical pending refresh" in error
                                    for error in eligibility_errors(mutation)))

        def mutate_checkpoint_plan(mutator):
            changed = copy.deepcopy(document)
            checkpoint_changed = copy.deepcopy(checkpoint)
            checkpoint_plan = next(plan for plan in checkpoint_changed["reconciliationHistory"]
                                   if plan["id"] == "issue-317-closed-helm-contract-v1")
            current_plan = next(plan for plan in changed["reconciliationHistory"]
                                if plan["id"] == "issue-317-closed-helm-contract-v1")
            mutator(checkpoint_plan)
            mutator(current_plan)
            checkpoint_changed_raw = json.dumps(checkpoint_changed).encode("utf-8")
            for record in changed["normalizedIdentityReappearanceHistory"]:
                record["identityCheckpointInventoryDigest"] = audit.hashlib.sha256(
                    checkpoint_changed_raw).hexdigest()
            return anchored_errors(
                changed, checkpoint_document=checkpoint_changed,
                checkpoint_bytes=checkpoint_changed_raw)

        for plan_field, value in (
                ("sourceRevision", REAPPEARANCE_TARGET_REVISION),
                ("sourceInventoryPath", "inventory.json"),
                ("sourceInventoryDigest", "0" * 64),
                ("targetRevision", REAPPEARANCE_PRIOR_REVISION)):
            with self.subTest(checkpoint_plan_field=plan_field):
                def mutate_anchor(plan, field=plan_field, replacement=value):
                    plan[field] = replacement
                self.assertTrue(any("exact approved checkpoint addition" in error for error in
                                    mutate_checkpoint_plan(mutate_anchor)))

        self.assertTrue(any("exact approved checkpoint addition" in error for error in
                            mutate_checkpoint_plan(lambda plan: plan.__setitem__(
                                "additions", [item for item in plan["additions"]
                                              if item["id"] != identifier]))))

        def replace_addition_with_mapping(plan):
            plan["additions"] = [item for item in plan["additions"] if item["id"] != identifier]
            plan["mappings"].append({
                "fromId": identifier, "toId": identifier, "approved": True,
                "rationale": "Invalid same-id mapping.", "equivalence": {},
            })
        self.assertTrue(any("exact approved checkpoint addition" in error for error in
                            mutate_checkpoint_plan(replace_addition_with_mapping)))

        unapproved = copy.deepcopy(document)
        unapproved["reconciliationHistory"] = [
            plan for plan in unapproved["reconciliationHistory"]
            if plan.get("id") != "issue-317-closed-helm-contract-v1"]
        self.assertTrue(any("identity reconciliation is absent" in error
                            for error in anchored_errors(unapproved)))

        candidate = next(candidate for candidate in candidates if candidate.id == identifier)
        active = next(entry for entry in document["entries"] if entry["id"] == identifier)
        source_mutations = {
            "path": ("path", "deploy/helm/ravenroot/other.json"),
            "symbol": ("symbol", "other"),
            "kind": ("kind", "fixed-declaration"),
            "role": ("role", "other"),
            "expression": ("expression", '"other"'),
            "expressionDigest": ("expression_digest", "0" * 64),
            "evidenceDigest": ("evidence_digest", "0" * 64),
            "surface": ("surface", "java"),
        }
        for entry_field, (candidate_field, value) in source_mutations.items():
            with self.subTest(source_field=entry_field):
                changed = copy.deepcopy(document)
                next(entry for entry in changed["entries"]
                     if entry["id"] == identifier)[entry_field] = value
                changed_candidates = tuple(
                    audit.replace(item, **{candidate_field: value}) if item.id == identifier else item
                    for item in candidates)
                self.assertTrue(anchored_errors(changed, discovered=changed_candidates))

        missing_candidate = tuple(item for item in candidates if item.id != identifier)
        self.assertTrue(any("current candidate is absent" in error
                            for error in anchored_errors(document, discovered=missing_candidate)))

        moved = copy.deepcopy(document)
        next(entry for entry in moved["entries"] if entry["id"] == identifier)["line"] = active["line"] + 1
        moved_candidates = tuple(
            audit.replace(item, line=item.line + 1) if item.id == identifier else item
            for item in candidates)
        self.assertEqual([], anchored_errors(moved, discovered=moved_candidates))

    def test_manifest_pin_attempt_authority_is_closed_over_binding_default_and_wiring(self) -> None:
        discovered = {candidate.id: candidate for candidate in audit.discover(ROOT)}
        expected = audit.manifest_pin_attempt_authorities(ROOT, discovered)
        self.assertIsNotNone(expected)
        contract = {
            "owner": f"{audit.MANIFEST_PIN_CONFIGURATION_PATH.as_posix()}#Shared",
            "field": "manifestPinAttempts",
            "bindings": ["RAVENROOT_EXECUTION_MANIFEST_PIN_ATTEMPTS"],
            "default": "3 attempts",
            "defaultEvidence": expected["defaultAuthority"]["candidateIds"],
            "bindingAuthority": expected["bindingAuthority"],
            "defaultAuthority": expected["defaultAuthority"],
        }
        entries = {
            identifier: {"id": identifier, "setting": audit.MANIFEST_PIN_ATTEMPTS_SETTING}
            for identifier in expected["candidateIds"]
        }
        self.assertEqual([], audit.manifest_pin_attempt_authority_errors(
            ROOT, audit.MANIFEST_PIN_ATTEMPTS_SETTING, contract,
            list(entries.values()), entries, discovered))

        mutations = (
            ("field", "anotherField"),
            ("bindingAuthority.environmentSymbol", "ANOTHER_VARIABLE"),
            ("defaultAuthority.terminalField", "ANOTHER_DEFAULT"),
        )
        for field, value in mutations:
            altered = copy.deepcopy(contract)
            target = altered
            parts = field.split(".")
            for part in parts[:-1]:
                target = target[part]
            target[parts[-1]] = value
            self.assertTrue(audit.manifest_pin_attempt_authority_errors(
                ROOT, audit.MANIFEST_PIN_ATTEMPTS_SETTING, altered,
                list(entries.values()), entries, discovered), field)

    def test_inventory_routes_manifest_pin_setting_through_its_closed_authority(self) -> None:
        candidates = audit.discover(ROOT)
        discovered = {candidate.id: candidate for candidate in candidates}
        expected = audit.manifest_pin_attempt_authorities(ROOT, discovered)
        self.assertIsNotNone(expected)
        authority_metadata = {
            "status": "already-centralized", "classification": "operator-configurable",
            "setting": audit.MANIFEST_PIN_ATTEMPTS_SETTING,
            "owner": f"{audit.MANIFEST_PIN_CONFIGURATION_PATH.as_posix()}#Shared",
            "field": "manifestPinAttempts",
            "bindings": ["RAVENROOT_EXECUTION_MANIFEST_PIN_ATTEMPTS"],
            "default": "3 attempts", "defaultEvidence": expected["defaultAuthority"]["candidateIds"],
            "bindingAuthority": expected["bindingAuthority"],
            "defaultAuthority": expected["defaultAuthority"],
            "validation": "A positive whole number of lost-race repair attempts.",
            "scope": "Each PostgreSQL execution-manifest store instance.",
            "pinning": "Read when the shared store is composed.",
            "coverage": "Typed parsing, selector conflict, and adapter propagation are source-backed.",
            "rationale": "The typed server setting controls PostgreSQL manifest pin repair attempts.",
        }
        entries = []
        for candidate in candidates:
            entry = candidate.inventory_entry()
            if candidate.surface == "test-fixture":
                entry.update(status="retained", classification="test-fixture",
                             rationale="Executable audit fixture.")
            if candidate.id in expected["candidateIds"]:
                entry.update(copy.deepcopy(authority_metadata))
            entries.append(entry)
        document = {
            "schemaVersion": audit.SCHEMA_VERSION, "reconciliationRequired": False,
            "entries": entries, "retiredEntries": [], "migrationHistory": [],
            "reconciliationHistory": [], "semanticReviewHistory": [],
            "evidenceRecords": {
                candidate.evidence_digest: candidate.evidence for candidate in candidates
            },
            "routeTableAuthorities": {
                audit.ROUTE_TABLE_AUTHORITY_ID: audit.current_route_table_authority(ROOT),
            },
        }
        document["remediationDomains"] = audit.build_remediation_domains(entries)
        baseline = audit.inventory_errors(ROOT, document, candidates)
        self.assertFalse(any(audit.MANIFEST_PIN_ATTEMPTS_SETTING in error for error in baseline), baseline)

        mutations = (
            ("bindingAuthority", "environmentSymbol", "ANOTHER_VARIABLE", "binding authority"),
            ("defaultAuthority", "terminalField", "ANOTHER_DEFAULT", "default authority"),
        )
        for section, field, value, expected_error in mutations:
            altered = copy.deepcopy(document)
            for entry in altered["entries"]:
                if entry.get("setting") == audit.MANIFEST_PIN_ATTEMPTS_SETTING:
                    entry[section][field] = value
            errors = audit.inventory_errors(ROOT, altered, candidates)
            self.assertTrue(any(audit.MANIFEST_PIN_ATTEMPTS_SETTING in error
                                and expected_error in error for error in errors), errors)

        incomplete = copy.deepcopy(document)
        terminal = expected["defaultAuthority"]["candidateIds"][0]
        next(entry for entry in incomplete["entries"] if entry["id"] == terminal).pop("setting")
        errors = audit.inventory_errors(ROOT, incomplete, candidates)
        self.assertTrue(any(audit.MANIFEST_PIN_ATTEMPTS_SETTING in error
                            and "partition" in error for error in errors), errors)

    def test_external_io_policy_authority_is_closed_over_source_and_candidates(self) -> None:
        candidates = audit.discover(ROOT)
        discovered = {candidate.id: candidate for candidate in candidates}
        expected = audit.external_io_policy_authority_from_source(ROOT, discovered)
        self.assertIsNotNone(expected)
        assert expected is not None
        self.assertEqual(40, len(expected["contracts"]))
        self.assertEqual(81, sum(len(contract["candidateIds"])
                                 for contract in expected["contracts"]))
        self.assertEqual({
            "derived": 11, "presentation-text": 38,
            "protocol-or-format-invariant": 70,
            "published-contract-description": 2,
            "security-ceiling-or-default": 78,
        }, {partition["classification"]: len(partition["candidateIds"])
            for partition in expected["semanticPartitions"]})
        self.assertEqual(280, len(expected["candidateIds"]))
        self.assertEqual(set(expected["candidateIds"]),
                         audit.external_io_policy_cohort_candidate_ids(discovered))

        entries = external_io_reviewed_entries(candidates, expected)
        authorities = {audit.EXTERNAL_IO_POLICY_AUTHORITY_ID: expected}
        self.assertEqual([], audit.external_io_policy_authority_errors(
            ROOT, authorities, entries, discovered))

        missing = copy.deepcopy(authorities)
        missing.clear()
        self.assertTrue(audit.external_io_policy_authority_errors(
            ROOT, missing, entries, discovered))

        operator_id = expected["contracts"][0]["candidateIds"][0]
        relabelled_operator = copy.deepcopy(entries)
        relabelled_operator[operator_id].update(
            status="retained", classification="protocol-or-format-invariant")
        errors = audit.external_io_policy_authority_errors(
            ROOT, authorities, relabelled_operator, discovered)
        self.assertTrue(any("requires one reviewed operator setting" in error
                            for error in errors), errors)

        retained_id = expected["semanticPartitions"][0]["candidateIds"][0]
        relabelled_retained = copy.deepcopy(entries)
        relabelled_retained[retained_id]["classification"] = "protocol-or-format-invariant"
        errors = audit.external_io_policy_authority_errors(
            ROOT, authorities, relabelled_retained, discovered)
        self.assertTrue(any("retained semantic partition has drifted" in error
                            for error in errors), errors)

        unmarked = copy.deepcopy(entries)
        unmarked[operator_id].pop("externalIoPolicyAuthority")
        errors = audit.external_io_policy_authority_errors(
            ROOT, authorities, unmarked, discovered)
        self.assertTrue(any("candidate partition" in error for error in errors), errors)

        missing_entry = copy.deepcopy(entries)
        missing_entry.pop(retained_id)
        errors = audit.external_io_policy_authority_errors(
            ROOT, authorities, missing_entry, discovered)
        self.assertTrue(any("current source candidate set is incomplete" in error
                            for error in errors), errors)

        foreign = copy.deepcopy(entries)
        outsider = next(candidate for candidate in candidates
                        if candidate.id not in expected["candidateIds"])
        foreign[outsider.id] = {
            **outsider.inventory_entry(), "status": "retained",
            "classification": "protocol-or-format-invariant",
            "rationale": "Unrelated fixed vocabulary.",
            "externalIoPolicyAuthority": audit.EXTERNAL_IO_POLICY_AUTHORITY_ID,
        }
        errors = audit.external_io_policy_authority_errors(
            ROOT, authorities, foreign, discovered)
        self.assertTrue(any("candidate partition" in error for error in errors), errors)

        missing_contract = copy.deepcopy(authorities)
        missing_contract[audit.EXTERNAL_IO_POLICY_AUTHORITY_ID]["contracts"].pop()
        self.assertTrue(audit.external_io_policy_authority_errors(
            ROOT, missing_contract, entries, discovered))
        missing_partition = copy.deepcopy(authorities)
        missing_partition[audit.EXTERNAL_IO_POLICY_AUTHORITY_ID]["semanticPartitions"].pop()
        self.assertTrue(audit.external_io_policy_authority_errors(
            ROOT, missing_partition, entries, discovered))

    def missing_program_and_interaction_fixture_diagnostics(self, candidates):
        discovered = {candidate.id: candidate for candidate in candidates}
        program = audit.program_github_policy_authority_from_source(ROOT, discovered)
        interaction = audit.interaction_websocket_authority_from_source(ROOT, discovered)
        self.assertIsNotNone(program)
        self.assertIsNotNone(interaction)
        self.assertEqual(1227, len(program["candidateIds"]))
        self.assertEqual(164, len(interaction["candidateIds"]))
        self.assertFalse(any(discovered[identifier].fixture for identifier in program["candidateIds"]))
        expected = {
            "program/GitHub settings require the exact mandatory source-derived authority",
            "program/GitHub authority candidate partition is missing, duplicated, or foreign",
            "interaction WebSocket settings require the exact mandatory source-derived authority",
            "interaction WebSocket candidate partition is missing, duplicated, or foreign",
        }
        expected.update(f"{identifier}: mandatory program/GitHub candidate requires resolved semantic review"
                        for identifier in program["candidateIds"])
        # Candidate.inventory_entry retains test fixtures; their only missing row metadata is the
        # interaction marker. Production rows are still pending in these deliberately partial docs.
        expected.update(
            f"{identifier}: interaction WebSocket marker has drifted" if discovered[identifier].fixture
            else f"{identifier}: mandatory interaction WebSocket candidate requires resolved semantic review"
            for identifier in interaction["candidateIds"])
        return expected

    def test_inventory_routes_mandatory_external_io_authority_without_markers(self) -> None:
        candidates = audit.discover(ROOT)
        discovered = {candidate.id: candidate for candidate in candidates}
        authority = audit.external_io_policy_authority_from_source(ROOT, discovered)
        self.assertIsNotNone(authority)
        assert authority is not None
        reviewed = external_io_reviewed_entries(candidates, authority)
        entries = []
        for candidate in candidates:
            entry = reviewed.get(candidate.id, candidate.inventory_entry())
            entries.append(entry)
        document = {
            "schemaVersion": audit.SCHEMA_VERSION, "reconciliationRequired": False,
            "entries": entries, "retiredEntries": [], "migrationHistory": [],
            "reconciliationHistory": [], "semanticReviewHistory": [],
            "evidenceRecords": {candidate.evidence_digest: candidate.evidence
                                for candidate in candidates},
            "externalIoPolicyAuthorities": {
                audit.EXTERNAL_IO_POLICY_AUTHORITY_ID: authority,
            },
        }
        document["remediationDomains"] = audit.build_remediation_domains(entries)
        errors = audit.inventory_errors(ROOT, document, candidates)
        target_ids = set(authority["candidateIds"])
        target_settings = {contract["setting"] for contract in authority["contracts"]}
        target_errors = [error for error in errors
                         if "external-I/O" in error or "externalIo" in error
                         or any(token in error for token in target_ids | target_settings)]
        self.assertEqual([], target_errors)
        self.assertTrue(errors, "the synthetic document deliberately omits unrelated authorities")
        unrelated = [error for error in errors if error not in target_errors]
        expected_missing_families = self.missing_program_and_interaction_fixture_diagnostics(candidates)
        self.assertIn("program/GitHub settings require the exact mandatory source-derived authority", unrelated)
        self.assertIn("interaction WebSocket settings require the exact mandatory source-derived authority", unrelated)
        self.assertTrue(expected_missing_families <= set(unrelated), expected_missing_families - set(unrelated))
        self.assertEqual([], [error for error in unrelated if not (
            error in expected_missing_families
            or error == "AssistantConfiguration operational limits require one closed family authority"
            or error == "Helm settings require the exact source-derived closed values authority"
            or error == "persistence settings require the exact mandatory source-derived authority"
            or (error.startswith("deployment.")
                and error.endswith("Helm candidate coverage is incomplete, duplicate, or foreign"))
        )], unrelated)

        removed = copy.deepcopy(document)
        removed.pop("externalIoPolicyAuthorities")
        errors = audit.inventory_errors(ROOT, removed, candidates)
        self.assertTrue(any("exact mandatory source-derived authority" in error
                            for error in errors), errors)

        markerless = copy.deepcopy(document)
        markerless.pop("externalIoPolicyAuthorities")
        markerless["entries"] = [entry for entry in markerless["entries"]
                                 if entry["id"] not in target_ids]
        markerless["remediationDomains"] = audit.build_remediation_domains(markerless["entries"])
        errors = audit.inventory_errors(ROOT, markerless, candidates)
        self.assertTrue(any("exact mandatory source-derived authority" in error
                            or "current source candidate set is incomplete" in error
                            for error in errors), errors)

        relabelled = copy.deepcopy(document)
        operator_id = authority["contracts"][0]["candidateIds"][0]
        next(entry for entry in relabelled["entries"] if entry["id"] == operator_id).update(
            status="retained", classification="protocol-or-format-invariant")
        errors = audit.inventory_errors(ROOT, relabelled, candidates)
        self.assertTrue(any("requires one reviewed operator setting" in error
                            for error in errors), errors)

        retained_id = authority["semanticPartitions"][0]["candidateIds"][0]
        retained_relabel = copy.deepcopy(document)
        next(entry for entry in retained_relabel["entries"]
             if entry["id"] == retained_id)["classification"] = "protocol-or-format-invariant"
        errors = audit.inventory_errors(ROOT, retained_relabel, candidates)
        self.assertTrue(any("retained semantic partition has drifted" in error
                            for error in errors), errors)

    def test_external_io_policy_authority_rejects_partial_or_drifted_source(self) -> None:
        candidates = audit.discover(ROOT)
        discovered = {candidate.id: candidate for candidate in candidates}
        expected = audit.external_io_policy_authority_from_source(ROOT, discovered)
        self.assertIsNotNone(expected)
        assert expected is not None
        paths = tuple(Path(item["path"]) for item in expected["sourceDigests"])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in paths:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / relative).read_bytes())
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "add", *[path.as_posix() for path in paths]],
                           cwd=root, check=True)

            def derives() -> bool:
                current = {candidate.id: candidate for candidate in audit.discover(root)}
                return audit.external_io_policy_authority_from_source(root, current) is not None

            self.assertTrue(derives())

            def rejects(relative: Path, before: str, after: str) -> None:
                target = root / relative
                source = target.read_text(encoding="utf-8")
                self.assertEqual(1, source.count(before), (relative, before))
                target.write_text(source.replace(before, after, 1), encoding="utf-8")
                try:
                    self.assertFalse(derives(), relative)
                finally:
                    target.write_text(source, encoding="utf-8")

            rejects(audit.EXTERNAL_IO_LIMITS_PATH,
                    "DEFAULT_MANAGED_HTTP_DURATION = Duration.ofSeconds(30)",
                    "DEFAULT_MANAGED_HTTP_DURATION = Duration.ofSeconds(31)")
            rejects(audit.EXTERNAL_IO_NODE_CAPACITY_PATH,
                    "int maximumMessageBytes, int maximumFragments,\n"
                    "                                     Duration maximumTimeout, int maximumConcurrency",
                    "int maximumMessageBytes, int maximumConcurrency,\n"
                    "                                     Duration maximumTimeout, int maximumFragments")
            rejects(audit.EXTERNAL_IO_NODE_CAPACITY_PATH,
                    "maximumTimeout.toNanos();", "maximumTimeout.toMillis();")
            rejects(audit.EXTERNAL_IO_CAPACITY_CAPABLE_PATH,
                    "NodeExternalIoCapacity resolveExecutionIoCapacity(NodeConfiguration configuration);",
                    "NodeExternalIoCapacity resolveExecutionIoCapacity(NodeConfiguration configuration, "
                    "NodePackageServices services);")
            rejects(audit.EXTERNAL_IO_SERVER_MAIN_PATH,
                    "if (value < 1) throw new NumberFormatException(\"nonpositive\");",
                    "if (value < 0) throw new NumberFormatException(\"negative\");")
            rejects(audit.EXTERNAL_IO_APPLICATION_PATH,
                    "policyForNodeAdmission(behaviorNodes)",
                    "policyForNodes(behaviorNodes)")
            rejects(audit.EXTERNAL_IO_WS_ADMISSION_PATH,
                    "if (active >= maximum) return false;",
                    "if (active > maximum) return false;")
            rejects(audit.EXTERNAL_IO_TEAMS_PROFILE_PATH, "MAX_ACK_TIMEOUT_MS = 4_500",
                    "MAX_ACK_TIMEOUT_MS = 4_600")
            rejects(audit.EXTERNAL_IO_MATTERMOST_PROFILE_PATH, "MAX_ACK_TIMEOUT_MS = 2_800",
                    "MAX_ACK_TIMEOUT_MS = 2_900")

            test_path = Path(
                "ravenroot/ravenroot-core/src/test/java/ai/ravenroot/core/runtime/"
                "HostedExternalIoPolicyTest.java")
            rejects(test_path, "@Test void oldThenNewPinsCoexistOnOneHostedRunner()",
                    "void oldThenNewPinsCoexistOnOneHostedRunner()")

            capacity_path = root / audit.EXTERNAL_IO_NODE_CAPACITY_PATH
            capacity_bytes = capacity_path.read_bytes()
            capacity_path.unlink()
            try:
                self.assertFalse(derives())
            finally:
                capacity_path.write_bytes(capacity_bytes)

            # A new scanner-visible setting in a dedicated family source must not be omitted merely
            # because every previously reviewed identifier and method digest still matches.
            target = root / audit.EXTERNAL_IO_LIMITS_PATH
            source = target.read_text(encoding="utf-8")
            target.write_text(source.rsplit("}", 1)[0]
                              + "    static final int UNREVIEWED_EXTERNAL_IO_LIMIT = 17;\n}\n",
                              encoding="utf-8")
            self.assertFalse(derives())

    def test_persistence_policy_authority_is_closed_over_source_and_candidates(self) -> None:
        candidates = audit.discover(ROOT)
        discovered = {candidate.id: candidate for candidate in candidates}
        expected = audit.persistence_policy_authority_from_source(ROOT, discovered)
        self.assertIsNotNone(expected)
        self.assertEqual(43, len(expected["contracts"]))
        self.assertEqual(97, len(expected["candidateIds"]))
        self.assertEqual(
            "471891f10e915939b5d29ae276a5ece19c8ada9abc8513f2ccf24beef9872e83",
            hashlib.sha256(json.dumps(expected["contracts"][:40], sort_keys=True,
                                      separators=(",", ":")).encode("utf-8")).hexdigest(),
            "the accepted 40-contract prefix must not be rewritten to add the new path policies",
        )
        self.assertEqual(
            {
                "execution.store.selector", "execution.store.url", "execution.store.user",
                "execution.store.password", "execution.store.pool-size",
                "execution.store.pool-timeout", "execution.worker-id", "execution.lease-ttl",
                "deployment.audit-directory", "execution.store.directory",
                "execution.store.enabled",
            },
            {contract["setting"] for contract in expected["contracts"][-11:]},
        )
        entries = {}
        contracts = {identifier: contract for contract in expected["contracts"]
                     for identifier in contract["candidateIds"]}
        for identifier, contract in contracts.items():
            entry = discovered[identifier].inventory_entry()
            entry.update(
                status="already-centralized", classification="operator-configurable",
                setting=contract["setting"], owner=contract["owner"], field=contract["field"],
                bindings=contract["bindings"], default="source-derived typed default",
                defaultEvidence=contract["defaultCandidateIds"],
                validation="The typed policy validates this value before work.",
                scope="One explicitly composed adapter or caller.",
                pinning="Resolved before managed work where replay can observe it.",
                coverage="Source-derived owner, default, binding, consumer and test evidence.",
                rationale="The closed persistence policy is the source authority.",
                persistenceAuthority=audit.PERSISTENCE_POLICY_AUTHORITY_ID,
            )
            entries[identifier] = entry
        hikari_minimum = "oc-0c1ca37de0bb555b5298"
        self.assertNotIn(hikari_minimum, expected["candidateIds"])
        sqlite_file_name = [candidate.id for candidate in candidates
                            if candidate.path == audit.PERSISTENCE_SQLITE_LOCATION_PATH.as_posix()
                            and candidate.kind == "fixed-declaration"
                            and candidate.expression == '"ravenroot-execution-store.db"']
        self.assertEqual(1, len(sqlite_file_name))
        self.assertNotIn(sqlite_file_name[0], expected["candidateIds"])
        minimum_entry = discovered[hikari_minimum].inventory_entry()
        minimum_entry.update(
            status="retained", classification="protocol-or-format-invariant",
            rationale="HikariCP's minimum accepted pool-acquisition timeout is a fixed dependency contract.",
        )
        entries[hikari_minimum] = minimum_entry
        authorities = {audit.PERSISTENCE_POLICY_AUTHORITY_ID: expected}
        self.assertEqual([], audit.persistence_policy_authority_errors(
            ROOT, authorities, entries, discovered))

        missing = copy.deepcopy(authorities)
        missing.clear()
        self.assertTrue(audit.persistence_policy_authority_errors(
            ROOT, missing, entries, discovered))
        relabelled = copy.deepcopy(entries)
        for entry in relabelled.values():
            if entry["id"] in contracts:
                entry.update(status="retained", classification="protocol-or-format-invariant")
        self.assertTrue(any("requires one reviewed operator setting" in error for error in
                            audit.persistence_policy_authority_errors(
                                ROOT, authorities, relabelled, discovered)))
        unmarked = copy.deepcopy(relabelled)
        for entry in unmarked.values():
            if entry["id"] in contracts:
                entry.pop("persistenceAuthority")
        self.assertTrue(any("partition" in error for error in
                            audit.persistence_policy_authority_errors(
                                ROOT, authorities, unmarked, discovered)))
        malformed = copy.deepcopy(entries)
        first = next(iter(malformed.values()))
        first["defaultEvidence"] = "not-an-array"
        self.assertTrue(audit.persistence_policy_authority_errors(
            ROOT, authorities, malformed, discovered))
        duplicated = copy.deepcopy(authorities)
        duplicated_contract = duplicated[audit.PERSISTENCE_POLICY_AUTHORITY_ID]["contracts"][-1]
        duplicated_contract["candidateIds"].append(duplicated_contract["candidateIds"][0])
        self.assertTrue(audit.persistence_policy_authority_errors(
            ROOT, duplicated, entries, discovered))
        missing_contract = copy.deepcopy(authorities)
        missing_contract[audit.PERSISTENCE_POLICY_AUTHORITY_ID]["contracts"].pop()
        self.assertTrue(audit.persistence_policy_authority_errors(
            ROOT, missing_contract, entries, discovered))
        missing_entry = copy.deepcopy(entries)
        missing_entry.pop(next(iter(missing_entry)))
        self.assertTrue(any("current source candidate set is incomplete" in error for error in
                            audit.persistence_policy_authority_errors(
                                ROOT, authorities, missing_entry, discovered)))
        foreign = copy.deepcopy(entries)
        outsider = next(candidate for candidate in candidates if candidate.id not in contracts)
        foreign[outsider.id] = {
            **outsider.inventory_entry(), "status": "retained",
            "classification": "protocol-or-format-invariant",
            "rationale": "Unrelated fixed vocabulary.",
            "persistenceAuthority": audit.PERSISTENCE_POLICY_AUTHORITY_ID,
        }
        self.assertTrue(any("partition" in error for error in
                            audit.persistence_policy_authority_errors(
                                ROOT, authorities, foreign, discovered)))

    def test_inventory_routes_mandatory_persistence_authority_without_markers(self) -> None:
        candidates = audit.discover(ROOT)
        discovered = {candidate.id: candidate for candidate in candidates}
        authority = audit.persistence_policy_authority_from_source(ROOT, discovered)
        self.assertIsNotNone(authority)
        entries = []
        protected = set(authority["candidateIds"])
        contracts = {identifier: contract for contract in authority["contracts"]
                     for identifier in contract["candidateIds"]}
        for candidate in candidates:
            entry = candidate.inventory_entry()
            contract = contracts.get(candidate.id)
            if contract is not None:
                entry.update(
                    status="already-centralized", classification="operator-configurable",
                    setting=contract["setting"], owner=contract["owner"], field=contract["field"],
                    bindings=contract["bindings"], default=contract["defaultExpression"],
                    defaultEvidence=contract["defaultCandidateIds"],
                    validation="The typed policy validates this value before work.",
                    scope="One explicitly composed adapter or caller.",
                    pinning="Resolved before managed work where replay can observe it.",
                    coverage="Source-derived owner, default, binding, consumer and test evidence.",
                    rationale="The closed persistence policy is the source authority.",
                    persistenceAuthority=audit.PERSISTENCE_POLICY_AUTHORITY_ID,
                )
            elif candidate.surface == "test-fixture":
                entry.update(status="retained", classification="test-fixture",
                             rationale="Executable audit fixture.")
            entries.append(entry)
        document = {
            "schemaVersion": audit.SCHEMA_VERSION, "reconciliationRequired": False,
            "entries": entries, "retiredEntries": [], "migrationHistory": [],
            "reconciliationHistory": [], "semanticReviewHistory": [],
            "evidenceRecords": {candidate.evidence_digest: candidate.evidence
                                for candidate in candidates},
            "persistencePolicyAuthorities": {
                audit.PERSISTENCE_POLICY_AUTHORITY_ID: authority,
            },
        }
        document["remediationDomains"] = audit.build_remediation_domains(entries)
        errors = audit.inventory_errors(ROOT, document, candidates)
        target_tokens = protected | {contract["setting"] for contract in authority["contracts"]}
        target_errors = [error for error in errors
                         if "persistence policy" in error or "persistence authority" in error
                         or any(token in error for token in target_tokens)]
        self.assertEqual([], target_errors)
        self.assertTrue(errors, "the synthetic document deliberately omits unrelated family authorities")
        unrelated = [error for error in errors if error not in target_errors]
        self.assertTrue(unrelated, "the synthetic document deliberately omits unrelated authorities")
        self.assertIn(
            "external-I/O settings require the exact mandatory source-derived authority",
            unrelated,
        )
        expected_missing_families = self.missing_program_and_interaction_fixture_diagnostics(candidates)
        self.assertIn("program/GitHub settings require the exact mandatory source-derived authority", unrelated)
        self.assertIn("interaction WebSocket settings require the exact mandatory source-derived authority", unrelated)
        self.assertTrue(expected_missing_families <= set(unrelated), expected_missing_families - set(unrelated))
        self.assertEqual([], [error for error in unrelated if not (
            error in expected_missing_families
            or error == "AssistantConfiguration operational limits require one closed family authority"
            or error == "Helm settings require the exact source-derived closed values authority"
            or error == "external-I/O settings require the exact mandatory source-derived authority"
            or (error.startswith("deployment.")
                and error.endswith("Helm candidate coverage is incomplete, duplicate, or foreign"))
        )], unrelated)

        enabled = next(contract for contract in authority["contracts"]
                       if contract["setting"] == "execution.store.enabled")
        self.assertEqual(1, len(enabled["defaultCandidateIds"]))
        self.assertIn(enabled["defaultCandidateIds"][0], enabled["candidateIds"])
        missing_default = copy.deepcopy(document)
        for entry in missing_default["entries"]:
            if entry.get("setting") == "execution.store.enabled":
                entry["defaultEvidence"] = []
        errors = audit.inventory_errors(ROOT, missing_default, candidates)
        self.assertTrue(any("execution.store.enabled" in error or identifier in error
                            for identifier in enabled["candidateIds"] for error in errors), errors)

        removed = copy.deepcopy(document)
        removed.pop("persistencePolicyAuthorities")
        errors = audit.inventory_errors(ROOT, removed, candidates)
        self.assertTrue(any("exact mandatory source-derived authority" in error for error in errors))

        relabelled = copy.deepcopy(document)
        for entry in relabelled["entries"]:
            if entry["id"] in protected:
                entry.update(status="retained", classification="protocol-or-format-invariant",
                             rationale="Incorrectly hidden as a fixed protocol value.")
        errors = audit.inventory_errors(ROOT, relabelled, candidates)
        self.assertTrue(any("requires one reviewed operator setting" in error for error in errors),
                        errors)
        unmarked = copy.deepcopy(relabelled)
        for entry in unmarked["entries"]:
            if entry["id"] in protected:
                entry.pop("persistenceAuthority")
        errors = audit.inventory_errors(ROOT, unmarked, candidates)
        self.assertTrue(any("candidate partition" in error for error in errors), errors)

    def test_persistence_policy_authority_rejects_partial_or_drifted_source(self) -> None:
        paths = (
            audit.PERSISTENCE_POSTGRES_CONFIG_PATH, audit.PERSISTENCE_POSTGRES_RESOLVER_PATH,
            audit.PERSISTENCE_STORE_CONFIGURATION_PATH,
            audit.PERSISTENCE_SHARED_CONNECTION_PATH, audit.PERSISTENCE_SHARED_DATASOURCE_PATH,
            audit.PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH,
            audit.PERSISTENCE_EXECUTION_OWNERSHIP_PATH,
            audit.PERSISTENCE_BACKUP_CONFIGURATION_PATH,
            audit.PERSISTENCE_AUDIT_DIRECTORY_PATH,
            audit.PERSISTENCE_AUDIT_CONFIGURATION_PATH,
            audit.PERSISTENCE_SQLITE_LOCATION_PATH,
            audit.PERSISTENCE_REGISTRY_POLICY_PATH, audit.PERSISTENCE_IN_MEMORY_POLICY_PATH,
            audit.PERSISTENCE_SQLITE_CONFIG_PATH, audit.PERSISTENCE_SQLITE_CONNECTION_POLICY_PATH,
            audit.PERSISTENCE_QUERY_PATH, audit.PERSISTENCE_MANAGED_STORE_PATH,
            audit.PERSISTENCE_MANIFEST_PATH, audit.PERSISTENCE_BOOTSTRAP_PATH,
            audit.PERSISTENCE_SERVER_MAIN_PATH, audit.PERSISTENCE_IN_MEMORY_REGISTRY_PATH,
            audit.PERSISTENCE_SQLITE_REGISTRY_PATH, audit.PERSISTENCE_POSTGRES_REGISTRY_PATH,
            audit.PERSISTENCE_SQLITE_ARTIFACT_PATH, audit.PERSISTENCE_SQLITE_EMBED_PATH,
            audit.PERSISTENCE_SQLITE_EXECUTION_PATH, audit.PERSISTENCE_POSTGRES_EXECUTION_PATH,
            audit.PERSISTENCE_OPERATIONAL_POLICY_PATH, audit.PERSISTENCE_MANIFEST_DIGEST_PATH,
            audit.PERSISTENCE_AUTHORITY_PATH, audit.PERSISTENCE_MANIFEST_RESOLVER_PATH,
            audit.PERSISTENCE_DEFAULT_APPLICATION_PATH,
            audit.PERSISTENCE_STORE_CONFIGURATION_TEST_PATH,
            audit.PERSISTENCE_OWNERSHIP_CONFIGURATION_TEST_PATH,
            audit.PERSISTENCE_MANAGED_STORE_TEST_PATH,
            audit.PERSISTENCE_CLI_SELECTOR_TEST_PATH,
            audit.PERSISTENCE_AUDIT_DIRECTORY_TEST_PATH,
            audit.PERSISTENCE_AUDIT_CONFIGURATION_TEST_PATH,
            audit.PERSISTENCE_DIRECTORY_PARITY_TEST_PATH,
            audit.PERSISTENCE_SQLITE_LOCATION_TEST_PATH,
            audit.PERSISTENCE_OPERATIONAL_POLICY_TEST_PATH,
            audit.PERSISTENCE_MANIFEST_RESOLVER_TEST_PATH,
            audit.PERSISTENCE_APPLICATION_MANIFEST_TEST_PATH,
            Path("ravenroot/ravenroot-application-api/src/test/java/ai/ravenroot/api/deployment/registry/DeploymentRegistryPolicyTest.java"),
            Path("ravenroot/ravenroot-core/src/test/java/ai/ravenroot/core/persistence/InMemoryExecutionStorePolicyTest.java"),
            Path("ravenroot/ravenroot-persistence-sqlite/src/test/java/ai/ravenroot/persistence/sqlite/SqliteConnectionPolicyTest.java"),
            Path("ravenroot/ravenroot-persistence-testkit/src/main/java/ai/ravenroot/testkit/persistence/ManagedExecutionStoreContract.java"),
            Path("ravenroot/ravenroot-persistence-sqlite/src/test/java/ai/ravenroot/persistence/sqlite/SqliteManagedExecutionStoreContractTest.java"),
            Path("ravenroot/ravenroot-persistence-postgresql/src/test/java/ai/ravenroot/persistence/postgresql/PostgresManagedExecutionStoreContractTest.java"),
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in paths:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / relative).read_bytes())
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "add", *[path.as_posix() for path in paths]], cwd=root, check=True)
            discovered = {candidate.id: candidate for candidate in audit.discover(root)}
            self.assertIsNotNone(audit.persistence_policy_authority_from_source(root, discovered))

            resolver = root / audit.PERSISTENCE_POSTGRES_RESOLVER_PATH
            original = resolver.read_text(encoding="utf-8")
            resolver.write_text(original.replace(
                "String raw = properties.get(property);",
                "String raw = environment.get(variable);", 1), encoding="utf-8")
            changed = {candidate.id: candidate for candidate in audit.discover(root)}
            self.assertIsNone(audit.persistence_policy_authority_from_source(root, changed))
            resolver.write_text(original, encoding="utf-8")

            def rejects(relative: Path, before: str, after: str) -> None:
                target = root / relative
                source = target.read_text(encoding="utf-8")
                self.assertEqual(1, source.count(before), (relative, before))
                target.write_text(source.replace(before, after, 1), encoding="utf-8")
                try:
                    current = {candidate.id: candidate for candidate in audit.discover(root)}
                    self.assertIsNone(audit.persistence_policy_authority_from_source(root, current),
                                      relative)
                finally:
                    target.write_text(source, encoding="utf-8")

            rejects(audit.PERSISTENCE_POSTGRES_RESOLVER_PATH,
                    "defaults.maxClockSkew(), true)", "defaults.maxClockSkew(), false)")
            rejects(audit.PERSISTENCE_STORE_CONFIGURATION_PATH,
                    "|| isConfigured(properties, POOL_TIMEOUT_PROPERTY);",
                    "|| false;")
            rejects(audit.PERSISTENCE_STORE_CONFIGURATION_PATH,
                    'static final String DEFAULT_ENABLED_VALUE = "true";',
                    'static final String DEFAULT_ENABLED_VALUE = "false";')
            rejects(audit.PERSISTENCE_STORE_CONFIGURATION_PATH,
                    "? DEFAULT_ENABLED_VALUE\n                : raw.trim()",
                    '? "true"\n                : raw.trim()')
            rejects(audit.PERSISTENCE_STORE_CONFIGURATION_PATH,
                    "return SQLITE_SELECTOR;", "return POSTGRESQL_SELECTOR;")
            rejects(audit.PERSISTENCE_STORE_CONFIGURATION_PATH,
                    'String SQLITE_SELECTOR = "sqlite";',
                    'String SQLITE_SELECTOR = "single-host";')
            rejects(audit.PERSISTENCE_STORE_CONFIGURATION_PATH,
                    'String POSTGRESQL_SELECTOR = "postgresql";',
                    'String POSTGRESQL_SELECTOR = "shared";')
            rejects(audit.PERSISTENCE_SHARED_CONNECTION_PATH,
                    ".orElseThrow(() -> new IllegalArgumentException(",
                    ".orElseGet(() -> String.valueOf(")
            rejects(audit.PERSISTENCE_SHARED_CONNECTION_PATH,
                    'private static final String REQUIRED_URL_PREFIX = "jdbc:postgresql:";',
                    'private static final String REQUIRED_URL_PREFIX = "jdbc:";')
            rejects(audit.PERSISTENCE_SHARED_CONNECTION_PATH,
                    "if (!url.startsWith(REQUIRED_URL_PREFIX)) {",
                    "if (!url.endsWith(REQUIRED_URL_PREFIX)) {")
            rejects(audit.PERSISTENCE_SHARED_CONNECTION_PATH,
                    "trimmed(environment, ExecutionStoreConfiguration.USER_VARIABLE),",
                    "Optional.ofNullable(environment.get(ExecutionStoreConfiguration.USER_VARIABLE)),")
            rejects(audit.PERSISTENCE_SHARED_CONNECTION_PATH,
                    "Optional.ofNullable(environment.get(ExecutionStoreConfiguration.PASSWORD_VARIABLE)),",
                    "trimmed(environment, ExecutionStoreConfiguration.PASSWORD_VARIABLE),")
            rejects(audit.PERSISTENCE_SHARED_CONNECTION_PATH,
                    'return "SharedStoreConnection[url=<redacted>, user="',
                    'return "SharedStoreConnection[url=" + url + ", user="')
            rejects(audit.PERSISTENCE_SHARED_CONNECTION_PATH,
                    "private static final int DEFAULT_POOL_SIZE = 10;",
                    "private static final int DEFAULT_POOL_SIZE = 11;")
            rejects(audit.PERSISTENCE_SHARED_CONNECTION_PATH,
                    "private static final int MAX_POOL_SIZE = 1_000;",
                    "private static final int MAX_POOL_SIZE = 999;")
            rejects(audit.PERSISTENCE_SHARED_CONNECTION_PATH,
                    "private static final Duration MIN_POOL_TIMEOUT = Duration.ofMillis(250);",
                    "private static final Duration MIN_POOL_TIMEOUT = Duration.ofMillis(251);")
            rejects(audit.PERSISTENCE_SHARED_CONNECTION_PATH,
                    "connection.poolTimeout().compareTo(storeConfig.statementTimeout()) >= 0",
                    "connection.poolTimeout().compareTo(storeConfig.statementTimeout()) > 0")
            rejects(audit.PERSISTENCE_SHARED_DATASOURCE_PATH,
                    "config.setMaximumPoolSize(connection.poolSize());",
                    "config.setMaximumPoolSize(10);")
            rejects(audit.PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH,
                    "? hostName() : configured.trim();",
                    "? UNRESOLVED_REPLICA_NAME : configured.trim();")
            rejects(audit.PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH,
                    "WorkerIdentity.of(replicaName, WorkerIdentity.Role.RUNTIME);",
                    "WorkerIdentity.of(replicaName, WorkerIdentity.Role.RECOVERY);")
            rejects(audit.PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH,
                    "return WorkerIdentity.of(replicaName, WorkerIdentity.Role.RECOVERY);",
                    "return WorkerIdentity.of(replicaName, WorkerIdentity.Role.RUNTIME);")
            rejects(audit.PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH,
                    "return ExecutionOwnership.DEFAULT_LEASE_TTL;",
                    "return Duration.ofSeconds(1);")
            rejects(audit.PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH,
                    "if (seconds < 1) {", "if (seconds < 0) {")
            rejects(audit.PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH,
                    "if (leaseTtl.compareTo(store.maxLeaseTtl()) > 0) {",
                    "if (leaseTtl.compareTo(store.maxLeaseTtl()) >= 0) {")
            rejects(audit.PERSISTENCE_SERVER_MAIN_PATH,
                    "executionOwnershipConfiguration.requireCompatible(managedExecutionStore);",
                    "/* ownership compatibility omitted */")
            rejects(audit.PERSISTENCE_BACKUP_CONFIGURATION_PATH,
                    "return raw != null && SHARED_STORE_SELECTOR.equals(raw.trim().toLowerCase(java.util.Locale.ROOT));",
                    "return false;")
            rejects(audit.PERSISTENCE_POSTGRES_CONFIG_PATH,
                    "Duration.ofSeconds(30), 3", "Duration.ofSeconds(31), 3")
            rejects(audit.PERSISTENCE_REGISTRY_POLICY_PATH,
                    "Duration.ofMinutes(5)", "Duration.ofSeconds(5)")
            rejects(audit.PERSISTENCE_BOOTSTRAP_PATH,
                    "configuration.manifestPinAttempts(), storeConfig)",
                    "configuration.manifestPinAttempts(), PostgresStoreConfig.defaults())")
            rejects(audit.PERSISTENCE_SERVER_MAIN_PATH,
                    "executionStoreOwner.store(), executionStoreOwner.executionManifestStore())",
                    "executionStoreOwner.store(), null)")
            rejects(audit.PERSISTENCE_SQLITE_ARTIFACT_PATH,
                    "connectionPolicy.apply(connection);", "SqliteConnectionPolicy.DEFAULTS.apply(connection);")
            rejects(audit.PERSISTENCE_SQLITE_EMBED_PATH,
                    "connectionPolicy.apply(opened);", "SqliteConnectionPolicy.DEFAULTS.apply(opened);")
            rejects(audit.PERSISTENCE_SQLITE_REGISTRY_PATH,
                    "this.limits = policy.limits();", "this.limits = DeploymentRegistryPolicy.DEFAULTS.limits();")
            rejects(audit.PERSISTENCE_POSTGRES_REGISTRY_PATH,
                    "this.commandRetention = policy.commandRetention();",
                    "this.commandRetention = DeploymentRegistryPolicy.DEFAULTS.commandRetention();")
            rejects(audit.PERSISTENCE_IN_MEMORY_REGISTRY_PATH,
                    "this.limits = Objects.requireNonNull(limits, \"limits\");",
                    "this.limits = DeploymentRegistryPolicy.inMemoryLimits();")
            rejects(audit.PERSISTENCE_SQLITE_EXECUTION_PATH,
                    "return applyInternal(batch, authority);", "return apply(batch);")
            rejects(audit.PERSISTENCE_POSTGRES_EXECUTION_PATH,
                    "if (authority != null) {\n            requireManagedAuthority(connection, key, authority);\n            requireBatchPayloads(batch);\n        }",
                    "if (authority != null) {\n            requireManagedAuthority(connection, key, ExecutionPersistenceAuthority.from(null));\n            requireBatchPayloads(batch);\n        }")
            rejects(audit.PERSISTENCE_SQLITE_EXECUTION_PATH,
                    "rows.getInt(2) != ai.ravenroot.api.persistence.ExecutionManifest.FORMAT_VERSION_3",
                    "rows.getInt(2) != ai.ravenroot.api.persistence.ExecutionManifest.FORMAT_VERSION_2")
            rejects(audit.PERSISTENCE_POSTGRES_EXECUTION_PATH,
                    "pinned != config.maxPayloadBytes()",
                    "pinned < config.maxPayloadBytes()")
            rejects(audit.PERSISTENCE_SQLITE_EXECUTION_PATH,
                    'policy.persistence().orElseThrow(\n'
                    '                            () -> new IllegalArgumentException('
                    '"persistence capacity is absent"))',
                    'policy.persistence().orElseThrow()')
            rejects(audit.PERSISTENCE_POSTGRES_EXECUTION_PATH,
                    'policy.persistence().orElseThrow(\n'
                    '                            () -> new IllegalArgumentException('
                    '"persistence capacity is absent"))',
                    'policy.persistence().orElseThrow()')
            rejects(audit.PERSISTENCE_DEFAULT_APPLICATION_PATH,
                    "ExecutionManifestResolver.completeManaged(engine,",
                    "ExecutionManifestResolver.complete(engine,")
            rejects(audit.PERSISTENCE_MANIFEST_RESOLVER_PATH,
                    ".map(ResolvedOperationalPolicy.PersistenceLimits::new)",
                    ".map(ignored -> new ResolvedOperationalPolicy.PersistenceLimits(1))")
            rejects(audit.PERSISTENCE_MANIFEST_DIGEST_PATH,
                    "|| manifest.formatVersion() == ExecutionManifest.FORMAT_VERSION_3",
                    "&& manifest.formatVersion() == ExecutionManifest.FORMAT_VERSION_3")
            rejects(audit.PERSISTENCE_AUTHORITY_PATH,
                    "manifest.formatVersion() != ExecutionManifest.FORMAT_VERSION_4",
                    "manifest.formatVersion() != ExecutionManifest.FORMAT_VERSION_3")
            rejects(audit.PERSISTENCE_MANIFEST_RESOLVER_PATH,
                    "base.nodePackages(), base.persistence(), behaviors.nodeExternalIoCapacitiesFor(nodes)",
                    "base.nodePackages(), java.util.Optional.empty(), behaviors.nodeExternalIoCapacitiesFor(nodes)")
            rejects(audit.PERSISTENCE_MANIFEST_RESOLVER_PATH,
                    "return new ExecutionManifest(ExecutionManifest.FORMAT_VERSION_4, key,",
                    "return new ExecutionManifest(ExecutionManifest.FORMAT_VERSION_3, key,")
            rejects(audit.PERSISTENCE_OPERATIONAL_POLICY_PATH,
                    "case ExecutionManifest.FORMAT_VERSION_3 -> ENCODING_VERSION_2;",
                    "case ExecutionManifest.FORMAT_VERSION_3 -> ENCODING_VERSION_1;")
            rejects(audit.PERSISTENCE_OPERATIONAL_POLICY_PATH,
                    "case ExecutionManifest.FORMAT_VERSION_4 -> ENCODING_VERSION_3;",
                    "case ExecutionManifest.FORMAT_VERSION_4 -> ENCODING_VERSION_2;")
            rejects(Path(
                    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/persistence/ExecutionStoreConfigurationTest.java"),
                    "@Test\n    void poolPropertiesArePostgresqlOnlyWhileBlankValuesDelegate()",
                    "void poolPropertiesArePostgresqlOnlyWhileBlankValuesDelegate()")
            rejects(Path(
                    "ravenroot/ravenroot-persistence-testkit/src/main/java/ai/ravenroot/testkit/persistence/ManagedExecutionStoreContract.java"),
                    "@Test\n    final void restrictedPendingWorkClaimsAreAtomicAndExcludeUnverifiedNewKeys()",
                    "final void restrictedPendingWorkClaimsAreAtomicAndExcludeUnverifiedNewKeys()")
            rejects(audit.PERSISTENCE_APPLICATION_MANIFEST_TEST_PATH,
                    "@Test\n    void rawEmbeddedAdmissionAndRecoveryCarryV4NodeIoWithoutInventingPersistenceCapacity()",
                    "void rawEmbeddedAdmissionAndRecoveryCarryV4NodeIoWithoutInventingPersistenceCapacity()")
            rejects(Path(
                    "ravenroot/ravenroot-persistence-testkit/src/main/java/ai/ravenroot/testkit/persistence/ManagedExecutionStoreContract.java"),
                    "@Test\n    final void restrictedDueTimerClaimsAreAtomicAndExcludeUnverifiedNewKeys()",
                    "final void restrictedDueTimerClaimsAreAtomicAndExcludeUnverifiedNewKeys()")
            rejects(Path(
                    "ravenroot/ravenroot-persistence-testkit/src/main/java/ai/ravenroot/testkit/persistence/ManagedExecutionStoreContract.java"),
                    "@Test\n    final void formatFourWithoutPersistenceCapacityRefusesEveryManagedMutationRoute()",
                    "final void formatFourWithoutPersistenceCapacityRefusesEveryManagedMutationRoute()")
            rejects(Path(
                    "ravenroot/ravenroot-persistence-testkit/src/main/java/ai/ravenroot/testkit/persistence/ManagedExecutionStoreContract.java"),
                    "@Test\n    final void formatFourCleanupThatWinsBeforeCreationLeavesNoAuthorityToCreateTheProcess()",
                    "final void formatFourCleanupThatWinsBeforeCreationLeavesNoAuthorityToCreateTheProcess()")
            rejects(audit.PERSISTENCE_STORE_CONFIGURATION_TEST_PATH,
                    "@Test\n    void aPasswordIsNotTrimmedBecauseItIsOpaque()",
                    "void aPasswordIsNotTrimmedBecauseItIsOpaque()")
            rejects(audit.PERSISTENCE_OWNERSHIP_CONFIGURATION_TEST_PATH,
                    "@Test\n    void theConfiguredNameIsUsedForBothRolesAndTheRolesStayDistinct()",
                    "void theConfiguredNameIsUsedForBothRolesAndTheRolesStayDistinct()")
            rejects(audit.PERSISTENCE_CLI_SELECTOR_TEST_PATH,
                    "@Test\n    void theSelectorSpellingMatchesTheServersOwn()",
                    "void theSelectorSpellingMatchesTheServersOwn()")
            rejects(audit.PERSISTENCE_AUDIT_DIRECTORY_PATH,
                    'public static final String ENVIRONMENT_VARIABLE = "RAVENROOT_AUDIT_DIR";',
                    'public static final String ENVIRONMENT_VARIABLE = "RAVENROOT_AUDIT_PATH";')
            rejects(audit.PERSISTENCE_AUDIT_DIRECTORY_PATH,
                    'public static final String DEFAULT_DIRECTORY = "./data/audit";',
                    'public static final String DEFAULT_DIRECTORY = "./audit";')
            rejects(audit.PERSISTENCE_AUDIT_DIRECTORY_PATH,
                    "raw == null || raw.isBlank() ? DEFAULT_DIRECTORY : raw.trim()",
                    "raw == null ? DEFAULT_DIRECTORY : raw.trim()")
            rejects(audit.PERSISTENCE_AUDIT_DIRECTORY_PATH,
                    "DEFAULT_DIRECTORY : raw.trim()",
                    "DEFAULT_DIRECTORY : raw")
            rejects(audit.PERSISTENCE_AUDIT_DIRECTORY_PATH,
                    "return new AuditTrailDirectory(Path.of(selected));",
                    "return new AuditTrailDirectory(Path.of(DEFAULT_DIRECTORY));")
            rejects(audit.PERSISTENCE_AUDIT_CONFIGURATION_PATH,
                    "AuditTrailDirectory.resolve(environment.get(DIRECTORY_VARIABLE))",
                    "AuditTrailDirectory.resolve(null)")
            rejects(audit.PERSISTENCE_SERVER_MAIN_PATH,
                    "AuditTrailConfiguration.fromEnvironment(System.getenv()).directory()",
                    "AuditTrailConfiguration.fromEnvironment(Map.of()).directory()")
            rejects(audit.PERSISTENCE_SERVER_MAIN_PATH,
                    "new FileAuditTrail(auditDirectory.path(), java.time.Clock.systemUTC(),",
                    "new FileAuditTrail(Path.of(\"./data/audit\"), java.time.Clock.systemUTC(),")
            rejects(audit.PERSISTENCE_BACKUP_CONFIGURATION_PATH,
                    "AuditTrailDirectory.resolve(environment.get(AUDIT_DIR_VARIABLE)).path()",
                    "AuditTrailDirectory.resolve(null).path()")
            rejects(audit.PERSISTENCE_SQLITE_LOCATION_PATH,
                    'public static final String DEFAULT_DIRECTORY = "./data/execution-store";',
                    'public static final String DEFAULT_DIRECTORY = "./execution-store";')
            rejects(audit.PERSISTENCE_SQLITE_LOCATION_PATH,
                    "raw == null || raw.isBlank() ? DEFAULT_DIRECTORY : raw.trim()",
                    "raw == null ? DEFAULT_DIRECTORY : raw.trim()")
            rejects(audit.PERSISTENCE_SQLITE_LOCATION_PATH,
                    "return underDirectory(Path.of(selected));",
                    "return underDirectory(Path.of(DEFAULT_DIRECTORY));")
            rejects(audit.PERSISTENCE_STORE_CONFIGURATION_PATH,
                    "SqliteStoreLocation.underConfiguredDirectory(environment.get(DIRECTORY_VARIABLE))",
                    "SqliteStoreLocation.underConfiguredDirectory(null)")
            rejects(audit.PERSISTENCE_BACKUP_CONFIGURATION_PATH,
                    "SqliteStoreLocation.underConfiguredDirectory(\n                environment.get(EXECUTION_STORE_DIR_VARIABLE))",
                    "SqliteStoreLocation.underConfiguredDirectory(\n                null)")
            rejects(audit.PERSISTENCE_STORE_CONFIGURATION_PATH,
                    "case \"false\", \"off\", \"0\", \"no\" -> false;",
                    "case \"false\", \"off\", \"0\" -> false;")
            rejects(audit.PERSISTENCE_STORE_CONFIGURATION_PATH,
                    "default -> throw new IllegalArgumentException(ENABLED_VARIABLE",
                    "default -> throw new UnsupportedOperationException(ENABLED_VARIABLE")
            rejects(audit.PERSISTENCE_DIRECTORY_PARITY_TEST_PATH,
                    "@Test\n    void auditDirectoryDefaultBlankAndNondefaultResolutionMatchesTheServer()",
                    "void auditDirectoryDefaultBlankAndNondefaultResolutionMatchesTheServer()")
            rejects(audit.PERSISTENCE_DIRECTORY_PARITY_TEST_PATH,
                    "@Test\n    void executionStoreDirectoryDefaultBlankAndNondefaultResolutionMatchesTheServer()",
                    "void executionStoreDirectoryDefaultBlankAndNondefaultResolutionMatchesTheServer()")
            rejects(audit.PERSISTENCE_STORE_CONFIGURATION_TEST_PATH,
                    "@Test\n    void acceptsOnlyTheCanonicalPositiveAndDocumentedNegativeAliases()",
                    "void acceptsOnlyTheCanonicalPositiveAndDocumentedNegativeAliases()")

            test_path = root / Path(
                "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/persistence/ManagedExecutionStoreTest.java")
            test_bytes = test_path.read_bytes()
            test_path.unlink()
            self.assertIsNone(audit.persistence_policy_authority_from_source(
                root, {candidate.id: candidate for candidate in audit.discover(root)}))
            test_path.write_bytes(test_bytes)
            ownership_path = root / audit.PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH
            ownership_bytes = ownership_path.read_bytes()
            ownership_path.unlink()
            self.assertIsNone(audit.persistence_policy_authority_from_source(
                root, {candidate.id: candidate for candidate in audit.discover(root)}))
            ownership_path.write_bytes(ownership_bytes)
            resolver.unlink()
            self.assertTrue(audit.persistence_policy_source_present(root))
            self.assertIsNone(audit.persistence_policy_authority_from_source(
                root, {candidate.id: candidate for candidate in audit.discover(root)}))

    def test_manifest_pin_authority_rejects_behavior_changes_that_keep_old_markers(self) -> None:
        paths = (
            audit.MANIFEST_PIN_CONFIGURATION_PATH, audit.MANIFEST_PIN_BOOTSTRAP_PATH,
            audit.MANIFEST_PIN_STORE_PATH,
            Path("ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/persistence/ExecutionStoreConfigurationTest.java"),
            Path("ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/ReplicaTopologyStartupCheckTest.java"),
            Path("ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/persistence/SharedExecutionStoreBootstrapSmokeTest.java"),
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in paths:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / relative).read_bytes())
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "add", *[path.as_posix() for path in paths]], cwd=root, check=True)

            def authority() -> object:
                discovered = {candidate.id: candidate for candidate in audit.discover(root)}
                return audit.manifest_pin_attempt_authorities(root, discovered)

            self.assertIsNotNone(authority())
            configuration = root / audit.MANIFEST_PIN_CONFIGURATION_PATH
            original = configuration.read_text(encoding="utf-8")
            mutations = (
                ("if (raw == null || raw.isBlank()) return fallback;",
                 "if (raw == null) return fallback; if (raw.isBlank()) return 1;"),
                ("return value;\n        } catch", "return fallback;\n        } catch"),
                ("throw new IllegalArgumentException(variable + \" must be a positive integer\");",
                 "return fallback;"),
                ("if (manifestPinAttempts < 1) {",
                 "if (false && manifestPinAttempts < 1) {"),
            )
            for before, after in mutations:
                with self.subTest(before=before):
                    self.assertEqual(1, original.count(before))
                    configuration.write_text(original.replace(before, after, 1), encoding="utf-8")
                    self.assertIsNone(authority())
                    configuration.write_text(original, encoding="utf-8")
    def route_table_authority_fixture(self, root: Path):
        paths = (
            audit.ROUTE_TABLE_PATH, audit.ROUTE_DESCRIPTOR_PATH, audit.OPENAPI_GENERATOR_PATH,
            audit.ROUTE_TABLE_TEST_PATH, audit.STABLE_EDGE_ID_PATH, audit.EDGE_WIRE_BUDGET_PATH,
            audit.STABLE_EDGE_TEST_PATH, audit.STABLE_EDGE_WIRE_TEST_PATH,
        )
        for relative in paths:
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / relative).read_bytes())
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        subprocess.run(["git", "add", *[path.as_posix() for path in paths]], cwd=root, check=True)

        route_source = (root / audit.ROUTE_TABLE_PATH).read_text(encoding="utf-8")
        parsed = audit.route_table_candidate_partitions(route_source)
        self.assertIsNotNone(parsed)
        partitions, details, source_candidates = parsed
        entries = {}
        for role, identifiers in partitions.items():
            for identifier in identifiers:
                entry = source_candidates[identifier].inventory_entry()
                entry.update(
                    status="retained",
                    classification=("published-contract-description" if role == "summary"
                                    else "protocol-or-format-invariant"),
                    rationale="Exact typed RouteDescriptor publication evidence.",
                    retainedAuthority=audit.ROUTE_TABLE_AUTHORITY_ID,
                )
                entries[identifier] = entry

        descriptor = (root / audit.ROUTE_DESCRIPTOR_PATH).read_text(encoding="utf-8")
        generator = (root / audit.OPENAPI_GENERATOR_PATH).read_text(encoding="utf-8")
        publication_test = (root / audit.ROUTE_TABLE_TEST_PATH).read_text(encoding="utf-8")
        edge_test = (root / audit.STABLE_EDGE_TEST_PATH).read_text(encoding="utf-8")
        wire_test = (root / audit.STABLE_EDGE_WIRE_TEST_PATH).read_text(encoding="utf-8")
        authority = {
            "kind": "java-route-descriptor-publication-v1",
            "candidateIdsByRole": partitions,
            "descriptorCandidateIds": [
                {"ordinal": detail["ordinal"], "path": detail["path"],
                 "candidateIds": detail["candidateIds"]}
                for detail in details
            ],
            "consumerBodyDigests": {
                "routeDescriptorValidation": audit.java_span_digest(
                    descriptor, audit.java_compact_constructor_span(descriptor, "RouteDescriptor")),
                "openApiGenerate": audit.java_method_digest(
                    generator, "OpenApiSpecGenerator", "generate"),
                "openApiPathEntry": audit.java_method_digest(
                    generator, "OpenApiSpecGenerator", "pathEntry"),
                "openApiOperationEntry": audit.java_method_digest(
                    generator, "OpenApiSpecGenerator", "operationEntry"),
                "openApiSuccessResponse": audit.java_method_digest(
                    generator, "OpenApiSpecGenerator", "successResponse"),
            },
            "publicationTestAuthority": {
                "testBodyDigest": audit.java_method_digest(
                    publication_test, "RouteTableSpecServerAgreementTest",
                    "theCheckedInSpecMatchesWhatTheTableGeneratesRightNow"),
                "checkedInSpecBodyDigest": audit.java_method_digest(
                    publication_test, "RouteTableSpecServerAgreementTest", "checkedInSpec"),
            },
            "boundTestBodyDigests": {
                "StableEdgeIdContractTest": {
                    method: audit.java_method_digest(edge_test, "StableEdgeIdContractTest", method)
                    for method in (
                        "acceptsTheExactUtf8BoundWithoutChangingIdentityAndRejectsOneByteMore",
                        "auxiliaryReserveIsEnforcedAsOneCombinedEscapedByteBudget",
                    )
                },
                "StableEdgeIdWireContractTest": {
                    method: audit.java_method_digest(
                        wire_test, "StableEdgeIdWireContractTest", method)
                    for method in (
                        "worstCaseEscapedMaximumFitsTheCompleteRuntimeClientFrame",
                        "saturatedLiveAndLogFieldsStillFitWithTheMaximumEscapedIdentity",
                        "saturatedDurableProjectionAndPayloadStayInsideTheirExplicitBounds",
                    )
                },
            },
            "publishedBoundClauses": {
                identifier: list(fields)
                for identifier, fields in audit.ROUTE_BOUND_CANDIDATES.items()
            },
        }
        return authority, entries, source_candidates, details

    def route_table_errors(self, root: Path, authority: dict, entries: dict,
                           candidates: dict) -> list[str]:
        return audit.route_table_authority_errors(
            root, {audit.ROUTE_TABLE_AUTHORITY_ID: authority}, entries, candidates,
        )

    def test_route_table_authority_proves_all_508_positions_consumers_and_bounds(self) -> None:
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            authority, entries, candidates, details = self.route_table_authority_fixture(root)
            self.assertEqual(53, len(details))
            self.assertEqual(
                {"methods": 60, "path": 53, "summary": 348, "successStatuses": 54},
                {role: len(ids) for role, ids in authority["candidateIdsByRole"].items()},
            )
            self.assertEqual(515, len(entries))
            self.assertEqual([], self.route_table_errors(root, authority, entries, candidates))
            self.assertEqual({
                "StableEdgeId.MAX_UTF8_BYTES": 8192,
                "EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES": 12287,
                "StableEdgeId.SSE_FRAME_MAX_BYTES": 65536,
            }, audit.route_bound_values(root))
            self.assertIsNone(audit.java_int_expression_value(
                "2147483647 + 1", lambda _name: None))
            self.assertIsNone(audit.java_int_expression_value("1 / 0", lambda _name: None))
            self.assertIsNone(audit.java_int_expression_value("external()", lambda _name: None))

    def test_route_table_authority_rejects_metadata_and_position_mutations(self) -> None:
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            authority, entries, candidates, _details = self.route_table_authority_fixture(root)

            missing = copy.deepcopy(authority)
            missing["candidateIdsByRole"]["methods"].pop()
            errors = self.route_table_errors(root, missing, entries, candidates)
            self.assertTrue(any("positional partitions have drifted" in error for error in errors), errors)

            for identifier in audit.ROUTE_BOUND_CANDIDATES:
                with self.subTest(missing_bound_candidate_subset=identifier):
                    wrong_subset = copy.deepcopy(authority)
                    wrong_subset["publishedBoundClauses"].pop(identifier)
                    errors = self.route_table_errors(root, wrong_subset, entries, candidates)
                    self.assertTrue(any("exact candidate-specific authorities" in error
                                        for error in errors), errors)
            wrong_subset = copy.deepcopy(authority)
            wrong_subset["publishedBoundClauses"][next(iter(audit.ROUTE_BOUND_CANDIDATES))] = [
                "StableEdgeId.MAX_UTF8_BYTES", "StableEdgeId.SSE_FRAME_MAX_BYTES",
            ]
            errors = self.route_table_errors(root, wrong_subset, entries, candidates)
            self.assertTrue(any("exact candidate-specific authorities" in error
                                for error in errors), errors)

            wrong_classification = copy.deepcopy(entries)
            summary_id = authority["candidateIdsByRole"]["summary"][0]
            wrong_classification[summary_id]["classification"] = "protocol-or-format-invariant"
            errors = self.route_table_errors(root, authority, wrong_classification, candidates)
            self.assertTrue(any(summary_id in error and "exact retained positional authority" in error
                                for error in errors), errors)

            missing_consumer = copy.deepcopy(authority)
            missing_consumer["consumerBodyDigests"].pop("openApiOperationEntry")
            errors = self.route_table_errors(root, missing_consumer, entries, candidates)
            self.assertTrue(any("exact typed consumer body digests" in error
                                for error in errors), errors)

            route_path = root / audit.ROUTE_TABLE_PATH
            original_route = route_path.read_text(encoding="utf-8")
            route_path.write_text(original_route.replace(
                'Set.of("GET"), "/health", "Liveness probe."',
                'Set.of("GET"), "Liveness probe.", "/health"', 1,
            ), encoding="utf-8")
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("not the supported direct RouteDescriptor table" in error
                                for error in errors), errors)

            route_path.write_text(original_route.replace(
                '"Liveness probe."', 'summary()', 1,
            ), encoding="utf-8")
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("not the supported direct RouteDescriptor table" in error
                                for error in errors), errors)

            chained = original_route.replace(
                "                    concat(STANDARD_ERRORS, ErrorCode.INVALID_REQUEST.code()), NEVER, false));",
                "                    concat(STANDARD_ERRORS, ErrorCode.INVALID_REQUEST.code()), NEVER, false))"
                ".stream().filter(route -> false).toList();",
                1,
            )
            self.assertNotEqual(original_route, chained)
            route_path.write_text(chained, encoding="utf-8")
            refreshed_candidates = {
                candidate.id: candidate
                for _offset, candidate in audit.java_source_candidates(audit.ROUTE_TABLE_PATH, chained)
            }
            old_rows = list(candidates.values())
            new_rows = list(refreshed_candidates.values())
            self.assertEqual(len(old_rows), len(new_rows))
            self.assertEqual(
                [(row.symbol, row.kind, row.role, row.expression) for row in old_rows],
                [(row.symbol, row.kind, row.role, row.expression) for row in new_rows],
            )
            remapped = {old.id: new.id for old, new in zip(old_rows, new_rows)}
            refreshed_entries = {}
            for old_id, entry in entries.items():
                candidate = refreshed_candidates[remapped[old_id]]
                refreshed = candidate.inventory_entry()
                refreshed.update({
                    key: value for key, value in entry.items()
                    if key in {"status", "classification", "rationale", "retainedAuthority"}
                })
                refreshed_entries[candidate.id] = refreshed
            refreshed_authority = copy.deepcopy(authority)
            refreshed_authority["candidateIdsByRole"] = {
                role: [remapped[identifier] for identifier in identifiers]
                for role, identifiers in authority["candidateIdsByRole"].items()
            }
            for descriptor in refreshed_authority["descriptorCandidateIds"]:
                descriptor["candidateIds"] = {
                    role: [remapped[identifier] for identifier in identifiers]
                    for role, identifiers in descriptor["candidateIds"].items()
                }
            refreshed_authority["publishedBoundClauses"] = {
                remapped[identifier]: fields
                for identifier, fields in authority["publishedBoundClauses"].items()
            }
            errors = self.route_table_errors(
                root, refreshed_authority, refreshed_entries, refreshed_candidates)
            self.assertTrue(any("not the supported direct RouteDescriptor table" in error
                                for error in errors), errors)

            route_path.write_text(
                original_route.replace(
                    "package ai.ravenroot.server.spec;",
                    "package ai.ravenroot.server.spec;\n\nimport example.RouteDescriptor;",
                    1,
                ),
                encoding="utf-8",
            )
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("does not resolve the same-package RouteDescriptor type" in error
                                for error in errors), errors)

            route_path.write_text(original_route.replace(
                '"Liveness probe."', '"Health." /* "Liveness probe." */', 1,
            ), encoding="utf-8")
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("not the supported direct RouteDescriptor table" in error
                                for error in errors), errors)

            route_path.write_text(original_route.replace("8192", "8193", 1), encoding="utf-8")
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("summary lost typed bound clause" in error for error in errors), errors)

            route_path.write_text(original_route.rsplit("}", 1)[0]
                                  + '  static final String MAX_ROUTE_SUMMARY = "Liveness probe.";\n}\n',
                                  encoding="utf-8")
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("partition every RouteTable candidate exactly once" in error
                                for error in errors), errors)
            route_path.write_text(original_route, encoding="utf-8")

    def test_route_table_authority_rejects_consumer_test_and_typed_bound_mutations(self) -> None:
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            authority, entries, candidates, _details = self.route_table_authority_fixture(root)

            generator_path = root / audit.OPENAPI_GENERATOR_PATH
            original_generator = generator_path.read_text(encoding="utf-8")
            generator_path.write_text(original_generator.replace(
                "JsonStrings.escape(route.summary())", "JsonStrings.escape(route.path())", 1,
            ), encoding="utf-8")
            mutated = generator_path.read_text(encoding="utf-8")
            changed = copy.deepcopy(authority)
            changed["consumerBodyDigests"]["openApiOperationEntry"] = audit.java_method_digest(
                mutated, "OpenApiSpecGenerator", "operationEntry")
            errors = self.route_table_errors(root, changed, entries, candidates)
            self.assertTrue(any("lost JsonStrings.escape(route.summary())" in error
                                for error in errors), errors)
            generator_path.write_text(original_generator, encoding="utf-8")

            direct_receiver_shadow = original_generator.replace(
                "public final class OpenApiSpecGenerator {",
                """public final class OpenApiSpecGenerator {
    private static final ShadowCollectors Collectors = new ShadowCollectors();
    private static final class ShadowCollectors {
        java.util.stream.Collector<CharSequence, ?, String> joining(CharSequence delimiter) {
            return java.util.stream.Collectors.joining(delimiter);
        }
    }""",
                1,
            )
            generator_path.write_text(direct_receiver_shadow, encoding="utf-8")
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("import/receiver identity has drifted" in error
                                for error in errors), errors)
            generator_path.write_text(original_generator, encoding="utf-8")

            local_receiver_shadow = original_generator.replace(
                "public final class OpenApiSpecGenerator {",
                """public final class OpenApiSpecGenerator {
    private static final class ShadowJsonStrings {
        String escape(String value) { return "shadow"; }
    }""",
                1,
            )
            local_receiver_shadow = local_receiver_shadow.replace(
                "private static String operationEntry(RouteDescriptor route, String method) {",
                "private static String operationEntry(RouteDescriptor route, String method) {\n"
                "        ShadowJsonStrings JsonStrings = new ShadowJsonStrings();",
                1,
            )
            generator_path.write_text(local_receiver_shadow, encoding="utf-8")
            changed = copy.deepcopy(authority)
            changed["consumerBodyDigests"]["openApiOperationEntry"] = audit.java_method_digest(
                local_receiver_shadow, "OpenApiSpecGenerator", "operationEntry")
            errors = self.route_table_errors(root, changed, entries, candidates)
            self.assertTrue(any("import/receiver identity has drifted" in error
                                for error in errors), errors)
            generator_path.write_text(original_generator, encoding="utf-8")

            qualified_local_shadow = local_receiver_shadow.replace(
                "ShadowJsonStrings JsonStrings =",
                "OpenApiSpecGenerator.ShadowJsonStrings JsonStrings =",
                1,
            )
            self.assertNotEqual(local_receiver_shadow, qualified_local_shadow)
            generator_path.write_text(qualified_local_shadow, encoding="utf-8")
            changed = copy.deepcopy(authority)
            changed["consumerBodyDigests"]["openApiOperationEntry"] = audit.java_method_digest(
                qualified_local_shadow, "OpenApiSpecGenerator", "operationEntry")
            errors = self.route_table_errors(root, changed, entries, candidates)
            self.assertTrue(any("import/receiver identity has drifted" in error
                                for error in errors), errors)
            generator_path.write_text(original_generator, encoding="utf-8")

            generate_span = audit.java_method_span(
                original_generator, "OpenApiSpecGenerator", "generate")
            self.assertIsNotNone(generate_span)
            start, end = generate_span
            ignored_routes = original_generator[start:end].replace(
                "json.append(routes.stream()", "String ignored = routes.stream()", 1)
            ignored_routes = ignored_routes.replace(
                '.collect(Collectors.joining(",\\n")));',
                '.collect(Collectors.joining(",\\n"));\n        json.append("");', 1)
            self.assertNotEqual(original_generator[start:end], ignored_routes)
            generator_path.write_text(
                original_generator[:start] + ignored_routes + original_generator[end:],
                encoding="utf-8",
            )
            changed = copy.deepcopy(authority)
            mutated = generator_path.read_text(encoding="utf-8")
            changed["consumerBodyDigests"]["openApiGenerate"] = audit.java_method_digest(
                mutated, "OpenApiSpecGenerator", "generate")
            errors = self.route_table_errors(root, changed, entries, candidates)
            self.assertTrue(any("routes-to-pathEntry append chain" in error for error in errors), errors)
            generator_path.write_text(original_generator, encoding="utf-8")

            success_span = audit.java_method_span(
                original_generator, "OpenApiSpecGenerator", "successResponse")
            self.assertIsNotNone(success_span)
            start, end = success_span
            expected_status = 'return "          \\"" + status + "\\": {\\"description\\": \\"success\\""'
            lost_status = original_generator[start:end].replace(
                expected_status,
                "// " + expected_status + "\n"
                + '        return "          \\"" + 200 + "\\": {\\"description\\": \\"success\\""',
                1,
            )
            self.assertNotEqual(original_generator[start:end], lost_status)
            generator_path.write_text(
                original_generator[:start] + lost_status + original_generator[end:],
                encoding="utf-8",
            )
            changed = copy.deepcopy(authority)
            mutated = generator_path.read_text(encoding="utf-8")
            changed["consumerBodyDigests"]["openApiSuccessResponse"] = audit.java_method_digest(
                mutated, "OpenApiSpecGenerator", "successResponse")
            errors = self.route_table_errors(root, changed, entries, candidates)
            self.assertTrue(any("successResponse lost status serialization" in error
                                for error in errors), errors)
            generator_path.write_text(original_generator, encoding="utf-8")

            generator_path.write_text(
                original_generator.replace(
                    "package ai.ravenroot.server.spec;",
                    "package ai.ravenroot.server.spec;\n\nimport example.RouteDescriptor;",
                    1,
                ),
                encoding="utf-8",
            )
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("does not resolve the same-package RouteDescriptor type" in error
                                for error in errors), errors)
            generator_path.write_text(original_generator, encoding="utf-8")

            generator_path.write_text(
                original_generator.rsplit("}", 1)[0]
                + "  private static final class RouteDescriptor {}\n}\n",
                encoding="utf-8",
            )
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("does not resolve the same-package RouteDescriptor type" in error
                                for error in errors), errors)
            generator_path.write_text(original_generator, encoding="utf-8")

            descriptor_path = root / audit.ROUTE_DESCRIPTOR_PATH
            original_descriptor = descriptor_path.read_text(encoding="utf-8")
            descriptor_path.write_text(original_descriptor.replace(
                "Set.of(successStatus)", "Set.of(200)", 1,
            ), encoding="utf-8")
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("convenience constructor lost positional forwarding" in error
                                for error in errors), errors)
            descriptor_path.write_text(original_descriptor, encoding="utf-8")

            test_path = root / audit.ROUTE_TABLE_TEST_PATH
            original_test = test_path.read_text(encoding="utf-8")
            test_path.write_text(original_test.replace(
                "    @Test\n    void theCheckedInSpecMatchesWhatTheTableGeneratesRightNow",
                "    void theCheckedInSpecMatchesWhatTheTableGeneratesRightNow", 1,
            ), encoding="utf-8")
            changed = copy.deepcopy(authority)
            changed_test = test_path.read_text(encoding="utf-8")
            changed["publicationTestAuthority"]["testBodyDigest"] = audit.java_method_digest(
                changed_test, "RouteTableSpecServerAgreementTest",
                "theCheckedInSpecMatchesWhatTheTableGeneratesRightNow")
            errors = self.route_table_errors(root, changed, entries, candidates)
            self.assertTrue(any("not an exact runnable @Test" in error for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            test_path.write_text(
                original_test.replace(
                    "import static org.junit.jupiter.api.Assertions.assertEquals;",
                    "import static example.Assertions.assertEquals;",
                    1,
                ),
                encoding="utf-8",
            )
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("test type/import/TempDir identity has drifted" in error
                                for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            test_path.write_text(
                original_test.rsplit("}", 1)[0]
                + "  private static void assertTrue(Object... ignored) {}\n"
                + "  private static void assertTrue(boolean ignored) {}\n}\n",
                encoding="utf-8",
            )
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("test type/import/TempDir identity has drifted" in error
                                for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            test_path.write_text(
                original_test.rsplit("}", 1)[0]
                + "  private static final class OpenApiSpecGenerator {}\n}\n",
                encoding="utf-8",
            )
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("test type/import/TempDir identity has drifted" in error
                                for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            test_path.write_text(original_test.replace(
                "import org.junit.jupiter.api.Test;", "import example.fake.Test;", 1,
            ), encoding="utf-8")
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("test type/import/TempDir identity has drifted" in error
                                for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            test_path.write_text(original_test.replace("readAllBytes()", "toString()", 1),
                                 encoding="utf-8")
            changed = copy.deepcopy(authority)
            changed_test = test_path.read_text(encoding="utf-8")
            changed["publicationTestAuthority"]["checkedInSpecBodyDigest"] = audit.java_method_digest(
                changed_test, "RouteTableSpecServerAgreementTest", "checkedInSpec")
            errors = self.route_table_errors(root, changed, entries, candidates)
            self.assertTrue(any("checkedInSpec helper closure has drifted" in error
                                for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            constant_path = root / audit.STABLE_EDGE_ID_PATH
            original_constant = constant_path.read_text(encoding="utf-8")
            constant_path.write_text(original_constant.replace("64 * 1024", "32 * 1024", 1),
                                     encoding="utf-8")
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("summary lost typed bound clause" in error for error in errors), errors)
            constant_path.write_text(original_constant, encoding="utf-8")

            edge_budget_path = root / audit.EDGE_WIRE_BUDGET_PATH
            original_budget = edge_budget_path.read_text(encoding="utf-8")
            edge_budget_path.write_text(
                original_budget.replace(
                    "package ai.ravenroot.api.application;",
                    "package ai.ravenroot.api.application;\n\nimport example.StableEdgeId;",
                    1,
                ),
                encoding="utf-8",
            )
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("typed wire-bound constants are not resolvable" in error
                                for error in errors), errors)
            edge_budget_path.write_text(original_budget, encoding="utf-8")

            edge_test_path = root / audit.STABLE_EDGE_TEST_PATH
            original_edge_test = edge_test_path.read_text(encoding="utf-8")
            method = "acceptsTheExactUtf8BoundWithoutChangingIdentityAndRejectsOneByteMore"
            span = audit.java_method_span(original_edge_test, "StableEdgeIdContractTest", method)
            self.assertIsNotNone(span)
            start, end = span
            mutated_method = original_edge_test[start:end].replace(
                "StableEdgeId.MAX_UTF8_BYTES", "1")
            edge_test_path.write_text(
                original_edge_test[:start] + mutated_method + original_edge_test[end:],
                encoding="utf-8",
            )
            changed = copy.deepcopy(authority)
            changed_source = edge_test_path.read_text(encoding="utf-8")
            changed["boundTestBodyDigests"]["StableEdgeIdContractTest"][method] = \
                audit.java_method_digest(changed_source, "StableEdgeIdContractTest", method)
            errors = self.route_table_errors(root, changed, entries, candidates)
            self.assertTrue(any(f"{method} lost StableEdgeId.MAX_UTF8_BYTES" in error
                                for error in errors), errors)

            edge_test_path.write_text(
                original_edge_test.rsplit("}", 1)[0]
                + "  private static final class StableEdgeId {}\n}\n",
                encoding="utf-8",
            )
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("typed-bound test authority StableEdgeIdContractTest is incomplete"
                                in error for error in errors), errors)

            edge_test_path.write_text(
                original_edge_test.replace(
                    "package ai.ravenroot.api.application;",
                    "package ai.ravenroot.api.application;\n\nimport example.StableEdgeId;",
                    1,
                ),
                encoding="utf-8",
            )
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("typed-bound test authority StableEdgeIdContractTest is incomplete"
                                in error for error in errors), errors)

            edge_test_path.write_text(
                original_edge_test.rsplit("}", 1)[0]
                + "  private static void assertThrows(Object... ignored) {}\n}\n",
                encoding="utf-8",
            )
            errors = self.route_table_errors(root, authority, entries, candidates)
            self.assertTrue(any("typed-bound test authority StableEdgeIdContractTest is incomplete"
                                in error for error in errors), errors)

    def test_published_contract_description_cannot_classify_an_arbitrary_string(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            inventory = root / "scripts/operational-configuration-inventory.json"
            document = json.loads(inventory.read_text(encoding="utf-8"))
            entry = next(item for item in document["entries"] if item["status"] == "pending-review")
            entry.update(
                status="retained", classification="published-contract-description",
                rationale="This arbitrary source string is not a published RouteDescriptor summary.",
                retainedAuthority=audit.ROUTE_TABLE_AUTHORITY_ID,
            )
            errors = audit.inventory_errors(root, document, audit.discover(root))
            self.assertTrue(any("published-contract-description requires a closed publication authority"
                                in error for error in errors), errors)

    def test_environment_reference_publication_authority_has_source_derived_membership(self) -> None:
        candidates = audit.discover(ROOT)
        eligible = audit.environment_reference_description_candidate_ids(ROOT, candidates)
        websocket_boundary = next(candidate for candidate in candidates
                                  if candidate.path == audit.ENVIRONMENT_REFERENCE_PATH.as_posix()
                                  and candidate.symbol == "boundary"
                                  and candidate.role == "RAVENROOT_WEBSOCKET_")
        self.assertIn(websocket_boundary.id, eligible)
        unsupported_boundary = audit.Candidate(
            id="oc-unsupported-boundary", path=audit.ENVIRONMENT_REFERENCE_PATH.as_posix(),
            line=1, symbol="boundary", kind="environment-binding",
            role="RAVENROOT_NOT_A_REAL_FAMILY_", expression="RAVENROOT_NOT_A_REAL_FAMILY_",
            expression_digest="expression", evidence="unsupported",
            evidence_digest="evidence", surface="script",
        )
        off_path_boundary = audit.Candidate(
            id="oc-off-path-boundary", path="scripts/other_generator.py",
            line=1, symbol="boundary", kind="environment-binding",
            role="RAVENROOT_WEBSOCKET_", expression="RAVENROOT_WEBSOCKET_",
            expression_digest="expression", evidence="off path",
            evidence_digest="evidence", surface="script",
        )
        widened = audit.environment_reference_description_candidate_ids(
            ROOT, (*candidates, unsupported_boundary, off_path_boundary))
        self.assertNotIn(unsupported_boundary.id, widened)
        self.assertNotIn(off_path_boundary.id, widened)
        document = json.loads(audit.INVENTORY.read_text(encoding="utf-8"))
        authorized = {entry["id"] for entry in document["entries"]
                      if entry.get("retainedAuthority") ==
                      audit.ENVIRONMENT_REFERENCE_AUTHORITY_ID}
        self.assertEqual(authorized, authorized & eligible)

        arbitrary = next(entry for entry in document["entries"]
                         if entry.get("path") == audit.ENVIRONMENT_REFERENCE_PATH.as_posix()
                         and entry["id"] not in eligible)
        arbitrary.update(
            status="retained", classification="published-contract-description",
            rationale="Arbitrary text in the generator is not a published environment row.",
            retainedAuthority=audit.ENVIRONMENT_REFERENCE_AUTHORITY_ID,
        )
        errors = audit.inventory_errors(ROOT, document, candidates)
        self.assertTrue(any("published-contract-description requires a closed publication authority"
                            in error for error in errors), errors)

    def assistant_limit_authority_fixture(self, root: Path):
        paths = (
            audit.ASSISTANT_CONFIGURATION_PATH, audit.ASSISTANT_CONFIGURATION_TEST_PATH,
            audit.ASSISTANT_SERVICE_PATH, audit.ASSISTANT_SERVICE_TEST_PATH,
            audit.ASSISTANT_PLATFORM_TEST_PATH, Path("compose.yaml"),
            Path("docs/examples/assistant/compose.override.yaml"),
            Path("deploy/helm/ravenroot/values.yaml"),
            Path("deploy/helm/ravenroot/values.schema.json"),
            Path("deploy/helm/ravenroot/templates/deployment.yaml"),
            Path("deploy/kubernetes/ravenroot.yaml"),
        )
        pinned_revision = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, check=True,
            capture_output=True, text=True,
        ).stdout.strip()
        before_source = subprocess.run(
            ["git", "show", "e60a099ebbd900e38f3bb004564d8f5125a887c4:"
             + audit.ASSISTANT_CONFIGURATION_PATH.as_posix()],
            cwd=ROOT, check=True, capture_output=True, text=True,
        ).stdout
        for relative in paths:
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(subprocess.run(
                ["git", "show", f"{pinned_revision}:{relative.as_posix()}"], cwd=ROOT,
                check=True, capture_output=True,
            ).stdout)
        (root / audit.ASSISTANT_CONFIGURATION_PATH).write_text(before_source, encoding="utf-8")
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        subprocess.run(["git", "add", "."], cwd=root, check=True)
        subprocess.run([
            "git", "-c", "user.name=Audit Test", "-c", "user.email=audit@example.invalid",
            "commit", "-qm", "before assistant bindings",
        ], cwd=root, check=True)
        before_revision = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=root, check=True,
            capture_output=True, text=True).stdout.strip()
        current_source = (ROOT / audit.ASSISTANT_CONFIGURATION_PATH).read_text(encoding="utf-8")
        (root / audit.ASSISTANT_CONFIGURATION_PATH).write_text(current_source, encoding="utf-8")
        subprocess.run(["git", "add", audit.ASSISTANT_CONFIGURATION_PATH.as_posix()],
                       cwd=root, check=True)
        subprocess.run([
            "git", "-c", "user.name=Audit Test", "-c", "user.email=audit@example.invalid",
            "commit", "-qm", "add assistant bindings",
        ], cwd=root, check=True)
        after_revision = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=root, check=True,
            capture_output=True, text=True).stdout.strip()

        candidates = {candidate.id: candidate for candidate in audit.discover(root)}
        specs = audit.assistant_limit_source_specs(current_source)
        self.assertIsNotNone(specs)
        source_rows = audit.java_source_candidates(audit.ASSISTANT_CONFIGURATION_PATH, current_source)
        entries = {}
        setting_authorities = []
        conversions = {}
        carriers = {}
        for spec in specs:
            setting = spec["setting"]
            declaration_ids = sorted(
                candidate.id for offset, candidate in source_rows
                if spec["environmentSpan"][0] <= offset < spec["environmentSpan"][1])
            environment_ids = [
                candidate.id for offset, candidate in source_rows
                if spec["environmentSpan"][0] <= offset < spec["environmentSpan"][1]
                and candidate.kind == "environment-binding"
                and candidate.expression == spec["environment"]
            ]
            default_ids = [
                candidate.id for offset, candidate in source_rows
                if spec["defaultSpan"][0] <= offset < spec["defaultSpan"][1]
                and candidate.kind == "fixed-declaration"
                and candidate.role == spec["defaultSymbol"]
            ]
            carrier_ids = {
                group: sorted(candidate.id for candidate in candidates.values()
                              if candidate.path in paths_for_group
                              and candidate.kind == "environment-binding"
                              and candidate.expression == spec["environment"])
                for group, paths_for_group in audit.ASSISTANT_CARRIER_PATHS.items()
            }
            assigned = set(declaration_ids) | set(default_ids) | {
                identifier for identifiers in carrier_ids.values() for identifier in identifiers}
            for identifier in assigned:
                entry = candidates[identifier].inventory_entry()
                entry.update(setting=setting, status="converted", classification="operator-configurable")
                entries[identifier] = entry
            setting_authority = {
                "setting": setting,
                "bindingAuthority": {
                    "kind": "java-symbol-environment-constructor-v1",
                    "sourceOwner": (audit.ASSISTANT_CONFIGURATION_PATH.as_posix()
                                    + "#AssistantConfiguration"),
                    "method": "fromEnvironment", "constructorType": "AssistantConfiguration",
                    "component": spec["component"], "componentIndex": spec["componentIndex"],
                    "helper": "boundedPositiveInteger",
                    "environmentSymbol": spec["environmentSymbol"],
                    "environment": spec["environment"],
                    "environmentCandidateId": environment_ids[0],
                    "declarationCandidateIds": declaration_ids,
                    "callDigest": audit.hashlib.sha256(spec["call"].encode("utf-8")).hexdigest(),
                    "resolverAuthority": "assistant-bounded-positive-integer-v1",
                },
                "defaultAuthority": {
                    "kind": "java-static-final-int-default-v1",
                    "owner": (audit.ASSISTANT_CONFIGURATION_PATH.as_posix()
                              + "#AssistantConfiguration"),
                    "field": spec["component"], "componentIndex": spec["componentIndex"],
                    "constant": spec["defaultSymbol"],
                    "sourceExpression": spec["defaultExpression"],
                    "candidateIds": default_ids, "evaluatedDefault": spec["defaultValue"],
                },
            }
            setting_authorities.append(setting_authority)
            conversions[setting] = {
                "kind": "java-constructor-binding-conversion-v1", "issue": "#225",
                "beforeRevision": before_revision, "afterRevision": after_revision,
                "path": audit.ASSISTANT_CONFIGURATION_PATH.as_posix(),
                "ownerType": "AssistantConfiguration", "method": "fromEnvironment",
                "constructorType": "AssistantConfiguration", "component": spec["component"],
                "componentIndex": spec["componentIndex"],
                "beforeArgument": spec["defaultSymbol"], "afterArgument": spec["call"],
                "environmentSymbol": spec["environmentSymbol"],
                "environment": spec["environment"], "defaultSymbol": spec["defaultSymbol"],
            }
            carriers[setting] = {
                "environment": spec["environment"], "expectedCandidateIds": carrier_ids,
            }
            common = {
                "setting": setting, "status": "converted",
                "classification": "operator-configurable",
                "rationale": "Operator-tightenable assistant provider resource bound.",
                "owner": (audit.ASSISTANT_CONFIGURATION_PATH.as_posix()
                          + "#AssistantConfiguration"),
                "field": spec["component"], "bindings": [spec["environment"]],
                "default": str(spec["defaultValue"]),
                "defaultEvidence": default_ids,
                "validation": f"whole integer from 1 to {spec['defaultValue']}",
                "scope": "live AssistantService request processing",
                "pinning": "live process-startup configuration; not durable",
                "coverage": "closed Java and deployment carrier family",
                "bindingAuthority": setting_authority["bindingAuthority"],
                "defaultAuthority": setting_authority["defaultAuthority"],
                "carrierEvidence": carriers[setting],
                "conversion": conversions[setting],
            }
            for identifier in assigned:
                entries[identifier].update(copy.deepcopy(common))

        config_test = (root / audit.ASSISTANT_CONFIGURATION_TEST_PATH).read_text(encoding="utf-8")
        service = (root / audit.ASSISTANT_SERVICE_PATH).read_text(encoding="utf-8")
        service_test = (root / audit.ASSISTANT_SERVICE_TEST_PATH).read_text(encoding="utf-8")
        resolver_roles = (
            "assistantOperationalLimitsDefaultAndTightenIndependently",
            "invalidAssistantOperationalLimitsAreCauseFreeAndDoNotEchoValues",
            "compactConstructorKeepsItsCompatibilityFallbacks",
        )
        authority = {
            "kind": "assistant-symbol-operational-limits-v1",
            "settings": setting_authorities,
            "resolverAuthority": {
                "kind": "java-symbol-bounded-positive-integer-resolver-v1",
                "path": audit.ASSISTANT_CONFIGURATION_PATH.as_posix(),
                "type": "AssistantConfiguration", "factoryMethod": "fromEnvironment",
                "factoryBodyDigest": audit.java_method_digest(
                    current_source, "AssistantConfiguration", "fromEnvironment"),
                "integerMethod": "boundedPositiveInteger",
                "integerBodyDigest": audit.java_method_digest(
                    current_source, "AssistantConfiguration", "boundedPositiveInteger"),
                "dependencyBodyDigests": {
                    method: audit.java_method_digest(current_source, "AssistantConfiguration", method)
                    for method in ("trimmed", "boundedIntegerRefusal")
                },
                "testPath": audit.ASSISTANT_CONFIGURATION_TEST_PATH.as_posix(),
                "testType": "AssistantConfigurationTest",
                "testBodyDigests": {
                    method: audit.java_method_digest(config_test, "AssistantConfigurationTest", method)
                    for method in resolver_roles
                },
                "testHelperBodyDigests": {
                    "assertInvalidLimit": audit.java_method_digest(
                        config_test, "AssistantConfigurationTest", "assertInvalidLimit"),
                },
            },
            "conversionAuthorities": conversions,
            "carrierEvidence": carriers,
            "consumerAuthority": {
                "path": audit.ASSISTANT_SERVICE_PATH.as_posix(), "type": "AssistantService",
                "method": "send", "bodyDigest": audit.java_method_digest(
                    service, "AssistantService", "send"),
                "testPath": audit.ASSISTANT_SERVICE_TEST_PATH.as_posix(),
                "testType": "AssistantGraphProposalTest",
                "testBodyDigest": audit.java_method_digest(
                    service_test, "AssistantGraphProposalTest",
                    "configuredOperationalLimitsReachEveryRequestAndStopTheProviderLoop"),
            },
            "compatibilityAuthority": {
                "constructorBodyDigest": audit.java_span_digest(
                    current_source,
                    audit.java_compact_constructor_span(current_source, "AssistantConfiguration")),
                "testBodyDigest": audit.java_method_digest(
                    config_test, "AssistantConfigurationTest",
                    "compactConstructorKeepsItsCompatibilityFallbacks"),
            },
            "platformTestDigest": audit.hashlib.sha256(
                (root / audit.ASSISTANT_PLATFORM_TEST_PATH).read_bytes()).hexdigest(),
        }
        return {audit.ASSISTANT_LIMIT_FAMILY_ID: authority}, entries, candidates

    def assistant_limit_errors(self, root: Path, authorities, entries, candidates):
        return audit.assistant_limit_authority_errors(root, authorities, entries, candidates)

    def assistant_limit_inventory_document(self, authorities, entries, candidates):
        selected = tuple(candidate for candidate in candidates.values() if candidate.id in entries)
        return ({
            "schemaVersion": audit.SCHEMA_VERSION,
            "reconciliationRequired": False,
            "entries": [copy.deepcopy(entries[identifier]) for identifier in sorted(entries)],
            "retiredEntries": [], "migrationHistory": [],
            "evidenceRecords": {
                candidate.evidence_digest: candidate.evidence for candidate in selected},
            "assistantLimitAuthorities": copy.deepcopy(authorities),
        }, selected)

    def test_assistant_two_limit_inventory_adapter_preserves_generic_checks(self) -> None:
        # The assistant fixture has only its synthetic deployment carriers; the complete chart
        # authority is exercised by the unmocked Helm tests.
        with tempfile.TemporaryDirectory() as location, \
                mock.patch.object(audit, "helm_authority_errors", return_value=[]):
            root = Path(location)
            authorities, entries, candidates = self.assistant_limit_authority_fixture(root)
            document, selected = self.assistant_limit_inventory_document(
                authorities, entries, candidates)
            self.assertEqual([], audit.inventory_errors(root, document, selected))

            def errors_after(change):
                changed = copy.deepcopy(document)
                change(changed)
                return audit.inventory_errors(root, changed, selected)

            first = 0
            for field in ("defaultEvidence", "bindingAuthority", "defaultAuthority",
                          "carrierEvidence", "conversion"):
                self.assertTrue(errors_after(
                    lambda changed, field=field: changed["entries"][first].pop(field)), field)

            self.assertTrue(any("must all be converted operator settings" in error
                                for error in errors_after(
                                    lambda changed: changed["entries"][first].update(
                                        status="pending-review", classification=None))))
            self.assertTrue(any("must all be converted operator settings" in error
                                for error in errors_after(
                                    lambda changed: changed["entries"][first].update(
                                        status="retained",
                                        classification="protocol-or-format-invariant"))))
            self.assertTrue(any("absent or assigned elsewhere" in error
                                for error in errors_after(
                                    lambda changed: changed["entries"][first].pop("setting"))))
            self.assertTrue(errors_after(
                lambda changed: changed.pop("assistantLimitAuthorities")))

            mismatched = copy.deepcopy(document)
            mismatched["entries"][first]["conversion"] = copy.deepcopy(
                mismatched["entries"][first]["conversion"])
            mismatched["entries"][first]["conversion"]["beforeArgument"] = \
                "BROKEN_ASSISTANT_DEFAULT"
            self.assertTrue(any("exact family conversion" in error
                                or "differs from its family" in error
                                for error in audit.inventory_errors(root, mismatched, selected)))

            first_setting = document["entries"][first]["setting"]
            wrong_default_id = next(
                entry["id"] for entry in document["entries"]
                if entry["setting"] == first_setting
                and entry["id"] not in document["entries"][first]["defaultEvidence"])
            wrong_defaults = copy.deepcopy(document)
            for entry in wrong_defaults["entries"]:
                if entry["setting"] == first_setting:
                    entry["defaultEvidence"] = [wrong_default_id]
            self.assertTrue(any("direct default atom" in error
                                for error in audit.inventory_errors(
                                    root, wrong_defaults, selected)))

            missing_family_counterpart = copy.deepcopy(document)
            family_settings = missing_family_counterpart["assistantLimitAuthorities"][
                audit.ASSISTANT_LIMIT_FAMILY_ID]["settings"]
            family_setting = next(item for item in family_settings
                                  if item["setting"] == first_setting)
            family_setting.pop("defaultAuthority")
            self.assertTrue(audit.inventory_errors(
                root, missing_family_counterpart, selected))

            missing = copy.deepcopy(document)
            missing["entries"].pop(first)
            self.assertTrue(any("source-derived family rows" in error
                                or "unclassified operational candidate" in error
                                for error in audit.inventory_errors(root, missing, selected)))

            unrelated = next(candidate for candidate in candidates.values()
                             if candidate.id not in entries and not candidate.fixture)
            extra = copy.deepcopy(document)
            template = copy.deepcopy(extra["entries"][first])
            template.update(unrelated.inventory_entry())
            template.update(setting="assistant.unknown-third-setting", status="converted",
                            classification="operator-configurable")
            extra["entries"].append(template)
            extra["evidenceRecords"][unrelated.evidence_digest] = unrelated.evidence
            self.assertTrue(any("unknown setting" in error
                                for error in audit.inventory_errors(
                                    root, extra, selected + (unrelated,))))

            extra_fixed = copy.deepcopy(document)
            template = copy.deepcopy(extra_fixed["entries"][first])
            template.update(unrelated.inventory_entry())
            template.update(status="converted", classification="operator-configurable")
            extra_fixed["entries"].append(template)
            extra_fixed["evidenceRecords"][unrelated.evidence_digest] = unrelated.evidence
            self.assertTrue(any("independently derived family rows" in error
                                or "source-derived partition" in error
                                for error in audit.inventory_errors(
                                    root, extra_fixed, selected + (unrelated,))))

    def test_assistant_two_limit_authority_proves_bindings_defaults_history_and_consumers(self) -> None:
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            authorities, entries, candidates = self.assistant_limit_authority_fixture(root)
            self.assertEqual([], self.assistant_limit_errors(root, authorities, entries, candidates))
            authority = authorities[audit.ASSISTANT_LIMIT_FAMILY_ID]
            self.assertEqual(2, len(authority["settings"]))
            self.assertEqual({
                "assistant.max-output-tokens", "assistant.max-tool-iterations",
            }, {item["setting"] for item in authority["settings"]})

    def test_assistant_two_limit_authority_rejects_metadata_resolver_and_history_deletions(self) -> None:
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            authorities, entries, candidates = self.assistant_limit_authority_fixture(root)
            self.assertTrue(self.assistant_limit_errors(root, None, entries, candidates))

            missing = copy.deepcopy(authorities)
            missing[audit.ASSISTANT_LIMIT_FAMILY_ID]["settings"].pop()
            self.assertTrue(any("exactly two settings" in error
                                for error in self.assistant_limit_errors(
                                    root, missing, entries, candidates)))

            unknown = copy.deepcopy(authorities)
            unknown[audit.ASSISTANT_LIMIT_FAMILY_ID]["resolverAuthority"]["kind"] = "alien"
            self.assertTrue(any("exact resolver authority" in error
                                for error in self.assistant_limit_errors(
                                    root, unknown, entries, candidates)))

            missing_binding = copy.deepcopy(authorities)
            missing_binding[audit.ASSISTANT_LIMIT_FAMILY_ID]["settings"][0].pop(
                "bindingAuthority")
            self.assertTrue(any("unsupported shape" in error
                                for error in self.assistant_limit_errors(
                                    root, missing_binding, entries, candidates)))

            unknown_binding = copy.deepcopy(authorities)
            unknown_binding[audit.ASSISTANT_LIMIT_FAMILY_ID]["settings"][0][
                "bindingAuthority"]["kind"] = "alien"
            self.assertTrue(any("binding/default authority" in error
                                for error in self.assistant_limit_errors(
                                    root, unknown_binding, entries, candidates)))

            deleted_dependency = copy.deepcopy(authorities)
            deleted_dependency[audit.ASSISTANT_LIMIT_FAMILY_ID]["resolverAuthority"][
                "dependencyBodyDigests"].pop("trimmed")
            self.assertTrue(any("helper closure" in error
                                for error in self.assistant_limit_errors(
                                    root, deleted_dependency, entries, candidates)))

            wrong_history = copy.deepcopy(authorities)
            conversion = wrong_history[audit.ASSISTANT_LIMIT_FAMILY_ID]["conversionAuthorities"][
                "assistant.max-output-tokens"]
            conversion["beforeArgument"] = "DEFAULT_MAX_TOOL_ITERATIONS"
            self.assertTrue(any("constructor arguments" in error
                                for error in self.assistant_limit_errors(
                                    root, wrong_history, entries, candidates)))

            config_path = root / audit.ASSISTANT_CONFIGURATION_PATH
            config = config_path.read_text(encoding="utf-8")
            config_path.write_text(config.replace("value.strip()", "value.trim()", 1),
                                   encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed_source = config_path.read_text(encoding="utf-8")
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["resolverAuthority"][
                "dependencyBodyDigests"]["trimmed"] = audit.java_method_digest(
                    changed_source, "AssistantConfiguration", "trimmed")
            self.assertTrue(any("blank/refusal dependency structure" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))
            config_path.write_text(config, encoding="utf-8")

            ignored_parsed_value = config.replace(
                "        return parsed;\n    }\n\n"
                "    private static IllegalArgumentException boundedIntegerRefusal",
                "        return 1;\n    }\n\n"
                "    private static IllegalArgumentException boundedIntegerRefusal", 1)
            self.assertNotEqual(config, ignored_parsed_value)
            config_path.write_text(ignored_parsed_value, encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["resolverAuthority"]["integerBodyDigest"] = \
                audit.java_method_digest(
                    ignored_parsed_value, "AssistantConfiguration", "boundedPositiveInteger")
            self.assertTrue(any("helper closure/contract" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))
            config_path.write_text(config, encoding="utf-8")

            refusal_with_cause = config.replace(
                'new IllegalArgumentException(variable + " must be a whole number from 1 to " + maximum)',
                'new IllegalArgumentException(variable + " must be a whole number from 1 to " + maximum, '
                'new RuntimeException(variable))', 1)
            config_path.write_text(refusal_with_cause, encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["resolverAuthority"][
                "dependencyBodyDigests"]["boundedIntegerRefusal"] = audit.java_method_digest(
                    refusal_with_cause, "AssistantConfiguration", "boundedIntegerRefusal")
            self.assertTrue(any("blank/refusal dependency structure" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))
            config_path.write_text(config, encoding="utf-8")

            foreign_integer = config.replace(
                "import java.util.Map;", "import java.util.Map;\nimport example.Integer;", 1)
            config_path.write_text(foreign_integer, encoding="utf-8")
            self.assertTrue(any("blank/refusal dependency structure" in error
                                for error in self.assistant_limit_errors(
                                    root, authorities, entries, candidates)))
            config_path.write_text(config, encoding="utf-8")

            config_path.write_text(config.replace(
                "public static final String MAX_OUTPUT_TOKENS_VARIABLE",
                "private static final String MAX_OUTPUT_TOKENS_VARIABLE", 1),
                encoding="utf-8")
            self.assertTrue(any("source family has drifted" in error
                                for error in self.assistant_limit_errors(
                                    root, authorities, entries, candidates)))
            config_path.write_text(config, encoding="utf-8")

            local_environment_symbol = config.replace(
                "        Map<String, String> env = environment == null ? Map.of() : environment;",
                "        String MAX_OUTPUT_TOKENS_VARIABLE = \"shadow\";\n"
                "        Map<String, String> env = environment == null ? Map.of() : environment;", 1)
            config_path.write_text(local_environment_symbol, encoding="utf-8")
            self.assertTrue(any("source family has drifted" in error
                                for error in self.assistant_limit_errors(
                                    root, authorities, entries, candidates)))
            config_path.write_text(config, encoding="utf-8")

            local_default_symbol = config.replace(
                "        Map<String, String> env = environment == null ? Map.of() : environment;",
                "        int DEFAULT_MAX_OUTPUT_TOKENS = 8;\n"
                "        Map<String, String> env = environment == null ? Map.of() : environment;", 1)
            config_path.write_text(local_default_symbol, encoding="utf-8")
            self.assertTrue(any("source family has drifted" in error
                                for error in self.assistant_limit_errors(
                                    root, authorities, entries, candidates)))
            config_path.write_text(config, encoding="utf-8")

            reassigned_environment = config.replace(
                "        return new AssistantConfiguration(enabled, providerId, endpoint, model, credential, egress,",
                "        env = Map.of();\n"
                "        return new AssistantConfiguration(enabled, providerId, endpoint, model, credential, egress,",
                1,
            )
            config_path.write_text(reassigned_environment, encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["resolverAuthority"]["factoryBodyDigest"] = \
                audit.java_method_digest(
                    reassigned_environment, "AssistantConfiguration", "fromEnvironment")
            self.assertTrue(any("source family has drifted" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))
            config_path.write_text(config, encoding="utf-8")

            multi_declarator_shadow = config.replace(
                "        Map<String, String> env = environment == null ? Map.of() : environment;",
                "        Map<String, String> env = environment == null ? Map.of() : environment;\n"
                "        int unused = 0, DEFAULT_MAX_OUTPUT_TOKENS = 1;", 1)
            config_path.write_text(multi_declarator_shadow, encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["resolverAuthority"]["factoryBodyDigest"] = \
                audit.java_method_digest(
                    multi_declarator_shadow, "AssistantConfiguration", "fromEnvironment")
            self.assertTrue(any("source family has drifted" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))
            config_path.write_text(config, encoding="utf-8")

            config_path.write_text(config.replace(
                "Map<String, String> env = environment == null ? Map.of() : environment;",
                "Map<String, String> env = Map.of();", 1), encoding="utf-8")
            self.assertTrue(any("source family has drifted" in error
                                for error in self.assistant_limit_errors(
                                    root, authorities, entries, candidates)))
            config_path.write_text(config, encoding="utf-8")

            extra_binding = config.replace(
                "seconds(trimmed(env.get(TIMEOUT_VARIABLE)))",
                "Duration.ofSeconds(boundedPositiveInteger(env.get(MAX_OUTPUT_TOKENS_VARIABLE), "
                "MAX_OUTPUT_TOKENS_VARIABLE, DEFAULT_MAX_OUTPUT_TOKENS))",
                1,
            )
            self.assertNotEqual(config, extra_binding)
            config_path.write_text(extra_binding, encoding="utf-8")
            self.assertTrue(any("source family has drifted" in error
                                for error in self.assistant_limit_errors(
                                    root, authorities, entries, candidates)))
            config_path.write_text(config, encoding="utf-8")

            test_path = root / audit.ASSISTANT_CONFIGURATION_TEST_PATH
            test_source = test_path.read_text(encoding="utf-8")
            test_path.write_text(test_source.replace(
                "    @Test\n    void assistantOperationalLimitsDefaultAndTightenIndependently",
                "    void assistantOperationalLimitsDefaultAndTightenIndependently", 1),
                encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed_test = test_path.read_text(encoding="utf-8")
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["resolverAuthority"]["testBodyDigests"][
                "assistantOperationalLimitsDefaultAndTightenIndependently"] = \
                audit.java_method_digest(
                    changed_test, "AssistantConfigurationTest",
                    "assistantOperationalLimitsDefaultAndTightenIndependently")
            self.assertTrue(any("test role" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))
            test_path.write_text(test_source.replace(
                "        var defaults = AssistantConfiguration.fromEnvironment(Map.of());\n", "", 1),
                encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed_test = test_path.read_text(encoding="utf-8")
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["resolverAuthority"]["testBodyDigests"][
                "assistantOperationalLimitsDefaultAndTightenIndependently"] = \
                audit.java_method_digest(
                    changed_test, "AssistantConfigurationTest",
                    "assistantOperationalLimitsDefaultAndTightenIndependently")
            self.assertTrue(any("runnable test clauses" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))

    def test_assistant_two_limit_authority_rejects_carrier_consumer_and_compatibility_drift(self) -> None:
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            authorities, entries, candidates = self.assistant_limit_authority_fixture(root)
            authority = authorities[audit.ASSISTANT_LIMIT_FAMILY_ID]

            missing_carrier = copy.deepcopy(authorities)
            missing_carrier[audit.ASSISTANT_LIMIT_FAMILY_ID]["carrierEvidence"][
                "assistant.max-output-tokens"]["expectedCandidateIds"].pop("rawKubernetes")
            self.assertTrue(any("every checker-owned group" in error
                                for error in self.assistant_limit_errors(
                                    root, missing_carrier, entries, candidates)))

            schema_path = root / "deploy/helm/ravenroot/values.schema.json"
            schema_source = schema_path.read_text(encoding="utf-8")
            baseline_schema = json.loads(schema_source)
            assistant_schema = baseline_schema["properties"]["assistant"]
            schema_mutations = []

            missing_type = copy.deepcopy(baseline_schema)
            missing_type["properties"]["assistant"].pop("type")
            schema_mutations.append(("missing type", missing_type))
            wrong_type = copy.deepcopy(baseline_schema)
            wrong_type["properties"]["assistant"]["type"] = "array"
            schema_mutations.append(("wrong type", wrong_type))
            permissive = copy.deepcopy(baseline_schema)
            permissive["properties"]["assistant"]["additionalProperties"] = True
            schema_mutations.append(("permissive additional properties", permissive))
            missing_closed_flag = copy.deepcopy(baseline_schema)
            missing_closed_flag["properties"]["assistant"].pop("additionalProperties")
            schema_mutations.append(("missing additionalProperties", missing_closed_flag))
            extra_property = copy.deepcopy(baseline_schema)
            extra_property["properties"]["assistant"]["properties"]["alienLimit"] = {
                "type": "integer"}
            schema_mutations.append(("extra property", extra_property))
            missing_property = copy.deepcopy(baseline_schema)
            missing_property["properties"]["assistant"]["properties"].pop("maxOutputTokens")
            schema_mutations.append(("missing property", missing_property))
            extra_required = copy.deepcopy(baseline_schema)
            extra_required["properties"]["assistant"]["required"].append("alienLimit")
            schema_mutations.append(("extra required", extra_required))
            missing_required = copy.deepcopy(baseline_schema)
            missing_required["properties"]["assistant"]["required"].remove("maxOutputTokens")
            schema_mutations.append(("missing required", missing_required))
            duplicate_required = copy.deepcopy(baseline_schema)
            duplicate_required["properties"]["assistant"]["required"] = [
                "maxOutputTokens", "maxOutputTokens"]
            schema_mutations.append(("duplicate required", duplicate_required))
            self.assertEqual({"maxOutputTokens", "maxToolIterations"},
                             set(assistant_schema["properties"]))
            for label, mutated_schema in schema_mutations:
                schema_path.write_text(json.dumps(mutated_schema), encoding="utf-8")
                self.assertTrue(any("schema binding/range/blank" in error
                                    for error in self.assistant_limit_errors(
                                        root, authorities, entries, candidates)), label)
            schema_path.write_text(schema_source, encoding="utf-8")

            schema_path.write_text(schema_source.replace(
                '"maximum": 16000', '"maximum": 15999', 1), encoding="utf-8")
            self.assertTrue(any("schema binding/range/blank" in error
                                for error in self.assistant_limit_errors(
                                    root, authorities, entries, candidates)))
            schema_path.write_text(schema_source, encoding="utf-8")

            schema = json.loads(schema_source)
            schema["properties"]["assistant"]["properties"]["maxOutputTokens"]["oneOf"].append(
                {"type": "integer"})
            schema_path.write_text(json.dumps(schema), encoding="utf-8")
            self.assertTrue(any("schema binding/range/blank" in error
                                for error in self.assistant_limit_errors(
                                    root, authorities, entries, candidates)))
            schema_path.write_text(schema_source, encoding="utf-8")

            missing_assignment = copy.deepcopy(entries)
            candidate_id = authority["settings"][0]["bindingAuthority"][
                "environmentCandidateId"]
            missing_assignment[candidate_id]["setting"] = "assistant.max-tool-iterations"
            self.assertTrue(any("assigned elsewhere" in error
                                for error in self.assistant_limit_errors(
                                    root, authorities, missing_assignment, candidates)))

            service_path = root / audit.ASSISTANT_SERVICE_PATH
            service = service_path.read_text(encoding="utf-8")
            service_path.write_text(service.replace(
                "configuration.maxOutputTokens()", "configuration.maxToolIterations()", 1),
                encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed_source = service_path.read_text(encoding="utf-8")
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["consumerAuthority"]["bodyDigest"] = \
                audit.java_method_digest(changed_source, "AssistantService", "send")
            self.assertTrue(any("exact provider request argument" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))
            service_path.write_text(service, encoding="utf-8")

            service_path.write_text(service.replace(
                "AssistantProvider turnProvider = providerFor(context.subject());",
                "AssistantProvider turnProvider = provider;", 1), encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed_source = service_path.read_text(encoding="utf-8")
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["consumerAuthority"]["bodyDigest"] = \
                audit.java_method_digest(changed_source, "AssistantService", "send")
            self.assertTrue(any("provider selection" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))
            service_path.write_text(service, encoding="utf-8")

            service_path.write_text(service.replace(
                "configuration.maxToolIterations()", "configuration.maxOutputTokens()", 1),
                encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed_source = service_path.read_text(encoding="utf-8")
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["consumerAuthority"]["bodyDigest"] = \
                audit.java_method_digest(changed_source, "AssistantService", "send")
            self.assertTrue(any("active configured provider loop" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))
            service_path.write_text(service, encoding="utf-8")

            service_test_path = root / audit.ASSISTANT_SERVICE_TEST_PATH
            service_test = service_test_path.read_text(encoding="utf-8")
            service_test_path.write_text(service_test.replace(
                '.answering("a third provider call must never happen")',
                '.callingTool("a third provider call must never happen")', 1),
                encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed_test = service_test_path.read_text(encoding="utf-8")
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["consumerAuthority"]["testBodyDigest"] = \
                audit.java_method_digest(
                    changed_test, "AssistantGraphProposalTest",
                    "configuredOperationalLimitsReachEveryRequestAndStopTheProviderLoop")
            self.assertTrue(any("third-sentinel structure" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))
            service_test_path.write_text(service_test, encoding="utf-8")

            reordered = service_test.replace(
                '.callingTool("unknown-read-one")\n'
                '                    .callingTool("unknown-read-two")\n'
                '                    .answering("a third provider call must never happen")',
                '.answering("a third provider call must never happen")\n'
                '                    .callingTool("unknown-read-one")\n'
                '                    .callingTool("unknown-read-two")', 1)
            self.assertNotEqual(service_test, reordered)
            service_test_path.write_text(reordered, encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed_test = service_test_path.read_text(encoding="utf-8")
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["consumerAuthority"]["testBodyDigest"] = \
                audit.java_method_digest(
                    changed_test, "AssistantGraphProposalTest",
                    "configuredOperationalLimitsReachEveryRequestAndStopTheProviderLoop")
            self.assertTrue(any("ordered provider script" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))
            service_test_path.write_text(service_test, encoding="utf-8")

            config_path = root / audit.ASSISTANT_CONFIGURATION_PATH
            config = config_path.read_text(encoding="utf-8")
            config_path.write_text(config.replace(
                "maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS",
                "maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_TOOL_ITERATIONS", 1),
                encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed_config = config_path.read_text(encoding="utf-8")
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["compatibilityAuthority"][
                "constructorBodyDigest"] = audit.java_span_digest(
                    changed_config,
                    audit.java_compact_constructor_span(changed_config, "AssistantConfiguration"))
            self.assertTrue(any("field-specific compatibility" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))

            appended_write = config.replace(
                "        maxToolIterations = maxToolIterations > 0 ? maxToolIterations : "
                "DEFAULT_MAX_TOOL_ITERATIONS;",
                "        maxToolIterations = maxToolIterations > 0 ? maxToolIterations : "
                "DEFAULT_MAX_TOOL_ITERATIONS;\n"
                "        maxOutputTokens = 1;", 1)
            config_path.write_text(appended_write, encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["compatibilityAuthority"][
                "constructorBodyDigest"] = audit.java_span_digest(
                    appended_write,
                    audit.java_compact_constructor_span(
                        appended_write, "AssistantConfiguration"))
            self.assertTrue(any("field-specific compatibility" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))

            for shift_assignment in ("<<=", ">>=", ">>>="):
                shifted = config.replace(
                    "        maxToolIterations = maxToolIterations > 0 ? maxToolIterations : "
                    "DEFAULT_MAX_TOOL_ITERATIONS;",
                    "        maxToolIterations = maxToolIterations > 0 ? maxToolIterations : "
                    "DEFAULT_MAX_TOOL_ITERATIONS;\n"
                    f"        maxOutputTokens {shift_assignment} 1;", 1)
                config_path.write_text(shifted, encoding="utf-8")
                changed = copy.deepcopy(authorities)
                changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["compatibilityAuthority"][
                    "constructorBodyDigest"] = audit.java_span_digest(
                        shifted,
                        audit.java_compact_constructor_span(
                            shifted, "AssistantConfiguration"))
                self.assertTrue(any("field-specific compatibility" in error
                                    for error in self.assistant_limit_errors(
                                        root, changed, entries, candidates)), shift_assignment)

            config_path.write_text(config, encoding="utf-8")
            config_test_path = root / audit.ASSISTANT_CONFIGURATION_TEST_PATH
            config_test = config_test_path.read_text(encoding="utf-8")
            config_test_path.write_text(config_test.replace(
                "        assertEquals(AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS + 1,\n"
                "                positiveValues.maxOutputTokens(), "
                '"direct positive values remain API-compatible");\n', "", 1),
                encoding="utf-8")
            changed = copy.deepcopy(authorities)
            changed_test = config_test_path.read_text(encoding="utf-8")
            changed[audit.ASSISTANT_LIMIT_FAMILY_ID]["compatibilityAuthority"]["testBodyDigest"] = \
                audit.java_method_digest(
                    changed_test, "AssistantConfigurationTest",
                    "compactConstructorKeepsItsCompatibilityFallbacks")
            self.assertTrue(any("compatibility test" in error
                                for error in self.assistant_limit_errors(
                                    root, changed, entries, candidates)))

    def environment_authority_fixture(self, root: Path):
        source_path = root / "ravenroot/example/src/main/java/dev/example/RuntimeLimits.java"
        source_path.write_text(
            "package dev.example;\n"
            "import java.time.Duration;\n"
            "import java.util.Map;\n"
            "record RuntimeLimits(int maxRetries, int maxBurst, Duration leaseTtl) {\n"
            "  static final RuntimeLimits DEFAULTS = new RuntimeLimits(\n"
            "      16 * 1024, 8 * 1024, Duration.ofHours(1));\n"
            "  public RuntimeLimits {\n"
            "    positive(\"RAVENROOT_SYNTHETIC_MAX_RETRIES\", maxRetries);\n"
            "    burst(\"RAVENROOT_SYNTHETIC_MAX_BURST\", maxBurst, maxRetries);\n"
            "    if (leaseTtl.compareTo(Duration.ofSeconds(1)) < 0) throw new IllegalArgumentException(\n"
            "        \"RAVENROOT_SYNTHETIC_LEASE_SECONDS must be positive\");\n"
            "  }\n"
            "  public static RuntimeLimits fromEnvironment(Map<String, String> environment) {\n"
            "    return new RuntimeLimits(\n"
            "        integer(environment, \"RAVENROOT_SYNTHETIC_MAX_RETRIES\", DEFAULTS.maxRetries),\n"
            "        integer(environment, \"RAVENROOT_SYNTHETIC_MAX_BURST\", DEFAULTS.maxBurst),\n"
            "        Duration.ofSeconds(integer(environment, \"RAVENROOT_SYNTHETIC_LEASE_SECONDS\",\n"
            "            (int) DEFAULTS.leaseTtl.toSeconds())));\n"
            "  }\n"
            "  private static void positive(String name, int value) {\n"
            "    if (value < 1) throw new IllegalArgumentException(name);\n"
            "  }\n"
            "  private static void burst(String name, int value, int rate) {\n"
            "    positive(name, value); if (value < rate) throw new IllegalArgumentException(name);\n"
            "  }\n"
            "  private static int integer(Map<String, String> environment, String name, int defaultValue) {\n"
            "    String value = environment.get(name);\n"
            "    if (value == null || value.isBlank()) return defaultValue;\n"
            "    try { return Integer.parseInt(value.trim()); }\n"
            "    catch (NumberFormatException invalid) { throw new IllegalArgumentException(name); }\n"
            "  }\n"
            "}\n",
            encoding="utf-8",
        )
        test_path = root / "ravenroot/example/src/test/java/dev/example/RuntimeLimitsTest.java"
        test_path.parent.mkdir(parents=True, exist_ok=True)
        test_path.write_text(
            "package dev.example;\n"
            "import org.junit.jupiter.api.Test;\n"
            "import org.junit.jupiter.params.ParameterizedTest;\n"
            "import org.junit.jupiter.params.provider.MethodSource;\n"
            "import java.util.stream.Stream;\n"
            "import static org.junit.jupiter.api.Assertions.assertEquals;\n"
            "final class RuntimeLimitsTest {\n"
            "  @Test\n"
            "  void blankDefaults() { settingNames().count(); }\n"
            "  @Test\n"
            "  void asciiTrim() {}\n"
            "  @ParameterizedTest\n"
            "  @MethodSource(\"settingNames\")\n"
            "  void malformedOverflow(String name) {}\n"
            "  @ParameterizedTest\n"
            "  @MethodSource(\"settingNames\")\n"
            "  void nonPositive(String name) {}\n"
            "  @Test\n"
            "  void boundaries() {}\n"
            "  @Test\n"
            "  void relations() {}\n"
            "  private static Stream<String> settingNames() { return Stream.of(\n"
            "      \"RAVENROOT_SYNTHETIC_MAX_RETRIES\", \"RAVENROOT_SYNTHETIC_MAX_BURST\",\n"
            "      \"RAVENROOT_SYNTHETIC_LEASE_SECONDS\"); }\n"
            "}\n",
            encoding="utf-8",
        )
        for carrier in ("compose.yaml", "deploy/helm/ravenroot/values.yaml",
                        "deploy/helm/ravenroot/values.schema.json",
                        "deploy/helm/ravenroot/templates/deployment.yaml",
                        "deploy/kubernetes/ravenroot.yaml"):
            path = root / carrier
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("{}\n", encoding="utf-8")
        subprocess.run(["git", "add", "ravenroot", "compose.yaml", "deploy"], cwd=root, check=True)
        errors, _summary = audit.refresh_inventory(
            root, root / "scripts/operational-configuration-inventory.json",
            root / "docs/architecture/operational-configuration-audit.md",
        )
        self.assertEqual([], errors)
        inventory = root / "scripts/operational-configuration-inventory.json"
        document = json.loads(inventory.read_text(encoding="utf-8"))
        discovered = audit.discover(root)
        by_id = {entry["id"]: entry for entry in document["entries"]}
        source = source_path.read_text(encoding="utf-8")
        components = audit.java_record_components(source, "RuntimeLimits")
        compact = audit.java_compact_constructor_span(source, "RuntimeLimits")
        test_source = test_path.read_text(encoding="utf-8")
        test_methods = {
            "bindingEnumeration": "settingNames",
            "blankTypedDefault": "blankDefaults",
            "asciiTrimContract": "asciiTrim",
            "malformedOverflowRefusal": "malformedOverflow",
            "nonPositiveRefusal": "nonPositive",
            "documentedBoundaryAcceptance": "boundaries",
            "relationalConstraintRefusal": "relations",
        }
        resolver_id = "synthetic-environment-resolver-v1"
        validation_helpers = audit.java_reachable_helpers_from_span(
            source, "RuntimeLimits", compact,
        )
        document["resolverAuthorities"] = {resolver_id: {
            "kind": "java-environment-integer-resolver-v1",
            "path": str(source_path.relative_to(root)), "type": "RuntimeLimits",
            "factoryMethod": "fromEnvironment",
            "factoryBodyDigest": audit.java_method_digest(source, "RuntimeLimits", "fromEnvironment"),
            "integerMethod": "integer",
            "integerBodyDigest": audit.java_method_digest(source, "RuntimeLimits", "integer"),
            "dependencyBodyDigests": {},
            "validationBodyDigest": audit.java_span_digest(source, compact),
            "validationDependencyBodyDigests": {
                method: audit.java_method_digest(source, "RuntimeLimits", method)
                for method in validation_helpers
            },
            "testPath": str(test_path.relative_to(root)), "testType": "RuntimeLimitsTest",
            "testMethods": test_methods,
            "testMethodDigests": {
                method: audit.java_method_digest(test_source, "RuntimeLimitsTest", method)
                for method in test_methods.values()
            },
        }}
        fields = [
            ("synthetic.max-retries", "maxRetries", "RAVENROOT_SYNTHETIC_MAX_RETRIES"),
            ("synthetic.max-burst", "maxBurst", "RAVENROOT_SYNTHETIC_MAX_BURST"),
            ("synthetic.lease-ttl", "leaseTtl", "RAVENROOT_SYNTHETIC_LEASE_SECONDS"),
        ]
        owner = str(source_path.relative_to(root)) + "#RuntimeLimits"
        for index, (setting, field, environment) in enumerate(fields):
            call = audit.java_constructor_component_call(
                source, "RuntimeLimits", "fromEnvironment", "RuntimeLimits", components, field,
            )
            self.assertIsNotNone(call)
            argument, start, end = call
            constructor_ids = audit.candidate_ids_in_source_span(
                source_path.relative_to(root), source, start, end,
                "environment-binding", environment, {candidate.id: candidate for candidate in discovered},
            )
            self.assertEqual(1, len(constructor_ids))
            source_ids = sorted(
                candidate.id for candidate in discovered
                if candidate.path == str(source_path.relative_to(root))
                and candidate.kind == "environment-binding" and candidate.expression == environment
            )
            default_span = audit.java_record_default_expression_span(
                source, "RuntimeLimits", "DEFAULTS", field,
            )
            self.assertIsNotNone(default_span)
            default_ids = audit.candidate_ids_in_source_span(
                source_path.relative_to(root), source, default_span[1], default_span[2],
                "fixed-declaration", "DEFAULTS", {candidate.id: candidate for candidate in discovered},
            )
            duration = field == "leaseTtl"
            evaluated = audit.evaluated_java_default(default_span[0], duration)
            contract = {
                "status": "already-centralized", "classification": "operator-configurable",
                "setting": setting, "owner": owner, "field": field, "bindings": [environment],
                "default": str(evaluated["value"]), "defaultEvidence": default_ids,
                "bindingAuthority": {
                    "kind": "java-environment-constructor-v1", "sourceOwner": owner,
                    "method": "fromEnvironment", "constructorType": "RuntimeLimits",
                    "component": field, "componentIndex": index, "helper": "integer",
                    "environmentCandidateId": constructor_ids[0],
                    "sourceEnvironmentCandidateIds": source_ids, "environment": environment,
                    "defaultAccessor": (f"(int) DEFAULTS.{field}.toSeconds()" if duration
                                        else f"DEFAULTS.{field}"),
                    "valueTransform": "duration-seconds" if duration else "identity",
                    "callDigest": audit.hashlib.sha256(argument.encode("utf-8")).hexdigest(),
                    "resolverAuthority": resolver_id,
                },
                "defaultAuthority": {
                    "owner": owner, "instanceSymbol": "DEFAULTS", "field": field,
                    "sourceExpression": default_span[0], "candidateIds": default_ids,
                    "componentIndex": index, "evaluatedDefault": evaluated,
                },
                "carrierEvidence": {
                    "kind": "deployment-environment-carriers-v1", "environment": environment,
                    "expectedCandidateIds": {
                        "compose": [], "helm": [], "rawKubernetes": [],
                    },
                },
                "validation": "positive bounded value", "scope": "process",
                "pinning": "read once at startup", "coverage": "bounded synthetic proof",
                "rationale": "Synthetic environment authority.",
            }
            for candidate_id in set(source_ids) | set(default_ids):
                by_id[candidate_id].update(copy.deepcopy(contract))
        return document, discovered, source_path, test_path

    def test_real_repository_incremental_inventory_and_generated_report_are_current(self) -> None:
        errors = audit.check(ROOT, require_complete=False)
        self.assertEqual([], errors, "\n".join(errors[:20]))

    def test_real_reconciliation_domain_map_and_sse_delimiter_semantics_are_exact(self) -> None:
        document = json.loads(audit.INVENTORY.read_text(encoding="utf-8"))
        owners = document["remediationDomains"]["settingOwners"]
        expected_owners = {
            "embed.enabled": "#321",
            "ui.monitoring.max-deployment-event-streams": "#321",
        }
        expected_unresolved_settings = len(expected_owners)
        self.assertEqual(expected_unresolved_settings, len(owners))
        self.assertEqual(len(owners), len({item["setting"] for item in owners}))
        self.assertEqual(expected_owners, {item["setting"]: item["issue"] for item in owners})
        self.assertEqual(
            expected_unresolved_settings,
            sum(domain["confirmedUnresolvedOperatorSettings"]
                for domain in document["remediationDomains"]["domains"]),
        )
        self.assertNotIn("execution.lease-ttl", {item["setting"] for item in owners})
        lease_rows = [entry for entry in document["entries"]
                      if entry.get("setting") == "execution.lease-ttl"]
        self.assertEqual(5, len(lease_rows))
        for entry in lease_rows:
            with self.subTest(lease_candidate=entry["id"]):
                self.assertEqual("already-centralized", entry["status"])
                self.assertEqual("operator-configurable", entry["classification"])
                self.assertEqual("ravenroot-persistence-policy-v1", entry["persistenceAuthority"])
                self.assertEqual(
                    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/persistence/"
                    "ExecutionOwnershipConfiguration.java#ExecutionOwnershipConfiguration",
                    entry["owner"],
                )
                self.assertEqual("leaseTtl", entry["field"])
                self.assertNotIn("authorityStatus", entry)
                self.assertNotIn("followUp", entry)
        unresolved_rows = [entry for entry in document["entries"]
                           if entry.get("authorityStatus") == "unresolved"]
        self.assertEqual(expected_unresolved_settings,
                         len({entry["setting"] for entry in unresolved_rows}))
        self.assertTrue(all(entry.get("sourceFact") for entry in unresolved_rows))
        self.assertFalse(any(str(entry["default"]).startswith("Current internal source value:")
                             for entry in unresolved_rows))

        missing = copy.deepcopy(document)
        del missing["remediationDomains"]
        self.assertTrue(any("requires a remediation domain map" in error
                            for error in audit.remediation_domain_errors(missing)))
        duplicate = copy.deepcopy(document)
        duplicate["remediationDomains"]["settingOwners"].append(
            copy.deepcopy(duplicate["remediationDomains"]["settingOwners"][0]))
        self.assertTrue(any("duplicate setting ownership" in error
                            for error in audit.remediation_domain_errors(duplicate)))
        split = copy.deepcopy(document)
        conflicting_owner = copy.deepcopy(unresolved_rows[0])
        conflicting_owner.update(id="oc-split-owner", followUp="#318")
        split["entries"].append(conflicting_owner)
        self.assertTrue(any("requires one follow-up owner" in error
                            for error in audit.remediation_domain_errors(split)))

        delimiter = next(entry for entry in document["entries"]
                         if entry["id"] == "oc-7f698b1972e9090b6f1b")
        self.assertEqual("protocol-or-format-invariant", delimiter["classification"])
        self.assertIn("CR/LF", delimiter["rationale"])

        postgres_defaults = {
            "postgres.lock-timeout": "Duration.ofSeconds(5)",
            "postgres.statement-timeout": "Duration.ofSeconds(30)",
            "postgres.serialization-retries": "3",
            "postgres.max-lease-ttl": "Duration.ofMinutes(5)",
            "postgres.max-payload-bytes": "1024 * 1024",
            "postgres.max-clock-skew": "Duration.ofSeconds(5)",
            "postgres.journal-retention": "Duration.ofHours(24)",
            "postgres.max-inventory-page-size": "100",
            "postgres.terminal-retention": "Duration.ofDays(7)",
            "postgres.execution-result-retention": "Duration.ofDays(7)",
        }
        source_candidates = {candidate.id: candidate for candidate in audit.discover(ROOT)}
        persistence_authority = audit.persistence_policy_authority_from_source(ROOT, source_candidates)
        self.assertIsNotNone(persistence_authority)
        contracts = {contract["setting"]: contract
                     for contract in persistence_authority["contracts"]}
        for setting, expected in postgres_defaults.items():
            with self.subTest(postgres_default=setting):
                rows = [entry for entry in document["entries"]
                        if entry.get("setting") == setting]
                self.assertTrue(rows)
                self.assertEqual({expected}, {entry["default"] for entry in rows})
                contract = contracts[setting]
                self.assertEqual(expected, contract["defaultExpression"])
                self.assertEqual(set(contract["candidateIds"]), {entry["id"] for entry in rows})
                self.assertTrue(contract["defaultCandidateIds"])
                for entry in rows:
                    self.assertEqual("already-centralized", entry["status"])
                    self.assertEqual("operator-configurable", entry["classification"])
                    self.assertEqual(audit.PERSISTENCE_POLICY_AUTHORITY_ID,
                                     entry["persistenceAuthority"])
                    self.assertEqual(contract["defaultCandidateIds"], entry["defaultEvidence"])
                    self.assertEqual(source_candidates[entry["id"]].evidence_digest,
                                     entry["evidenceDigest"])

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
                                 root / "docs/architecture/operational-configuration-audit.md",
                                 allow_unreconciled=True)
        self.assertTrue(any("audit is incomplete" in error and "1 deferred" in error
                            for error in errors), errors)

    def test_environment_authority_is_candidate_driven_bijective_and_closed(self) -> None:
        # This resolver fixture uses partial carrier files, not a complete Helm chart.
        # Keep its target-family and generic checks independent of the Helm authority tests.
        with synthetic_repository() as location, \
                mock.patch.object(audit, "helm_authority_errors", return_value=[]):
            root = Path(location)
            document, discovered, _source, _tests = self.environment_authority_fixture(root)
            self.assertEqual([], audit.inventory_errors(root, document, discovered))

            missing_binding = copy.deepcopy(document)
            for entry in missing_binding["entries"]:
                if entry.get("setting") == "synthetic.max-retries":
                    entry.pop("bindingAuthority")
            errors = audit.inventory_errors(root, missing_binding, discovered)
            self.assertTrue(any("requires exact bindingAuthority" in error for error in errors), errors)

            missing_resolver_link = copy.deepcopy(document)
            for entry in missing_resolver_link["entries"]:
                if entry.get("setting") == "synthetic.max-retries":
                    entry["bindingAuthority"].pop("resolverAuthority")
            errors = audit.inventory_errors(root, missing_resolver_link, discovered)
            self.assertTrue(any("requires exact bindingAuthority" in error for error in errors), errors)

            missing_default = copy.deepcopy(document)
            for entry in missing_default["entries"]:
                if entry.get("setting") == "synthetic.max-retries":
                    entry.pop("defaultAuthority")
            errors = audit.inventory_errors(root, missing_default, discovered)
            self.assertTrue(any("requires defaultAuthority" in error for error in errors), errors)

            missing_carrier = copy.deepcopy(document)
            for entry in missing_carrier["entries"]:
                if entry.get("setting") == "synthetic.max-retries":
                    entry.pop("carrierEvidence")
            errors = audit.inventory_errors(root, missing_carrier, discovered)
            self.assertTrue(any("requires exact carrierEvidence" in error for error in errors), errors)

            missing_resolver = copy.deepcopy(document)
            missing_resolver.pop("resolverAuthorities")
            errors = audit.inventory_errors(root, missing_resolver, discovered)
            self.assertTrue(any("references an absent resolver authority" in error
                                for error in errors), errors)

            unsupported_extra_field = copy.deepcopy(document)
            unsupported_extra_field["resolverAuthorities"][
                "synthetic-environment-resolver-v1"
            ]["selfDeclaredExemption"] = True
            errors = audit.inventory_errors(root, unsupported_extra_field, discovered)
            self.assertTrue(any("requires exactly" in error for error in errors), errors)

            missing_test_role = copy.deepcopy(document)
            resolver = missing_test_role["resolverAuthorities"]["synthetic-environment-resolver-v1"]
            method = resolver["testMethods"].pop("asciiTrimContract")
            resolver["testMethodDigests"].pop(method)
            errors = audit.inventory_errors(root, missing_test_role, discovered)
            self.assertTrue(any("missing rate-limit test evidence" in error for error in errors), errors)

            dangling_resolver = copy.deepcopy(document)
            dangling_resolver["resolverAuthorities"]["unused"] = copy.deepcopy(
                dangling_resolver["resolverAuthorities"]["synthetic-environment-resolver-v1"],
            )
            errors = audit.inventory_errors(root, dangling_resolver, discovered)
            self.assertTrue(any("referenced by one exact component set" in error for error in errors), errors)

            missing_component = copy.deepcopy(document)
            for entry in missing_component["entries"]:
                if entry.get("setting") == "synthetic.lease-ttl":
                    entry["bindingAuthority"]["componentIndex"] = 1
            errors = audit.inventory_errors(root, missing_component, discovered)
            self.assertTrue(any("component index/field" in error
                                or "bijectively cover" in error for error in errors), errors)

            missing_source_partition = copy.deepcopy(document)
            for entry in missing_source_partition["entries"]:
                if entry.get("setting") == "synthetic.max-retries":
                    entry["bindingAuthority"]["sourceEnvironmentCandidateIds"].pop()
            errors = audit.inventory_errors(root, missing_source_partition, discovered)
            self.assertTrue(any("source environment candidate partition" in error
                                for error in errors), errors)

            missing_carrier_group = copy.deepcopy(document)
            for entry in missing_carrier_group["entries"]:
                if entry.get("setting") == "synthetic.max-retries":
                    entry["carrierEvidence"]["expectedCandidateIds"].pop("compose")
            errors = audit.inventory_errors(root, missing_carrier_group, discovered)
            self.assertTrue(any("every checker-owned carrier group" in error for error in errors), errors)

    def test_environment_authority_source_test_and_carrier_mutations_fail(self) -> None:
        # Isolate the unrelated chart proof while mutating the synthetic environment carriers.
        # The dedicated Helm tests exercise the production chart gate without this patch.
        with synthetic_repository() as location, \
                mock.patch.object(audit, "helm_authority_errors", return_value=[]):
            root = Path(location)
            document, discovered, source_path, test_path = self.environment_authority_fixture(root)
            original_source = source_path.read_text(encoding="utf-8")
            source_path.write_text(
                original_source.replace("if (value < 1)", "if (value < 2)"), encoding="utf-8",
            )
            errors = audit.inventory_errors(root, document, audit.discover(root))
            self.assertTrue(any("validation helper dependencies" in error for error in errors), errors)
            source_path.write_text(original_source, encoding="utf-8")

            source_path.write_text(
                original_source.replace("if (value < rate)", "if (value <= rate)"),
                encoding="utf-8",
            )
            errors = audit.inventory_errors(root, document, audit.discover(root))
            self.assertTrue(any("validation helper dependencies" in error for error in errors), errors)
            source_path.write_text(original_source, encoding="utf-8")

            source_path.write_text(
                original_source.replace(
                    "positive(name, value); if (value < rate)",
                    "if (value < rate)",
                ),
                encoding="utf-8",
            )
            modified = source_path.read_text(encoding="utf-8")
            updated = copy.deepcopy(document)
            resolver = updated["resolverAuthorities"]["synthetic-environment-resolver-v1"]
            resolver["validationDependencyBodyDigests"]["burst"] = audit.java_method_digest(
                modified, "RuntimeLimits", "burst",
            )
            errors = audit.inventory_errors(root, updated, audit.discover(root))
            self.assertTrue(any("burst validation no longer delegates" in error for error in errors), errors)
            source_path.write_text(original_source, encoding="utf-8")

            source_path.write_text(
                original_source.replace("value.isBlank()", "value.isEmpty()"), encoding="utf-8",
            )
            errors = audit.inventory_errors(root, document, audit.discover(root))
            self.assertTrue(any("integer body digest" in error for error in errors), errors)
            source_path.write_text(original_source, encoding="utf-8")

            swapped = original_source.replace(
                '"RAVENROOT_SYNTHETIC_MAX_RETRIES", DEFAULTS.maxRetries',
                '"RAVENROOT_SYNTHETIC_SWAP", DEFAULTS.maxRetries',
            ).replace(
                '"RAVENROOT_SYNTHETIC_MAX_BURST", DEFAULTS.maxBurst',
                '"RAVENROOT_SYNTHETIC_MAX_RETRIES", DEFAULTS.maxBurst',
            ).replace(
                '"RAVENROOT_SYNTHETIC_SWAP", DEFAULTS.maxRetries',
                '"RAVENROOT_SYNTHETIC_MAX_BURST", DEFAULTS.maxRetries',
            )
            source_path.write_text(swapped, encoding="utf-8")
            errors = audit.inventory_errors(root, document, audit.discover(root))
            self.assertTrue(any("environment candidate" in error
                                or "factory body digest" in error for error in errors), errors)
            source_path.write_text(original_source, encoding="utf-8")

            source_path.write_text(
                original_source.replace("16 * 1024", "8 * 2048"), encoding="utf-8",
            )
            errors = audit.inventory_errors(root, document, audit.discover(root))
            self.assertTrue(any("sourceExpression" in error for error in errors), errors)
            source_path.write_text(original_source, encoding="utf-8")

            source_path.write_text(
                original_source.replace("Duration.ofHours(1)", "Duration.ofMinutes(60)"),
                encoding="utf-8",
            )
            errors = audit.inventory_errors(root, document, audit.discover(root))
            self.assertTrue(any("sourceExpression" in error for error in errors), errors)
            source_path.write_text(original_source, encoding="utf-8")

            original_test = test_path.read_text(encoding="utf-8")
            test_path.write_text(
                original_test.replace(', "RAVENROOT_SYNTHETIC_MAX_BURST"', ""),
                encoding="utf-8",
            )
            errors = audit.inventory_errors(root, document, discovered)
            self.assertTrue(any("one direct exact Stream.of literal list" in error
                                for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            enumeration_digest = audit.java_method_digest(
                original_test, "RuntimeLimitsTest", "settingNames",
            )
            nonstatic_enumeration = original_test.replace(
                "private static Stream<String> settingNames()",
                "private Stream<String> settingNames()",
            )
            self.assertEqual(enumeration_digest, audit.java_method_digest(
                nonstatic_enumeration, "RuntimeLimitsTest", "settingNames",
            ))
            test_path.write_text(nonstatic_enumeration, encoding="utf-8")
            errors = audit.inventory_errors(root, document, discovered)
            self.assertTrue(any("unsupported factory signature" in error
                                for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            def enumeration_mutation(mutant: str) -> list[str]:
                test_path.write_text(mutant, encoding="utf-8")
                mutated = copy.deepcopy(document)
                resolver = mutated["resolverAuthorities"]["synthetic-environment-resolver-v1"]
                resolver["testMethodDigests"]["settingNames"] = audit.java_method_digest(
                    mutant, "RuntimeLimitsTest", "settingNames",
                )
                return audit.inventory_errors(root, mutated, discovered)

            commented = original_test.replace(
                '"RAVENROOT_SYNTHETIC_MAX_RETRIES", ',
                '/* "RAVENROOT_SYNTHETIC_MAX_RETRIES", */ ',
            )
            errors = enumeration_mutation(commented)
            self.assertTrue(any("one direct exact Stream.of literal list" in error
                                for error in errors), errors)

            unrelated = original_test.replace(
                'Stream<String> settingNames() { return Stream.of(\n'
                '      "RAVENROOT_SYNTHETIC_MAX_RETRIES", ',
                'Stream<String> settingNames() {\n'
                '    String ignored = "RAVENROOT_SYNTHETIC_MAX_RETRIES";\n'
                '    return Stream.of(\n      ',
            )
            errors = enumeration_mutation(unrelated)
            self.assertTrue(any("one direct exact Stream.of literal list" in error
                                for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            for role in ("malformedOverflow", "nonPositive"):
                with self.subTest(missing_parameterized_annotation=role):
                    mutant = original_test.replace(
                        '  @ParameterizedTest\n  @MethodSource("settingNames")\n'
                        f'  void {role}',
                        '  @MethodSource("settingNames")\n'
                        f'  void {role}',
                    )
                    test_path.write_text(mutant, encoding="utf-8")
                    errors = audit.inventory_errors(root, document, discovered)
                    self.assertTrue(any("is not linked to its enumeration" in error
                                        for error in errors), errors)
                with self.subTest(retargeted_method_source=role):
                    mutant = original_test.replace(
                        '@MethodSource("settingNames")\n  void ' + role,
                        '@MethodSource("otherNames")\n  void ' + role,
                    )
                    test_path.write_text(mutant, encoding="utf-8")
                    errors = audit.inventory_errors(root, document, discovered)
                    self.assertTrue(any("is not linked to its enumeration" in error
                                        for error in errors), errors)

            test_path.write_text(
                original_test.replace('  @Test\n  void asciiTrim', '  void asciiTrim'),
                encoding="utf-8",
            )
            errors = audit.inventory_errors(root, document, discovered)
            self.assertTrue(any("asciiTrimContract is not a runnable @Test" in error
                                for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            ordinary_digest = audit.java_method_digest(
                original_test, "RuntimeLimitsTest", "asciiTrim",
            )
            for label, replacement in (
                ("static", "static void asciiTrim"),
                ("private", "private void asciiTrim"),
            ):
                with self.subTest(unsupported_test_signature=label):
                    mutant = original_test.replace("void asciiTrim", replacement)
                    self.assertEqual(ordinary_digest, audit.java_method_digest(
                        mutant, "RuntimeLimitsTest", "asciiTrim",
                    ))
                    test_path.write_text(mutant, encoding="utf-8")
                    errors = audit.inventory_errors(root, document, discovered)
                    self.assertTrue(any("asciiTrimContract has unsupported signature" in error
                                        for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            exact_imports = (
                "org.junit.jupiter.api.Test",
                "org.junit.jupiter.params.ParameterizedTest",
                "org.junit.jupiter.params.provider.MethodSource",
                "java.util.stream.Stream",
            )
            for qualified in exact_imports:
                with self.subTest(retargeted_proof_import=qualified):
                    simple = qualified.rsplit(".", 1)[-1]
                    mutant = original_test.replace(
                        f"import {qualified};", f"import example.fake.{simple};",
                    )
                    test_path.write_text(mutant, encoding="utf-8")
                    errors = audit.inventory_errors(root, document, discovered)
                    self.assertTrue(any("does not bind the exact JUnit and Stream types" in error
                                        for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            local_annotations = original_test
            for qualified in exact_imports[:3]:
                local_annotations = local_annotations.replace(f"import {qualified};\n", "")
            local_annotations = local_annotations.replace(
                "final class RuntimeLimitsTest {",
                "@interface Test {}\n"
                "@interface ParameterizedTest {}\n"
                "@interface MethodSource { String value(); }\n"
                "final class RuntimeLimitsTest {",
            )
            test_path.write_text(local_annotations, encoding="utf-8")
            errors = audit.inventory_errors(root, document, discovered)
            self.assertTrue(any("does not bind the exact JUnit and Stream types" in error
                                for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            local_stream = original_test.replace("import java.util.stream.Stream;\n", "").replace(
                "final class RuntimeLimitsTest {",
                "final class Stream<T> {}\nfinal class RuntimeLimitsTest {",
            )
            test_path.write_text(local_stream, encoding="utf-8")
            errors = audit.inventory_errors(root, document, discovered)
            self.assertTrue(any("does not bind the exact JUnit and Stream types" in error
                                for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            stream_field_shadow = original_test.replace(
                "  private static Stream<String> settingNames()",
                "  private static final Object Stream = new Object();\n"
                "  private static Stream<String> settingNames()",
            )
            self.assertEqual(enumeration_digest, audit.java_method_digest(
                stream_field_shadow, "RuntimeLimitsTest", "settingNames",
            ))
            test_path.write_text(stream_field_shadow, encoding="utf-8")
            errors = audit.inventory_errors(root, document, discovered)
            self.assertTrue(any("does not bind the exact JUnit and Stream types" in error
                                for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            for label, imported in (
                ("stream-value", "import static example.Fake.Stream;\n"),
                ("wildcard", "import static example.Fake.*;\n"),
            ):
                with self.subTest(static_import_shadow=label):
                    mutant = original_test.replace(
                        "final class RuntimeLimitsTest {",
                        imported + "final class RuntimeLimitsTest {",
                    )
                    self.assertEqual(enumeration_digest, audit.java_method_digest(
                        mutant, "RuntimeLimitsTest", "settingNames",
                    ))
                    test_path.write_text(mutant, encoding="utf-8")
                    errors = audit.inventory_errors(root, document, discovered)
                    self.assertTrue(any("does not bind the exact JUnit and Stream types" in error
                                        for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            method_digest = audit.java_method_digest(
                original_test, "RuntimeLimitsTest", "asciiTrim",
            )
            for label, mutant in (
                ("disabled", original_test.replace(
                    "final class RuntimeLimitsTest {",
                    "@Disabled\nfinal class RuntimeLimitsTest {",
                )),
                ("abstract", original_test.replace(
                    "final class RuntimeLimitsTest {",
                    "abstract class RuntimeLimitsTest {",
                )),
                ("multiline-disabled", original_test.replace(
                    "final class RuntimeLimitsTest {",
                    "@Disabled(\n    \"maintenance\"\n)\nfinal class RuntimeLimitsTest {",
                )),
                ("split-abstract", original_test.replace(
                    "final class RuntimeLimitsTest {",
                    "abstract\nclass RuntimeLimitsTest {",
                )),
            ):
                with self.subTest(non_runnable_test_type=label):
                    self.assertEqual(method_digest, audit.java_method_digest(
                        mutant, "RuntimeLimitsTest", "asciiTrim",
                    ))
                    test_path.write_text(mutant, encoding="utf-8")
                    errors = audit.inventory_errors(root, document, discovered)
                    self.assertTrue(any("not a supported runnable top-level class" in error
                                        for error in errors), errors)
            test_path.write_text(original_test, encoding="utf-8")

            compose = root / "compose.yaml"
            compose.write_text(
                "environment:\n  RAVENROOT_SYNTHETIC_MAX_RETRIES: 9\n", encoding="utf-8",
            )
            errors = audit.inventory_errors(root, document, audit.discover(root))
            self.assertTrue(any("compose carrier candidate set has drifted" in error
                                for error in errors), errors)

    def test_typed_java_default_evaluator_is_closed_and_checked(self) -> None:
        self.assertEqual({"kind": "integer", "value": 16_384},
                         audit.evaluated_java_default("16 * 1_024", False))
        self.assertEqual({"kind": "duration-seconds", "value": 3_600},
                         audit.evaluated_java_default("Duration.ofHours(1)", True))
        self.assertEqual({"kind": "duration-seconds", "value": 86_400},
                         audit.evaluated_java_default("Duration.ofDays(1)", True))
        for expression in ("01", "1__0", "_10", "10_", "-1", "1L", "0x10",
                           "2147483647 * 2", "Duration.ofSeconds(2147483648)",
                           "Duration.ofMillis(1)", "Duration.ofHours(-1)"):
            with self.subTest(expression=expression):
                self.assertIsNone(audit.evaluated_java_default(
                    expression, expression.startswith("Duration."),
                ))

    def graph_limit_authority_fixture(self, root: Path):
        paths = (audit.GRAPH_EXECUTION_LIMITS_PATH, audit.GRAPH_ML_LIMITS_PATH,
                 audit.PAYLOAD_LIMITS_PATH, audit.GRAPH_DEFINITION_STORE_PATH,
                 Path("compose.yaml"), Path("deploy/helm/ravenroot/values.yaml"),
                 Path("deploy/helm/ravenroot/templates/deployment.yaml"),
                 Path("deploy/helm/ravenroot/values.schema.json"),
                 Path("deploy/kubernetes/ravenroot.yaml"),
                 Path("ravenroot/ravenroot-core/src/test/java/ai/ravenroot/core/runtime/"
                      "GraphDeploymentConfigurationContractTest.java"),
                 Path("scripts/tests/test_graph_platform_configuration.sh"))
        pinned = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, check=True,
            capture_output=True, text=True,
        ).stdout.strip()
        for relative in paths:
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(subprocess.run(
                ["git", "show", f"{pinned}:{relative.as_posix()}"], cwd=ROOT,
                check=True, capture_output=True,
            ).stdout)
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        subprocess.run(["git", "add", *[path.as_posix() for path in paths]],
                       cwd=root, check=True)

        inventory = json.loads((ROOT / "scripts/operational-configuration-inventory.json").read_text())
        records = inventory["evidenceRecords"]
        graph_rows = [copy.deepcopy(entry) for entry in inventory["entries"]
                      if entry.get("setting") in audit.GRAPH_LIMIT_AUTHORITY_BY_SETTING]
        for entry in graph_rows:
            coverage = entry.get("coverageEvidence")
            if isinstance(coverage, dict) and coverage.get("kind") == "graph-platform-carriers-v1":
                for path_field, digest_field in (
                        ("contractTestPath", "contractTestDigest"),
                        ("shellTestPath", "shellTestDigest")):
                    coverage[digest_field] = audit.hashlib.sha256(
                        (ROOT / coverage[path_field]).read_bytes()).hexdigest()
        candidates = {
            entry["id"]: audit.Candidate(
                id=entry["id"], path=entry["path"], line=entry["line"],
                symbol=entry["symbol"], kind=entry["kind"], role=entry["role"],
                expression=entry["expression"], expression_digest=entry["expressionDigest"],
                evidence=records[entry["evidenceDigest"]],
                evidence_digest=entry["evidenceDigest"], surface=entry["surface"],
            )
            for entry in graph_rows
        }
        entries = {entry["id"]: entry for entry in graph_rows}
        authority = audit.graph_limit_family_from_source(root, candidates)
        self.assertIsNotNone(authority)
        return ({audit.GRAPH_LIMIT_FAMILY_ID: authority}, entries, candidates)

    def graph_limit_inventory_document(self, authorities, entries, candidates):
        return {
            "schemaVersion": audit.SCHEMA_VERSION,
            "reconciliationRequired": False,
            "entries": [copy.deepcopy(entries[identifier]) for identifier in sorted(entries)],
            "retiredEntries": [], "migrationHistory": [],
            "evidenceRecords": {
                candidate.evidence_digest: candidate.evidence
                for candidate in candidates.values()
            },
            "graphLimitAuthorities": copy.deepcopy(authorities),
        }

    def graph_limit_inventory_errors(self, document, candidates):
        # Only graph rows are present. Other mandatory families remain unmocked in their dedicated
        # direct/global tests; this scope isolates graph metadata rather than hiding their diagnostics.
        with mock.patch.object(audit, "assistant_limit_authority_errors", return_value=[]), \
                mock.patch.object(audit, "helm_authority_errors", return_value=[]), \
                mock.patch.object(audit, "persistence_policy_authority_errors", return_value=[]), \
                mock.patch.object(audit, "external_io_policy_authority_errors", return_value=[]), \
                mock.patch.object(audit, "program_github_policy_authority_errors", return_value=[]), \
                mock.patch.object(audit, "interaction_websocket_authority_errors", return_value=[]):
            return audit.inventory_errors(ROOT, document, tuple(candidates.values()))

    def graph_limit_errors(self, root: Path, authorities, entries, candidates):
        return audit.graph_limit_authority_errors(root, authorities, entries, candidates)

    def assert_graph_source_family_rejected(self, root: Path) -> None:
        refreshed = {candidate.id: candidate for candidate in audit.discover(root)}
        self.assertIsNone(audit.graph_limit_family_from_source(root, refreshed))

    def test_graph_environment_family_proves_all_25_cross_owner_bindings(self) -> None:
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            authorities, entries, candidates = self.graph_limit_authority_fixture(root)
            authority = authorities[audit.GRAPH_LIMIT_FAMILY_ID]
            self.assertEqual(25, len(authority["settings"]))
            self.assertEqual(
                {"GraphMlLimits": 10, "PayloadLimits": 6, "GraphExecutionLimits": 9},
                dict(Counter(setting["targetConstructor"] for setting in authority["settings"])),
            )
            self.assertEqual(
                ("10485760 bytes", "1..268435456",
                 ["oc-2f030c18b06c04d7ca7d", "oc-4fcd39d64aa4eba16a87",
                  "oc-8a17ba40f2d9eefefd3c"]),
                next((setting["defaultDisplay"], setting["validationDisplay"],
                      setting["defaultEvidence"]) for setting in authority["settings"]
                     if setting["setting"] == "graph.graphml.max-bytes"),
            )
            self.assertEqual([], self.graph_limit_errors(
                root, authorities, entries, candidates))

    def test_graph_environment_family_binds_public_defaults_ranges_and_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            authorities, entries, candidates = self.graph_limit_authority_fixture(root)
            document = self.graph_limit_inventory_document(authorities, entries, candidates)
            self.assertEqual([], self.graph_limit_inventory_errors(document, candidates))
            setting = "graph.graphml.max-nodes"
            setting_rows = [entry for entry in document["entries"]
                            if entry.get("setting") == setting]
            environment_id = next(
                entry["id"] for entry in setting_rows
                if entry["path"] == audit.GRAPH_EXECUTION_LIMITS_PATH.as_posix()
                and entry["kind"] == "environment-binding")
            correct_evidence = list(setting_rows[0]["defaultEvidence"])

            for label, change in (
                ("default", lambda entry: entry.update(default="fabricated default")),
                ("validation", lambda entry: entry.update(validation="fabricated validation")),
                ("default-evidence", lambda entry: entry.update(
                    defaultEvidence=[environment_id])),
                ("duplicate-default-evidence", lambda entry: entry.update(
                    defaultEvidence=correct_evidence + [correct_evidence[0]])),
                ("default-evidence-with-environment", lambda entry: entry.update(
                    defaultEvidence=correct_evidence + [environment_id])),
                ("combined", lambda entry: entry.update(
                    default="fabricated default", validation="fabricated validation",
                    defaultEvidence=[environment_id])),
            ):
                with self.subTest(row_metadata=label):
                    changed = copy.deepcopy(document)
                    for entry in changed["entries"]:
                        if entry.get("setting") == setting:
                            change(entry)
                    errors = self.graph_limit_inventory_errors(changed, candidates)
                    self.assertTrue(any("graph default, range, or exact default evidence"
                                        in error for error in errors), errors)

            missing = copy.deepcopy(document)
            for entry in missing["entries"]:
                if entry.get("setting") == setting:
                    entry["defaultEvidence"] = []
            self.assertTrue(self.graph_limit_inventory_errors(missing, candidates))

    def test_graph_environment_family_rejects_metadata_candidates_and_source_flow_mutations(self) -> None:
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            authorities, entries, candidates = self.graph_limit_authority_fixture(root)
            authority = authorities[audit.GRAPH_LIMIT_FAMILY_ID]

            for label, change in (
                ("missing-family", lambda changed: changed.clear()),
                ("missing-setting", lambda changed: changed[audit.GRAPH_LIMIT_FAMILY_ID][
                    "settings"].pop()),
                ("wrong-owner", lambda changed: changed[audit.GRAPH_LIMIT_FAMILY_ID][
                    "settings"][0].update(typedOwner=audit.PAYLOAD_LIMITS_PATH.as_posix()
                                          + "#PayloadLimits")),
                ("same-valued-wrong-field", lambda changed: next(
                    setting for setting in changed[audit.GRAPH_LIMIT_FAMILY_ID]["settings"]
                    if setting["setting"] == "graph.graphml.max-nodes"
                ).update(field="maxNamespaceDeclarations")),
                ("wrong-environment", lambda changed: changed[audit.GRAPH_LIMIT_FAMILY_ID][
                    "settings"][0].update(environment="RAVENROOT_GRAPH_MAX_NODES")),
                ("wrong-component", lambda changed: changed[audit.GRAPH_LIMIT_FAMILY_ID][
                    "settings"][0].update(targetComponentIndex=1)),
                ("wrong-candidate", lambda changed: changed[audit.GRAPH_LIMIT_FAMILY_ID][
                    "settings"][0].update(environmentCandidateId="oc-not-the-declaration")),
            ):
                with self.subTest(metadata=label):
                    changed = copy.deepcopy(authorities)
                    change(changed)
                    self.assertTrue(self.graph_limit_errors(
                        root, changed, entries, candidates), label)

            first = authority["settings"][0]
            same_environment = audit.Candidate(
                id="oc-alien", path="ravenroot/example/Alien.java", line=1,
                symbol="Alien", kind="environment-binding", role=first["environment"],
                expression=first["environment"],
                expression_digest=audit.hashlib.sha256(first["environment"].encode()).hexdigest(),
                evidence=first["environment"],
                evidence_digest=audit.hashlib.sha256(b"alien graph binding").hexdigest(),
                surface="java",
            )
            alien_candidates = {**candidates, same_environment.id: same_environment}
            alien_entries = copy.deepcopy(entries)
            alien_entries[same_environment.id] = {
                **same_environment.inventory_entry(), "status": "already-centralized",
                "classification": "operator-configurable", "setting": first["setting"],
            }
            self.assertTrue(any("not fully partitioned" in error for error in
                                self.graph_limit_errors(
                                    root, authorities, alien_entries, alien_candidates)))

            source_path = root / audit.GRAPH_EXECUTION_LIMITS_PATH
            original = source_path.read_text(encoding="utf-8")
            source_mutations = {
                "wrong-fallback": original.replace(
                    "graphMl.maxBytes(),\n                        GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES",
                    "graphMl.maxNodes(),\n                        GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES",
                    1),
                "same-valued-wrong-fallback": original.replace(
                    "MAX_NODES_VARIABLE, graphMl.maxNodes(), GraphMlLimits.HARD_MAX_NODES",
                    "MAX_NODES_VARIABLE, graphMl.maxNamespaceDeclarations(), "
                    "GraphMlLimits.HARD_MAX_NODES", 1),
                "same-valued-wrong-ceiling": original.replace(
                    "MAX_NODES_VARIABLE, graphMl.maxNodes(), GraphMlLimits.HARD_MAX_NODES",
                    "MAX_NODES_VARIABLE, graphMl.maxNodes(), "
                    "GraphMlLimits.HARD_MAX_NAMESPACE_DECLARATIONS", 1),
                "missing-root-link": original.replace(
                    "return new GraphExecutionLimits(graphMl, payload,",
                    "return new GraphExecutionLimits(GraphMlLimits.DEFAULTS, payload,", 1),
                "nested-constructor-is-unused": original.replace(
                    "graphMl = new GraphMlLimits(",
                    "graphMl = GraphMlLimits.DEFAULTS;\n"
                    "        GraphMlLimits unusedGraphMl = new GraphMlLimits(", 1),
                "root-constructor-is-unused": original.replace(
                    "return new GraphExecutionLimits(graphMl, payload,",
                    "GraphExecutionLimits unusedRoot = new GraphExecutionLimits(graphMl, payload,",
                    1).replace(
                        "defaults.maxRecoveryDeliveriesPerAttempt, HARD_MAX_RECOVERY_DELIVERIES));\n"
                        "    }\n\n    private static int integer",
                        "defaults.maxRecoveryDeliveriesPerAttempt, HARD_MAX_RECOVERY_DELIVERIES));\n"
                        "        return DEFAULTS;\n"
                        "    }\n\n    private static int integer",
                        1),
                "wrong-helper-result": original.replace("return (int) value;", "return 1;", 1),
                "ignored-parsed-long": original.replace("return value;", "return fallback;", 1),
                "raw-value-refusal": original.replace(
                    "throw invalid(name, ceiling);",
                    "throw new IllegalArgumentException(raw, invalid);", 1),
                "ignored-positive-within": original.replace(
                    "if (value < 1) throw new IllegalArgumentException(name + \" must be positive\");",
                    "if (false) throw new IllegalArgumentException(name + \" must be positive\");", 1),
                "wrong-environment-literal": original.replace(
                    '"RAVENROOT_GRAPHML_MAX_BYTES"', '"RAVENROOT_GRAPH_MAX_NODES"', 1),
                "wrong-root-graphml-default": original.replace(
                    "GraphMlLimits.DEFAULTS,\n            PayloadLimits.DEFAULTS,",
                    "new GraphMlLimits(1, 1, 1, 1, 1, 1, 1, 1, 1, 1),\n"
                    "            PayloadLimits.DEFAULTS,", 1),
                "wrong-root-payload-default": original.replace(
                    "GraphMlLimits.DEFAULTS,\n            PayloadLimits.DEFAULTS,",
                    "GraphMlLimits.DEFAULTS,\n"
                    "            new PayloadLimits(1, 1, 1, 1, 1, 1),", 1),
                "nested-long-shadow": original.replace(
                    "\n}\n", "\n    private static final class Long {\n"
                    "        static long parseLong(String raw) { return 1; }\n"
                    "    }\n}\n", 1),
                "conflicting-long-import": original.replace(
                    "import java.util.Map;", "import example.Long;\nimport java.util.Map;", 1),
            }
            for label, mutated in source_mutations.items():
                with self.subTest(source=label):
                    self.assertNotEqual(original, mutated)
                    source_path.write_text(mutated, encoding="utf-8")
                    self.assert_graph_source_family_rejected(root)
            source_path.write_text(original, encoding="utf-8")

            graph_ml_path = root / audit.GRAPH_ML_LIMITS_PATH
            original_graph_ml = graph_ml_path.read_text(encoding="utf-8")
            graph_ml_mutations = {
                "wrong-default": original_graph_ml.replace(
                    "GraphDefinitionStore.DEFAULT_MAX_DEFINITION_BYTES,\n            10_000,",
                    "GraphDefinitionStore.DEFAULT_MAX_DEFINITION_BYTES,\n            10_001,", 1),
                "wrong-default-same-valued-field": original_graph_ml.replace(
                    "GraphDefinitionStore.DEFAULT_MAX_DEFINITION_BYTES,\n            10_000,",
                    "GraphDefinitionStore.DEFAULT_MAX_DEFINITION_BYTES,\n"
                    "            DEFAULTS.maxNamespaceDeclarations(),", 1),
                "wrong-ceiling": original_graph_ml.replace(
                    "public static final int HARD_MAX_NODES = 1_000_000;",
                    "public static final int HARD_MAX_NODES = 1_000_001;", 1),
                "missing-defaults": original_graph_ml.replace(
                    "public static final GraphMlLimits DEFAULTS =",
                    "public static final GraphMlLimits LEGACY_DEFAULTS =", 1),
                "multiple-defaults": original_graph_ml.replace(
                    "public GraphMlLimits(int maxBytes,",
                    "public static final GraphMlLimits DEFAULTS = new GraphMlLimits(\n"
                    "        GraphDefinitionStore.DEFAULT_MAX_DEFINITION_BYTES, 10_000, 25_000,\n"
                    "        100_000, 64, 1024 * 1024, 4_096, 250_000, 500_000, 10_000);\n\n"
                    "    public GraphMlLimits(int maxBytes,", 1),
                "wrong-constant-owner-import": original_graph_ml.replace(
                    "import ai.ravenroot.api.persistence.GraphDefinitionStore;",
                    "import example.GraphDefinitionStore;", 1),
                "compact-constructor-rewrites-component": original_graph_ml.replace(
                    "public GraphMlLimits {\n",
                    "public GraphMlLimits {\n        maxNodes = 1;\n", 1),
                "explicit-component-accessor": original_graph_ml.rsplit("\n}", 1)[0]
                    + "\n\n    public int maxNodes() {\n        return 1;\n    }\n}\n",
            }
            for label, mutated in graph_ml_mutations.items():
                with self.subTest(default_source=label):
                    self.assertNotEqual(original_graph_ml, mutated)
                    graph_ml_path.write_text(mutated, encoding="utf-8")
                    self.assert_graph_source_family_rejected(root)
            graph_ml_path.write_text(original_graph_ml, encoding="utf-8")

            graph_store_path = root / audit.GRAPH_DEFINITION_STORE_PATH
            original_graph_store = graph_store_path.read_text(encoding="utf-8")
            wrong_owner_kind = original_graph_store.replace(
                "public interface GraphDefinitionStore extends AutoCloseable {",
                "public abstract class GraphDefinitionStore implements AutoCloseable {", 1)
            self.assertNotEqual(original_graph_store, wrong_owner_kind)
            graph_store_path.write_text(wrong_owner_kind, encoding="utf-8")
            self.assert_graph_source_family_rejected(root)
            graph_store_path.write_text(original_graph_store, encoding="utf-8")

    def test_report_counts_retained_published_contract_descriptions(self) -> None:
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            _authority, entries, _candidates, _details = self.route_table_authority_fixture(root)
            document = {"entries": list(entries.values()), "retiredEntries": [],
                        "migrationHistory": []}
            self.assertIn(
                "| Retained published contract descriptions | 348 |",
                audit.render_report(document),
            )
            deferred = copy.deepcopy(document)
            published = next(entry for entry in deferred["entries"]
                             if entry["classification"] == "published-contract-description")
            published.update(status="deferred", followUp="#225")
            self.assertIn(
                "| Retained published contract descriptions | 347 |",
                audit.render_report(deferred),
            )
        self.assertIn(
            "| Retained published contract descriptions | 0 |",
            audit.render_report({"entries": [], "retiredEntries": [], "migrationHistory": []}),
        )

    def test_report_counts_active_retired_and_reappearance_history_separately(self) -> None:
        document = {
            "entries": [{
                "id": "oc-active", "path": "runtime/Policy.java", "line": 1,
                "symbol": "Policy", "surface": "java", "status": "retained",
                "classification": "protocol-or-format-invariant", "rationale": "Protocol token.",
            }],
            "retiredEntries": [{"id": "oc-retired"}, {"id": "oc-active"}],
            "migrationHistory": [], "reconciliationHistory": [],
            "normalizedIdentityReappearanceHistory": [{"candidateId": "oc-active"}],
        }
        report = audit.render_report(document)
        self.assertIn("| Atomic operational candidates discovered | 1 |", report)
        self.assertIn("Retired source candidates preserved in inventory history: 2.", report)
        self.assertIn("Approved normalized-identity reappearances: 1.", report)

    def test_new_named_operational_constant_is_rejected(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            source = root / "ravenroot/example/src/main/java/dev/example/RuntimePolicy.java"
            source.write_text(source.read_text(encoding="utf-8").replace(
                "}\n", "  static final int WORKER_CAPACITY = 37;\n}\n"), encoding="utf-8")
            errors = audit.check(root, root / "scripts/operational-configuration-inventory.json",
                                 root / "docs/architecture/operational-configuration-audit.md",
                                 require_complete=False, allow_unreconciled=True)
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
                                 require_complete=False, allow_unreconciled=True)
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
                                 require_complete=False, allow_unreconciled=True)
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
                                 require_complete=False, allow_unreconciled=True)
        self.assertTrue(any("service.sh" in error and "RAVENROOT_WAIT_TIMEOUT" in error
                            for error in errors), errors)

    def test_docker_identity_and_port_directives_are_rejected(self) -> None:
        with synthetic_repository() as location:
            root = Path(location)
            (root / "Dockerfile").write_text(
                "FROM scratch\nUSER 12345:12345\nEXPOSE 31337\n", encoding="utf-8")
            errors = audit.check(root, root / "scripts/operational-configuration-inventory.json",
                                 root / "docs/architecture/operational-configuration-audit.md",
                                 require_complete=False, allow_unreconciled=True)
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
                                 require_complete=False, allow_unreconciled=True)
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
                                 require_complete=False, allow_unreconciled=True)
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
                                 require_complete=False, allow_unreconciled=True)
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
                                 root / "docs/architecture/operational-configuration-audit.md",
                                 allow_unreconciled=True)
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
            errors = audit.check(root, inventory, report, allow_unreconciled=True)
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
            "reconciliationRequired": False,
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
            ("0" * 40, ""),
            ("0" * 40, "inventory.json\0ignored"),
            ("0" * 40, "./inventory.json"),
            ("0" * 40, "nested//inventory.json"),
        )
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            assistant = root / audit.ASSISTANT_CONFIGURATION_PATH
            assistant.parent.mkdir(parents=True, exist_ok=True)
            assistant.write_bytes((ROOT / audit.ASSISTANT_CONFIGURATION_PATH).read_bytes())
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "add", assistant.relative_to(root).as_posix()],
                           cwd=root, check=True)
            assistant_source = assistant.read_text(encoding="utf-8")
            candidate = next(
                candidate for _offset, candidate in audit.java_source_candidates(
                    audit.ASSISTANT_CONFIGURATION_PATH, assistant_source)
                if candidate.kind == "environment-binding"
                and candidate.expression == "RAVENROOT_ASSISTANT_MAX_OUTPUT_TOKENS")
            entry = candidate.inventory_entry()
            entry.update(
                status="already-centralized", classification="operator-configurable",
                setting="synthetic.assistant.max-output-tokens",
                owner=f"{audit.ASSISTANT_CONFIGURATION_PATH.as_posix()}#AssistantConfiguration",
                field="maxOutputTokens", bindings=[candidate.expression], default="4096 tokens",
                defaultEvidence=[candidate.id], validation="positive integer",
                scope="synthetic assistant service", pinning="live service policy",
                coverage="source owner ordering regression fixture",
                rationale="Exercises source-owner validation after migration preflight.")
            base["entries"] = [entry]
            base["evidenceRecords"] = {candidate.evidence_digest: candidate.evidence}
            discovered = (candidate,)
            for revision, source_path in cases:
                with self.subTest(revision=revision, source_path=source_path):
                    audit.current_source_owner.cache_clear()
                    audit.committed_source.cache_clear()
                    audit.commit_exists.cache_clear()
                    audit.revision_is_ancestor.cache_clear()
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
                    with mock.patch.object(audit.subprocess, "run") as run:
                        errors = audit.inventory_errors(root, document, discovered)
                    self.assertTrue(any("migration source is not locally resolvable" in error
                                        for error in errors), errors)
                    run.assert_not_called()

            audit.current_source_owner.cache_clear()
            audit.committed_source.cache_clear()
            audit.commit_exists.cache_clear()
            audit.revision_is_ancestor.cache_clear()
            misplaced = copy.deepcopy(base)
            misplaced["migrationHistory"] = [{
                "fromSchema": 1,
                "toSchema": audit.SCHEMA_VERSION,
                "sourceRevision": "--output=/tmp/never-write",
                "sourcePath": "inventory.json",
                "sourceFileDigest": "0" * 64,
                "candidateCount": 0,
                "statusCounts": {},
                "rationale": "Synthetic misplaced-preflight mutation.",
            }]
            real_locator = audit.historical_source_locator_is_safe
            locator_calls = 0

            def skip_only_preflight(revision, source_path):
                nonlocal locator_calls
                locator_calls += 1
                return True if locator_calls == 1 else real_locator(revision, source_path)

            with mock.patch.object(
                    audit, "historical_source_locator_is_safe",
                    side_effect=skip_only_preflight), \
                    mock.patch.object(audit.subprocess, "run") as run:
                audit.inventory_errors(root, misplaced, discovered)
                with self.assertRaises(AssertionError):
                    run.assert_not_called()
            self.assertEqual(
                ["git", "ls-files", "--error-unmatch", "--",
                 audit.ASSISTANT_CONFIGURATION_PATH.as_posix()],
                run.call_args_list[0].args[0])
            self.assertTrue(all("--output=/tmp/never-write" not in call.args[0]
                                for call in run.call_args_list))

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
                "reconciliationRequired": False,
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

            audit.committed_source.cache_clear()
            unresolved = copy.deepcopy(document)
            unresolved["migrationHistory"][0]["sourceRevision"] = "f" * 40
            real_run = subprocess.run
            with mock.patch.object(audit.subprocess, "run", wraps=real_run) as run:
                errors = audit.inventory_errors(root, unresolved, ())
            self.assertTrue(any("migration source is not locally resolvable" in error
                                for error in errors), errors)
            self.assertTrue(any(call.args[0][:2] == ["git", "show"]
                                for call in run.call_args_list), run.call_args_list)

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

            unknown_resolver_kind = copy.deepcopy(document)
            unknown_resolver_kind["resolverAuthorities"][resolver_id]["kind"] = "alien"
            unknown_kind_errors = audit.resolver_authority_errors(
                root, unknown_resolver_kind["resolverAuthorities"],
            )
            self.assertTrue(any("unsupported kind alien" in error
                                for error in unknown_kind_errors), unknown_kind_errors)

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
            self.assertTrue(any("retirement.approved=true" in error for error in refused), refused)
            self.assertGreaterEqual(plan["added"], 1)
            document = json.loads(inventory.read_text(encoding="utf-8"))
            current_ids = {candidate.id for candidate in audit.discover(root)}
            for entry in document["entries"]:
                if entry["id"] not in current_ids:
                    entry["retirement"] = {
                        "approved": True,
                        "rationale": f"Reviewed source replacement for {entry['id']}.",
                    }
            inventory.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            accepted, summary = audit.refresh_inventory(
                root, inventory, root / "docs/architecture/operational-configuration-audit.md",
                accept_retired_pending=True,
            )
            document = json.loads(inventory.read_text(encoding="utf-8"))
        self.assertEqual([], accepted)
        self.assertEqual(plan["added"], summary["added"])
        self.assertEqual(plan["retired"], summary["retired"])
        self.assertEqual(summary["retired"], len(document["retiredEntries"]))
        self.assertTrue(all(entry["retirementRationale"].startswith("Reviewed source replacement")
                            for entry in document["retiredEntries"]))

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

    def test_reconciliation_requires_complete_approved_source_backed_partitions(self) -> None:
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            inventory = root / "scripts/operational-configuration-inventory.json"
            inventory.parent.mkdir(parents=True)
            old_entry = {
                "id": "oc-old", "path": "runtime/Policy.java", "line": 1,
                "symbol": "Policy", "kind": "fixed-declaration", "role": "TOKEN",
                "expression": '"v1"', "expressionDigest": audit.hashlib.sha256(b'"v1"').hexdigest(),
                "evidenceDigest": audit.hashlib.sha256(b"old evidence").hexdigest(),
                "surface": "java", "status": "retained",
                "classification": "protocol-or-format-invariant",
                "rationale": "Stable protocol token.",
                "defaultEvidence": ["oc-old"],
            }
            source_document = {
                "schemaVersion": audit.SCHEMA_VERSION, "reconciliationRequired": False,
                "entries": [old_entry],
                "retiredEntries": [{
                    "id": "oc-legacy-retired", "retirementRationale": "Earlier approved removal.",
                }],
                "migrationHistory": [{
                    "fromSchema": 3, "toSchema": 4, "sourceRevision": "a" * 40,
                    "sourcePath": "scripts/operational-configuration-inventory.json",
                    "sourceFileDigest": "b" * 64, "candidateCount": 1,
                    "statusCounts": {"retained": 1}, "rationale": "Earlier schema migration.",
                }],
                "reconciliationHistory": [{
                    "id": "earlier-reconciliation",
                    "additions": [{"id": "oc-old"}],
                }],
                "normalizedIdentityReappearanceHistory": [{
                    "candidateId": "oc-old", "rationale": "Anchored historical reference fixture.",
                }],
                "evidenceRecords": {old_entry["evidenceDigest"]: "old evidence"},
            }
            inventory.write_text(json.dumps(source_document, indent=2) + "\n", encoding="utf-8")
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "add", "scripts/operational-configuration-inventory.json"],
                           cwd=root, check=True)
            subprocess.run(["git", "-c", "user.name=Audit Test", "-c",
                            "user.email=audit@example.invalid", "commit", "-qm", "source inventory"],
                           cwd=root, check=True)
            revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                                      capture_output=True, text=True).stdout.strip()
            source_raw = inventory.read_bytes()
            candidate = audit.Candidate(
                "oc-new", "runtime/Policy.java", 2, "Policy", "fixed-declaration", "TOKEN",
                '"v1"', audit.hashlib.sha256(b'"v1"').hexdigest(), "new evidence",
                audit.hashlib.sha256(b"new evidence").hexdigest(), "java",
            )
            plan = {
                "id": "synthetic-reconciliation", "issue": "#316",
                "sourceRevision": revision, "targetRevision": revision,
                "sourceInventoryPath": "scripts/operational-configuration-inventory.json",
                "sourceInventoryDigest": audit.hashlib.sha256(source_raw).hexdigest(),
                "targetCandidateDigest": audit.candidate_set_digest([candidate.id]),
                "mappings": [{
                    "fromId": "oc-old", "toId": "oc-new", "approved": True,
                    "rationale": "The same source atom moved.",
                    "equivalence": {
                        "kind": "same-atom-v1",
                        "beforeEvidenceDigest": old_entry["evidenceDigest"],
                        "afterEvidenceDigest": candidate.evidence_digest,
                    },
                }],
                "retirements": [], "additions": [],
            }
            self.assertEqual([], audit.reconciliation_plan_errors(
                root, source_document, (candidate,), plan)[0])

            with mock.patch.object(audit, "current_route_table_authority", return_value={}):
                remapped, remap_errors = audit.apply_reconciliation(
                    root, source_document, (candidate,), plan)
            self.assertEqual([], remap_errors)
            self.assertIsNotNone(remapped)
            self.assertEqual(["oc-new"], remapped["entries"][0]["defaultEvidence"])
            self.assertEqual("oc-old", remapped["reconciliationHistory"][0]["additions"][0]["id"],
                             "anchored history must never be rewritten")
            self.assertEqual(
                "oc-old", remapped["normalizedIdentityReappearanceHistory"][0]["candidateId"],
                "reappearance history must never be rewritten")

            malformed_issue = copy.deepcopy(plan)
            malformed_issue["issue"] = "316"
            self.assertTrue(any("unsupported or incomplete shape" in error for error in
                                audit.reconciliation_plan_errors(
                                    root, source_document, (candidate,), malformed_issue)[0]))
            for invalid_issue in (True, " #316", "#316 "):
                malformed_issue = copy.deepcopy(plan)
                malformed_issue["issue"] = invalid_issue
                self.assertTrue(any("unsupported or incomplete shape" in error for error in
                                    audit.reconciliation_plan_errors(
                                        root, source_document, (candidate,), malformed_issue)[0]))

            partial = copy.deepcopy(plan)
            partial["mappings"] = []
            self.assertTrue(any("partition" in error for error in
                                audit.reconciliation_plan_errors(
                                    root, source_document, (candidate,), partial)[0]))

            refused = copy.deepcopy(plan)
            refused["mappings"][0]["approved"] = False
            self.assertTrue(any("row-level approval" in error for error in
                                audit.reconciliation_plan_errors(
                                    root, source_document, (candidate,), refused)[0]))

            arbitrary = copy.deepcopy(plan)
            arbitrary["mappings"][0]["equivalence"]["beforeEvidenceDigest"] = "0" * 64
            self.assertTrue(any("same source atom" in error for error in
                                audit.reconciliation_plan_errors(
                                    root, source_document, (candidate,), arbitrary)[0]))

            referenced = copy.deepcopy(source_document)
            referenced["description"] = "oc-old"
            with mock.patch.object(audit, "current_route_table_authority", return_value={}):
                _ignored, reference_errors = audit.apply_reconciliation(
                    root, referenced, (candidate,), plan)
            self.assertTrue(any("undeclared reference field" in error
                                for error in reference_errors), reference_errors)

            with mock.patch.object(audit, "current_route_table_authority", return_value={}):
                refreshed, errors = audit.apply_reconciliation(
                    root, source_document, (candidate,), plan)
            self.assertEqual([], errors)
            self.assertIsNotNone(refreshed)
            assert refreshed is not None
            self.assertIs(refreshed["reconciliationRequired"], True)
            active = refreshed["entries"][0]
            self.assertEqual("Stable protocol token.", active["rationale"])
            self.assertEqual({"history": plan["id"], "fromId": "oc-old"},
                             active["identityMigration"])
            self.assertEqual([], audit.reconciliation_history_errors(
                root, refreshed, (candidate,)))

            tampered = copy.deepcopy(refreshed)
            tampered["reconciliationHistory"].insert(0, {"id": "forged-prior-record"})
            self.assertTrue(any("append-only chain" in error for error in
                                audit.reconciliation_history_errors(root, tampered, (candidate,))))

            tampered_reappearance = copy.deepcopy(refreshed)
            tampered_reappearance["normalizedIdentityReappearanceHistory"][0]["rationale"] = \
                "Changed later."
            self.assertTrue(any("normalizedIdentityReappearanceHistory is not an append-only chain"
                                in error for error in audit.reconciliation_history_errors(
                                    root, tampered_reappearance, (candidate,))))

            missing_retirement = copy.deepcopy(refreshed)
            missing_retirement["retiredEntries"] = []
            self.assertTrue(any("retiredEntries is not the exact anchored ledger" in error
                                for error in audit.reconciliation_history_errors(
                                    root, missing_retirement, (candidate,))))

            tampered_retirement = copy.deepcopy(refreshed)
            tampered_retirement["retiredEntries"][0]["retirementRationale"] = "Changed later."
            self.assertTrue(any("retiredEntries is not the exact anchored ledger" in error
                                for error in audit.reconciliation_history_errors(
                                    root, tampered_retirement, (candidate,))))

            missing_migration = copy.deepcopy(refreshed)
            missing_migration["migrationHistory"] = []
            self.assertTrue(any("migrationHistory is not the exact anchored ledger" in error
                                for error in audit.reconciliation_history_errors(
                                    root, missing_migration, (candidate,))))

            tampered_migration = copy.deepcopy(refreshed)
            tampered_migration["migrationHistory"][0]["rationale"] = "Changed later."
            self.assertTrue(any("migrationHistory is not the exact anchored ledger" in error
                                for error in audit.reconciliation_history_errors(
                                    root, tampered_migration, (candidate,))))

            inventory.write_text(json.dumps(refreshed, indent=2) + "\n", encoding="utf-8")
            subprocess.run(["git", "add", "scripts/operational-configuration-inventory.json"],
                           cwd=root, check=True)
            subprocess.run(["git", "-c", "user.name=Audit Test", "-c",
                            "user.email=audit@example.invalid", "commit", "-qm", "reconciled inventory"],
                           cwd=root, check=True)
            review_revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                                             capture_output=True, text=True).stdout.strip()
            reviewed = copy.deepcopy(refreshed)
            before_metadata = audit.candidate_semantic_payload(reviewed["entries"][0])
            after_metadata = copy.deepcopy(before_metadata)
            after_metadata["rationale"] = "Approved later semantic evidence."
            reviewed["entries"][0]["rationale"] = after_metadata["rationale"]
            reviewed["semanticReviewHistory"] = [{
                "candidateId": "oc-new", "approved": True,
                "rationale": "Later review approved stronger evidence.",
                "sourceRevision": review_revision,
                "beforeMetadata": before_metadata, "afterMetadata": after_metadata,
            }]
            self.assertEqual([], audit.reconciliation_history_errors(
                root, reviewed, (candidate,)))

    def test_unresolved_operator_authority_stays_deferred_and_exact(self) -> None:
        candidate = audit.Candidate(
            "oc-unresolved", "runtime/Policy.java", 1, "Policy", "fixed-declaration",
            "MAX_RETRIES", "3", audit.hashlib.sha256(b"3").hexdigest(),
            "static final int MAX_RETRIES = 3;",
            audit.hashlib.sha256(b"static final int MAX_RETRIES = 3;").hexdigest(), "java",
        )
        binding = audit.Candidate(
            "oc-unresolved-binding", "runtime/Policy.java", 2, "Policy",
            "environment-binding", "RAVENROOT_MAX_RETRIES", "RAVENROOT_MAX_RETRIES",
            audit.hashlib.sha256(b"RAVENROOT_MAX_RETRIES").hexdigest(),
            'String name = "RAVENROOT_MAX_RETRIES";',
            audit.hashlib.sha256(b'String name = "RAVENROOT_MAX_RETRIES";').hexdigest(), "java",
        )
        shared = {
            "status": "deferred", "classification": "operator-configurable",
            "setting": "runtime.max-retries", "authorityStatus": "unresolved",
            "prospectiveOwner": "runtime/Policy.java#Policy",
            "unresolvedEvidence": "The literal exists without an operator binding.",
            "bindings": ["RAVENROOT_MAX_RETRIES"],
            "default": "3 attempts; RAVENROOT_MAX_RETRIES is the proposed binding.",
            "defaultEvidence": [candidate.id, binding.id],
            "validation": "No operator validation contract.",
            "scope": "Runtime scope unresolved.", "pinning": "Pinning unresolved.",
            "coverage": "Deployment coverage unresolved.", "followUp": "#318",
            "rationale": "Operational retry policy lacks a complete authority.",
        }
        entry = {
            **candidate.source_fields(), "status": "deferred",
            **shared, "sourceFact": "Shipped default: 3 attempts.",
        }
        binding_entry = {
            **binding.source_fields(), **shared,
            "sourceFact": "Proposed environment binding: RAVENROOT_MAX_RETRIES.",
        }
        document = {
            "schemaVersion": audit.SCHEMA_VERSION, "reconciliationRequired": False,
            "entries": [entry, binding_entry], "retiredEntries": [], "migrationHistory": [],
            "evidenceRecords": {
                candidate.evidence_digest: candidate.evidence,
                binding.evidence_digest: binding.evidence,
            },
        }
        def errors(value):
            # These two synthetic unresolved rows contain none of the separately mandatory families.
            # Their real direct/global tests stay unmocked; only this unresolved-state unit is isolated.
            with mock.patch.object(audit, "assistant_limit_authority_errors", return_value=[]), \
                    mock.patch.object(audit, "helm_authority_errors", return_value=[]), \
                    mock.patch.object(audit, "persistence_policy_authority_errors", return_value=[]), \
                    mock.patch.object(audit, "external_io_policy_authority_errors", return_value=[]), \
                    mock.patch.object(audit, "program_github_policy_authority_errors", return_value=[]), \
                    mock.patch.object(audit, "interaction_websocket_authority_errors", return_value=[]):
                return audit.inventory_errors(ROOT, value, (candidate, binding))

        self.assertEqual([], errors(document))
        self.assertIn("in progress", audit.render_report(document))
        self.assertEqual(
            audit.render_report(document),
            audit.render_report({**document, "entries": list(reversed(document["entries"]))}),
        )
        report = audit.render_report(document)
        self.assertIn("Shipped default: 3 attempts.", report)
        self.assertIn("Proposed environment binding: RAVENROOT_MAX_RETRIES.", report)

        resolved = copy.deepcopy(document)
        for item in resolved["entries"]:
            item["status"] = "already-centralized"
        self.assertTrue(any("must remain deferred" in error for error in
                            errors(resolved)))

        incomplete = copy.deepcopy(document)
        incomplete["entries"][0]["defaultEvidence"] = ["oc-other"]
        self.assertTrue(any("inconsistent unresolved configuration metadata" in error
                            or "must equal its exact setting rows" in error for error in
                            errors(incomplete)))

        inconsistent = copy.deepcopy(document)
        inconsistent["entries"][1]["default"] = "RAVENROOT_MAX_RETRIES"
        self.assertTrue(any("inconsistent unresolved configuration metadata" in error
                            for error in errors(inconsistent)))

    def test_public_check_rejects_disabled_reconciliation_history(self) -> None:
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            inventory = root / "scripts/operational-configuration-inventory.json"
            report = root / "docs/architecture/operational-configuration-audit.md"
            inventory.parent.mkdir(parents=True)
            report.parent.mkdir(parents=True)
            document = {
                "schemaVersion": audit.SCHEMA_VERSION,
                "reconciliationRequired": False,
                "reconciliationHistory": [],
                "entries": [], "retiredEntries": [], "migrationHistory": [],
                "evidenceRecords": {},
            }
            inventory.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
            report.write_text(audit.render_report(document), encoding="utf-8")
            errors = audit.check(root, inventory, report, require_complete=False)
        self.assertTrue(any("requires reconciliationRequired=true" in error for error in errors), errors)

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


class ProgramGithubPolicyAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        for relative in audit.PROGRAM_GITHUB_REQUIRED_PATHS:
            destination = cls.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, destination)
        subprocess.run(["git", "init", "-q"], cwd=cls.root, check=True)
        subprocess.run(["git", "add", "."], cwd=cls.root, check=True)
        cls.candidates = audit.discover(cls.root)
        cls.discovered = {candidate.id: candidate for candidate in cls.candidates}
        cls.authority = audit.program_github_policy_authority_from_source(cls.root, cls.discovered)
        if cls.authority is None:
            raise AssertionError("The actual supported source must derive before any negative test")
        cls.entries = {candidate.id: {**candidate.source_fields(), "status": "pending-review",
                                     "classification": None} for candidate in cls.candidates}
        for contract in cls.authority["contracts"] + cls.authority["bindingCarriers"]:
            for identifier in contract["candidateIds"]:
                cls.entries[identifier].update(
                    status="already-centralized", classification="operator-configurable",
                    programGithubPolicyAuthority=audit.PROGRAM_GITHUB_POLICY_AUTHORITY_ID,
                    setting=contract["setting"], owner=contract["owner"], field=contract["field"],
                    bindings=contract["bindings"], defaultEvidence=contract["defaultCandidateIds"],
                    default=contract["defaultExpression"], scope=contract["scope"], pinning=contract["pinning"],
                    validation=contract["validation"], coverage=contract["coverage"])
        for partition in cls.authority["semanticPartitions"]:
            for identifier in partition["candidateIds"]:
                cls.entries[identifier].update(status=partition["status"], classification=partition["classification"],
                    programGithubPolicyAuthority=audit.PROGRAM_GITHUB_POLICY_AUTHORITY_ID)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    def test_program_github_all_59_fields_are_closed_and_scanner_blind_fields_are_not_invented(self) -> None:
        authority = self.authority
        self.assertEqual(59, len(authority["contracts"]))
        self.assertEqual(50, sum(item["setting"].startswith("github.") for item in authority["contracts"]))
        self.assertEqual(1, len(authority["bindingCarriers"]))
        self.assertTrue(any(not item["candidateIds"] for item in authority["contracts"]),
                        "A scanner-blind field must have structural proof, never fabricated literal IDs")
        all_ids = [identifier for item in authority["contracts"] + authority["bindingCarriers"]
                   + authority["semanticPartitions"] for identifier in item["candidateIds"]]
        self.assertEqual(len(all_ids), len(set(all_ids)))
        self.assertEqual(set(all_ids), audit.program_github_policy_cohort_candidate_ids(self.root, self.discovered))
        self.assertEqual([], audit.program_github_policy_authority_errors(self.root,
            {audit.PROGRAM_GITHUB_POLICY_AUTHORITY_ID: authority}, self.entries, self.discovered))

    def test_program_github_missing_markers_whole_family_or_scanner_blind_contract_cannot_opt_out(self) -> None:
        for authorities in (None, {}, {audit.PROGRAM_GITHUB_POLICY_AUTHORITY_ID: {}}):
            with self.subTest(authorities=authorities):
                self.assertTrue(audit.program_github_policy_authority_errors(
                    self.root, authorities, self.entries, self.discovered))
        changed = copy.deepcopy(self.authority)
        changed["contracts"] = [item for item in changed["contracts"] if item["candidateIds"]]
        self.assertTrue(audit.program_github_policy_authority_errors(self.root,
            {audit.PROGRAM_GITHUB_POLICY_AUTHORITY_ID: changed}, self.entries, self.discovered))
        entries = copy.deepcopy(self.entries)
        for row in entries.values(): row.pop("programGithubPolicyAuthority", None)
        self.assertTrue(audit.program_github_policy_authority_errors(self.root,
            {audit.PROGRAM_GITHUB_POLICY_AUTHORITY_ID: self.authority}, entries, self.discovered))

    def test_program_github_entry_relabel_foreign_setting_pending_and_omission_refuse(self) -> None:
        identifier = next(item["candidateIds"][0] for item in self.authority["contracts"] if item["candidateIds"])
        for change in ({"classification": "derived", "status": "retained"}, {"setting": "foreign.setting"},
                       {"status": "pending-review", "classification": None}, {"authorityStatus": "unresolved"},
                       {"owner": "foreign/File.java#Owner"}, {"bindings": ["RAVENROOT_FOREIGN"]},
                       {"defaultEvidence": ["oc-not-an-actual-source-id"]}):
            with self.subTest(change=change):
                entries = copy.deepcopy(self.entries); entries[identifier].update(change)
                self.assertTrue(audit.program_github_policy_authority_errors(self.root,
                    {audit.PROGRAM_GITHUB_POLICY_AUTHORITY_ID: self.authority}, entries, self.discovered))
        entries = copy.deepcopy(self.entries); del entries[identifier]
        self.assertTrue(audit.program_github_policy_authority_errors(self.root,
            {audit.PROGRAM_GITHUB_POLICY_AUTHORITY_ID: self.authority}, entries, self.discovered))
        retained_id = self.authority["semanticPartitions"][0]["candidateIds"][0]
        entries = copy.deepcopy(self.entries)
        entries[retained_id]["classification"] = "security-ceiling-or-default" if entries[retained_id]["classification"] != "security-ceiling-or-default" else "derived"
        self.assertTrue(audit.program_github_policy_authority_errors(self.root,
            {audit.PROGRAM_GITHUB_POLICY_AUTHORITY_ID: self.authority}, entries, self.discovered))

    def test_program_github_current_cohort_omission_or_extra_candidate_refuses(self) -> None:
        from dataclasses import replace
        identifier = self.authority["candidateIds"][0]
        changed = dict(self.discovered); del changed[identifier]
        self.assertIsNone(audit.program_github_policy_authority_from_source(self.root, changed))
        source = next(item for item in self.candidates if item.path == audit.PROGRAM_GITHUB_PATHS["graal"])
        extra = replace(source, id="oc-injected-unreviewed", role="NEW_UNREVIEWED_SETTING", expression="123")
        changed = {**self.discovered, extra.id: extra}
        self.assertIsNone(audit.program_github_policy_authority_from_source(self.root, changed))
        excluded = set(audit.PROGRAM_GITHUB_EXCLUDED_PRIOR_IDS)
        self.assertFalse(excluded & set(self.authority["candidateIds"]))
        self.assertNotIn(extra.id, excluded)

    def test_program_github_actual_source_mutations_cannot_be_blessed_by_metadata_refresh(self) -> None:
        mutations = (
            ("graal", "return value != null ? value : environment.get(variable);", "return environment.get(variable);"),
            ("graal", "if (standard != null)", "if (standard != null && !standard.isBlank())"),
            ("graal", "!(properties.get(name) instanceof String)", "false"),
            ("authoring", "source.getBytes(StandardCharsets.UTF_8).length > maxSourceBytes", "source.length() > maxSourceBytes"),
            ("authoring", "count > maxProgramsPerBuild", "count > HARD_MAX_PROGRAMS_PER_BUILD"),
            ("selector", "if (properties.containsKey(PROPERTY))", "if (false)"),
            ("runtime", "policyFor(configuration.javaExecutable(), configuration.timeout(), configuration.maxHeapMegabytes())",
                         "policyFor(configuration.javaExecutable(), Duration.ofMillis(5000), 64)"),
            ("runtime", "policy.deadline().toMillis()", "5000"),
            ("launcher", 'if (placement.hasOverride()) throw new IOException("SANDBOX_RESOURCE_CACHE_UNSUPPORTED");', "if (false) throw new IOException(\"SANDBOX_RESOURCE_CACHE_UNSUPPORTED\");"),
            ("process", "builder.environment().clear();", ""),
            ("process", "verifyPlacement(placement);", ""),
            ("github", 'profiles.get(tenantId + "\\u0000" + name)', 'profiles.get(name)'),
            ("profile", 'Map.entry("name", name)', 'Map.entry("credentialReference", credentialReference)'),
            ("store", "beforePrune != null && !profileContractDigest.equals(beforePrune.profileContractDigest())", "false"),
            ("store", 'statement.execute("BEGIN IMMEDIATE")', 'statement.execute("BEGIN")'),
            ("core", "programAuthoringLimits.requireSource(source);", ""),
            ("authorized", "delegate.programAuthoringLimits().requireProgramCount(artifactIds.size());", ""),
            ("server", "input.readNBytes(programAuthoringLimits.maxBuildRequestBytes() + 1)", "input.readNBytes(10485760 + 1)"),
            ("manifest", "was.programRuntimeDigest(), programRuntimeDigest", "was.programRuntimeDigest(), was.programRuntimeDigest()"),
            ("client", "value?.schemaVersion === 1 && value?.programAuthoring === undefined", "value.schemaVersion === 1"),
            ("ui", "const batchLimit = authoringLimits.maxProgramsPerBuild;", "const batchLimit = 256;"),
        )
        for key, before, after in mutations:
            path = self.root / audit.PROGRAM_GITHUB_PATHS[key]
            original = path.read_text(encoding="utf-8")
            with self.subTest(path=path, before=before):
                self.assertIn(before, original, "Mutation must alter the actual accepted executable source")
                try:
                    path.write_text(original.replace(before, after, 1), encoding="utf-8")
                    refreshed = {candidate.id: candidate for candidate in audit.discover(self.root)}
                    self.assertIsNone(audit.program_github_policy_authority_from_source(self.root, refreshed))
                    errors = audit.program_github_policy_authority_errors(self.root, {}, {}, refreshed)
                    self.assertTrue(any("source family" in error for error in errors), errors)
                finally:
                    path.write_text(original, encoding="utf-8")

    def test_program_github_source_or_decisive_assertion_deletion_refuses(self) -> None:
        path = self.root / audit.PROGRAM_GITHUB_PATHS["authoring"]
        original = path.read_text(encoding="utf-8")
        try:
            path.unlink()
            self.assertTrue(audit.program_github_policy_source_present(self.root))
            self.assertIsNone(audit.program_github_policy_authority_from_source(self.root, self.discovered))
        finally:
            path.write_text(original, encoding="utf-8")
        relative, _, _, _ = audit.PROGRAM_GITHUB_TEST_PROOFS[0]
        path = self.root / relative; original = path.read_text(encoding="utf-8")
        try:
            self.assertIn("assert", original)
            path.write_text(original.replace("assert", "removedAssert", 1), encoding="utf-8")
            self.assertIsNone(audit.program_github_policy_authority_from_source(self.root, self.discovered))
        finally:
            path.write_text(original, encoding="utf-8")

    def test_program_github_live_references_remap_without_rewriting_history_or_prose(self) -> None:
        old, new = 'oc-reviewed-old', 'oc-reviewed-new'
        authority = {"candidateIds": [old], "contracts": [{"candidateIds": [old], "defaultCandidateIds": [old], "rationale": old}],
                     "bindingCarriers": [{"candidateIds": [old], "defaultCandidateIds": [old]}],
                     "semanticPartitions": [{"candidateIds": [old], "rationale": old}]}
        history = [{"candidateIds": [old], "source": old}]
        document = {"programGithubPolicyAuthorities": {audit.PROGRAM_GITHUB_POLICY_AUTHORITY_ID: authority},
                    "reconciliationHistory": copy.deepcopy(history), "retiredEntries": copy.deepcopy(history)}
        audit.remap_declared_candidate_references(document, {old: new})
        self.assertEqual([new], authority["candidateIds"])
        for field in ("contracts", "bindingCarriers", "semanticPartitions"):
            self.assertEqual([new], authority[field][0]["candidateIds"])
        self.assertEqual([new], authority["contracts"][0]["defaultCandidateIds"])
        self.assertEqual([new], authority["bindingCarriers"][0]["defaultCandidateIds"])
        self.assertEqual(old, authority["contracts"][0]["rationale"])
        self.assertEqual(old, authority["semanticPartitions"][0]["rationale"])
        self.assertEqual(history, document["reconciliationHistory"])
        self.assertEqual(history, document["retiredEntries"])

    def test_program_github_closed_deployment_mirrors_and_client_consumer_refuse_bypass(self) -> None:
        mutations = (
            ("compose", "RAVENROOT_PROGRAM_AUTHORING_MAX_SOURCE_BYTES:-}", "RAVENROOT_PROGRAM_AUTHORING_MAX_SOURCE_BYTES:-1024}"),
            ("helmSchema", '"maximum": 1048576', '"maximum": 1048577'),
            ("helmDeployment", '.Values.programAuthoring.maxSourceBytes', '.Values.programAuthoring.maxBuildRequestBytes'),
            ("helmValues", 'programAuthoring:\n  maxSourceBytes: ""', 'programAuthoring:\n  maxSourceBytes: 1048576'),
            ("client", "programs.length > authoring.maxProgramsPerBuild", "programs.length > 256"),
            ("client", "new TextEncoder().encode(source).byteLength > authoring.maxSourceBytes", "source.length > authoring.maxSourceBytes"),
        )
        for key, before, after in mutations:
            path = self.root / audit.PROGRAM_GITHUB_PATHS[key]
            original = path.read_text()
            with self.subTest(key=key):
                self.assertIn(before, original)
                try:
                    path.write_text(original.replace(before, after, 1))
                    self.assertIsNone(audit.program_github_policy_authority_from_source(self.root, self.discovered))
                finally:
                    path.write_text(original)

    def program_github_inventory_document(self, entries: dict[str, dict[str, object]]) -> dict[str, object]:
        return {"schemaVersion": audit.SCHEMA_VERSION, "entries": list(entries.values()),
                "evidenceRecords": {candidate.evidence_digest: candidate.evidence for candidate in self.candidates},
                "programGithubPolicyAuthorities": {audit.PROGRAM_GITHUB_POLICY_AUTHORITY_ID: self.authority}}

    def test_program_github_exact_contract_prose_refuses_each_fictional_field_in_both_routes(self) -> None:
        contract = next(item for item in self.authority["contracts"] if len(item["candidateIds"]) > 1)
        identifiers = contract["candidateIds"]
        self.assertGreater(len(identifiers), 1)
        authorities = {audit.PROGRAM_GITHUB_POLICY_AUTHORITY_ID: self.authority}
        self.assertEqual([], audit.program_github_policy_authority_errors(
            self.root, authorities, self.entries, self.discovered))
        baseline = audit.inventory_errors(self.root, self.program_github_inventory_document(self.entries), self.candidates)
        self.assertFalse([error for error in baseline if "program/GitHub" in error], baseline)
        # This fixture copies the program composition root but deliberately omits the listener
        # family. Its new mandatory diagnostic remains visible; the program route is unmocked.
        self.assertIn("interaction WebSocket source family is incomplete, unpartitioned, or unsupported", baseline)
        for field in ("default", "scope", "pinning", "validation", "coverage"):
            with self.subTest(field=field):
                entries = copy.deepcopy(self.entries)
                # Keep all rows mutually consistent: only the independent contract can reject this claim.
                for identifier in identifiers:
                    entries[identifier][field] = "fictional but nonempty " + field
                self.assertEqual(1, len({entries[identifier][field] for identifier in identifiers}))
                direct = audit.program_github_policy_authority_errors(
                    self.root, authorities, entries, self.discovered)
                global_errors = audit.inventory_errors(
                    self.root, self.program_github_inventory_document(entries), self.candidates)
                for identifier in identifiers:
                    expected = f"{identifier}: program/GitHub {field} authority has drifted"
                    self.assertIn(expected, direct)
                    self.assertIn(expected, global_errors)

    def test_program_github_release_asset_verification_cannot_be_blessed_by_digest_refresh(self) -> None:
        path = self.root / audit.PROGRAM_GITHUB_PATHS["release"]
        original = path.read_text(encoding="utf-8")
        before = "if digest(downloaded) != digest(local):"
        self.assertEqual(1, original.count(before))
        try:
            path.write_text(original.replace(before, "if digest(downloaded) == digest(local):"), encoding="utf-8")
            refreshed = {candidate.id: candidate for candidate in audit.discover(self.root)}
            document = self.program_github_inventory_document(copy.deepcopy(self.entries))
            claimed = copy.deepcopy(self.authority)
            for item in claimed["sourceDigests"]:
                if item["path"] == audit.PROGRAM_GITHUB_PATHS["release"]:
                    item["digest"] = audit._source_digest(path.read_text(encoding="utf-8"))
            self.assertNotEqual(self.authority["sourceDigests"], claimed["sourceDigests"])
            document["programGithubPolicyAuthorities"] = {audit.PROGRAM_GITHUB_POLICY_AUTHORITY_ID: claimed}
            self.assertIsNone(audit.program_github_policy_authority_from_source(self.root, refreshed))
            self.assertTrue(any("source family" in error for error in audit.program_github_policy_authority_errors(
                self.root, document["programGithubPolicyAuthorities"], self.entries, refreshed)))
            self.assertTrue(any("program/GitHub policy source family" in error for error in audit.inventory_errors(
                self.root, document, tuple(refreshed.values()))))
        finally:
            path.write_text(original, encoding="utf-8")

    def test_program_github_build_envelope_and_non_source_guard_bypasses_refuse(self) -> None:
        path = self.root / audit.PROGRAM_GITHUB_PATHS["submission"]
        original = path.read_text(encoding="utf-8")
        mutations = (
            ("PayloadJson.read(body, buildEnvelopeLimits(limits, authoring))", "PayloadJson.read(body, limits)"),
            ("enforceGenericTextLimitsOutsideProgramSource(root, limits);", ""),
            ('if (!"source".equals(field.getKey()))', 'if (false)'),
        )
        for before, after in mutations:
            with self.subTest(before=before):
                self.assertEqual(1, original.count(before))
                try:
                    path.write_text(original.replace(before, after, 1), encoding="utf-8")
                    refreshed = {candidate.id: candidate for candidate in audit.discover(self.root)}
                    self.assertIsNone(audit.program_github_policy_authority_from_source(self.root, refreshed))
                finally:
                    path.write_text(original, encoding="utf-8")
        helper_path = self.root / "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/payload/ProgramBuildSubmissionTest.java"
        helper_source = helper_path.read_text(encoding="utf-8")
        helper_span = audit.java_method_span(helper_source, "ProgramBuildSubmissionTest", "assertPayloadReason")
        self.assertIsNotNone(helper_span)
        start, end = helper_span
        try:
            helper_path.write_text(helper_source[:start] + helper_source[start:end].replace("assertThrows", "removedAssertThrows", 1)
                                   + helper_source[end:], encoding="utf-8")
            self.assertIsNone(audit.program_github_policy_authority_from_source(self.root, self.discovered))
        finally:
            helper_path.write_text(helper_source, encoding="utf-8")
        relative = "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/ContentAddressedProgramBuildHttpIntegrationTest.java"
        path = self.root / relative
        original = path.read_text(encoding="utf-8")
        span = audit.java_method_span(original, "ContentAddressedProgramBuildHttpIntegrationTest",
                                      "buildRouteMakesTheSelectedRequestAndUtf8SourceCeilingsReachable")
        self.assertIsNotNone(span)
        start, end = span
        body = original[start:end]
        self.assertIn("assert", body)
        try:
            path.write_text(original[:start] + body.replace("assert", "removedAssert", 1) + original[end:], encoding="utf-8")
            self.assertIsNone(audit.program_github_policy_authority_from_source(self.root, self.discovered))
        finally:
            path.write_text(original, encoding="utf-8")

    def test_program_github_inventory_dispatch_is_mandatory_without_markers(self) -> None:
        with synthetic_repository() as directory:
            root = Path(directory)
            document = json.loads((root / "scripts/operational-configuration-inventory.json").read_text())
            with mock.patch.object(audit, "program_github_policy_authority_errors", return_value=["program-github-routing-probe"]) as routed:
                self.assertIn("program-github-routing-probe", audit.inventory_errors(root, document, audit.discover(root)))
                routed.assert_called_once()

class AgentBudgetPolicyAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        for relative in (
                audit.AGENT_BUDGET_CONFIGURATION_PATH,
                audit.AGENT_BUDGET_POLICY_PATH,
                audit.AGENT_BUDGET_TEST_PATH,
                audit.AGENT_BUDGET_COMPOSITION_PATH,
                audit.AGENT_BUDGET_CONSUMER_PATH,
                audit.AGENT_BUDGET_VECTOR_PATH):
            target = cls.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        subprocess.run(["git", "init", "-q"], cwd=cls.root, check=True)
        subprocess.run(["git", "add", "."], cwd=cls.root, check=True)
        cls.candidates = audit.discover(cls.root)
        cls.discovered = {candidate.id: candidate for candidate in cls.candidates}
        cls.authority = audit.agent_budget_authority_from_source(cls.root, cls.discovered)
        if cls.authority is None:
            raise AssertionError("The exact agent budget setting must derive before negative tests")
        cls.entries = {candidate.id: candidate.inventory_entry() for candidate in cls.candidates}
        for contract in cls.authority["contracts"]:
            expected = {
                "status": "already-centralized", "classification": "operator-configurable",
                "agentBudgetAuthority": audit.AGENT_BUDGET_AUTHORITY_ID,
                "setting": contract["setting"], "owner": contract["owner"],
                "field": contract["field"], "bindings": contract["bindings"],
                "default": contract["evaluatedDefault"],
                "defaultEvidence": contract["defaultCandidateIds"],
                "validation": contract["validation"], "scope": contract["scope"],
                "pinning": contract["pinning"], "coverage": contract["coverage"],
                "rationale": contract["rationale"],
            }
            for identifier in contract["candidateIds"]:
                cls.entries[identifier].update(copy.deepcopy(expected))
        for partition in cls.authority["semanticPartitions"]:
            for identifier in partition["candidateIds"]:
                cls.entries[identifier].update(
                    status=partition["status"], classification=partition["classification"],
                    rationale=partition["rationale"],
                    agentBudgetAuthority=audit.AGENT_BUDGET_AUTHORITY_ID)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    def document(self, entries=None, authority=None):
        rows = self.entries if entries is None else entries
        claimed = self.authority if authority is None else authority
        return {
            "schemaVersion": audit.SCHEMA_VERSION,
            "entries": list(copy.deepcopy(rows).values()),
            "evidenceRecords": {
                candidate.evidence_digest: candidate.evidence for candidate in self.candidates
            },
            "agentBudgetAuthorities": {audit.AGENT_BUDGET_AUTHORITY_ID: copy.deepcopy(claimed)},
        }

    def assert_direct_and_global_agent_error(self, document, candidates=None) -> None:
        current_candidates = self.candidates if candidates is None else candidates
        current = {candidate.id: candidate for candidate in current_candidates}
        entries = {entry["id"]: entry for entry in document["entries"]}
        direct = audit.agent_budget_authority_errors(
            self.root, document.get("agentBudgetAuthorities"), entries, current)
        self.assertTrue(direct)
        global_errors = audit.inventory_errors(self.root, document, tuple(current_candidates))
        self.assertTrue(any("agent budget" in error for error in global_errors), global_errors)

    def test_agent_budget_setting_is_derived_from_exact_factory_slot_helpers_and_tests(self) -> None:
        authority = self.authority
        self.assertEqual(20, authority["logicalSettingCount"])
        self.assertEqual(56, len(authority["candidateIds"]))
        contract = next(item for item in authority["contracts"]
                        if item["setting"] == "agent.maximum-input-tokens-per-turn")
        self.assertEqual(list(audit.AGENT_BUDGET_CANDIDATE_IDS), contract["candidateIds"])
        self.assertEqual(["oc-473ffef3055ed509d856"], contract["defaultCandidateIds"])
        self.assertEqual("128000", contract["evaluatedDefault"])
        self.assertEqual(
            'positive(environment, "RAVENROOT_AGENT_MAX_INPUT_TOKENS_PER_TURN", 128_000)',
            contract["factoryExpression"])
        self.assertEqual([], audit.agent_budget_authority_errors(
            self.root, {audit.AGENT_BUDGET_AUTHORITY_ID: authority},
            self.entries, self.discovered))
        global_errors = audit.inventory_errors(self.root, self.document(), self.candidates)
        self.assertFalse([error for error in global_errors if "agent budget" in error], global_errors)

    def test_agent_budget_missing_duplicate_marker_and_row_reclassification_fail_both_routes(self) -> None:
        without_authority = self.document()
        del without_authority["agentBudgetAuthorities"]
        self.assert_direct_and_global_agent_error(without_authority)

        without_markers = self.document()
        for row in without_markers["entries"]:
            row.pop("agentBudgetAuthority", None)
        self.assert_direct_and_global_agent_error(without_markers)

        reclassified = self.document()
        operator_ids = {identifier for contract in self.authority["contracts"]
                        for identifier in contract["candidateIds"]}
        for row in reclassified["entries"]:
            if row["id"] in operator_ids:
                row.update(status="retained", classification="derived")
        self.assert_direct_and_global_agent_error(reclassified)

        duplicate = self.document()
        foreign = next(row for row in duplicate["entries"]
                       if row["id"] not in self.authority["candidateIds"])
        foreign["agentBudgetAuthority"] = audit.AGENT_BUDGET_AUTHORITY_ID
        self.assert_direct_and_global_agent_error(duplicate)

    def test_agent_budget_actual_source_mutations_fail_even_after_superficial_digest_refresh(self) -> None:
        mutations = (
            (audit.AGENT_BUDGET_CONFIGURATION_PATH,
             'positive(environment, "RAVENROOT_AGENT_MAX_INPUT_TOKENS_PER_TURN", 128_000)',
             'positive(environment, "RAVENROOT_AGENT_MAX_INPUT_TOKENS_PER_TURN", 128_001)'),
            (audit.AGENT_BUDGET_CONFIGURATION_PATH,
             "if (value <= 0) throw new IllegalArgumentException(name + \" must be positive\");",
             "if (value < 0) throw new IllegalArgumentException(name + \" must be positive\");"),
            (audit.AGENT_BUDGET_CONFIGURATION_PATH,
             "raw == null || raw.isBlank() ? fallback : Long.parseLong(raw.strip())",
             "raw == null ? fallback : Long.parseLong(raw.strip())"),
            (audit.AGENT_BUDGET_CONFIGURATION_PATH,
             'positive(environment, "RAVENROOT_AGENT_MAX_INPUT_TOKENS_PER_TURN", 128_000)',
             'positive(environment, "RAVENROOT_AGENT_MAX_OUTPUT_TOKENS_PER_TURN", 32_000)'),
            (audit.AGENT_BUDGET_POLICY_PATH,
             "maximumInputTokensPerTurn <= 0", "maximumInputTokensPerTurn < 0"),
            (audit.AGENT_BUDGET_TEST_PATH,
             "assertEquals(128_000, first.maximumInputTokensPerTurn());",
             "assertTrue(first.maximumInputTokensPerTurn() > 0);"),
            (audit.AGENT_BUDGET_TEST_PATH,
             "@Test\n    void shippedDefaultsAreFinitePinnedAndUseDistinctBootEpochs()",
             "void shippedDefaultsAreFinitePinnedAndUseDistinctBootEpochs()"),
            (audit.AGENT_BUDGET_TEST_PATH,
             "@Test\n    void shippedDefaultsAreFinitePinnedAndUseDistinctBootEpochs()",
             "@Disabled\n    @Test\n    void shippedDefaultsAreFinitePinnedAndUseDistinctBootEpochs()"),
            (audit.AGENT_BUDGET_TEST_PATH,
             '@MethodSource("positiveNumericNames")', '@MethodSource("rateNames")'),
            (audit.AGENT_BUDGET_TEST_PATH,
             '@ParameterizedTest\n    @MethodSource("positiveNumericNames")',
             '@Test\n    @MethodSource("positiveNumericNames")'),
            (audit.AGENT_BUDGET_COMPOSITION_PATH,
             "ai.ravenroot.server.agent.AgentAuthorityBudgetConfiguration\n"
             "                                .fromEnvironment(System.getenv())",
             "ai.ravenroot.server.agent.AgentAuthorityBudgetConfiguration\n"
             "                                .fromEnvironment(Map.of())"),
            (audit.AGENT_BUDGET_CONSUMER_PATH,
             "long input = policy.maximumInputTokensPerTurn();", "long input = 128_000;"),
        )
        for relative, before, after in mutations:
            path = self.root / relative
            original = path.read_text(encoding="utf-8")
            with self.subTest(path=relative, before=before):
                self.assertEqual(1, original.count(before))
                try:
                    path.write_text(original.replace(before, after, 1), encoding="utf-8")
                    refreshed_candidates = audit.discover(self.root)
                    refreshed = {candidate.id: candidate for candidate in refreshed_candidates}
                    self.assertIsNone(audit.agent_budget_authority_from_source(self.root, refreshed))
                    claimed = copy.deepcopy(self.authority)
                    for source in claimed["sourceDigests"]:
                        if source["path"] == relative.as_posix():
                            source["digest"] = audit._source_digest(
                                path.read_text(encoding="utf-8"))
                    document = self.document(authority=claimed)
                    direct = audit.agent_budget_authority_errors(
                        self.root, document["agentBudgetAuthorities"], self.entries, refreshed)
                    self.assertIn(
                        "agent budget policy source family is incomplete, mis-slotted, or unsupported",
                        direct)
                    global_errors = audit.inventory_errors(
                        self.root, document, refreshed_candidates)
                    self.assertIn(
                        "agent budget policy source family is incomplete, mis-slotted, or unsupported",
                        global_errors)
                finally:
                    path.write_text(original, encoding="utf-8")

    def test_agent_budget_candidate_omission_and_foreign_atom_cannot_change_the_roster(self) -> None:
        from dataclasses import replace
        missing = dict(self.discovered)
        del missing[self.authority["candidateIds"][0]]
        self.assertIsNone(audit.agent_budget_authority_from_source(self.root, missing))
        template = self.discovered[self.authority["candidateIds"][0]]
        injected = replace(template, id="oc-injected-agent-budget-atom")
        foreign = {**self.discovered, injected.id: injected}
        self.assertIsNone(audit.agent_budget_authority_from_source(self.root, foreign))

    def test_agent_budget_inventory_dispatch_is_mandatory(self) -> None:
        with synthetic_repository() as directory:
            root = Path(directory)
            document = json.loads((root / "scripts/operational-configuration-inventory.json").read_text())
            with mock.patch.object(
                    audit, "agent_budget_authority_errors",
                    return_value=["agent-budget-routing-probe"]) as routed:
                self.assertIn("agent-budget-routing-probe",
                              audit.inventory_errors(root, document, audit.discover(root)))
                routed.assert_called_once()


class JwkPolicyAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        for relative in (
                audit.JWK_PROVIDER_PATH, audit.JWK_CONFIGURATION_PATH, audit.JWK_TEST_PATH,
                audit.JWK_CONFIGURATION_DOC_PATH, audit.JWK_ENVIRONMENT_DOC_PATH):
            target = cls.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        subprocess.run(["git", "init", "-q"], cwd=cls.root, check=True)
        common = subprocess.run(
            ["git", "rev-parse", "--git-common-dir"], cwd=ROOT, check=True,
            capture_output=True, text=True).stdout.strip()
        common_path = (ROOT / common).resolve()
        alternates = cls.root / ".git/objects/info/alternates"
        alternates.parent.mkdir(parents=True, exist_ok=True)
        alternates.write_text(str(common_path / "objects") + "\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=cls.root, check=True)
        tree = subprocess.run(
            ["git", "write-tree"], cwd=cls.root, check=True,
            capture_output=True, text=True).stdout.strip()
        commit = subprocess.run(
            ["git", "commit-tree", tree, "-p", audit.JWK_CONVERSION_BEFORE_REVISION],
            cwd=cls.root, check=True, input="JWKS source fixture\n", capture_output=True,
            text=True, env={**dict(os.environ), "GIT_AUTHOR_NAME": "audit fixture",
                            "GIT_AUTHOR_EMAIL": "audit@example.invalid",
                            "GIT_COMMITTER_NAME": "audit fixture",
                            "GIT_COMMITTER_EMAIL": "audit@example.invalid"}).stdout.strip()
        subprocess.run(["git", "update-ref", "refs/heads/main", commit], cwd=cls.root, check=True)
        subprocess.run(["git", "symbolic-ref", "HEAD", "refs/heads/main"], cwd=cls.root, check=True)
        cls.candidates = audit.discover(cls.root)
        cls.discovered = {candidate.id: candidate for candidate in cls.candidates}
        cls.authority = audit.jwk_policy_authority_from_source(cls.root, cls.discovered)
        if cls.authority is None:
            raise AssertionError("The exact JWKS policy family must derive before negative tests")
        cls.entries = {candidate.id: candidate.inventory_entry() for candidate in cls.candidates}
        for contract in cls.authority["contracts"]:
            expected = {
                "status": contract["status"], "classification": "operator-configurable",
                "jwkPolicyAuthority": audit.JWK_POLICY_AUTHORITY_ID,
                "setting": contract["setting"], "owner": contract["owner"],
                "field": contract["field"], "bindings": contract["bindings"],
                "default": contract["default"],
                "defaultEvidence": contract["defaultCandidateIds"],
                "validation": contract["validation"], "scope": contract["scope"],
                "pinning": contract["pinning"], "coverage": contract["coverage"],
                "rationale": contract["rationale"],
            }
            if "conversion" in contract:
                expected["conversion"] = contract["conversion"]
            for identifier in contract["candidateIds"]:
                cls.entries[identifier].update(copy.deepcopy(expected))
        for partition in cls.authority["semanticPartitions"]:
            for identifier in partition["candidateIds"]:
                cls.entries[identifier].update(
                    status=partition["status"], classification=partition["classification"],
                    rationale=partition["rationale"],
                    jwkPolicyAuthority=audit.JWK_POLICY_AUTHORITY_ID)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    def document(self, entries=None, authority=None):
        rows = self.entries if entries is None else entries
        claimed = self.authority if authority is None else authority
        return {
            "schemaVersion": audit.SCHEMA_VERSION,
            "entries": list(copy.deepcopy(rows).values()),
            "evidenceRecords": {
                candidate.evidence_digest: candidate.evidence for candidate in self.candidates
            },
            "jwkPolicyAuthorities": {audit.JWK_POLICY_AUTHORITY_ID: copy.deepcopy(claimed)},
        }

    def assert_direct_and_global_jwk_error(self, document, candidates=None) -> None:
        current_candidates = self.candidates if candidates is None else candidates
        current = {candidate.id: candidate for candidate in current_candidates}
        entries = {entry["id"]: entry for entry in document["entries"]}
        direct = audit.jwk_policy_authority_errors(
            self.root, document.get("jwkPolicyAuthorities"), entries, current)
        self.assertTrue(direct)
        global_errors = audit.inventory_errors(self.root, document, tuple(current_candidates))
        self.assertTrue(any("JWKS" in error for error in global_errors), global_errors)

    def test_jwk_policy_is_derived_from_exact_settings_typed_slots_and_consumers(self) -> None:
        self.assertEqual(3, self.authority["logicalSettingCount"])
        self.assertEqual(26, len(self.authority["candidateIds"]))
        contracts = {contract["setting"]: contract for contract in self.authority["contracts"]}
        self.assertEqual("already-centralized", contracts["security.oidc.jwks-cache-seconds"]["status"])
        self.assertEqual([], contracts["security.oidc.jwks-cache-seconds"]["defaultCandidateIds"])
        self.assertEqual(["oc-4ebf73a069117c0f9ea5"],
                         contracts["security.oidc.jwks-connect-timeout-seconds"]["defaultCandidateIds"])
        self.assertEqual(["oc-c46f0158107cc34e12fd"],
                         contracts["security.oidc.jwks-request-timeout-seconds"]["defaultCandidateIds"])
        self.assertEqual([], audit.jwk_policy_authority_errors(
            self.root, {audit.JWK_POLICY_AUTHORITY_ID: self.authority},
            self.entries, self.discovered))
        global_errors = audit.inventory_errors(self.root, self.document(), self.candidates)
        self.assertFalse([error for error in global_errors if "JWKS" in error], global_errors)

    def test_jwk_missing_duplicate_marker_and_row_reclassification_fail_both_routes(self) -> None:
        without_authority = self.document()
        del without_authority["jwkPolicyAuthorities"]
        self.assert_direct_and_global_jwk_error(without_authority)

        without_markers = self.document()
        for row in without_markers["entries"]:
            row.pop("jwkPolicyAuthority", None)
        self.assert_direct_and_global_jwk_error(without_markers)

        reclassified = self.document()
        operator_ids = {identifier for contract in self.authority["contracts"]
                        for identifier in contract["candidateIds"]}
        for row in reclassified["entries"]:
            if row["id"] in operator_ids:
                row.update(status="retained", classification="derived")
        self.assert_direct_and_global_jwk_error(reclassified)

        duplicate = self.document()
        foreign = next(row for row in duplicate["entries"]
                       if row["id"] not in self.authority["candidateIds"])
        foreign["jwkPolicyAuthority"] = audit.JWK_POLICY_AUTHORITY_ID
        self.assert_direct_and_global_jwk_error(duplicate)

    def test_jwk_actual_source_and_test_mutations_fail_with_refreshed_superficial_digests(self) -> None:
        mutations = (
            (audit.JWK_CONFIGURATION_PATH,
             "defaults.connectTimeout().toSeconds(), 1, 300",
             "defaults.requestTimeout().toSeconds(), 1, 300"),
            (audit.JWK_CONFIGURATION_PATH,
             "defaults.requestTimeout().toSeconds(), 1, 300",
             "defaults.requestTimeout().toSeconds(), 1, 301"),
            (audit.JWK_PROVIDER_PATH, "Duration.ofSeconds(3), Duration.ofSeconds(5)",
             "Duration.ofSeconds(4), Duration.ofSeconds(5)"),
            (audit.JWK_PROVIDER_PATH, ".connectTimeout(transportPolicy.connectTimeout())",
             ".connectTimeout(transportPolicy.requestTimeout())"),
            (audit.JWK_PROVIDER_PATH, "HttpRequest.newBuilder(uri).timeout(requestTimeout)",
             "HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))"),
            (audit.JWK_PROVIDER_PATH,
             "requestTimeout, MINIMUM_REQUEST_TIMEOUT, MAXIMUM_REQUEST_TIMEOUT",
             "connectTimeout, MINIMUM_REQUEST_TIMEOUT, MAXIMUM_REQUEST_TIMEOUT"),
            (audit.JWK_TEST_PATH,
             "@Test\n    void jwksTransportPolicyOwnsDefaultsAndRejectsValuesOutsideItsTypedRange()",
             "void jwksTransportPolicyOwnsDefaultsAndRejectsValuesOutsideItsTypedRange()"),
            (audit.JWK_TEST_PATH,
             "@Test\n    void authenticationDurationsAcceptTheirExactBoundaries()",
             "@Disabled\n    @Test\n    void authenticationDurationsAcceptTheirExactBoundaries()"),
            (audit.JWK_CONFIGURATION_DOC_PATH,
             "whole seconds from `1` through `300`; `3`, `5`",
             "whole seconds; defaults vary"),
            (audit.JWK_ENVIRONMENT_DOC_PATH,
             "| `RAVENROOT_AUTH_JWKS_REQUEST_TIMEOUT_SECONDS` |",
             "| `RAVENROOT_AUTH_JWKS_REQUEST_TIMEOUT` |"),
        )
        for relative, before, after in mutations:
            path = self.root / relative
            original = path.read_text(encoding="utf-8")
            with self.subTest(path=relative, before=before):
                self.assertEqual(1, original.count(before))
                try:
                    path.write_text(original.replace(before, after, 1), encoding="utf-8")
                    refreshed_candidates = audit.discover(self.root)
                    refreshed = {candidate.id: candidate for candidate in refreshed_candidates}
                    self.assertIsNone(audit.jwk_policy_authority_from_source(self.root, refreshed))
                    claimed = copy.deepcopy(self.authority)
                    for source in claimed["sourceDigests"]:
                        if source["path"] == relative.as_posix():
                            source["digest"] = audit._source_digest(path.read_text(encoding="utf-8"))
                    document = self.document(authority=claimed)
                    direct = audit.jwk_policy_authority_errors(
                        self.root, document["jwkPolicyAuthorities"], self.entries, refreshed)
                    self.assertIn(
                        "JWKS policy source family is incomplete, mis-slotted, or unsupported", direct)
                    global_errors = audit.inventory_errors(
                        self.root, document, refreshed_candidates)
                    self.assertIn(
                        "JWKS policy source family is incomplete, mis-slotted, or unsupported",
                        global_errors)
                finally:
                    path.write_text(original, encoding="utf-8")

    def test_jwk_candidate_omission_and_foreign_atom_cannot_change_the_roster(self) -> None:
        from dataclasses import replace
        missing = dict(self.discovered)
        del missing[self.authority["candidateIds"][0]]
        self.assertIsNone(audit.jwk_policy_authority_from_source(self.root, missing))
        template = self.discovered[self.authority["candidateIds"][0]]
        injected = replace(template, id="oc-injected-jwks-atom")
        foreign = {**self.discovered, injected.id: injected}
        self.assertIsNone(audit.jwk_policy_authority_from_source(self.root, foreign))

    def test_jwk_inventory_dispatch_is_mandatory(self) -> None:
        with synthetic_repository() as directory:
            root = Path(directory)
            document = json.loads((root / "scripts/operational-configuration-inventory.json").read_text())
            with mock.patch.object(
                    audit, "jwk_policy_authority_errors",
                    return_value=["JWKS-routing-probe"]) as routed:
                self.assertIn("JWKS-routing-probe",
                              audit.inventory_errors(root, document, audit.discover(root)))
                routed.assert_called_once()


class EmbedEnabledAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        for relative in (*audit.EMBED_SOURCE_DIGESTS, audit.EMBED_CONFIGURATION_DOC_PATH):
            target = cls.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        subprocess.run(["git", "init", "-q"], cwd=cls.root, check=True)
        common = subprocess.run(
            ["git", "rev-parse", "--git-common-dir"], cwd=ROOT, check=True,
            capture_output=True, text=True).stdout.strip()
        common_path = (ROOT / common).resolve()
        alternates = cls.root / ".git/objects/info/alternates"
        alternates.parent.mkdir(parents=True, exist_ok=True)
        alternates.write_text(str(common_path / "objects") + "\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=cls.root, check=True)
        tree = subprocess.run(
            ["git", "write-tree"], cwd=cls.root, check=True,
            capture_output=True, text=True).stdout.strip()
        commit = subprocess.run(
            ["git", "commit-tree", tree, "-p", audit.EMBED_CENTRALIZATION_AFTER_REVISION],
            cwd=cls.root, check=True, input="Embed authority fixture\n", capture_output=True,
            text=True, env={**dict(os.environ), "GIT_AUTHOR_NAME": "audit fixture",
                            "GIT_AUTHOR_EMAIL": "audit@example.invalid",
                            "GIT_COMMITTER_NAME": "audit fixture",
                            "GIT_COMMITTER_EMAIL": "audit@example.invalid"}).stdout.strip()
        subprocess.run(["git", "update-ref", "refs/heads/main", commit], cwd=cls.root, check=True)
        subprocess.run(["git", "symbolic-ref", "HEAD", "refs/heads/main"], cwd=cls.root, check=True)
        cls.candidates = audit.discover(cls.root)
        cls.discovered = {candidate.id: candidate for candidate in cls.candidates}
        cls.authority = audit.embed_enabled_authority_from_source(cls.root, cls.discovered)
        if cls.authority is None:
            raise AssertionError("The exact embed enablement pipeline must derive before negative tests")
        cls.entries = {candidate.id: candidate.inventory_entry() for candidate in cls.candidates}
        contract = cls.authority["contract"]
        expected = {key: copy.deepcopy(value) for key, value in contract.items()
                    if key != "candidateIds"}
        for identifier in cls.authority["candidateIds"]:
            cls.entries[identifier].update(copy.deepcopy(expected))

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    def document(self, entries=None):
        rows = self.entries if entries is None else entries
        return {
            "schemaVersion": audit.SCHEMA_VERSION,
            "entries": list(copy.deepcopy(rows).values()),
            "evidenceRecords": {
                candidate.evidence_digest: candidate.evidence for candidate in self.candidates
            },
            "embedEnabledAuthorities": {
                audit.EMBED_ENABLED_AUTHORITY_ID: copy.deepcopy(self.authority),
            },
        }

    def assert_direct_and_global_embed_error(self, document, candidates=None) -> None:
        current_candidates = self.candidates if candidates is None else candidates
        current = {candidate.id: candidate for candidate in current_candidates}
        entries = {entry["id"]: entry for entry in document["entries"]}
        direct = audit.embed_enabled_authority_errors(
            self.root, document.get("embedEnabledAuthorities"), entries, current)
        self.assertTrue(direct)
        global_errors = audit.inventory_errors(self.root, document, tuple(current_candidates))
        self.assertTrue(any("embed enabled" in error for error in global_errors), global_errors)

    def test_embed_setting_is_derived_from_strict_parser_and_ordered_startup_consumers(self) -> None:
        self.assertEqual(2, len(self.authority["candidateIds"]))
        self.assertEqual(
            ["oc-c92f93b348c318a14a6d", "oc-e67c99abfd1d50dd0a6f"],
            self.authority["candidateIds"])
        self.assertEqual([], self.authority["contract"]["defaultEvidence"])
        self.assertEqual("#321", self.authority["contract"]["centralization"]["issue"])
        self.assertEqual([], audit.embed_enabled_authority_errors(
            self.root, {audit.EMBED_ENABLED_AUTHORITY_ID: self.authority},
            self.entries, self.discovered))
        global_errors = audit.inventory_errors(self.root, self.document(), self.candidates)
        self.assertFalse([error for error in global_errors if "embed enabled" in error], global_errors)

    def test_embed_missing_duplicate_marker_and_row_reclassification_fail_both_routes(self) -> None:
        without_authority = self.document()
        del without_authority["embedEnabledAuthorities"]
        self.assert_direct_and_global_embed_error(without_authority)

        without_markers = self.document()
        for row in without_markers["entries"]:
            row.pop("embedEnabledAuthority", None)
        self.assert_direct_and_global_embed_error(without_markers)

        reclassified = self.document()
        for row in reclassified["entries"]:
            if row["id"] in self.authority["candidateIds"]:
                row.update(status="retained", classification="derived")
        self.assert_direct_and_global_embed_error(reclassified)

        duplicate = self.document()
        foreign = next(row for row in duplicate["entries"]
                       if row["id"] not in self.authority["candidateIds"])
        foreign["embedEnabledAuthority"] = audit.EMBED_ENABLED_AUTHORITY_ID
        self.assert_direct_and_global_embed_error(duplicate)

    def test_embed_actual_source_and_test_mutations_fail_after_file_digest_refresh(self) -> None:
        mutations = (
            (audit.EMBED_CONFIGURATION_PATH,
             'strictBoolean(environment, "RAVENROOT_EMBED_ENABLED", false)',
             'strictBoolean(environment, "RAVENROOT_EMBED_ENABLED", true)'),
            (audit.EMBED_CONFIGURATION_PATH, 'case "true" -> true;',
             'case "TRUE" -> true;'),
            (audit.EMBED_STARTUP_CHECK_PATH,
             'enabled = EmbedBrowserConfiguration.enabledFromEnvironment(environment);',
             'enabled = EmbedBrowserConfiguration.enabledFromEnvironment(Map.of());'),
            (audit.EMBED_MAIN_PATH, 'refuseUnsupportablePackagedEmbed(System.getenv());',
             '// packaged embed startup validation removed'),
            (audit.EMBED_MAIN_PATH,
             '.enabledFromEnvironment(System.getenv())',
             '.enabledFromEnvironment(Map.of())'),
            (audit.EMBED_REPLICA_CHECK_PATH,
             'EmbedBrowserConfiguration.enabledFromEnvironment(environment)',
             'EmbedBrowserConfiguration.enabledFromEnvironment(Map.of())'),
            (audit.EMBED_CONFIGURATION_TEST_PATH,
             '@Test\n    void invalidBooleanCapacityAndTtlFailAtStartup()',
             'void invalidBooleanCapacityAndTtlFailAtStartup()'),
            (audit.EMBED_MAIN_TEST_PATH,
             '@Test\n    void packagedEmbedDisabledLeavesStartupPathUnchanged()',
             '@Disabled\n    @Test\n    void packagedEmbedDisabledLeavesStartupPathUnchanged()'),
            (audit.EMBED_CONFIGURATION_DOC_PATH,
             '| `RAVENROOT_EMBED_ENABLED` | strict Boolean; `false` |',
             '| `RAVENROOT_EMBED_ENABLED` | Boolean |'),
        )
        for relative, before, after in mutations:
            path = self.root / relative
            original = path.read_text(encoding="utf-8")
            with self.subTest(path=relative, before=before):
                self.assertEqual(1, original.count(before))
                try:
                    path.write_text(original.replace(before, after, 1), encoding="utf-8")
                    refreshed_candidates = audit.discover(self.root)
                    refreshed = {candidate.id: candidate for candidate in refreshed_candidates}
                    refreshed_digests = dict(audit.EMBED_SOURCE_DIGESTS)
                    if relative in refreshed_digests:
                        refreshed_digests[relative] = audit._source_digest(
                            path.read_text(encoding="utf-8"))
                    with mock.patch.object(audit, "EMBED_SOURCE_DIGESTS", refreshed_digests):
                        self.assertIsNone(audit.embed_enabled_authority_from_source(
                            self.root, refreshed))
                        document = self.document()
                        direct = audit.embed_enabled_authority_errors(
                            self.root, document.get("embedEnabledAuthorities"),
                            self.entries, refreshed)
                        self.assertIn(
                            "embed enabled source pipeline is incomplete, misordered, or unsupported",
                            direct)
                        global_errors = audit.inventory_errors(
                            self.root, document, refreshed_candidates)
                        self.assertIn(
                            "embed enabled source pipeline is incomplete, misordered, or unsupported",
                            global_errors)
                finally:
                    path.write_text(original, encoding="utf-8")

    def test_embed_candidate_omission_and_foreign_atom_cannot_change_the_roster(self) -> None:
        from dataclasses import replace
        missing = dict(self.discovered)
        del missing[self.authority["candidateIds"][0]]
        self.assertIsNone(audit.embed_enabled_authority_from_source(self.root, missing))
        template = self.discovered[self.authority["candidateIds"][0]]
        injected = replace(template, id="oc-injected-embed-enabled-atom")
        foreign = {**self.discovered, injected.id: injected}
        self.assertIsNone(audit.embed_enabled_authority_from_source(self.root, foreign))

    def test_embed_inventory_dispatch_is_mandatory(self) -> None:
        with synthetic_repository() as directory:
            root = Path(directory)
            document = json.loads((root / "scripts/operational-configuration-inventory.json").read_text())
            with mock.patch.object(
                    audit, "embed_enabled_authority_errors",
                    return_value=["embed-enabled-routing-probe"]) as routed:
                self.assertIn("embed-enabled-routing-probe",
                              audit.inventory_errors(root, document, audit.discover(root)))
                routed.assert_called_once()


class InteractionWebSocketPolicyAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        for relative in audit.INTERACTION_WEBSOCKET_REQUIRED_PATHS:
            target = cls.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        subprocess.run(["git", "init", "-q"], cwd=cls.root, check=True)
        subprocess.run(["git", "add", "."], cwd=cls.root, check=True)
        cls.candidates = audit.discover(cls.root)
        cls.discovered = {candidate.id: candidate for candidate in cls.candidates}
        cls.authority = audit.interaction_websocket_authority_from_source(cls.root, cls.discovered)
        if cls.authority is None:
            raise AssertionError("The exact supported interaction source must derive before any mutation")
        cls.entries = {candidate.id: candidate.inventory_entry() for candidate in cls.candidates}
        for contract in cls.authority["contracts"]:
            for identifier in contract["candidateIds"]:
                cls.entries[identifier].update(
                    status="already-centralized", classification="operator-configurable",
                    interactionWebSocketAuthority=audit.INTERACTION_WEBSOCKET_AUTHORITY_ID,
                    **{key: copy.deepcopy(contract[key]) for key in (
                        "setting", "owner", "field", "bindings", "bindingAuthority", "defaultAuthority",
                        "validation", "scope", "pinning", "coverage")},
                    default=contract["defaultExpression"], defaultEvidence=contract["defaultCandidateIds"],
                    rationale="The exact typed interaction factory consumes this field and its source-proven fallback.")
        for group in cls.authority["semanticPartitions"] + cls.authority["bindingCarriers"]:
            for identifier in group["candidateIds"]:
                cls.entries[identifier].update(
                    status=group["status"], classification=group["classification"],
                    interactionWebSocketAuthority=audit.INTERACTION_WEBSOCKET_AUTHORITY_ID,
                    rationale=group.get("rationale", "The fixed property prefix is a non-setting protocol binding carrier."))
                for key in ("retainedAuthority", "bindingCarrier"):
                    if key in group:
                        cls.entries[identifier][key] = copy.deepcopy(group[key])

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def document(self, entries=None, authority=None):
        return {"schemaVersion": audit.SCHEMA_VERSION, "reconciliationRequired": False,
                "entries": list((self.entries if entries is None else entries).values()),
                "retiredEntries": [], "migrationHistory": [], "reconciliationHistory": [],
                "evidenceRecords": {candidate.evidence_digest: candidate.evidence for candidate in self.candidates},
                "interactionWebSocketAuthorities": {audit.INTERACTION_WEBSOCKET_AUTHORITY_ID:
                    self.authority if authority is None else authority}}

    def both_routes_refuse(self, entries, authority=None):
        authority = self.authority if authority is None else authority
        direct = audit.interaction_websocket_authority_errors(self.root,
            {audit.INTERACTION_WEBSOCKET_AUTHORITY_ID: authority}, entries, self.discovered)
        global_errors = audit.inventory_errors(self.root, self.document(entries, authority), self.candidates)
        self.assertTrue(direct)
        for diagnostic in direct:
            self.assertIn(diagnostic, global_errors)
        return direct

    def source_mutation_refuses(self, relative, before, after):
        path = self.root / relative
        original = path.read_text(encoding="utf-8")
        self.assertIn(before, original, f"Actual executable mutation target is required: {relative}")
        try:
            path.write_text(original.replace(before, after, 1), encoding="utf-8")
            refreshed = {item.id: item for item in audit.discover(self.root)}
            claimed = copy.deepcopy(self.authority)
            for item in claimed["sourceDigests"]:
                item["digest"] = audit._source_digest((self.root / item["path"]).read_text(encoding="utf-8"))
            self.assertIsNone(audit.interaction_websocket_authority_from_source(self.root, refreshed))
            expected = "interaction WebSocket source family is incomplete, unpartitioned, or unsupported"
            direct = audit.interaction_websocket_authority_errors(self.root,
                {audit.INTERACTION_WEBSOCKET_AUTHORITY_ID: claimed}, self.entries, refreshed)
            self.assertEqual([expected], direct)
            self.assertIn(expected, audit.inventory_errors(self.root, self.document(authority=claimed), tuple(refreshed.values())))
        finally:
            path.write_text(original, encoding="utf-8")

    def test_interaction_exact_21_defaults_20_new_atoms_and_non_setting_carrier(self):
        self.assertEqual(21, len(self.authority["contracts"]))
        self.assertEqual(84, sum(len(contract["candidateIds"]) for contract in self.authority["contracts"]))
        self.assertEqual(1, len(self.authority["bindingCarriers"]))
        self.assertEqual(79, sum(len(group["candidateIds"]) for group in self.authority["semanticPartitions"]))
        ids = [identifier for group in self.authority["contracts"] + self.authority["bindingCarriers"]
               + self.authority["semanticPartitions"] for identifier in group["candidateIds"]]
        self.assertEqual(164, len(ids))
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(set(ids), audit.interaction_websocket_cohort_candidate_ids(self.root, self.discovered))
        source = (self.root / audit.INTERACTION_WEBSOCKET_CONFIGURATION_PATH).read_text()
        calls = audit.interaction_websocket_default_calls(source)
        self.assertEqual(21, len(calls))
        new = [item for item in self.candidates if item.role.startswith("interaction-websocket-default:")]
        self.assertEqual(20, len(new))
        for contract in self.authority["contracts"]:
            with self.subTest(setting=contract["setting"]):
                self.assertEqual(1, len(contract["defaultCandidateIds"]))
                default = self.discovered[contract["defaultCandidateIds"][0]]
                suffix = contract["bindingAuthority"]["suffix"]
                self.assertEqual(calls[suffix]["expression"], default.expression)
                self.assertEqual(calls[suffix]["evaluated"], contract["evaluatedDefault"])
                self.assertEqual([default.id], self.entries[contract["candidateIds"][0]]["defaultEvidence"])
                if suffix == "port":
                    self.assertEqual("oc-884995f478d5726b523e", default.id)
                    self.assertEqual("operational-declaration", default.kind)
                else:
                    self.assertEqual("interaction-websocket-default:" + suffix, default.role)
        carrier = self.entries[self.authority["bindingCarriers"][0]["candidateIds"][0]]
        self.assertEqual("protocol-or-format-invariant", carrier["classification"])
        self.assertFalse({"setting", "default", "defaultEvidence"} & carrier.keys())
        self.assertEqual([], audit.interaction_websocket_authority_errors(self.root,
            {audit.INTERACTION_WEBSOCKET_AUTHORITY_ID: self.authority}, self.entries, self.discovered))
        self.assertEqual([], audit.inventory_errors(self.root, self.document(), self.candidates))

    def test_interaction_extractor_is_exact_factory_path_call_arity_and_literal_span(self):
        relative = audit.INTERACTION_WEBSOCKET_CONFIGURATION_PATH
        source = (self.root / relative).read_text()
        self.assertEqual([], audit.interaction_websocket_default_candidates(Path("other/InteractionWebSocketConfiguration.java"), source))
        for before, after in (
            ("from(Properties properties,", "from(OtherProperties properties,"),
            ('integer(properties, environment, "max-connections", 256)', 'integer(properties, environment, "max-connections", 256, 1)'),
            ('integer(properties, environment, "max-connections", 256)', 'integer(properties, environment, dynamicName, 256)'),
            ('integer(properties, environment, "max-connections", 256)', 'integer(properties, environment, "unreviewed", 256)'),
            ('integer(properties, environment, "max-connections", 256)', 'integer(properties, environment, "max-connections", unsafe())'),
            ('bool(properties, environment, "enabled", false)', 'other(properties, environment, "enabled", false)'),
        ):
            with self.subTest(before=before):
                self.assertIn(before, source)
                changed = source.replace(before, after, 1)
                self.assertIsNone(audit.interaction_websocket_default_calls(changed))
                self.assertEqual([], audit.interaction_websocket_default_candidates(relative, changed))
        outside = source.rsplit("}", 1)[0] + '''
            private static int unrelated(Properties properties, Map<String, String> environment) {
                return integer(properties, environment, "max-connections", 999);
            }
        }
        '''
        self.assertEqual(audit.interaction_websocket_default_calls(source), audit.interaction_websocket_default_calls(outside))
        changed = source.replace("512 * 1_024", "524288", 1)
        old = [c for _, c in audit.java_source_candidates(relative, source) if c.role == "interaction-websocket-default:max-message-bytes"][0]
        new = [c for _, c in audit.java_source_candidates(relative, changed) if c.role == old.role][0]
        self.assertNotEqual(old.id, new.id)
        self.assertNotEqual(old.evidence_digest, new.evidence_digest)
        self.assertEqual(audit.interaction_websocket_default_calls(source)["max-message-bytes"]["evaluated"],
                         audit.interaction_websocket_default_calls(changed)["max-message-bytes"]["evaluated"])

    def test_interaction_all_contract_metadata_and_truthful_default_evidence_are_exact(self):
        contract = self.authority["contracts"][0]
        for field in ("setting", "owner", "field", "bindings", "default", "defaultEvidence", "bindingAuthority",
                      "defaultAuthority", "validation", "scope", "pinning", "coverage"):
            with self.subTest(field=field):
                changed = copy.deepcopy(self.entries)
                for identifier in contract["candidateIds"]:
                    changed[identifier][field] = "fictional consistent value"
                errors = self.both_routes_refuse(changed)
                for identifier in contract["candidateIds"]:
                    self.assertIn(f"{identifier}: interaction WebSocket {field} authority has drifted", errors)
        for evidence in ([], contract["candidateIds"], self.authority["bindingCarriers"][0]["candidateIds"],
                         self.authority["contracts"][1]["defaultCandidateIds"]):
            with self.subTest(defaultEvidence=evidence):
                changed = copy.deepcopy(self.entries)
                for identifier in contract["candidateIds"]: changed[identifier]["defaultEvidence"] = evidence
                self.both_routes_refuse(changed)
        carrier_id = self.authority["bindingCarriers"][0]["candidateIds"][0]
        for field in ("setting", "default", "defaultEvidence", "bindingCarrier"):
            changed = copy.deepcopy(self.entries); changed[carrier_id][field] = "fictional value"
            self.both_routes_refuse(changed)

    def test_interaction_mandatory_metadata_and_candidate_partitions_cannot_opt_out(self):
        for field in ("contracts", "bindingCarriers", "semanticPartitions", "candidateIds"):
            changed = copy.deepcopy(self.authority); changed[field] = []
            self.both_routes_refuse(self.entries, changed)
        converted = copy.deepcopy(self.entries)
        converted_id = self.authority["contracts"][0]["candidateIds"][0]
        converted[converted_id]["status"] = "converted"
        converted_errors = self.both_routes_refuse(converted)
        self.assertIn(f"{converted_id}: interaction WebSocket operator classification has drifted",
                      converted_errors)
        for change in ("unmark-all", "foreign-marker", "remove-row", "operator-relabel", "retained-relabel"):
            with self.subTest(change=change):
                entries = copy.deepcopy(self.entries)
                identifier = self.authority["contracts"][0]["candidateIds"][0]
                if change == "unmark-all":
                    for row in entries.values(): row.pop("interactionWebSocketAuthority", None)
                elif change == "foreign-marker": entries[identifier]["interactionWebSocketAuthority"] = "foreign"
                elif change == "remove-row": del entries[identifier]
                elif change == "operator-relabel": entries[identifier].update(status="retained", classification="derived")
                else:
                    identifier = self.authority["semanticPartitions"][0]["candidateIds"][0]
                    entries[identifier]["classification"] = ("derived" if entries[identifier]["classification"] != "derived"
                        else "protocol-or-format-invariant")
                self.both_routes_refuse(entries)
        document = self.document(); document.pop("interactionWebSocketAuthorities")
        self.assertIn("interaction WebSocket settings require the exact mandatory source-derived authority",
                      audit.inventory_errors(self.root, document, self.candidates))
        from dataclasses import replace
        candidate = self.discovered[self.authority["contracts"][0]["candidateIds"][0]]
        extra = replace(candidate, id="oc-injected-interaction-row", role="UNREVIEWED_BOUND", expression="123")
        discovered = {**self.discovered, extra.id: extra}
        self.assertIsNone(audit.interaction_websocket_authority_from_source(self.root, discovered))
        self.assertIn("interaction WebSocket source family is incomplete, unpartitioned, or unsupported",
                      audit.inventory_errors(self.root, self.document(), tuple(discovered.values())))

    def test_interaction_named_binding_map_and_each_shared_helper_refuse_source_drift(self):
        config = audit.INTERACTION_WEBSOCKET_CONFIGURATION_PATH
        mutations = (
            ('Map.entry("enabled", "RAVENROOT_WEBSOCKET_ENABLED")', 'Map.entry("enabled", "RAVENROOT_WEBSOCKET_PORT")'),
            ('Map.entry("enabled", "RAVENROOT_WEBSOCKET_ENABLED"),', ''),
            ('Map.entry("enabled", "RAVENROOT_WEBSOCKET_ENABLED"),', 'Map.entry("enabled", "RAVENROOT_WEBSOCKET_ENABLED"), Map.entry("unknown", "RAVENROOT_UNKNOWN"),'),
            ('property != null && !property.isBlank()', 'property != null'),
            ('return property.trim();', 'return property;'),
            ('env == null || env.isBlank() ? fallback : env.trim()', 'env == null ? fallback : env'),
            ('Integer.parseInt(value(', 'Integer.parseUnsignedInt(value('),
            ('"true".equalsIgnoreCase(text)', '"yes".equalsIgnoreCase(text)'),
            ('return Duration.ofSeconds(integer(', 'return Duration.ofMillis(integer('),
            ('return Duration.ofMillis(integer(', 'return Duration.ofSeconds(integer('),
            ('value < minimum || value > maximum', 'value < minimum'),
            ('value.compareTo(Duration.ofSeconds(maximum)) > 0', 'false'),
            ('value.compareTo(Duration.ofMillis(maximum)) > 0', 'false'),
            ('enabled && "disabled".equals(mode)', 'false'),
            ('512 * 1_024', '524288'),
            ('"max-connections", 256)', '"max-connections", 257)'),
        )
        for before, after in mutations:
            with self.subTest(before=before): self.source_mutation_refuses(config, before, after)

    def test_interaction_all_cross_field_bounds_and_compact_constructor_are_sealed(self):
        config = audit.INTERACTION_WEBSOCKET_CONFIGURATION_PATH
        for before, after in (
            ('maxPendingAuthentication, 1, maxConnections', 'maxPendingAuthentication, 1, 256'),
            ('1, maxPendingAuthentication);', '1, 32);'),
            ('maxQueuedIncomingBytes, maxMessageBytes,', 'maxQueuedIncomingBytes, 524288,'),
            ('1_024, maxMessageBytes);', '1_024, 524288);'),
            ('maxQueuedOutgoingBytes, maxOutgoingFrameBytes,', 'maxQueuedOutgoingBytes, 65536,'),
            ('requireBetween("maxConnections", maxConnections, 1, 100_000);', ''),
            ('Objects.requireNonNull(bindAddress, "bindAddress");', ''),
        ):
            with self.subTest(before=before): self.source_mutation_refuses(config, before, after)

    def test_interaction_main_enabled_authentication_and_listener_lifecycle_are_sealed(self):
        main = audit.INTERACTION_WEBSOCKET_MAIN_PATH
        server = "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/RavenrootServer.java"
        for path, before, after in (
            (main, 'if (interactionWebSockets.enabled())', 'if (true)'),
            (main, 'if (interactionWebSockets.enabled())', 'if (false)'),
            (main, 'if (interactionWebSockets.enabled())', ''),
            (main, 'interactionWebSockets.requireAuthenticatedMode(authentication.mode());', ''),
            (main, 'InteractionWebSocketConfiguration.from(\n                System.getProperties(), System.getenv())', 'InteractionWebSocketConfiguration.from(\n                new java.util.Properties(), System.getenv())'),
            (server, 'interactionWebSockets.start();', ''),
            (server, 'interactionWebSockets.close();', ''),
            (server, 'if (interactionWebSockets != null) interactionWebSockets.close();', ''),
        ):
            with self.subTest(path=path, before=before): self.source_mutation_refuses(path, before, after)

    def test_interaction_actual_listener_budget_consumers_and_wire_limits_are_sealed(self):
        server = "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/interaction/InteractionWebSocketServer.java"
        for before, after in (
            ('configuration.maxPendingAuthentication()', '32'),
            ('configuration.maxBackendOperations()', '128'),
            ('configuration.maxConnections()', '256'),
            ('configuration.maxMessageBytes()', '524288'),
            ('configuration.maxOutgoingFrameBytes()', '65536'),
            ('configuration.maxQueuedOutgoingFrames()', '64'),
            ('configuration.maxQueuedIncomingBytes()', '1048576'),
            ('configuration.maxQueuedOutgoingBytes()', '1048576'),
            ('configuration.maxUnacknowledgedEvents()', '64'),
            ('configuration.authenticationDeadline()', 'java.time.Duration.ofSeconds(5)'),
            ('configuration.shutdownTimeout()', 'java.time.Duration.ofSeconds(5)'),
        ):
            with self.subTest(before=before): self.source_mutation_refuses(server, before, after)
        protocol = "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/interaction/InteractionProtocol.java"
        self.source_mutation_refuses(protocol, 'new PayloadLimits(maxBytes, 4, 32, 128,', 'new PayloadLimits(maxBytes, 40, 32, 128,')

    def test_interaction_source_deletion_and_test_helper_bypasses_cannot_opt_out(self):
        saved = {path: (self.root / path).read_bytes() for path in audit.INTERACTION_WEBSOCKET_PRODUCTION_PATHS}
        try:
            for path in saved: (self.root / path).unlink()
            self.assertTrue(audit.interaction_websocket_source_present(self.root), "The existing Main consumer keeps the family mandatory")
            expected = "interaction WebSocket source family is incomplete, unpartitioned, or unsupported"
            self.assertIn(expected, audit.interaction_websocket_authority_errors(self.root, None, self.entries, self.discovered))
            self.assertIn(expected, audit.inventory_errors(self.root, self.document(), self.candidates))
        finally:
            for path, raw in saved.items(): (self.root / path).write_bytes(raw)
        tests = "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/interaction/InteractionWebSocketConfigurationTest.java"
        self.source_mutation_refuses(tests, 'assertFalse(configuration.enabled());', '')
        self.source_mutation_refuses(tests, 'boundary.read().apply(configuration(boundary.values()))', 'configuration(boundary.values()).maxConnections()')
        self.source_mutation_refuses(tests, 'return List.of(', 'return java.util.Collections.emptyList(); /* disabled helper */ //')
        lifecycle = "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/RavenrootServerInteractionLifecycleTest.java"
        self.source_mutation_refuses(lifecycle, 'assertFalse(canConnect(loopback, interactionPort)', 'assertTrue(canConnect(loopback, interactionPort)')

    def test_interaction_publication_is_exact_and_precedes_the_outbound_prefix(self):
        published = {identifier for group in self.authority["semanticPartitions"]
                     if group["classification"] == "published-contract-description" for identifier in group["candidateIds"]}
        self.assertEqual(42, len(published))
        self.assertTrue(published <= audit.environment_reference_description_candidate_ids(self.root, iter(self.candidates)))
        publisher = audit.ENVIRONMENT_REFERENCE_PATH.as_posix()
        self.source_mutation_refuses(publisher, 'if name in INTERACTION_WEBSOCKET_VARIABLES:', 'if False:')
        self.source_mutation_refuses(publisher, '"RAVENROOT_WEBSOCKET_ENABLED",', '"RAVENROOT_WEBSOCKET_UNREVIEWED",')

    def test_interaction_live_references_remap_without_modifying_history_or_prose(self):
        old, new = "oc-live-before", "oc-live-after"
        authority = {"candidateIds": [old], "contracts": [{"candidateIds": [old], "defaultCandidateIds": [old], "rationale": old}],
                     "bindingCarriers": [{"candidateIds": [old], "bindingCarrier": {"description": old}}],
                     "semanticPartitions": [{"candidateIds": [old], "rationale": old}]}
        history = [{"candidateIds": [old], "source": old}]
        document = {"interactionWebSocketAuthorities": {audit.INTERACTION_WEBSOCKET_AUTHORITY_ID: authority},
                    "reconciliationHistory": copy.deepcopy(history), "retiredEntries": copy.deepcopy(history)}
        audit.remap_declared_candidate_references(document, {old: new})
        self.assertEqual([new], authority["candidateIds"])
        for field in ("contracts", "bindingCarriers", "semanticPartitions"):
            self.assertEqual([new], authority[field][0]["candidateIds"])
        self.assertEqual([new], authority["contracts"][0]["defaultCandidateIds"])
        self.assertEqual(old, authority["contracts"][0]["rationale"])
        self.assertEqual(old, authority["bindingCarriers"][0]["bindingCarrier"]["description"])
        self.assertEqual(old, authority["semanticPartitions"][0]["rationale"])
        self.assertEqual(history, document["reconciliationHistory"])
        self.assertEqual(history, document["retiredEntries"])
        for location in audit.candidate_reference_locations(document, {new}):
            self.assertTrue(audit.allowed_migrated_reference(location), location)


if __name__ == "__main__":
    unittest.main()
