from __future__ import annotations

import json
import io
import copy
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


class OperationalConfigurationAuditTest(unittest.TestCase):
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
        with tempfile.TemporaryDirectory() as location:
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
        self.assertEqual(28, len(owners))
        self.assertEqual(len(owners), len({item["setting"] for item in owners}))
        self.assertEqual(
            28,
            sum(domain["confirmedUnresolvedOperatorSettings"]
                for domain in document["remediationDomains"]["domains"]),
        )
        self.assertEqual(
            "#318",
            next(item["issue"] for item in owners
                 if item["setting"] == "execution.lease-ttl"),
        )
        lease_rows = [entry for entry in document["entries"]
                      if entry.get("setting") == "execution.lease-ttl"]
        self.assertEqual({"#318"}, {entry["followUp"] for entry in lease_rows})

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
        next(entry for entry in split["entries"]
             if entry.get("setting") == "execution.lease-ttl")["followUp"] = "#321"
        self.assertTrue(any("requires one follow-up owner" in error
                            for error in audit.remediation_domain_errors(split)))

        delimiter = next(entry for entry in document["entries"]
                         if entry["id"] == "oc-7f698b1972e9090b6f1b")
        self.assertEqual("protocol-or-format-invariant", delimiter["classification"])
        self.assertIn("CR/LF", delimiter["rationale"])

        postgres_defaults = {
            "postgres.lock-timeout": "5 seconds.",
            "postgres.statement-timeout": "30 seconds.",
            "postgres.serialization-retries": "3 retries.",
            "postgres.max-lease-ttl": "5 minutes.",
            "postgres.max-payload-bytes": "1,048,576 bytes (1,024 × 1,024).",
            "postgres.max-clock-skew": "5 seconds.",
            "postgres.journal-retention": "24 hours.",
            "postgres.max-inventory-page-size": "100 rows.",
            "postgres.terminal-retention": "7 days.",
            "postgres.execution-result-retention": "7 days.",
        }
        for setting, expected in postgres_defaults.items():
            with self.subTest(postgres_default=setting):
                rows = [entry for entry in document["entries"]
                        if entry.get("setting") == setting]
                self.assertTrue(rows)
                self.assertEqual({expected}, {entry["default"] for entry in rows})
                self.assertTrue(all(entry.get("sourceFact") for entry in rows))

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
        with synthetic_repository() as location:
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
        with synthetic_repository() as location:
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
        # This fixture intentionally contains only graph rows. Assistant's separately mandatory
        # fixed family is covered by its own general-entrypoint tests.
        with mock.patch.object(audit, "assistant_limit_authority_errors", return_value=[]):
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
                "id": "synthetic-reconciliation", "issue": "#315",
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
            with mock.patch.object(audit, "assistant_limit_authority_errors", return_value=[]):
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


if __name__ == "__main__":
    unittest.main()
