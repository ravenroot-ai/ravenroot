#!/usr/bin/env python3
"""Inventory fixed operational candidates and reject unreviewed source drift.

The scanner is deliberately lexical. It finds a stable, reviewable set of candidates
within its documented patterns and leaves each semantic decision in
``scripts/operational-configuration-inventory.json``. The inventory and generated
report are the source of the issue's counts.
"""

from __future__ import annotations

import argparse
from bisect import bisect_right
import hashlib
import json
import re
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass, replace
from functools import lru_cache
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "scripts" / "operational-configuration-inventory.json"
REPORT = ROOT / "docs" / "architecture" / "operational-configuration-audit.md"

SCHEMA_VERSION = 4
CLASSIFICATIONS = {
    "operator-configurable",
    "security-ceiling-or-default",
    "protocol-or-format-invariant",
    "published-contract-description",
    "derived",
    "test-fixture",
}
STATUSES = {
    "pending-review",
    "already-centralized",
    "confirmed-hardcoded",
    "converted",
    "retained",
    "deferred",
    "duplicate-removed",
}
CLASSIFICATION_STATUSES = {
    "operator-configurable": {"already-centralized", "confirmed-hardcoded", "converted", "deferred"},
    "security-ceiling-or-default": {"retained", "deferred"},
    "protocol-or-format-invariant": {"retained", "deferred"},
    "published-contract-description": {"retained", "deferred"},
    "derived": {"retained", "deferred"},
    "test-fixture": {"retained"},
}
TESTKIT_MODULES = {
    "ravenroot-api-testkit",
    "ravenroot-engine-testkit",
    "ravenroot-persistence-testkit",
    "ravenroot-sandbox-supervisor-testkit",
}
EXCLUDED_PARTS = {"target", "node_modules", "dist", ".git"}
SOURCE_SUFFIXES = {".java", ".js", ".mjs", ".ts", ".py", ".sh", ".yaml", ".yml", ".json"}

OPERATIONAL_WORD = re.compile(
    r"(?i)(timeout|deadline|interval|poll|retry|attempt|capacity|queue|limit|max|min|retention|ttl|"
    r"lifetime|grace|delay|period|expiry|expire|workers|threads|batch|burst|rate|bytes|size|count|"
    r"lease|drain|age|port|path|directory|dir|memory|heap|cpu|replica|probe|health|cumulative)"
)
NUMBER = re.compile(r"(?<![\w.])(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?)(?:[lLdDfF])?(?![\w.])")
QUOTED = re.compile(r'''(?s)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')''')
FIXED = re.compile(
    r"(?s)(?<![\w.])(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?)(?:[lLdDfF])?(?![\w.])|"
    r'''"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|Duration\.of(?:Nanos|Millis|Seconds|Minutes|Hours|Days)\s*\('''
)
FIXED_ATOM = re.compile(
    r'''(?<![\w.])(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?)(?:[lLdDfF])?(?![\w.])|'''
    r'''"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*' '''.rstrip()
)
KNOWN_OPERATIONAL_CALL = re.compile(
    r"(?i)(Duration\.of(?:Nanos|Millis|Seconds|Minutes|Hours|Days)|new\s+(?:PayloadLimits|GraphExecutionLimits|GraphMlLimits|Limits|RetryPolicy|RetryBackoff|AgentBudgetVector|Semaphore|"
    r"ArrayBlockingQueue|ThreadPoolExecutor)|newScheduledThreadPool|newFixedThreadPool|withStash|"
    r"orTimeout|completeOnTimeout|readNBytes|sleep|setTimeout|setInterval|getOrDefault)\s*\("
)
# This is intentionally a bounded lexical contract rather than Java receiver-type inference. The
# explicit TimeUnit argument separates timed Future/latch/process/executor/lock/semaphore overloads
# from ordinary get()/await()/tryAcquire() calls. Dynamic and static-imported units are outside the
# supported pattern and remain visible only through their own fixed declarations, when present.
TIME_UNIT_OPERATIONAL_CALL = re.compile(
    r"\.(get|await|tryAcquire|tryLock|waitFor|awaitTermination)\s*\("
)
TIME_UNIT_ARGUMENT = re.compile(
    r"(?:java\.util\.concurrent\.)?TimeUnit\."
    r"(?:NANOSECONDS|MICROSECONDS|MILLISECONDS|SECONDS|MINUTES|HOURS|DAYS)"
)
ASSIGNMENT_NAME = re.compile(r"(?s)\b([A-Za-z_$][\w$]*)\s*=\s*[^=]")
STATIC_FINAL = re.compile(r"\bstatic\s+final\b")
JS_DECLARATION = re.compile(r"\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=")
PY_ASSIGNMENT = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?![=])")
YAML_SCALAR = re.compile(r'''^\s*["']?([A-Za-z_][A-Za-z0-9_.-]*)["']?\s*:\s*(\S.*)$''')
ENVIRONMENT_BINDING = re.compile(r"RAVENROOT_[A-Z0-9_]+")
PROPERTY_BINDING = re.compile(r'''"(ravenroot\.[A-Za-z0-9_.-]*)"''')
SYSTEM_PROPERTY_READ = re.compile(r"\bSystem\.getProperty\s*\(\s*([^,)]+)")
RESOLVER_TEST_ROLES = {
    "propertyPrecedence",
    "blankPropertyEnvironmentFallback",
    "blankSourcesTypedDefault",
    "malformedNonblankRefusal",
}
RESOLVER_COMPOSITION_METHOD_ROLES = {
    "typedDefaultsFactory",
    "nestedDefaultsFactory",
}
RESOLVER_COMPOSITION_LINK_ROLES = {
    "typedDefaultsInitializer",
    "nestedDefaultsAccessor",
}
ENVIRONMENT_RESOLVER_TEST_ROLES = {
    "bindingEnumeration",
    "blankTypedDefault",
    "asciiTrimContract",
    "malformedOverflowRefusal",
    "nonPositiveRefusal",
    "documentedBoundaryAcceptance",
    "relationalConstraintRefusal",
}
DEPLOYMENT_ENVIRONMENT_CARRIER_PATHS = {
    "compose": frozenset({"compose.yaml"}),
    "helm": frozenset({
        "deploy/helm/ravenroot/values.yaml",
        "deploy/helm/ravenroot/values.schema.json",
        "deploy/helm/ravenroot/templates/deployment.yaml",
    }),
    "rawKubernetes": frozenset({"deploy/kubernetes/ravenroot.yaml"}),
}
LEGACY_GRAPH_ENVIRONMENT_AUTHORITIES = frozenset({
    ("graph.execution.max-amplified-deliveries", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxAmplifiedDeliveries", "RAVENROOT_GRAPH_MAX_AMPLIFIED_DELIVERIES"),
    ("graph.execution.max-cumulative-payload-bytes", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxCumulativePayloadBytes", "RAVENROOT_GRAPH_MAX_CUMULATIVE_PAYLOAD_BYTES"),
    ("graph.execution.max-fan-out", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxFanOut", "RAVENROOT_GRAPH_MAX_FAN_OUT"),
    ("graph.execution.max-in-flight-hops-per-traversal", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxInFlightHopsPerTraversal", "RAVENROOT_GRAPH_MAX_IN_FLIGHT_HOPS"),
    ("graph.execution.max-live-actors-per-traversal", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxLiveActorsPerTraversal", "RAVENROOT_GRAPH_MAX_LIVE_ACTORS_PER_TRAVERSAL"),
    ("graph.execution.max-queued-admissions-per-node", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxQueuedAdmissionsPerNode", "RAVENROOT_GRAPH_MAX_QUEUED_ADMISSIONS_PER_NODE"),
    ("graph.execution.max-recovery-deliveries-per-attempt", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxRecoveryDeliveriesPerAttempt", "RAVENROOT_GRAPH_MAX_RECOVERY_DELIVERIES_PER_ATTEMPT"),
    ("graph.execution.max-resident-actors", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxResidentActors", "RAVENROOT_GRAPH_MAX_RESIDENT_ACTORS"),
    ("graph.execution.max-traversal-steps", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxTraversalSteps", "RAVENROOT_GRAPH_MAX_TRAVERSAL_STEPS"),
    ("graph.graphml.max-attributes", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxAttributes", "RAVENROOT_GRAPHML_MAX_ATTRIBUTES"),
    ("graph.graphml.max-bytes", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxBytes", "RAVENROOT_GRAPHML_MAX_BYTES"),
    ("graph.graphml.max-depth", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxDepth", "RAVENROOT_GRAPHML_MAX_DEPTH"),
    ("graph.graphml.max-edges", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxEdges", "RAVENROOT_GRAPH_MAX_EDGES"),
    ("graph.graphml.max-elements", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxElements", "RAVENROOT_GRAPHML_MAX_ELEMENTS"),
    ("graph.graphml.max-keys", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxKeys", "RAVENROOT_GRAPHML_MAX_KEYS"),
    ("graph.graphml.max-namespace-declarations", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxNamespaceDeclarations", "RAVENROOT_GRAPHML_MAX_NAMESPACE_DECLARATIONS"),
    ("graph.graphml.max-nodes", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxNodes", "RAVENROOT_GRAPH_MAX_NODES"),
    ("graph.graphml.max-properties", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxProperties", "RAVENROOT_GRAPH_MAX_PROPERTIES"),
    ("graph.graphml.max-string-length", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxStringLength", "RAVENROOT_GRAPHML_MAX_STRING_LENGTH"),
    ("graph.payload.max-collection-size", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxCollectionSize", "RAVENROOT_GRAPH_MAX_PAYLOAD_COLLECTION_SIZE"),
    ("graph.payload.max-depth", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxDepth", "RAVENROOT_GRAPH_MAX_PAYLOAD_DEPTH"),
    ("graph.payload.max-encoded-bytes", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxEncodedBytes", "RAVENROOT_GRAPH_MAX_PAYLOAD_BYTES"),
    ("graph.payload.max-key-length", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxKeyLength", "RAVENROOT_GRAPH_MAX_PAYLOAD_KEY_LENGTH"),
    ("graph.payload.max-text-length", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxTextLength", "RAVENROOT_GRAPH_MAX_PAYLOAD_TEXT_LENGTH"),
    ("graph.payload.max-value-count", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxValueCount", "RAVENROOT_GRAPH_MAX_PAYLOAD_VALUE_COUNT"),
})


@dataclass(frozen=True)
class Candidate:
    id: str
    path: str
    line: int
    symbol: str
    kind: str
    role: str
    expression: str
    expression_digest: str
    evidence: str
    evidence_digest: str
    surface: str
    fixture: bool = False

    def inventory_entry(self) -> dict[str, object]:
        if self.fixture:
            return {
                **self.source_fields(),
                "status": "retained",
                "classification": "test-fixture",
                "rationale": "A testkit module ships reusable test fixtures under src/main; it is not production runtime configuration.",
            }
        return {
            **self.source_fields(),
            "status": "pending-review",
            "classification": None,
        }

    def source_fields(self) -> dict[str, object]:
        return {
            "id": self.id,
            "path": self.path,
            "line": self.line,
            "symbol": self.symbol,
            "kind": self.kind,
            "role": self.role,
            "expression": self.expression,
            "expressionDigest": self.expression_digest,
            "evidenceDigest": self.evidence_digest,
            "surface": self.surface,
        }


def tracked_files(root: Path) -> tuple[Path, ...]:
    listing = subprocess.run(
        ["git", "ls-files", "-co", "--exclude-standard", "-z"],
        cwd=root,
        check=True,
        capture_output=True,
    ).stdout.decode("utf-8").split("\0")
    return tuple(sorted(Path(item) for item in listing if item and (root / item).is_file()))


def surface(relative: Path) -> str | None:
    parts = relative.parts
    text = relative.as_posix()
    if any(part in EXCLUDED_PARTS for part in parts):
        return None
    if relative.name in {"package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock"}:
        return None
    if text in {
        "scripts/audit_operational_configuration.py",
        "scripts/operational-configuration-inventory.json",
    }:
        # The audit implementation and inventory describe the scan. Including either would make
        # checker maintenance create candidates about the checker rather than runtime policy.
        return None
    if relative.name in {"Dockerfile", "Dockerfile.ci"}:
        return "deployment"
    if text == "compose.yaml" or text.startswith("deploy/"):
        return "deployment"
    if relative.suffix == ".sh":
        if text.startswith("scripts/tests/") or "/e2e/" in text:
            return "test-fixture"
        return "script"
    # Documented runnable configuration is a deployment surface, not ordinary prose.
    if text in {
        "docs/examples/assistant/compose.override.yaml",
        "docs/examples/plugins/compose.openapi-client.override.yaml",
    }:
        return "deployment-example"
    if text.startswith("ravenroot/ravenroot-ui/src/") or text.startswith("ravenroot/ravenroot-ui/public/"):
        return "ui"
    if text.startswith("scripts/"):
        if text.startswith("scripts/tests/") or text.startswith("scripts/fixtures/"):
            return "test-fixture"
        return "script"
    if "/src/test/" in text or "/e2e/" in text or "/test/" in text:
        return None
    if "/src/main/" in text:
        module = next((part for part in parts if part.startswith("ravenroot-") and part != "ravenroot"), "")
        return "test-fixture" if module in TESTKIT_MODULES else "java"
    return None


def strip_c_comments(text: str) -> str:
    """Remove // and /* */ comments while preserving strings and newlines."""
    out: list[str] = []
    index = 0
    state = "code"
    quote = ""
    while index < len(text):
        char = text[index]
        following = text[index + 1] if index + 1 < len(text) else ""
        if state == "line":
            if char == "\n":
                state = "code"
                out.append(char)
            else:
                out.append(" ")
        elif state == "block":
            if char == "*" and following == "/":
                out.extend((" ", " "))
                index += 1
                state = "code"
            else:
                out.append("\n" if char == "\n" else " ")
        elif state == "textblock":
            if text.startswith('"""', index):
                out.extend(('"', '"', '"'))
                index += 2
                state = "code"
            elif char == "\\" and following:
                out.extend((char, following))
                index += 1
            else:
                out.append(char)
        elif state == "string":
            out.append(char)
            if char == "\\" and following:
                out.append(following)
                index += 1
            elif char == quote:
                state = "code"
        elif char == "/" and following == "/":
            out.extend((" ", " "))
            index += 1
            state = "line"
        elif char == "/" and following == "*":
            out.extend((" ", " "))
            index += 1
            state = "block"
        elif text.startswith('"""', index):
            state = "textblock"
            out.extend(('"', '"', '"'))
            index += 2
        elif char in {'"', "'", "`"}:
            quote = char
            state = "string"
            out.append(char)
        else:
            out.append(char)
        index += 1
    return "".join(out)


def strip_c_comments_and_literals(text: str) -> str:
    """Mask comments and quoted literals while preserving offsets and newlines."""
    without_comments = strip_c_comments(text)
    out: list[str] = []
    index = 0
    quote = ""
    textblock = False
    while index < len(without_comments):
        char = without_comments[index]
        following = without_comments[index + 1] if index + 1 < len(without_comments) else ""
        if textblock:
            if without_comments.startswith('"""', index):
                out.extend((" ", " ", " "))
                index += 2
                textblock = False
            elif char == "\\" and following:
                out.extend((" ", " "))
                index += 1
            else:
                out.append("\n" if char == "\n" else " ")
        elif quote:
            if char == "\\" and following:
                out.extend((" ", " "))
                index += 1
            elif char == quote:
                out.append(" ")
                quote = ""
            else:
                out.append("\n" if char == "\n" else " ")
        elif without_comments.startswith('"""', index):
            textblock = True
            out.extend((" ", " ", " "))
            index += 2
        elif char in {'"', "'", "`"}:
            quote = char
            out.append(" ")
        else:
            out.append(char)
        index += 1
    return "".join(out)


def java_type_span(source: str, symbol: str) -> tuple[int, int] | None:
    """Return one Java type declaration span, ignoring declaration-shaped text in literals/comments."""
    code = strip_c_comments_and_literals(source)
    declaration = re.search(
        rf"\b(?:class|record|interface|enum|@interface)\s+{re.escape(symbol)}\b", code,
    )
    if declaration is None:
        return None
    opening = code.find("{", declaration.end())
    if opening < 0:
        return None
    depth = 0
    for offset in range(opening, len(code)):
        if code[offset] == "{":
            depth += 1
        elif code[offset] == "}":
            depth -= 1
            if depth == 0:
                return declaration.start(), offset + 1
    return None


def java_type_declares_field(source: str, symbol: str, field: str) -> bool:
    """Check an exact record component or direct member declared by the named Java type."""
    span = java_type_span(source, symbol)
    if span is None:
        return False
    code = strip_c_comments_and_literals(source)[slice(*span)]
    opening = code.find("{")
    parts = field.split(".")
    if len(parts) != 1:
        return False
    identifier = parts[-1]

    type_match = re.search(
        rf"\b(?P<kind>class|record|interface|enum|@interface)\s+{re.escape(symbol)}\b", code[:opening],
    )
    if type_match is None:
        return False
    declared: set[str] = set()
    if type_match.group("kind") == "record":
        parenthesis = code.find("(", type_match.end(), opening)
        if parenthesis >= 0:
            depth = 0
            component_start = parenthesis + 1
            for offset in range(parenthesis + 1, opening):
                char = code[offset]
                if char in "(<[":
                    depth += 1
                elif char in ")>]":
                    if char == ")" and depth == 0:
                        component = code[component_start:offset]
                        names = re.findall(r"\b[A-Za-z_$][\w$]*\b", component)
                        if names:
                            declared.add(names[-1])
                        break
                    depth -= 1
                elif char == "," and depth == 0:
                    component = code[component_start:offset]
                    names = re.findall(r"\b[A-Za-z_$][\w$]*\b", component)
                    if names:
                        declared.add(names[-1])
                    component_start = offset + 1

    body = code[opening + 1:-1]
    depth = 1
    statement: list[str] = []
    for char in body:
        if char == "{":
            if depth == 1:
                statement.clear()
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 1:
                statement.clear()
        elif depth == 1:
            statement.append(char)
            if char == ";":
                unit = "".join(statement)
                statement.clear()
                declaration = re.match(
                    r"\s*(?:(?:public|protected|private|static|final|volatile|transient)\s+)*"
                    r"[A-Za-z_$][\w$<>,.?\[\] @]*\s+([A-Za-z_$][\w$]*)\s*(?:=|;|,)",
                    unit,
                )
                if declaration is not None:
                    declared.add(declaration.group(1))
    return identifier in declared


def normalized(value: str) -> str:
    return " ".join(value.split())


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def symbol_markers(code: str, suffix: str) -> tuple[tuple[int, str], ...]:
    """Index declaration starts once; candidate lookup must stay linearithmic on large files."""
    markers: list[tuple[int, str]] = [(0, "module")]
    offset = 0
    for line in code.splitlines(keepends=True):
        match = None
        if suffix == ".java":
            match = re.search(r"\b(?:class|record|interface|enum)\s+([A-Za-z_$][\w$]*)", line)
            if match is None and "(" in line and line.rstrip().endswith("{"):
                match = re.search(r"([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[^{}]+)?\{\s*$", line)
        elif suffix in {".js", ".mjs", ".ts"}:
            match = re.search(r"\b(?:function\s+)?([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*\{\s*$", line)
        elif suffix == ".py":
            match = re.match(r"\s*(?:async\s+)?def\s+([A-Za-z_][\w]*)\s*\(", line)
        if match is not None:
            markers.append((offset + match.start(), match.group(1)))
        offset += len(line)
    return tuple(markers)


def containing_symbol(markers: tuple[tuple[int, str], ...], offset: int) -> str:
    position = bisect_right(markers, (offset, chr(0x10FFFF))) - 1
    return markers[position][1]


def statement_spans(code: str) -> Iterable[tuple[int, int, str]]:
    """Yield semicolon or newline units without splitting inside quoted strings."""
    start = 0
    quote = ""
    escaped = False
    for index, char in enumerate(code):
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = ""
            continue
        if char in {'"', "'", "`"}:
            quote = char
        elif char == ";":
            yield start, index + 1, code[start:index + 1]
            start = index + 1
    if start < len(code):
        for match in re.finditer(r"[^\n]+", code[start:]):
            yield start + match.start(), start + match.end(), match.group(0)


def code_candidates(relative: Path, text: str, surface_name: str) -> list[tuple[int, str, str, str, str, str]]:
    suffix = relative.suffix
    code = strip_c_comments(text)
    markers = symbol_markers(code, suffix)
    rows: list[tuple[int, str, str, str, str, str]] = []
    for start, _end, raw in statement_spans(code):
        evidence = normalized(raw)
        if not evidence:
            continue

        for binding in ENVIRONMENT_BINDING.finditer(raw):
            candidate_offset = start + binding.start()
            token = binding.group(0)
            rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                         "environment-binding", token, token, evidence))
        for binding in PROPERTY_BINDING.finditer(raw):
            candidate_offset = start + binding.start()
            token = binding.group(1)
            rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                         "property-binding", token, token, evidence))
        for binding in SYSTEM_PROPERTY_READ.finditer(raw):
            argument = normalized(binding.group(1))
            if PROPERTY_BINDING.fullmatch(argument):
                continue
            candidate_offset = start + binding.start(1)
            rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                         "property-binding", "System.getProperty", argument.strip('"'), evidence))

        kind: str | None = None
        label = "literal"
        assignment = JS_DECLARATION.search(evidence) if suffix in {".js", ".mjs", ".ts"} \
            else ASSIGNMENT_NAME.search(evidence)
        if STATIC_FINAL.search(evidence) or (suffix in {".js", ".mjs", ".ts"}
                                              and evidence.lstrip().startswith("const ")):
            kind = "fixed-declaration"
            if assignment:
                label = assignment.group(1)
        elif assignment and OPERATIONAL_WORD.search(assignment.group(1)):
            kind = "operational-declaration"
            label = assignment.group(1)
        elif KNOWN_OPERATIONAL_CALL.search(evidence):
            kind = "inline-operational-call"
            call = KNOWN_OPERATIONAL_CALL.search(evidence)
            assert call is not None
            label = normalized(call.group(1)).replace(" ", "-")
        if kind:
            for atom in FIXED_ATOM.finditer(raw):
                candidate_offset = start + atom.start()
                rows.append((candidate_offset, containing_symbol(markers, candidate_offset), kind,
                             label, normalized(atom.group(0)), evidence))
    if suffix == ".java":
        # Parse only the supported timeout argument. Feeding these method names into the broad
        # KNOWN_OPERATIONAL_CALL branch would incorrectly collect permit counts and unrelated
        # literals elsewhere in the containing statement.
        occupied_offsets = {row[0] for row in rows}
        masked = strip_c_comments_and_literals(text)
        for start, end, raw in statement_spans(code):
            evidence = normalized(raw)
            for call in TIME_UNIT_OPERATIONAL_CALL.finditer(masked, start, end):
                opening = masked.find("(", call.start(), end)
                if opening < 0:
                    continue
                parsed = split_java_arguments(text, masked, opening)
                if parsed is None:
                    continue
                arguments, closing = parsed
                if closing >= end:
                    continue
                method = call.group(1)
                if method == "tryAcquire":
                    if len(arguments) not in {2, 3}:
                        continue
                elif len(arguments) != 2:
                    continue
                unit = arguments[-1]
                unit_expression = re.sub(r"\s+", "", masked[unit[1]:unit[2]])
                if TIME_UNIT_ARGUMENT.fullmatch(unit_expression) is None:
                    continue
                timeout = arguments[-2]
                for atom in NUMBER.finditer(masked, timeout[1], timeout[2]):
                    candidate_offset = atom.start()
                    if candidate_offset in occupied_offsets:
                        continue
                    occupied_offsets.add(candidate_offset)
                    rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                                 "inline-operational-call", f"timeunit-{method}",
                                 normalized(text[atom.start():atom.end()]), evidence))
    return rows


def line_candidates(relative: Path, text: str, surface_name: str) -> list[tuple[int, str, str, str, str, str]]:
    rows: list[tuple[int, str, str, str, str, str]] = []
    suffix = relative.suffix
    markers = symbol_markers(text, suffix)
    offset = 0
    for index, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith(("#", "//")):
            offset += len(raw) + 1
            continue
        evidence = normalized(raw)
        for binding in ENVIRONMENT_BINDING.finditer(raw):
            candidate_offset = offset + binding.start()
            token = binding.group(0)
            rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                         "environment-binding", token, token, evidence))
        label: str | None = None
        kind: str | None = None
        if suffix in {".yaml", ".yml", ".json"}:
            match = YAML_SCALAR.match(raw)
            if match and FIXED.search(match.group(2)):
                label = match.group(1)
                kind = "configuration-scalar"
        elif suffix in {".py", ".sh"} or relative.name.startswith("Dockerfile"):
            docker = re.match(r"^\s*(USER|EXPOSE)\s+(.+)$", raw, re.IGNORECASE)
            match = PY_ASSIGNMENT.match(raw)
            shell = re.match(r"^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$", raw)
            name = match.group(1) if match else shell.group(1) if shell else None
            if docker and FIXED.search(docker.group(2)):
                label = docker.group(1).upper()
                kind = "container-directive"
            elif name and (name.isupper() or OPERATIONAL_WORD.search(name)) and FIXED.search(raw):
                label = name
                kind = "script-default"
            elif (re.search(r"\b(?:sleep|timeout)\s+[0-9]", raw)
                  or (OPERATIONAL_WORD.search(raw) and FIXED.search(raw))):
                label = "inline"
                kind = "inline-script-operational"
            elif ENVIRONMENT_BINDING.search(raw) and FIXED.search(raw):
                label = "binding-value"
                kind = "binding-default"
        if kind and label:
            for atom in FIXED_ATOM.finditer(raw):
                candidate_offset = offset + atom.start()
                rows.append((candidate_offset, containing_symbol(markers, candidate_offset), kind,
                             label, normalized(atom.group(0)), evidence))
        offset += len(raw) + 1
    return rows


def json_pointer(document: object, reference: str) -> object:
    """Resolve one same-document JSON Pointer, rejecting external and malformed references."""
    if not reference.startswith("#/"):
        raise ValueError("only same-document JSON Pointer references are supported")
    value = document
    for encoded in reference[2:].split("/"):
        token = encoded.replace("~1", "/").replace("~0", "~")
        if isinstance(value, dict) and token in value:
            value = value[token]
        elif isinstance(value, list) and token.isdigit() and int(token) < len(value):
            value = value[int(token)]
        else:
            raise ValueError("JSON Pointer target is absent")
    return value


def resolved_json_schema_value(document: object, value: object,
                               references: tuple[str, ...] = ()) -> object:
    """Return a canonicalizable local-ref expansion and fail closed on cycles."""
    if isinstance(value, dict):
        if "$ref" in value:
            if set(value) != {"$ref"} or not isinstance(value["$ref"], str):
                raise ValueError("unsupported sibling or value next to $ref")
            reference = value["$ref"]
            if reference in references:
                raise ValueError("cyclic local JSON reference")
            target = json_pointer(document, reference)
            return {"$ref": reference,
                    "resolved": resolved_json_schema_value(document, target, references + (reference,))}
        return {key: resolved_json_schema_value(document, child, references)
                for key, child in sorted(value.items())}
    if isinstance(value, list):
        return [resolved_json_schema_value(document, child, references) for child in value]
    return value


def json_schema_reference_candidates(text: str) -> list[tuple[int, str, str, str, str, str]]:
    """Discover per-property local `$ref` edges with their resolved constraint evidence."""
    try:
        document = json.loads(text)
    except json.JSONDecodeError:
        return []
    rows: list[tuple[int, str, str, str, str, str]] = []
    cursor = 0

    def visit(value: object, pointer: str, required_property: bool = False) -> None:
        nonlocal cursor
        if isinstance(value, dict):
            reference = value.get("$ref")
            if isinstance(reference, str):
                offset = text.find('"$ref"', cursor)
                if offset < 0:
                    offset = 0
                else:
                    cursor = offset + len('"$ref"')
                try:
                    if set(value) != {"$ref"}:
                        raise ValueError("unsupported sibling next to $ref")
                    target = json_pointer(document, reference)
                    resolved = resolved_json_schema_value(document, target, (reference,))
                    resolution_error = None
                except ValueError as invalid:
                    resolved = None
                    resolution_error = str(invalid)
                reference_pointer = f"{pointer}/$ref"
                evidence = {
                    "pointer": reference_pointer,
                    "reference": reference,
                    "required": required_property,
                    "resolved": resolved,
                    "resolutionError": resolution_error,
                }
                rows.append((offset, pointer, "schema-reference-binding", reference_pointer,
                             reference, json.dumps(evidence, sort_keys=True, separators=(",", ":"))))
            for key, child in value.items():
                escaped = str(key).replace("~", "~0").replace("/", "~1")
                if key == "properties" and isinstance(child, dict):
                    required = set(value.get("required", [])) if isinstance(value.get("required"), list) else set()
                    for property_name, property_schema in child.items():
                        property_token = str(property_name).replace("~", "~0").replace("/", "~1")
                        visit(property_schema, f"{pointer}/{escaped}/{property_token}",
                              property_name in required)
                else:
                    visit(child, f"{pointer}/{escaped}", required_property)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                visit(child, f"{pointer}/{index}", required_property)

    visit(document, "")
    return rows


def discover(root: Path) -> tuple[Candidate, ...]:
    provisional: list[tuple[str, int, str, str, str, str, str, str, bool]] = []
    for relative in tracked_files(root):
        surface_name = surface(relative)
        if surface_name is None or (relative.suffix not in SOURCE_SUFFIXES
                                    and not relative.name.startswith("Dockerfile")):
            continue
        text = (root / relative).read_text(encoding="utf-8", errors="strict")
        if relative.suffix in {".java", ".js", ".mjs", ".ts"}:
            found = code_candidates(relative, text, surface_name)
        else:
            found = line_candidates(relative, text, surface_name)
            if relative.suffix == ".json":
                found.extend(json_schema_reference_candidates(text))
        for offset, symbol_name, kind, role, expression, evidence in found:
            provisional.append((relative.as_posix(), line_number(text, offset), symbol_name,
                                kind, role, expression, evidence, surface_name,
                                surface_name == "test-fixture"))

    occurrences: Counter[tuple[str, str, str, str, str]] = Counter()
    candidates: list[Candidate] = []
    for path, line, symbol_name, kind, role, expression, evidence, surface_name, fixture in sorted(provisional):
        key = (path, symbol_name, kind, role, expression)
        occurrence = occurrences[key]
        occurrences[key] += 1
        evidence_digest = hashlib.sha256(evidence.encode("utf-8")).hexdigest()
        material = "\0".join((path, symbol_name, kind, role, expression, evidence_digest,
                               str(occurrence)))
        digest = hashlib.sha256(expression.encode("utf-8")).hexdigest()
        identifier = "oc-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:20]
        candidates.append(Candidate(identifier, path, line, symbol_name, kind, role, expression, digest,
                                    evidence, evidence_digest, surface_name, fixture))
    return tuple(candidates)


def load_inventory(path: Path = INVENTORY, *, allow_previous_schema: bool = False) -> dict[str, object]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise ValueError(f"missing inventory: {path.relative_to(ROOT)}") from None
    accepted_versions = {SCHEMA_VERSION, SCHEMA_VERSION - 1} if allow_previous_schema else {SCHEMA_VERSION}
    if document.get("schemaVersion") not in accepted_versions:
        raise ValueError(f"inventory schemaVersion must be {SCHEMA_VERSION}")
    entries = document.get("entries")
    if not isinstance(entries, list):
        raise ValueError("inventory entries must be an array")
    retired = document.get("retiredEntries", [])
    if not isinstance(retired, list):
        raise ValueError("inventory retiredEntries must be an array")
    if document.get("schemaVersion") == SCHEMA_VERSION and not isinstance(document.get("evidenceRecords"), dict):
        raise ValueError("inventory evidenceRecords must be an object")
    if not isinstance(document.get("migrationHistory", []), list):
        raise ValueError("inventory migrationHistory must be an array")
    return document


@lru_cache(maxsize=None)
def current_source_owner(root: Path, owner: str) -> tuple[Path, str] | None:
    """Resolve a tracked in-repository ``path#symbol`` authority without following escapes."""
    if "#" not in owner:
        return None
    owner_path, owner_symbol = owner.rsplit("#", 1)
    relative = Path(owner_path)
    if not owner_symbol or relative.is_absolute() or ".." in relative.parts:
        return None
    root_resolved = root.resolve()
    authority = (root / relative).resolve()
    try:
        authority.relative_to(root_resolved)
    except ValueError:
        return None
    tracked = subprocess.run(["git", "ls-files", "--error-unmatch", "--", relative.as_posix()], cwd=root,
                             capture_output=True)
    if tracked.returncode != 0 or not authority.is_file():
        return None
    source = authority.read_text(encoding="utf-8")
    suffix = relative.suffix
    symbol = re.escape(owner_symbol)
    if suffix == ".java":
        return (relative, owner_symbol) if java_type_span(source, owner_symbol) is not None else None
    declarations = {
        ".py": (rf"(?m)^\s*(?:class|def|async\s+def)\s+{symbol}\b",),
        ".js": (rf"\b(?:class|function)\s+{symbol}\b", rf"\b(?:const|let|var)\s+{symbol}\s*="),
        ".mjs": (rf"\b(?:class|function)\s+{symbol}\b", rf"\b(?:const|let|var)\s+{symbol}\s*="),
        ".ts": (rf"\b(?:class|interface|type|enum|function)\s+{symbol}\b",
                rf"\b(?:const|let|var)\s+{symbol}\s*="),
        ".sh": (rf"(?m)^\s*(?:function\s+)?{symbol}\s*(?:\(\s*\))?\s*\{{",
                rf"(?m)^\s*{symbol}="),
        ".yaml": (rf"(?m)^\s*{symbol}\s*:",),
        ".yml": (rf"(?m)^\s*{symbol}\s*:",),
    }.get(suffix, ())
    if not declarations or not any(re.search(pattern, source) for pattern in declarations):
        return None
    return relative, owner_symbol


def current_source_field(root: Path, owner: str, field: str) -> bool:
    """Verify a Java operator field is declared by its typed current-source owner."""
    resolved = current_source_owner(root, owner)
    if resolved is None:
        return False
    relative, owner_symbol = resolved
    if relative.suffix != ".java":
        return False
    return java_type_declares_field((root / relative).read_text(encoding="utf-8"), owner_symbol, field)


def java_record_components(source: str, symbol: str) -> tuple[str, ...]:
    span = java_type_span(source, symbol)
    if span is None:
        return ()
    code = strip_c_comments_and_literals(source)[slice(*span)]
    declaration = re.search(rf"\brecord\s+{re.escape(symbol)}\b", code)
    opening_brace = code.find("{")
    if declaration is None or opening_brace < 0:
        return ()
    opening = code.find("(", declaration.end(), opening_brace)
    if opening < 0:
        return ()
    components: list[str] = []
    start = opening + 1
    depth = 0
    for offset in range(start, opening_brace):
        char = code[offset]
        if char in "(<[":
            depth += 1
        elif char in ")>]":
            if char == ")" and depth == 0:
                names = re.findall(r"\b[A-Za-z_$][\w$]*\b", code[start:offset])
                if names:
                    components.append(names[-1])
                return tuple(components)
            depth -= 1
        elif char == "," and depth == 0:
            names = re.findall(r"\b[A-Za-z_$][\w$]*\b", code[start:offset])
            if names:
                components.append(names[-1])
            start = offset + 1
    return ()


def java_record_default_expression_span(source: str, symbol: str, instance_symbol: str,
                                        field: str) -> tuple[str, int, int] | None:
    """Read one positional component expression and span from a unique direct record default."""
    components = java_record_components(source, symbol)
    if field not in components:
        return None
    span = java_type_span(source, symbol)
    assert span is not None
    base, limit = span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    initializers = list(re.finditer(
        rf"\b{re.escape(instance_symbol)}\b\s*=\s*new\s+{re.escape(symbol)}\s*\(", code,
    ))
    if len(initializers) != 1:
        return None
    opening = code.find("(", initializers[0].start())
    parsed = split_java_arguments(actual, code, opening)
    if parsed is None or len(parsed[0]) != len(components):
        return None
    expression, start, end = parsed[0][components.index(field)]
    return expression, base + start, base + end


def java_record_default_expression(source: str, symbol: str, instance_symbol: str,
                                   field: str) -> str | None:
    result = java_record_default_expression_span(source, symbol, instance_symbol, field)
    return result[0] if result is not None else None


def java_static_final_initializer(source: str, symbol: str,
                                  field: str) -> tuple[str, int, int] | None:
    """Return one direct static-final field initializer in a named Java type."""
    span = java_type_span(source, symbol)
    if span is None:
        return None
    base, limit = span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    matches: list[tuple[str, int, int]] = []
    for name in re.finditer(rf"\b{re.escape(field)}\b\s*=", code):
        if depths[name.start()] != 1:
            continue
        statement_start = max(code.rfind(";", 0, name.start()), code.rfind("{", 0, name.start())) + 1
        prefix = code[statement_start:name.start()]
        if re.search(r"\bstatic\s+final\b|\bfinal\s+static\b", prefix) is None:
            continue
        equals = code.find("=", name.start(), name.end())
        end = equals + 1
        round_depth = square_depth = brace_depth = 0
        while end < len(code):
            char = code[end]
            if char == "(": round_depth += 1
            elif char == ")": round_depth -= 1
            elif char == "[": square_depth += 1
            elif char == "]": square_depth -= 1
            elif char == "{": brace_depth += 1
            elif char == "}": brace_depth -= 1
            elif char == ";" and round_depth == square_depth == brace_depth == 0:
                start = equals + 1
                matches.append((normalized(actual[start:end]), base + start, base + end))
                break
            end += 1
    return matches[0] if len(matches) == 1 else None


def candidate_ids_in_source_span(relative: Path, source: str, start: int, end: int,
                                 kind: str, role: str,
                                 discovered: dict[str, Candidate]) -> list[str]:
    """Reconcile lexical offsets with stable candidate occurrences without storing offsets publicly."""
    grouped: dict[tuple[str, str, str, str, str], list[str]] = {}
    for candidate in discovered.values():
        if candidate.path != relative.as_posix():
            continue
        key = (candidate.symbol, candidate.kind, candidate.role, candidate.expression,
               candidate.evidence_digest)
        grouped.setdefault(key, []).append(candidate.id)
    occurrences: Counter[tuple[str, str, str, str, str]] = Counter()
    selected: list[str] = []
    for offset, symbol_name, candidate_kind, candidate_role, expression, evidence in code_candidates(
            relative, source, surface(relative) or "java"):
        evidence_digest = hashlib.sha256(evidence.encode("utf-8")).hexdigest()
        key = (symbol_name, candidate_kind, candidate_role, expression, evidence_digest)
        occurrence = occurrences[key]
        occurrences[key] += 1
        ids = grouped.get(key, [])
        if start <= offset < end and candidate_kind == kind and candidate_role == role \
                and occurrence < len(ids):
            selected.append(ids[occurrence])
    return selected


def java_package(source: str) -> str:
    code = strip_c_comments_and_literals(source)
    match = re.search(r"\bpackage\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;", code)
    return match.group(1) if match is not None else ""


def java_constant_reference_matches(source: str, source_owner: str, expression: str,
                                    target_owner: str, target_field: str,
                                    target_source: str) -> bool:
    """Match one bounded unqualified, imported/simple, or fully qualified Java field reference."""
    normalized_expression = normalized(expression)
    source_path, source_type = source_owner.rsplit("#", 1)
    target_path, target_type = target_owner.rsplit("#", 1)
    if normalized_expression == target_field:
        return source_path == target_path and source_type == target_type
    if normalized_expression == f"{target_type}.{target_field}":
        target_package = java_package(target_source)
        return java_package(source) == target_package or re.search(
            rf"\bimport\s+{re.escape(target_package + '.' + target_type)}\s*;",
            strip_c_comments_and_literals(source),
        ) is not None
    target_package = java_package(target_source)
    return bool(target_package) and normalized_expression == \
        f"{target_package}.{target_type}.{target_field}"

def matching_delimiter(code: str, opening: int, left: str, right: str) -> int | None:
    depth = 0
    for offset in range(opening, len(code)):
        if code[offset] == left:
            depth += 1
        elif code[offset] == right:
            depth -= 1
            if depth == 0:
                return offset
    return None


def java_brace_depths(code: str) -> list[int]:
    """Return the brace depth immediately before each character in masked Java source."""
    depths: list[int] = []
    depth = 0
    for char in code:
        depths.append(depth)
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
    return depths


def java_method_span(source: str, type_symbol: str, method: str) -> tuple[int, int] | None:
    """Resolve exactly one direct Java method body in a named type."""
    type_span = java_type_span(source, type_symbol)
    if type_span is None:
        return None
    base, limit = type_span
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    matches: list[tuple[int, int]] = []
    for name in re.finditer(rf"\b{re.escape(method)}\s*\(", code):
        if depths[name.start()] != 1 or (name.start() and code[name.start() - 1] == "."):
            continue
        opening = code.find("(", name.start())
        closing = matching_delimiter(code, opening, "(", ")")
        if closing is None:
            continue
        suffix = re.match(r"\s*(?:throws\s+[^{};]+)?\s*\{", code[closing + 1:])
        if suffix is None:
            continue
        opening_brace = closing + 1 + suffix.end() - 1
        closing_brace = matching_delimiter(code, opening_brace, "{", "}")
        if closing_brace is not None:
            matches.append((base + name.start(), base + closing_brace + 1))
    return matches[0] if len(matches) == 1 else None


def split_java_arguments(actual: str, code: str, opening: int) -> tuple[list[tuple[str, int, int]], int] | None:
    closing = matching_delimiter(code, opening, "(", ")")
    if closing is None:
        return None
    arguments: list[tuple[str, int, int]] = []
    start = opening + 1
    round_depth = square_depth = brace_depth = 0
    for offset in range(start, closing):
        char = code[offset]
        if char == "(": round_depth += 1
        elif char == ")": round_depth -= 1
        elif char == "[": square_depth += 1
        elif char == "]": square_depth -= 1
        elif char == "{": brace_depth += 1
        elif char == "}": brace_depth -= 1
        elif char == "," and round_depth == square_depth == brace_depth == 0:
            arguments.append((normalized(actual[start:offset]), start, offset))
            start = offset + 1
    arguments.append((normalized(actual[start:closing]), start, closing))
    return arguments, closing


def java_constructor_component_call(source: str, type_symbol: str, method: str,
                                    constructor_type: str, components: tuple[str, ...],
                                    component: str) -> tuple[str, int, int] | None:
    """Return the exact direct constructor argument occupying one record-component position."""
    method_span = java_method_span(source, type_symbol, method)
    if method_span is None or component not in components:
        return None
    base, limit = method_span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    constructor = re.compile(rf"\bnew\s+{re.escape(constructor_type)}\s*\(")
    found: list[tuple[list[tuple[str, int, int]], int, int]] = []
    for match in constructor.finditer(code):
        opening = code.find("(", match.start())
        parsed = split_java_arguments(actual, code, opening)
        if parsed is not None and len(parsed[0]) == len(components):
            found.append((parsed[0], opening, parsed[1]))
    if len(found) != 1:
        return None
    arguments, opening, closing = found[0]
    argument, start, end = arguments[components.index(component)]
    return argument, base + start, base + end


def java_method_digest(source: str, type_symbol: str, method: str) -> str | None:
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    body = normalized(strip_c_comments(source[slice(*span)]))
    return hashlib.sha256(body.encode("utf-8")).hexdigest()


def java_method_header(source: str, type_symbol: str, method: str) -> str | None:
    """Return the normalized declaration header for one supported direct method."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    start = source.rfind("\n", 0, span[0]) + 1
    code = strip_c_comments_and_literals(source)
    opening = code.find("{", span[0], span[1])
    if opening < 0:
        return None
    return normalized(source[start:opening])


def java_method_annotations(source: str, type_symbol: str, method: str) -> tuple[str, ...] | None:
    """Return the contiguous, one-line annotations on one supported direct Java method."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    declaration_line = source.rfind("\n", 0, span[0]) + 1
    preceding = source[:declaration_line].splitlines()
    annotations: list[str] = []
    while preceding:
        line = preceding.pop().strip()
        if not line.startswith("@"):
            break
        annotations.append(normalized(line))
    annotations.reverse()
    return tuple(annotations)


def java_test_type_is_directly_runnable(source: str, type_symbol: str) -> bool:
    """Accept one unannotated top-level test class with no abstract/inherited execution shape."""
    code = strip_c_comments_and_literals(source)
    depths = java_brace_depths(code)
    declarations = [
        match for match in re.finditer(rf"\bclass\s+{re.escape(type_symbol)}\b", code)
        if depths[match.start()] == 0
    ]
    if len(declarations) != 1:
        return False
    declaration = declarations[0]
    opening = code.find("{", declaration.end())
    if opening < 0 or re.fullmatch(r"\s*\{", code[declaration.end():opening + 1]) is None:
        return False
    boundary = 0
    for offset, char in enumerate(code[:declaration.start()]):
        if depths[offset] == 0 and char in ";}":
            boundary = offset + 1
    prefix = code[boundary:declaration.start()]
    return re.fullmatch(r"\s*(?:final\s+)?", prefix) is not None


RATE_TEST_IMPORTS = {
    "Test": "org.junit.jupiter.api.Test",
    "ParameterizedTest": "org.junit.jupiter.params.ParameterizedTest",
    "MethodSource": "org.junit.jupiter.params.provider.MethodSource",
    "Stream": "java.util.stream.Stream",
}


def java_has_exact_rate_test_imports(source: str, type_symbol: str) -> bool:
    """Bind the supported short annotation/factory names to their exact library types."""
    code = strip_c_comments_and_literals(source)
    if re.search(
            r"(?m)^\s*import\s+static\s+[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*"
            r"\.(?:Stream|\*)\s*;", code,
    ) is not None:
        return False
    imports = re.findall(
        r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;", code,
    )
    for simple, qualified in RATE_TEST_IMPORTS.items():
        matching = [imported for imported in imports if imported.rsplit(".", 1)[-1] == simple]
        if matching != [qualified]:
            return False
        if re.search(
                rf"\b(?:class|record|enum|interface)\s+{re.escape(simple)}\b"
                rf"|@interface\s+{re.escape(simple)}\b", code,
        ) is not None:
            return False
    if java_type_declares_field(source, type_symbol, "Stream"):
        return False
    return True


def java_direct_stream_string_return(source: str, type_symbol: str,
                                     method: str) -> tuple[str, ...] | None:
    """Parse one direct `return Stream.of("...")` body with quoted literals only."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    actual = strip_c_comments(source[slice(*span)])
    code = strip_c_comments_and_literals(source[slice(*span)])
    body = code.find("{")
    if body < 0:
        return None
    direct = re.match(r"\s*return\s+Stream\s*\.\s*of\s*\(", code[body + 1:])
    if direct is None:
        return None
    opening = body + 1 + direct.end() - 1
    parsed = split_java_arguments(actual, code, opening)
    if parsed is None:
        return None
    arguments, closing = parsed
    if re.fullmatch(r"\s*;\s*}", code[closing + 1:]) is None:
        return None
    values: list[str] = []
    for argument, _start, _end in arguments:
        literal = re.fullmatch(r'"(RAVENROOT_[A-Z0-9_]+)"', argument)
        if literal is None:
            return None
        values.append(literal.group(1))
    return tuple(values)


def java_compact_constructor_span(source: str, type_symbol: str) -> tuple[int, int] | None:
    """Resolve one direct compact record constructor, excluding methods and nested types."""
    type_span = java_type_span(source, type_symbol)
    if type_span is None:
        return None
    base, limit = type_span
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    matches: list[tuple[int, int]] = []
    for name in re.finditer(rf"\b{re.escape(type_symbol)}\s*\{{", code):
        if depths[name.start()] != 1:
            continue
        opening = code.find("{", name.start())
        closing = matching_delimiter(code, opening, "{", "}")
        if closing is not None:
            matches.append((base + name.start(), base + closing + 1))
    return matches[0] if len(matches) == 1 else None


def java_span_digest(source: str, span: tuple[int, int] | None) -> str | None:
    if span is None:
        return None
    body = normalized(strip_c_comments(source[slice(*span)]))
    return hashlib.sha256(body.encode("utf-8")).hexdigest()


def java_reachable_helpers_from_span(source: str, type_symbol: str,
                                    span: tuple[int, int] | None) -> set[str] | None:
    """Close same-type calls beginning in a direct constructor or initializer span."""
    if span is None:
        return None
    declared = java_declared_method_names(source, type_symbol)
    code = strip_c_comments_and_literals(source)[slice(*span)]
    roots = {name for name in declared if re.search(rf"\b{re.escape(name)}\s*\(", code)}
    reachable = set(roots)
    pending = list(roots)
    while pending:
        method = pending.pop()
        calls = java_method_calls(source, type_symbol, method, declared)
        if calls is None:
            return None
        for called in calls - reachable:
            reachable.add(called)
            pending.append(called)
    return reachable


JAVA_DECIMAL_INTEGER = re.compile(r"(?:0|[1-9](?:_?[0-9])*)")
JAVA_INT_MAX = (1 << 31) - 1
JAVA_LONG_MAX = (1 << 63) - 1


def java_positive_decimal(value: str, maximum: int) -> int | None:
    if JAVA_DECIMAL_INTEGER.fullmatch(value) is None:
        return None
    parsed = int(value.replace("_", ""))
    return parsed if 0 < parsed <= maximum else None


def evaluated_java_default(expression: str, component_is_duration: bool) -> dict[str, object] | None:
    """Evaluate the closed positive int/default-duration forms used by rate configuration."""
    expression = normalized(expression)
    if component_is_duration:
        matched = re.fullmatch(
            r"Duration\.of(Seconds|Minutes|Hours|Days)\(((?:0|[1-9](?:_?[0-9])*))\)",
            expression,
        )
        if matched is None:
            return None
        value = java_positive_decimal(matched.group(2), JAVA_LONG_MAX)
        multipliers = {"Seconds": 1, "Minutes": 60, "Hours": 3600, "Days": 86400}
        if value is None or value > JAVA_LONG_MAX // multipliers[matched.group(1)]:
            return None
        seconds = value * multipliers[matched.group(1)]
        if seconds > JAVA_INT_MAX:
            return None
        return {"kind": "duration-seconds", "value": seconds}
    direct = java_positive_decimal(expression, JAVA_INT_MAX)
    if direct is not None:
        return {"kind": "integer", "value": direct}
    multiplied = re.fullmatch(
        r"((?:0|[1-9](?:_?[0-9])*))\s*\*\s*((?:0|[1-9](?:_?[0-9])*))",
        expression,
    )
    if multiplied is None:
        return None
    left = java_positive_decimal(multiplied.group(1), JAVA_INT_MAX)
    right = java_positive_decimal(multiplied.group(2), JAVA_INT_MAX)
    if left is None or right is None or left > JAVA_INT_MAX // right:
        return None
    return {"kind": "integer", "value": left * right}


def java_declared_method_names(source: str, type_symbol: str) -> set[str]:
    """Return unambiguous method names declared directly by one Java type."""
    type_span = java_type_span(source, type_symbol)
    if type_span is None:
        return set()
    base, limit = type_span
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    names: Counter[str] = Counter()
    for match in re.finditer(r"\b([A-Za-z_$][\w$]*)\s*\(", code):
        if depths[match.start()] != 1:
            continue
        name = match.group(1)
        if name in {"if", "for", "while", "switch", "catch", "synchronized", "try", "do"}:
            continue
        opening = code.find("(", match.start())
        closing = matching_delimiter(code, opening, "(", ")")
        if closing is None:
            continue
        suffix = re.match(r"\s*(?:throws\s+[^{};]+)?\s*\{", code[closing + 1:])
        if suffix is not None:
            names[name] += 1
    return {name for name, count in names.items() if count == 1}


def java_direct_method_declaration_count(source: str, type_symbol: str, method: str) -> int:
    """Count every supported direct declaration, including ambiguous overloads."""
    type_span = java_type_span(source, type_symbol)
    if type_span is None:
        return 0
    base, limit = type_span
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    count = 0
    for match in re.finditer(rf"\b{re.escape(method)}\s*\(", code):
        if depths[match.start()] != 1:
            continue
        opening = code.find("(", match.start())
        closing = matching_delimiter(code, opening, "(", ")")
        if closing is None:
            continue
        suffix = re.match(r"\s*(?:throws\s+[^{};]+)?\s*\{", code[closing + 1:])
        if suffix is not None:
            count += 1
    return count


def java_method_calls(source: str, type_symbol: str, method: str,
                      declared_methods: set[str]) -> set[str] | None:
    """Find same-type helper names called from one supported, unambiguous method body."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    code = strip_c_comments_and_literals(source)[slice(*span)]
    opening = code.find("{")
    if opening < 0:
        return None
    body = code[opening + 1:-1]
    return {name for name in declared_methods
            if re.search(rf"\b{re.escape(name)}\s*\(", body)}


def java_reachable_helper_methods(source: str, type_symbol: str,
                                  roots: tuple[str, ...]) -> set[str] | None:
    """Close direct same-type calls from resolver roots; overloads fail closed."""
    declared = java_declared_method_names(source, type_symbol)
    if any(root not in declared for root in roots):
        return None
    reachable = set(roots)
    pending = list(roots)
    while pending:
        method = pending.pop()
        calls = java_method_calls(source, type_symbol, method, declared)
        if calls is None:
            return None
        for called in calls - reachable:
            reachable.add(called)
            pending.append(called)
    return reachable - set(roots)


@lru_cache(maxsize=None)
def committed_source(root: Path, revision: str, path: str) -> str | None:
    if re.fullmatch(r"[0-9a-f]{40}", revision) is None:
        return None
    relative = Path(path)
    if relative.is_absolute() or ".." in relative.parts:
        return None
    result = subprocess.run(["git", "show", f"{revision}:{relative.as_posix()}"], cwd=root,
                            capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else None


@lru_cache(maxsize=None)
def commit_exists(root: Path, revision: str) -> bool:
    if re.fullmatch(r"[0-9a-f]{40}", revision) is None:
        return False
    return subprocess.run(["git", "cat-file", "-e", f"{revision}^{{commit}}"], cwd=root,
                          capture_output=True).returncode == 0


@lru_cache(maxsize=None)
def revision_is_ancestor(root: Path, before: str, after: str) -> bool:
    if re.fullmatch(r"[0-9a-f]{40}", before) is None \
            or re.fullmatch(r"[0-9a-f]{40}", after) is None:
        return False
    return subprocess.run(["git", "merge-base", "--is-ancestor", before, after], cwd=root,
                          capture_output=True).returncode == 0


def revision_transition_errors(root: Path, identifier: str, provenance: dict[str, object],
                               *, path: str, symbol: str, label: str) -> tuple[list[str], str | None, str | None]:
    errors: list[str] = []
    before = str(provenance["beforeRevision"])
    after = str(provenance["afterRevision"])
    if before == after:
        errors.append(f"{identifier}: {label} revisions must be distinct")
    for revision_field, revision in (("beforeRevision", before), ("afterRevision", after)):
        if not commit_exists(root, revision):
            errors.append(f"{identifier}: {label} {revision_field} is not a local commit")
    if not errors and not revision_is_ancestor(root, before, after):
        errors.append(f"{identifier}: {label} beforeRevision is not an ancestor of afterRevision")
    before_source = committed_source(root, before, path)
    after_source = committed_source(root, after, path)
    if before_source is None or not re.search(rf"\b{re.escape(symbol)}\b", before_source):
        errors.append(f"{identifier}: {label} path/symbol is absent from beforeRevision")
    if after_source is None or not re.search(rf"\b{re.escape(symbol)}\b", after_source):
        errors.append(f"{identifier}: {label} path/symbol is absent from afterRevision")
    if before_source is not None and after_source is not None and before_source == after_source:
        errors.append(f"{identifier}: {label} source is unchanged between revisions")
    return errors, before_source, after_source


def yaml_scalar_rows(source: str) -> list[tuple[int, str, str]]:
    """Return line, path, and value for mapping-only YAML scalars used by deployment values."""
    stack: list[tuple[int, str]] = []
    rows: list[tuple[int, str, str]] = []
    for line, raw in enumerate(source.splitlines(), start=1):
        if not raw.strip() or raw.lstrip().startswith(("#", "-")):
            continue
        match = re.match(r'^([ ]*)([A-Za-z_][A-Za-z0-9_.-]*)\s*:\s*(.*?)\s*$', raw)
        if match is None:
            continue
        indent = len(match.group(1))
        while stack and stack[-1][0] >= indent:
            stack.pop()
        key = match.group(2)
        value = match.group(3)
        path = tuple(item[1] for item in stack) + (key,)
        if value:
            rows.append((line, ".".join(path), normalized(value)))
        else:
            stack.append((indent, key))
    return rows


def yaml_scalar_at_path(source: str, dotted_path: str) -> str | None:
    """Return one scalar at an exact mapping-only YAML path used by deployment values."""
    found = [value for _line, path, value in yaml_scalar_rows(source) if path == dotted_path]
    return found[0] if len(found) == 1 else None


def yaml_default_removal_errors(root: Path, identifier: str, entry: dict[str, object],
                                removal: dict[str, object],
                                active_entries: dict[str, dict[str, object]]) -> list[str]:
    """Verify removal of one exact YAML default in favor of a typed Java authority."""
    required = ("yamlPath", "beforeValue", "afterValue", "replacementOwner", "replacementField",
                "replacementInstanceSymbol", "replacementDefaultExpression")
    if any(not isinstance(removal.get(field), str) or not str(removal[field]).strip()
           for field in required):
        return [f"{identifier}: YAML default removal requires {', '.join(required)}"]
    errors: list[str] = []
    before = str(removal.get("beforeRevision", ""))
    after = str(removal.get("afterRevision", ""))
    path = str(entry.get("path", ""))
    if before == after:
        errors.append(f"{identifier}: removal revisions must be distinct")
    for field, revision in (("beforeRevision", before), ("afterRevision", after)):
        if not commit_exists(root, revision):
            errors.append(f"{identifier}: removal {field} is not a local commit")
    if not errors and not revision_is_ancestor(root, before, after):
        errors.append(f"{identifier}: removal beforeRevision is not an ancestor of afterRevision")
    before_source = committed_source(root, before, path)
    after_source = committed_source(root, after, path)
    if before_source is None or after_source is None:
        errors.append(f"{identifier}: removal YAML path is absent from a revision")
        return errors
    yaml_path = str(removal["yamlPath"])
    before_value = yaml_scalar_at_path(before_source, yaml_path)
    after_value = yaml_scalar_at_path(after_source, yaml_path)
    exact_before = [(candidate_path, value) for line, candidate_path, value
                    in yaml_scalar_rows(before_source) if line == entry.get("line")]
    if exact_before != [(yaml_path, before_value)]:
        errors.append(f"{identifier}: YAML default removal path does not identify the retired source line")
    if before_value != normalized(str(removal["beforeValue"])) \
            or before_value != normalized(str(entry.get("expression", ""))):
        errors.append(f"{identifier}: YAML default removal beforeValue does not match the exact path")
    if after_value != normalized(str(removal["afterValue"])):
        errors.append(f"{identifier}: YAML default removal afterValue does not match the exact path")
    if str(entry.get("role", "")) != yaml_path.rsplit(".", 1)[-1]:
        errors.append(f"{identifier}: YAML default removal path does not match the candidate role")

    setting = str(entry.get("setting", ""))
    replacement_owner = str(removal["replacementOwner"])
    replacement_field = str(removal["replacementField"])
    representatives = [candidate for candidate in active_entries.values()
                       if candidate.get("setting") == setting
                       and candidate.get("classification") == "operator-configurable"
                       and candidate.get("status") != "pending-review"
                       and candidate.get("owner") == replacement_owner
                       and candidate.get("field") == replacement_field]
    if not setting or not representatives:
        errors.append(f"{identifier}: YAML default removal has no active typed replacement setting")
        return errors
    authority = representatives[0].get("defaultAuthority")
    if not isinstance(authority, dict) \
            or authority.get("owner") != replacement_owner \
            or authority.get("field") != replacement_field \
            or authority.get("instanceSymbol") != removal["replacementInstanceSymbol"] \
            or normalized(str(authority.get("sourceExpression", ""))) \
            != normalized(str(removal["replacementDefaultExpression"])):
        errors.append(f"{identifier}: YAML default removal does not match active defaultAuthority")

    if "#" not in replacement_owner:
        errors.append(f"{identifier}: replacementOwner must be path#symbol")
        return errors
    owner_path, owner_symbol = replacement_owner.rsplit("#", 1)
    replacement_source = committed_source(root, after, owner_path)
    if replacement_source is None or java_type_span(replacement_source, owner_symbol) is None \
            or not java_type_declares_field(replacement_source, owner_symbol, replacement_field):
        errors.append(f"{identifier}: typed replacement owner/field is absent from afterRevision")
        return errors
    actual_default = java_record_default_expression(
        replacement_source, owner_symbol, str(removal["replacementInstanceSymbol"]), replacement_field)
    if actual_default is None or normalized(actual_default) \
            != normalized(str(removal["replacementDefaultExpression"])):
        errors.append(f"{identifier}: typed replacement default has drifted in afterRevision")
    return errors


def conversion_evidence_errors(identifier: str, entry: dict[str, object],
                               conversion: dict[str, object], before_source: str | None,
                               after_source: str | None) -> list[str]:
    """Verify that a converted setting's declared binding transition occurred in executable source."""
    if before_source is None or after_source is None:
        return []
    binding = str(conversion["binding"])
    binding_symbol = str(conversion["bindingSymbol"])
    field = str(conversion["field"])
    before_expression = normalized(str(conversion["beforeExpression"]))
    after_expression = normalized(str(conversion["afterExpression"]))
    before_code = normalized(strip_c_comments_and_literals(before_source))
    after_code = normalized(strip_c_comments_and_literals(after_source))
    errors: list[str] = []
    bindings = entry.get("bindings", [])
    if not isinstance(bindings, list) or binding not in bindings:
        errors.append(f"{identifier}: conversion binding is not declared by the setting")
    if field not in before_expression or field not in after_expression:
        errors.append(f"{identifier}: conversion expressions must both identify the setting field")
    if binding_symbol not in after_expression:
        errors.append(f"{identifier}: conversion afterExpression must use bindingSymbol")
    if before_expression == after_expression:
        errors.append(f"{identifier}: conversion expressions must be distinct")
    if before_expression not in before_code or before_expression in after_code:
        errors.append(f"{identifier}: conversion beforeExpression does not identify the replaced source")
    if after_expression in before_code or after_expression not in after_code:
        errors.append(f"{identifier}: conversion afterExpression does not identify the added source")
    if re.search(rf"\b{re.escape(binding)}\b", strip_c_comments(before_source)):
        errors.append(f"{identifier}: conversion binding already exists in beforeRevision")
    if not re.search(rf"\b{re.escape(binding)}\b", strip_c_comments(after_source)):
        errors.append(f"{identifier}: conversion binding is absent from afterRevision")
    declaration = re.compile(rf"\b{re.escape(binding_symbol)}\b\s*=")
    executable_after = strip_c_comments_and_literals(after_source)
    declared_binding = any(
        re.match(rf"\s*\"{re.escape(binding)}\"", after_source[match.end():])
        for match in declaration.finditer(executable_after)
    )
    if not declared_binding:
        errors.append(f"{identifier}: conversion bindingSymbol does not declare the named binding")
    return errors


def environment_resolver_authority_errors(root: Path, identifier: str,
                                          authority: dict[str, object]) -> list[str]:
    required = {
        "kind", "path", "type", "factoryMethod", "factoryBodyDigest", "integerMethod",
        "integerBodyDigest", "dependencyBodyDigests", "validationBodyDigest",
        "validationDependencyBodyDigests", "testPath", "testType", "testMethods",
        "testMethodDigests",
    }
    if set(authority) != required:
        return [f"resolver authority {identifier} requires exactly {', '.join(sorted(required))}"]
    relative = Path(str(authority["path"]))
    type_symbol = str(authority["type"])
    if current_source_owner(root, f"{relative.as_posix()}#{type_symbol}") is None:
        return [f"resolver authority {identifier} has no tracked Java type"]
    source = (root / relative).read_text(encoding="utf-8")
    factory = str(authority["factoryMethod"])
    integer = str(authority["integerMethod"])
    errors: list[str] = []
    expected_factory = f"public static {type_symbol} {factory}(Map<String, String> environment)"
    expected_integer = (
        f"private static int {integer}(Map<String, String> environment, "
        "String name, int defaultValue)"
    )
    if java_method_header(source, type_symbol, factory) != expected_factory:
        errors.append(f"resolver authority {identifier} factory signature is unsupported")
    if java_method_header(source, type_symbol, integer) != expected_integer:
        errors.append(f"resolver authority {identifier} integer signature is unsupported")
    if java_method_digest(source, type_symbol, factory) != authority["factoryBodyDigest"]:
        errors.append(f"resolver authority {identifier} factory body digest has drifted")
    if java_method_digest(source, type_symbol, integer) != authority["integerBodyDigest"]:
        errors.append(f"resolver authority {identifier} integer body digest has drifted")
    dependencies = authority["dependencyBodyDigests"]
    reachable = java_reachable_helper_methods(source, type_symbol, (integer,))
    if not isinstance(dependencies, dict) or reachable != set() or set(dependencies) != reachable \
            or any(java_method_digest(source, type_symbol, method) != digest
                   for method, digest in dependencies.items()):
        errors.append(f"resolver authority {identifier} has incomplete integer helper dependencies")
    constructor = java_compact_constructor_span(source, type_symbol)
    if java_span_digest(source, constructor) != authority["validationBodyDigest"]:
        errors.append(f"resolver authority {identifier} compact constructor digest has drifted")
    validation_dependencies = authority["validationDependencyBodyDigests"]
    validation_reachable = java_reachable_helpers_from_span(source, type_symbol, constructor)
    if not isinstance(validation_dependencies, dict) or validation_reachable is None \
            or validation_reachable != {"positive", "burst"} \
            or set(validation_dependencies) != validation_reachable \
            or any(java_method_digest(source, type_symbol, method) != digest
                   for method, digest in validation_dependencies.items()):
        errors.append(f"resolver authority {identifier} has incomplete validation helper dependencies")
    declared_methods = java_declared_method_names(source, type_symbol)
    burst_calls = java_method_calls(source, type_symbol, "burst", declared_methods)
    if burst_calls is None or "positive" not in burst_calls:
        errors.append(f"resolver authority {identifier} burst validation no longer delegates to positive")

    test_relative = Path(str(authority["testPath"]))
    test_type = str(authority["testType"])
    if current_source_owner(root, f"{test_relative.as_posix()}#{test_type}") is None:
        errors.append(f"resolver authority {identifier} has no tracked Java test type")
    else:
        test_source = (root / test_relative).read_text(encoding="utf-8")
        methods = authority["testMethods"]
        digests = authority["testMethodDigests"]
        if not java_test_type_is_directly_runnable(test_source, test_type):
            errors.append(
                f"resolver authority {identifier} test type is not a supported runnable top-level class")
        if not java_has_exact_rate_test_imports(test_source, test_type):
            errors.append(
                f"resolver authority {identifier} test type does not bind the exact JUnit and Stream types")
        if not isinstance(methods, dict) or set(methods) != ENVIRONMENT_RESOLVER_TEST_ROLES \
                or any(not isinstance(method, str) or not method.strip()
                       for method in methods.values()) \
                or len(set(methods.values())) != len(ENVIRONMENT_RESOLVER_TEST_ROLES) \
                or not isinstance(digests, dict) or set(digests) != set(methods.values()) \
                or any(java_method_digest(test_source, test_type, method) != digests.get(method)
                       for method in methods.values()):
            errors.append(f"resolver authority {identifier} has missing rate-limit test evidence")
        else:
            parameterized = {"malformedOverflowRefusal", "nonPositiveRefusal"}
            ordinary = {
                "blankTypedDefault", "asciiTrimContract", "documentedBoundaryAcceptance",
                "relationalConstraintRefusal",
            }
            for role in parameterized:
                method = str(methods[role])
                if java_method_header(test_source, test_type, method) != f"void {method}(String name)":
                    errors.append(
                        f"resolver authority {identifier} test role {role} has unsupported signature")
                if java_method_annotations(test_source, test_type, method) != (
                        "@ParameterizedTest", f'@MethodSource("{methods["bindingEnumeration"]}")'):
                    errors.append(
                        f"resolver authority {identifier} test role {role} is not linked to its enumeration")
            for role in ordinary:
                method = str(methods[role])
                if java_method_header(test_source, test_type, method) != f"void {method}()":
                    errors.append(
                        f"resolver authority {identifier} test role {role} has unsupported signature")
                if java_method_annotations(test_source, test_type, method) != ("@Test",):
                    errors.append(
                        f"resolver authority {identifier} test role {role} is not a runnable @Test")
            blank_span = java_method_span(
                test_source, test_type, str(methods["blankTypedDefault"]),
            )
            blank_code = strip_c_comments_and_literals(
                test_source[slice(*blank_span)] if blank_span is not None else "",
            )
            if re.search(
                    rf'\b{re.escape(str(methods["bindingEnumeration"]))}\s*\(', blank_code,
            ) is None:
                errors.append(
                    f"resolver authority {identifier} blank-default test does not use its enumeration")
    return errors


def resolver_authority_errors(root: Path, authorities: object) -> list[str]:
    if not isinstance(authorities, dict):
        return ["property-bound settings require a resolverAuthorities object"]
    errors: list[str] = []
    required = ("path", "type", "integerMethod", "wholeMethod", "integerBodyDigest",
                "wholeBodyDigest", "dependencyBodyDigests", "testPath", "testType",
                "testMethods", "testMethodDigests", "compositionMethods",
                "compositionMethodDigests", "compositionLinks")
    for identifier, authority in authorities.items():
        if isinstance(authority, dict) and authority.get("kind") == "java-environment-integer-resolver-v1":
            errors.extend(environment_resolver_authority_errors(root, str(identifier), authority))
            continue
        if isinstance(authority, dict) and "kind" in authority:
            errors.append(f"resolver authority {identifier} has unsupported kind {authority['kind']}")
            continue
        if not isinstance(authority, dict) or any(key not in authority for key in required):
            errors.append(f"resolver authority {identifier} requires {', '.join(required)}")
            continue
        relative = Path(str(authority["path"]))
        source_path = root / relative
        if current_source_owner(root, f"{relative.as_posix()}#{authority['type']}") is None:
            errors.append(f"resolver authority {identifier} has no tracked Java type")
            continue
        source = source_path.read_text(encoding="utf-8")
        for key in ("integerMethod", "wholeMethod"):
            method = str(authority[key])
            digest = java_method_digest(source, str(authority["type"]), method)
            if digest != authority[f"{key.removesuffix('Method')}BodyDigest"]:
                errors.append(f"resolver authority {identifier} {method} body digest has drifted")
        integer_span = java_method_span(source, str(authority["type"]), str(authority["integerMethod"]))
        if integer_span is None or not re.search(
                rf"\b{re.escape(str(authority['wholeMethod']))}\s*\(",
                strip_c_comments_and_literals(source[slice(*integer_span)])):
            errors.append(f"resolver authority {identifier} integer helper does not delegate to whole")
        dependencies = authority["dependencyBodyDigests"]
        reachable = java_reachable_helper_methods(
            source, str(authority["type"]),
            (str(authority["integerMethod"]), str(authority["wholeMethod"])),
        )
        if not isinstance(dependencies, dict) or reachable is None or set(dependencies) != reachable \
                or any(java_method_digest(source, str(authority["type"]), method) != digest
                       for method, digest in dependencies.items()):
            errors.append(f"resolver authority {identifier} has incomplete or drifted helper dependencies")
        composition_methods = authority["compositionMethods"]
        composition_digests = authority["compositionMethodDigests"]
        composition_links = authority["compositionLinks"]
        if not isinstance(composition_methods, dict) \
                or set(composition_methods) != RESOLVER_COMPOSITION_METHOD_ROLES \
                or any(not isinstance(method, str) or not method.strip()
                       for method in composition_methods.values()) \
                or len(set(composition_methods.values())) != len(RESOLVER_COMPOSITION_METHOD_ROLES) \
                or not isinstance(composition_digests, dict) \
                or set(composition_digests) != set(composition_methods.values()) \
                or not isinstance(composition_links, dict) \
                or set(composition_links) != RESOLVER_COMPOSITION_LINK_ROLES:
            errors.append(f"resolver authority {identifier} has incomplete fallback-composition evidence")
        else:
            for method in composition_methods.values():
                if java_method_digest(source, str(authority["type"]), str(method)) \
                        != composition_digests.get(method):
                    errors.append(f"resolver authority {identifier} composition method {method} has drifted")
            for role, link in composition_links.items():
                if not isinstance(link, dict) or set(link) != {"method", "expression"} \
                        or link["method"] not in composition_methods.values() \
                        or not isinstance(link["expression"], str) or not link["expression"].strip():
                    errors.append(
                        f"resolver authority {identifier} fallback-composition link {role} is incomplete")
                    continue
                span = java_method_span(source, str(authority["type"]), str(link["method"]))
                code = normalized(strip_c_comments_and_literals(
                    source[slice(*span)] if span is not None else ""))
                if normalized(str(link["expression"])) not in code:
                    errors.append(
                        f"resolver authority {identifier} fallback-composition link {role} has drifted")
        test_relative = Path(str(authority["testPath"]))
        if current_source_owner(root, f"{test_relative.as_posix()}#{authority['testType']}") is None:
            errors.append(f"resolver authority {identifier} has no tracked Java test type")
        else:
            test_source = (root / test_relative).read_text(encoding="utf-8")
            methods = authority["testMethods"]
            digests = authority["testMethodDigests"]
            if not isinstance(methods, dict) or set(methods) != RESOLVER_TEST_ROLES \
                    or any(not isinstance(method, str) or not method for method in methods.values()) \
                    or not isinstance(digests, dict) or set(digests) != set(methods.values()) or any(
                    java_method_digest(test_source, str(authority["testType"]), method)
                    != digests.get(method) for method in methods.values()):
                errors.append(f"resolver authority {identifier} has missing precedence/refusal test evidence")
    return errors


def legacy_graph_environment_errors(setting: str, contract: dict[str, object],
                                    setting_entries: list[dict[str, object]],
                                    discovered: dict[str, Candidate]) -> list[str] | None:
    bindings = contract.get("bindings", [])
    environment = str(bindings[0]) if isinstance(bindings, list) and len(bindings) == 1 else ""
    authority = (setting, str(contract.get("owner", "")), str(contract.get("field", "")), environment)
    if authority not in LEGACY_GRAPH_ENVIRONMENT_AUTHORITIES:
        return None
    coverage = contract.get("coverageEvidence")
    if not isinstance(coverage, dict) or coverage.get("kind") != "graph-platform-carriers-v1":
        return [f"{setting}: legacy graph binding exemption requires complete graph carrier evidence"]
    source_path = str(contract["owner"]).rsplit("#", 1)[0]
    expected_ids = {
        candidate.id for candidate in discovered.values()
        if candidate.kind == "environment-binding" and candidate.expression == environment
        and candidate.path == source_path
    }
    for field in ("composeCandidateIds", "helmTemplateCandidateIds",
                  "helmSchemaEnvironmentCandidateIds", "rawKubernetesCandidateIds"):
        identifiers = coverage.get(field, [])
        if isinstance(identifiers, list):
            expected_ids.update(str(identifier) for identifier in identifiers)
    assigned_ids = {
        str(entry["id"]) for entry in setting_entries
        if entry.get("kind") == "environment-binding"
    }
    if assigned_ids != expected_ids or any(
            candidate_id not in discovered
            or discovered[candidate_id].expression != environment
            for candidate_id in expected_ids):
        return [f"{setting}: legacy graph environment candidate set is incomplete or contains an alien candidate"]
    return []


def deployment_carrier_evidence_errors(setting: str, contract: dict[str, object],
                                       entries: dict[str, dict[str, object]],
                                       discovered: dict[str, Candidate]) -> tuple[list[str], set[str]]:
    evidence = contract.get("carrierEvidence")
    required = {"kind", "environment", "expectedCandidateIds"}
    if not isinstance(evidence, dict) or set(evidence) != required:
        return ([f"{setting}: environment-bound setting requires exact carrierEvidence fields"], set())
    errors: list[str] = []
    if evidence["kind"] != "deployment-environment-carriers-v1":
        errors.append(f"{setting}: unsupported carrierEvidence kind")
    bindings = contract.get("bindings", [])
    environment = str(evidence["environment"])
    if not isinstance(bindings, list) or bindings != [environment]:
        errors.append(f"{setting}: carrier environment must be the setting's sole binding")
    expected = evidence["expectedCandidateIds"]
    if not isinstance(expected, dict) or set(expected) != set(DEPLOYMENT_ENVIRONMENT_CARRIER_PATHS):
        return (errors + [f"{setting}: carrierEvidence must retain every checker-owned carrier group"], set())
    accounted: set[str] = set()
    for group, paths in DEPLOYMENT_ENVIRONMENT_CARRIER_PATHS.items():
        actual_ids = sorted(
            candidate.id for candidate in discovered.values()
            if candidate.path in paths and candidate.kind == "environment-binding"
            and candidate.expression == environment
        )
        declared = expected[group]
        if not isinstance(declared, list) or [str(identifier) for identifier in declared] != actual_ids:
            errors.append(f"{setting}: {group} carrier candidate set has drifted")
            continue
        accounted.update(actual_ids)
        if any(entries.get(identifier, {}).get("setting") != setting for identifier in actual_ids):
            errors.append(f"{setting}: {group} carrier candidate is absent or assigned elsewhere")
    return errors, accounted


def environment_binding_authority_errors(root: Path, setting: str, contract: dict[str, object],
                                         setting_entries: list[dict[str, object]],
                                         entries: dict[str, dict[str, object]],
                                         discovered: dict[str, Candidate],
                                         resolver_authorities: object) -> list[str]:
    required = {
        "kind", "sourceOwner", "method", "constructorType", "component", "componentIndex",
        "helper", "environmentCandidateId", "sourceEnvironmentCandidateIds", "environment",
        "defaultAccessor", "valueTransform", "callDigest", "resolverAuthority",
    }
    authority = contract.get("bindingAuthority")
    if not isinstance(authority, dict) or set(authority) != required:
        return [f"{setting}: environment-bound setting requires exact bindingAuthority fields"]
    errors: list[str] = []
    if authority["kind"] != "java-environment-constructor-v1":
        errors.append(f"{setting}: unsupported environment bindingAuthority kind")
    source_owner = str(authority["sourceOwner"])
    if source_owner != contract.get("owner"):
        errors.append(f"{setting}: environment binding sourceOwner must equal the typed setting owner")
        return errors
    resolved = current_source_owner(root, source_owner)
    if resolved is None or resolved[0].suffix != ".java":
        return errors + [f"{setting}: environment binding sourceOwner must be a tracked Java record"]
    source_path, source_type = resolved
    source = (root / source_path).read_text(encoding="utf-8")
    components = java_record_components(source, source_type)
    component = str(authority["component"])
    index = authority["componentIndex"]
    if not isinstance(index, int) or isinstance(index, bool) or not 0 <= index < len(components) \
            or components[index] != component or component != contract.get("field"):
        errors.append(f"{setting}: environment binding component index/field has drifted")
        return errors
    if authority["constructorType"] != source_type:
        errors.append(f"{setting}: environment constructorType must be the exact owner record")
        return errors
    method = str(authority["method"])
    call = java_constructor_component_call(source, source_type, method, source_type, components, component)
    if call is None:
        return errors + [f"{setting}: environment binding has no unique constructor-position call"]
    argument, start, end = call
    environment = str(authority["environment"])
    identity = re.fullmatch(
        rf'integer\(environment, "{re.escape(environment)}", DEFAULTS\.{re.escape(component)}\)',
        argument,
    )
    duration = re.fullmatch(
        rf'Duration\.ofSeconds\(integer\(environment, "{re.escape(environment)}", '
        rf'\(int\) DEFAULTS\.{re.escape(component)}\.toSeconds\(\)\)\)',
        argument,
    )
    expected_transform = "identity" if identity else "duration-seconds" if duration else None
    expected_accessor = (f"DEFAULTS.{component}" if identity
                         else f"(int) DEFAULTS.{component}.toSeconds()" if duration else None)
    if authority["helper"] != "integer" or authority["valueTransform"] != expected_transform \
            or authority["defaultAccessor"] != expected_accessor:
        errors.append(f"{setting}: constructor component is not a supported environment integer call")
    if hashlib.sha256(argument.encode("utf-8")).hexdigest() != authority["callDigest"]:
        errors.append(f"{setting}: environment binding callDigest has drifted")
    if contract.get("bindings") != [environment]:
        errors.append(f"{setting}: environment binding must be the setting's sole binding")
    constructor_ids = candidate_ids_in_source_span(
        source_path, source, start, end, "environment-binding", environment, discovered,
    )
    if constructor_ids != [str(authority["environmentCandidateId"])]:
        errors.append(f"{setting}: constructor environment candidate is not the exact component literal")
    source_ids = sorted(
        candidate.id for candidate in discovered.values()
        if candidate.path == source_path.as_posix() and candidate.kind == "environment-binding"
        and candidate.expression == environment
    )
    declared_source_ids = authority["sourceEnvironmentCandidateIds"]
    if not isinstance(declared_source_ids, list) \
            or [str(identifier) for identifier in declared_source_ids] != source_ids:
        errors.append(f"{setting}: source environment candidate partition has drifted")
    carrier_errors, carrier_ids = deployment_carrier_evidence_errors(
        setting, contract, entries, discovered,
    )
    errors.extend(carrier_errors)
    assigned_ids = {
        str(entry["id"]) for entry in setting_entries
        if entry.get("kind") == "environment-binding"
    }
    if assigned_ids != set(source_ids) | carrier_ids:
        errors.append(f"{setting}: assigned environment candidates are not fully partitioned")
    resolver = str(authority["resolverAuthority"])
    if not isinstance(resolver_authorities, dict) or resolver not in resolver_authorities:
        errors.append(f"{setting}: environment bindingAuthority references an absent resolver authority")
    else:
        resolved_authority = resolver_authorities[resolver]
        if not isinstance(resolved_authority, dict) \
                or resolved_authority.get("kind") != "java-environment-integer-resolver-v1" \
                or resolved_authority.get("path") != source_path.as_posix() \
                or resolved_authority.get("type") != source_type \
                or resolved_authority.get("factoryMethod") != method \
                or resolved_authority.get("integerMethod") != authority["helper"]:
            errors.append(f"{setting}: environment resolver authority does not match the binding source")
    return errors


def dual_source_binding_authority_errors(root: Path, setting: str, contract: dict[str, object],
                                        entries: dict[str, dict[str, object]],
                                        discovered: dict[str, Candidate],
                                        resolver_authorities: object) -> list[str]:
    bindings = contract.get("bindings", [])
    property_candidates = [entry for entry in entries.values()
                           if entry.get("setting") == setting and entry.get("kind") == "property-binding"]
    authority = contract.get("bindingAuthority")
    if not property_candidates:
        return [] if authority is None else [f"{setting}: bindingAuthority exists without a property candidate"]
    required = ("kind", "sourceOwner", "method", "constructorType", "component", "helper",
                "propertyCandidateId", "property", "environmentCandidateId", "environment",
                "defaultAccessor", "callDigest", "resolverAuthority")
    if not isinstance(authority, dict) or any(not isinstance(authority.get(key), str)
                                              or not str(authority[key]).strip() for key in required):
        return [f"{setting}: property-bound setting requires atomic bindingAuthority fields {', '.join(required)}"]
    errors: list[str] = []
    if authority["kind"] != "java-dual-source-constructor-v1":
        errors.append(f"{setting}: unsupported bindingAuthority kind")
    source_owner = str(authority["sourceOwner"])
    resolved_source = current_source_owner(root, source_owner)
    if resolved_source is None or resolved_source[0].suffix != ".java":
        errors.append(f"{setting}: bindingAuthority sourceOwner must be a tracked Java type")
        return errors
    source_path, source_type = resolved_source
    owner = current_source_owner(root, str(contract.get("owner", "")))
    component = str(authority["component"])
    if owner is None or owner[0].suffix != ".java" or component != contract.get("field"):
        errors.append(f"{setting}: bindingAuthority component must match its Java setting owner")
        return errors
    owner_source = (root / owner[0]).read_text(encoding="utf-8")
    components = java_record_components(owner_source, owner[1])
    if str(authority["constructorType"]).rsplit(".", 1)[-1] != owner[1]:
        errors.append(f"{setting}: bindingAuthority constructorType does not match its setting owner")
        return errors
    call = java_constructor_component_call(
        (root / source_path).read_text(encoding="utf-8"), source_type, str(authority["method"]),
        str(authority["constructorType"]), components, component,
    )
    if call is None:
        errors.append(f"{setting}: bindingAuthority has no unique constructor-position call")
        return errors
    argument, start, end = call
    pattern = re.compile(
        r'^(integer|whole)\(properties, environment, "([^"]+)", "([^"]+)", '
        rf'defaults\.{re.escape(component)}\(\)\)$'
    )
    parsed = pattern.fullmatch(argument)
    if parsed is None or parsed.group(1) != authority["helper"]:
        errors.append(f"{setting}: constructor component is not a supported direct integer/whole authority call")
        return errors
    helper, property_name, environment_name = parsed.groups()
    expected_accessor = f"defaults.{component}()"
    if (property_name != authority["property"] or environment_name != authority["environment"]
            or authority["defaultAccessor"] != expected_accessor):
        errors.append(f"{setting}: bindingAuthority literals/default accessor do not match the direct call")
    digest = hashlib.sha256(argument.encode("utf-8")).hexdigest()
    if digest != authority["callDigest"]:
        errors.append(f"{setting}: bindingAuthority callDigest has drifted")
    if not isinstance(bindings, list) or sorted(str(value) for value in bindings) != sorted(
            (property_name, environment_name)):
        errors.append(f"{setting}: bindings do not equal the constructor authority pair")
    source = (root / source_path).read_text(encoding="utf-8")
    expected_candidates = (
        (str(authority["propertyCandidateId"]), "property-binding", property_name),
        (str(authority["environmentCandidateId"]), "environment-binding", environment_name),
    )
    component_candidate_ids: dict[str, list[str]] = {}
    for candidate_id, kind, name in expected_candidates:
        candidate = discovered.get(candidate_id)
        entry = entries.get(candidate_id)
        if candidate is None or entry is None or entry.get("setting") != setting:
            errors.append(f"{setting}: binding authority candidate is absent or assigned elsewhere: {candidate_id}")
            continue
        actual_ids = candidate_ids_in_source_span(
            source_path, source, start, end, kind, name, discovered,
        )
        component_candidate_ids[kind] = actual_ids
        if candidate.path != source_path.as_posix() or candidate.kind != kind or candidate.expression != name \
                or actual_ids != [candidate_id]:
            errors.append(f"{setting}: binding candidate is not the literal in its constructor component: {candidate_id}")
    assigned_property_ids = {str(entry["id"]) for entry in property_candidates}
    if assigned_property_ids != {str(authority["propertyCandidateId"])}:
        errors.append(f"{setting}: every property candidate must be the one atomic binding authority")
    assigned_environment_ids = {
        candidate_id for candidate_id in component_candidate_ids.get("environment-binding", [])
        if entries.get(candidate_id, {}).get("setting") == setting
    }
    if assigned_environment_ids != {str(authority["environmentCandidateId"])}:
        errors.append(f"{setting}: constructor binding authority must select exactly one environment candidate")
    resolver = str(authority["resolverAuthority"])
    if not isinstance(resolver_authorities, dict) or resolver not in resolver_authorities:
        errors.append(f"{setting}: bindingAuthority references an absent resolver authority")
    else:
        resolver_contract = resolver_authorities[resolver]
        if not isinstance(resolver_contract, dict) or helper not in {
                resolver_contract.get("integerMethod"), resolver_contract.get("wholeMethod")}:
            errors.append(f"{setting}: binding helper is outside its resolver authority")
        elif resolver_contract.get("path") != source_path.as_posix() \
                or resolver_contract.get("type") != source_type:
            errors.append(f"{setting}: resolver authority must be the binding source type")
    return errors


def binding_authority_errors(root: Path, setting: str, contract: dict[str, object],
                             setting_entries: list[dict[str, object]],
                             entries: dict[str, dict[str, object]],
                             discovered: dict[str, Candidate],
                             resolver_authorities: object) -> list[str]:
    property_candidates = [entry for entry in setting_entries if entry.get("kind") == "property-binding"]
    environment_candidates = [
        entry for entry in setting_entries if entry.get("kind") == "environment-binding"
    ]
    if property_candidates:
        return dual_source_binding_authority_errors(
            root, setting, contract, entries, discovered, resolver_authorities,
        )
    if not environment_candidates:
        return ([] if contract.get("bindingAuthority") is None
                else [f"{setting}: bindingAuthority exists without a binding candidate"])
    legacy = legacy_graph_environment_errors(setting, contract, setting_entries, discovered)
    if legacy is not None and contract.get("bindingAuthority") is None:
        return legacy
    return environment_binding_authority_errors(
        root, setting, contract, setting_entries, entries, discovered, resolver_authorities,
    )


def environment_resolver_group_errors(root: Path,
                                      representatives: dict[str, dict[str, object]],
                                      resolver_authorities: object) -> list[str]:
    """Require one reviewed authority for every component of each supported env-only factory."""
    if not isinstance(resolver_authorities, dict):
        return (["environment-bound settings require a resolverAuthorities object"]
                if any(isinstance(item.get("bindingAuthority"), dict)
                       and item["bindingAuthority"].get("kind") == "java-environment-constructor-v1"
                       for item in representatives.values()) else [])
    groups: dict[str, list[tuple[str, dict[str, object], dict[str, object]]]] = {}
    for setting, contract in representatives.items():
        authority = contract.get("bindingAuthority")
        if isinstance(authority, dict) and authority.get("kind") == "java-environment-constructor-v1":
            groups.setdefault(str(authority.get("resolverAuthority", "")), []).append(
                (setting, contract, authority),
            )
    errors: list[str] = []
    environment_resolvers = {
        str(identifier) for identifier, authority in resolver_authorities.items()
        if isinstance(authority, dict)
        and authority.get("kind") == "java-environment-integer-resolver-v1"
    }
    if set(groups) != environment_resolvers:
        errors.append("environment resolver authorities must be referenced by one exact component set")
    for resolver_id, contracts in groups.items():
        resolver = resolver_authorities.get(resolver_id)
        if not isinstance(resolver, dict):
            continue
        path = Path(str(resolver.get("path", "")))
        type_symbol = str(resolver.get("type", ""))
        if current_source_owner(root, f"{path.as_posix()}#{type_symbol}") is None:
            continue
        source = (root / path).read_text(encoding="utf-8")
        components = java_record_components(source, type_symbol)
        actual = {(item[2].get("componentIndex"), item[2].get("component")) for item in contracts}
        expected = set(enumerate(components))
        if actual != expected or len(contracts) != len(components):
            errors.append(f"resolver authority {resolver_id} does not bijectively cover every record component")
        environments = [str(item[2].get("environment", "")) for item in contracts]
        if len(set(environments)) != len(components):
            errors.append(f"resolver authority {resolver_id} environment bindings are not one-to-one")
        if any(item[2].get("sourceOwner") != item[1].get("owner")
               or item[2].get("method") != resolver.get("factoryMethod")
               for item in contracts):
            errors.append(f"resolver authority {resolver_id} component ownership/factory has drifted")
        test_methods = resolver.get("testMethods", {})
        enumeration = (test_methods.get("bindingEnumeration")
                       if isinstance(test_methods, dict) else None)
        test_path = Path(str(resolver.get("testPath", "")))
        test_type = str(resolver.get("testType", ""))
        test_source = ((root / test_path).read_text(encoding="utf-8")
                       if current_source_owner(root, f"{test_path.as_posix()}#{test_type}") is not None
                       else "")
        if enumeration and java_method_header(test_source, test_type, str(enumeration)) \
                != f"private static Stream<String> {enumeration}()":
            errors.append(
                f"resolver authority {resolver_id} binding enumeration has unsupported factory signature")
        enumerated = (java_direct_stream_string_return(
            test_source, test_type, str(enumeration),
        ) if enumeration else None)
        if enumerated is None or Counter(enumerated) != Counter(environments) \
                or len(enumerated) != len(environments):
            errors.append(
                f"resolver authority {resolver_id} binding enumeration is not one direct exact Stream.of literal list")
    return errors


def default_authority_errors(root: Path, setting: str, contract: dict[str, object],
                             entries: dict[str, dict[str, object]],
                             discovered: dict[str, Candidate]) -> list[str]:
    property_bound = any(entry.get("setting") == setting and entry.get("kind") == "property-binding"
                         for entry in entries.values())
    binding_authority = contract.get("bindingAuthority")
    environment_bound = isinstance(binding_authority, dict) \
        and binding_authority.get("kind") == "java-environment-constructor-v1"
    authority = contract.get("defaultAuthority")
    if authority is None:
        return ([f"{setting}: bound setting requires defaultAuthority"]
                if property_bound or environment_bound else [])
    required = {"owner", "instanceSymbol", "field", "sourceExpression", "candidateIds"}
    if environment_bound:
        required.update({"componentIndex", "evaluatedDefault"})
    if not isinstance(authority, dict) or not required.issubset(authority):
        return [f"{setting}: defaultAuthority requires {', '.join(sorted(required))}"]
    if environment_bound and set(authority) != required:
        return [f"{setting}: environment defaultAuthority requires exactly {', '.join(sorted(required))}"]
    errors: list[str] = []
    owner = str(authority["owner"])
    field = str(authority["field"])
    if owner != contract.get("owner") or field != contract.get("field"):
        errors.append(f"{setting}: defaultAuthority owner/field must match the setting authority")
    resolved = current_source_owner(root, owner)
    if resolved is None or resolved[0].suffix != ".java":
        errors.append(f"{setting}: defaultAuthority must resolve to a tracked Java record")
    else:
        relative, symbol = resolved
        owner_source = (root / relative).read_text(encoding="utf-8")
        actual_span = java_record_default_expression_span(
            owner_source, symbol, str(authority["instanceSymbol"]), field)
        if actual_span is None or normalized(str(authority["sourceExpression"])) != normalized(actual_span[0]):
            errors.append(f"{setting}: defaultAuthority sourceExpression does not match the record component")
        if environment_bound:
            components = java_record_components(owner_source, symbol)
            index = authority["componentIndex"]
            if not isinstance(index, int) or isinstance(index, bool) or not 0 <= index < len(components) \
                    or components[index] != field:
                errors.append(f"{setting}: defaultAuthority componentIndex has drifted")
            evaluated = evaluated_java_default(
                actual_span[0] if actual_span is not None else "",
                isinstance(binding_authority, dict)
                and binding_authority.get("valueTransform") == "duration-seconds",
            )
            declared = authority["evaluatedDefault"]
            if not isinstance(declared, dict) or set(declared) != {"kind", "value"} \
                    or declared != evaluated:
                errors.append(f"{setting}: evaluatedDefault does not match the typed Java expression")
            elif contract.get("default") != str(declared["value"]):
                errors.append(f"{setting}: default must be the canonical evaluated decimal value")
    candidate_ids = authority.get("candidateIds")
    if not isinstance(candidate_ids, list):
        errors.append(f"{setting}: defaultAuthority candidateIds must be an array")
    elif resolved is not None and resolved[0].suffix == ".java" and actual_span is not None:
        expected_ids = candidate_ids_in_source_span(
            resolved[0], owner_source, actual_span[1], actual_span[2],
            "fixed-declaration", str(authority["instanceSymbol"]), discovered,
        )
        if [str(candidate_id) for candidate_id in candidate_ids] != expected_ids or any(
                entries.get(candidate_id, {}).get("setting") != setting for candidate_id in expected_ids):
            errors.append(f"{setting}: defaultAuthority candidateIds are not the exact initializer atom multiset")
        reference = authority.get("constantReferenceAuthority")
        if expected_ids and reference is not None:
            errors.append(f"{setting}: direct default atoms cannot also use constantReferenceAuthority")
        if not expected_ids and reference is None:
            errors.append(f"{setting}: indirect default requires constantReferenceAuthority")
        if not expected_ids and reference is not None:
            errors.extend(constant_reference_authority_errors(
                root, setting, contract, authority, owner_source, discovered, entries))
    return errors


def constant_reference_authority_errors(root: Path, setting: str, contract: dict[str, object],
                                        default_authority: dict[str, object], owner_source: str,
                                        discovered: dict[str, Candidate],
                                        entries: dict[str, dict[str, object]]) -> list[str]:
    """Verify a bounded ordered static-final reference chain ending in fixed atoms."""
    reference = default_authority.get("constantReferenceAuthority")
    if not isinstance(reference, dict) or reference.get("kind") != "java-static-final-chain-v1" \
            or not isinstance(reference.get("hops"), list) or not 1 <= len(reference["hops"]) <= 4:
        return [f"{setting}: constantReferenceAuthority requires a bounded static-final hop chain"]
    previous_owner = str(default_authority["owner"])
    previous_source = owner_source
    previous_expression = str(default_authority["sourceExpression"])
    errors: list[str] = []
    terminal_ids: list[str] = []
    for index, hop in enumerate(reference["hops"]):
        required = ("owner", "field", "sourceExpression", "initializerDigest", "candidateIds")
        if not isinstance(hop, dict) or any(key not in hop for key in required) \
                or not isinstance(hop.get("candidateIds"), list):
            errors.append(f"{setting}: constant reference hop {index} is incomplete")
            return errors
        hop_owner = str(hop["owner"])
        if "#" not in hop_owner:
            errors.append(f"{setting}: constant reference hop {index} owner must be path#type")
            return errors
        resolved = current_source_owner(root, hop_owner)
        if resolved is None or resolved[0].suffix != ".java":
            errors.append(f"{setting}: constant reference hop {index} has no tracked Java owner")
            return errors
        relative, symbol = resolved
        hop_source = (root / relative).read_text(encoding="utf-8")
        field = str(hop["field"])
        if not java_constant_reference_matches(
                previous_source, previous_owner, previous_expression, hop_owner, field, hop_source):
            errors.append(f"{setting}: constant reference hop {index} does not match the preceding initializer")
        initializer = java_static_final_initializer(hop_source, symbol, field)
        if initializer is None:
            errors.append(f"{setting}: constant reference hop {index} is not one direct static-final field")
            return errors
        expression, start, end = initializer
        if normalized(str(hop["sourceExpression"])) != normalized(expression):
            errors.append(f"{setting}: constant reference hop {index} initializer has drifted")
        digest = hashlib.sha256(normalized(expression).encode("utf-8")).hexdigest()
        if hop["initializerDigest"] != digest:
            errors.append(f"{setting}: constant reference hop {index} initializer digest has drifted")
        expected_ids = candidate_ids_in_source_span(
            relative, hop_source, start, end, "fixed-declaration", field, discovered)
        actual_ids = [str(candidate_id) for candidate_id in hop["candidateIds"]]
        if actual_ids != expected_ids or any(
                entries.get(candidate_id, {}).get("setting") != setting for candidate_id in expected_ids):
            errors.append(f"{setting}: constant reference hop {index} atom multiset has drifted")
        if index < len(reference["hops"]) - 1 and expected_ids:
            errors.append(f"{setting}: nonterminal constant reference hop contains fixed atoms")
        terminal_ids = expected_ids
        previous_owner = hop_owner
        previous_source = hop_source
        previous_expression = expression
    default_evidence = contract.get("defaultEvidence")
    if not terminal_ids or not isinstance(default_evidence, list) \
            or [str(candidate_id) for candidate_id in default_evidence] != terminal_ids:
        errors.append(f"{setting}: defaultEvidence must equal the terminal constant atom multiset")
    return errors


def schema_evidence_errors(setting: str, contract: dict[str, object],
                           entries: dict[str, dict[str, object]],
                           discovered: dict[str, Candidate],
                           evidence_records: dict[str, object]) -> list[str]:
    schema = contract.get("schemaEvidence")
    if schema is None:
        return []
    required = ("candidateId", "path", "pointer", "reference", "required")
    if not isinstance(schema, dict) or any(key not in schema for key in required):
        return [f"{setting}: schemaEvidence requires {', '.join(required)}"]
    errors: list[str] = []
    candidate_id = str(schema["candidateId"])
    candidate = discovered.get(candidate_id)
    inventory_entry = entries.get(candidate_id)
    if candidate is None or inventory_entry is None:
        return [f"{setting}: schemaEvidence candidate is not current: {candidate_id}"]
    if candidate.kind != "schema-reference-binding" or candidate.path != schema["path"] \
            or candidate.role != schema["pointer"] or candidate.expression != schema["reference"]:
        errors.append(f"{setting}: schemaEvidence does not match its reference candidate")
    if inventory_entry.get("setting") != setting:
        errors.append(f"{setting}: schemaEvidence candidate is assigned to another setting")
    try:
        resolved = json.loads(str(evidence_records.get(candidate.evidence_digest, "")))
    except json.JSONDecodeError:
        resolved = {}
    if resolved.get("resolutionError") is not None or resolved.get("resolved") is None:
        errors.append(f"{setting}: schemaEvidence reference is not a resolved local edge")
    if resolved.get("pointer") != schema["pointer"] or resolved.get("reference") != schema["reference"] \
            or resolved.get("required") is not schema["required"]:
        errors.append(f"{setting}: schemaEvidence pointer/reference/required metadata has drifted")
    return errors


def graph_platform_coverage_errors(root: Path, setting: str, contract: dict[str, object],
                                   entries: dict[str, dict[str, object]],
                                   discovered: dict[str, Candidate],
                                   evidence_records: dict[str, object],
                                   tracked_paths: set[Path]) -> list[str]:
    """Verify graph carrier coverage against exact current candidates and drift-test bodies."""
    if not setting.startswith("graph."):
        return []
    coverage = contract.get("coverageEvidence")
    candidate_fields = {
        "composeCandidateIds": ("compose.yaml", "environment-binding", None, 2),
        "helmValueCandidateIds": ("deploy/helm/ravenroot/values.yaml", "configuration-scalar", '""', 1),
        "helmTemplateCandidateIds": (
            "deploy/helm/ravenroot/templates/deployment.yaml", "environment-binding", None, 1),
        "helmSchemaEnvironmentCandidateIds": (
            "deploy/helm/ravenroot/values.schema.json", "environment-binding", None, 1),
        "helmSchemaReferenceCandidateIds": (
            "deploy/helm/ravenroot/values.schema.json", "schema-reference-binding",
            "#/definitions/graphBlank", 1),
        "rawKubernetesCandidateIds": (
            "deploy/kubernetes/ravenroot.yaml", "environment-binding", None, 1),
    }
    required = ("kind", "environment", "helmPath", "contractTestPath", "contractTestDigest",
                "shellTestPath", "shellTestDigest", *candidate_fields)
    if not isinstance(coverage, dict) or any(key not in coverage for key in required):
        return [f"{setting}: graph coverageEvidence requires {', '.join(required)}"]
    errors: list[str] = []
    if coverage["kind"] != "graph-platform-carriers-v1":
        errors.append(f"{setting}: unsupported graph coverageEvidence kind")
    environment = str(coverage["environment"])
    bindings = contract.get("bindings", [])
    if not isinstance(bindings, list) or bindings != [environment]:
        errors.append(f"{setting}: graph coverage environment must be the setting's sole binding")
    helm_leaf = str(coverage["helmPath"]).rsplit(".", 1)[-1]
    for field, (path, kind, fixed_expression, count) in candidate_fields.items():
        identifiers = coverage[field]
        if not isinstance(identifiers, list) or len(identifiers) != count \
                or len(set(str(identifier) for identifier in identifiers)) != count:
            errors.append(f"{setting}: {field} must contain {count} unique candidate ids")
            continue
        for identifier in identifiers:
            candidate = discovered.get(str(identifier))
            entry = entries.get(str(identifier))
            expected_expression = fixed_expression if fixed_expression is not None else environment
            if candidate is None or entry is None or entry.get("setting") != setting:
                errors.append(f"{setting}: {field} candidate is absent or assigned elsewhere: {identifier}")
                continue
            if candidate.path != path or candidate.kind != kind or candidate.expression != expected_expression:
                errors.append(f"{setting}: {field} candidate does not match its carrier: {identifier}")
            if field == "helmValueCandidateIds" and candidate.role != helm_leaf:
                errors.append(f"{setting}: Helm value candidate does not match helmPath: {identifier}")
            if field == "helmSchemaReferenceCandidateIds":
                try:
                    schema = json.loads(str(evidence_records.get(candidate.evidence_digest, "")))
                except json.JSONDecodeError:
                    schema = {}
                if schema.get("required") is not True or schema.get("resolutionError") is not None:
                    errors.append(f"{setting}: Helm schema reference must be required and locally resolved")
    for path_field, digest_field in (("contractTestPath", "contractTestDigest"),
                                     ("shellTestPath", "shellTestDigest")):
        relative = Path(str(coverage[path_field]))
        if relative.is_absolute() or ".." in relative.parts or relative not in tracked_paths:
            errors.append(f"{setting}: {path_field} must be a tracked in-repository file")
            continue
        actual_digest = hashlib.sha256((root / relative).read_bytes()).hexdigest()
        if actual_digest != coverage[digest_field]:
            errors.append(f"{setting}: {path_field} body digest has drifted")
    return errors


ROUTE_TABLE_AUTHORITY_ID = "route-table-all-v1"
ROUTE_TABLE_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/spec/RouteTable.java")
ROUTE_DESCRIPTOR_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/spec/RouteDescriptor.java")
OPENAPI_GENERATOR_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/spec/OpenApiSpecGenerator.java")
ROUTE_TABLE_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/spec/RouteTableSpecServerAgreementTest.java")
STABLE_EDGE_ID_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/application/StableEdgeId.java")
EDGE_WIRE_BUDGET_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/application/EdgeTraversalWireBudget.java")
STABLE_EDGE_TEST_PATH = Path(
    "ravenroot/ravenroot-application-api/src/test/java/ai/ravenroot/api/application/StableEdgeIdContractTest.java")
STABLE_EDGE_WIRE_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/StableEdgeIdWireContractTest.java")
ROUTE_BOUND_CANDIDATES = {
    "oc-68d83961ae8fd9333d39": ("StableEdgeId.MAX_UTF8_BYTES",),
    "oc-87cc337254d84e793594":
        ("EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",),
    "oc-72d27bee3c7d60226c09": ("StableEdgeId.SSE_FRAME_MAX_BYTES",),
    "oc-af3a93f860fc52c46a00": (
        "StableEdgeId.MAX_UTF8_BYTES",
        "EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",
    ),
    "oc-e29595d4bc323f7da368": ("StableEdgeId.SSE_FRAME_MAX_BYTES",),
}
ROUTE_BOUND_PATHS = {
    "oc-68d83961ae8fd9333d39": "/v1/events",
    "oc-87cc337254d84e793594": "/v1/events",
    "oc-72d27bee3c7d60226c09": "/v1/events",
    "oc-af3a93f860fc52c46a00": "/v1/events/recent",
    "oc-e29595d4bc323f7da368": "/v1/events/recent",
}
ASSISTANT_CONFIGURATION_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/assistant/AssistantConfiguration.java")
ASSISTANT_CONFIGURATION_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/assistant/AssistantConfigurationTest.java")
ASSISTANT_SERVICE_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/assistant/AssistantService.java")
ASSISTANT_SERVICE_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/assistant/AssistantGraphProposalTest.java")
ASSISTANT_PLATFORM_TEST_PATH = Path("scripts/tests/test_assistant_platform_configuration.sh")
ASSISTANT_LIMIT_FAMILY_ID = "assistant-operational-limits-v1"
ASSISTANT_LIMIT_COMPONENTS = (
    "enabled", "providerId", "endpoint", "model", "credential", "egressPolicy", "timeout",
    "maxOutputTokens", "maxToolIterations", "credentialSource", "allowLocalHttp",
)
ASSISTANT_LIMIT_SETTINGS = (
    {
        "setting": "assistant.max-output-tokens", "component": "maxOutputTokens",
        "componentIndex": 7, "environmentSymbol": "MAX_OUTPUT_TOKENS_VARIABLE",
        "environment": "RAVENROOT_ASSISTANT_MAX_OUTPUT_TOKENS",
        "defaultSymbol": "DEFAULT_MAX_OUTPUT_TOKENS", "defaultValue": 16_000,
        "helmField": "maxOutputTokens",
    },
    {
        "setting": "assistant.max-tool-iterations", "component": "maxToolIterations",
        "componentIndex": 8, "environmentSymbol": "MAX_TOOL_ITERATIONS_VARIABLE",
        "environment": "RAVENROOT_ASSISTANT_MAX_TOOL_ITERATIONS",
        "defaultSymbol": "DEFAULT_MAX_TOOL_ITERATIONS", "defaultValue": 8,
        "helmField": "maxToolIterations",
    },
)
ASSISTANT_CARRIER_PATHS = {
    "compose": frozenset({"compose.yaml"}),
    "deploymentExamples": frozenset({"docs/examples/assistant/compose.override.yaml"}),
    "helm": frozenset({
        "deploy/helm/ravenroot/values.yaml", "deploy/helm/ravenroot/values.schema.json",
        "deploy/helm/ravenroot/templates/deployment.yaml",
    }),
    "rawKubernetes": frozenset({"deploy/kubernetes/ravenroot.yaml"}),
}


def java_source_candidates(relative: Path, source: str) -> tuple[tuple[int, Candidate], ...]:
    """Reproduce stable candidate IDs and retain offsets for one Java source."""
    provisional = [
        (relative.as_posix(), line_number(source, offset), symbol, kind, role, expression,
         evidence, "java", False, offset)
        for offset, symbol, kind, role, expression, evidence
        in code_candidates(relative, source, "java")
    ]
    occurrences: Counter[tuple[str, str, str, str, str]] = Counter()
    result: list[tuple[int, Candidate]] = []
    for row in sorted(provisional, key=lambda item: item[:-1]):
        path, line, symbol, kind, role, expression, evidence, surface_name, fixture, offset = row
        key = (path, symbol, kind, role, expression)
        occurrence = occurrences[key]
        occurrences[key] += 1
        evidence_digest = hashlib.sha256(evidence.encode("utf-8")).hexdigest()
        material = "\0".join(
            (path, symbol, kind, role, expression, evidence_digest, str(occurrence)))
        candidate = Candidate(
            "oc-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:20],
            path, line, symbol, kind, role, expression,
            hashlib.sha256(expression.encode("utf-8")).hexdigest(), evidence,
            evidence_digest, surface_name, fixture,
        )
        result.append((offset, candidate))
    return tuple(result)


def java_string_value(expression: str) -> str | None:
    """Decode the bounded Java string-literal subset shared with JSON escaping."""
    try:
        value = json.loads(expression)
    except json.JSONDecodeError:
        return None
    return value if isinstance(value, str) else None


def java_literal_concatenation(expression: str) -> tuple[str, tuple[tuple[int, int], ...]] | None:
    """Read one expression made only from Java double-quoted literals and plus operators."""
    token = re.compile(r'"(?:\\.|[^"\\])*"')
    cursor = 0
    values: list[str] = []
    spans: list[tuple[int, int]] = []
    for match in token.finditer(expression):
        separator = expression[cursor:match.start()]
        if values:
            if re.fullmatch(r"\s*\+\s*", separator) is None:
                return None
        elif separator.strip():
            return None
        value = java_string_value(match.group())
        if value is None:
            return None
        values.append(value)
        spans.append(match.span())
        cursor = match.end()
    if not values or expression[cursor:].strip():
        return None
    return "".join(values), tuple(spans)


def direct_factory_arguments(expression: str, factory: str) \
        -> tuple[tuple[str, int, int], ...] | None:
    """Return arguments of one expression that is exactly `factory(...)`."""
    code = strip_c_comments_and_literals(expression)
    match = re.match(rf"\s*{re.escape(factory)}\s*\(", code)
    if match is None:
        return None
    opening = code.find("(", match.start())
    parsed = split_java_arguments(expression, code, opening)
    if parsed is None or code[parsed[1] + 1:].strip():
        return None
    return tuple(parsed[0])


def route_table_descriptors(source: str) -> tuple[tuple[tuple[str, int, int], ...], ...] | None:
    """Parse the direct RouteTable.ALL List.of initializer and its nine-argument descriptors."""
    span = java_type_span(source, "RouteTable")
    if span is None or java_package(source) != "ai.ravenroot.server.spec" \
            or not exact_import_identity(source, "java.util.List") \
            or not exact_import_identity(source, "java.util.Set") \
            or not same_package_type_identity(
                source, "RouteTable", "ai.ravenroot.server.spec.RouteDescriptor") \
            or not java_has_no_simple_name_shadow(source, "RouteTable", {"List", "Set"}):
        return None
    base, limit = span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    declarations = [
        match for match in re.finditer(
            r"\bpublic\s+static\s+final\s+List\s*<\s*RouteDescriptor\s*>\s+"
            r"ALL\s*=\s*List\s*\.\s*of\s*\(", code)
        if depths[match.start()] == 1
    ]
    if len(declarations) != 1:
        return None
    opening = code.find("(", declarations[0].start())
    parsed = split_java_arguments(actual, code, opening)
    if parsed is None or re.match(r"\s*;", code[parsed[1] + 1:]) is None:
        return None
    descriptors: list[tuple[tuple[str, int, int], ...]] = []
    for _descriptor, start, end in parsed[0]:
        descriptor = actual[start:end]
        descriptor_code = strip_c_comments_and_literals(descriptor)
        match = re.match(r"\s*new\s+RouteDescriptor\s*\(", descriptor_code)
        if match is None:
            return None
        descriptor_opening = descriptor_code.find("(", match.start())
        arguments = split_java_arguments(descriptor, descriptor_code, descriptor_opening)
        if arguments is None or len(arguments[0]) != 9 \
                or descriptor_code[arguments[1] + 1:].strip():
            return None
        descriptors.append(tuple(
            (argument, base + start + argument_start, base + start + argument_end)
            for argument, argument_start, argument_end in arguments[0]
        ))
    return tuple(descriptors)


def route_table_candidate_partitions(source: str) -> tuple[
        dict[str, list[str]], list[dict[str, object]], dict[str, Candidate]] | None:
    """Bind every supported RouteTable candidate to one typed constructor position."""
    descriptors = route_table_descriptors(source)
    if descriptors is None:
        return None
    occurrences = java_source_candidates(ROUTE_TABLE_PATH, source)
    by_id = {candidate.id: candidate for _offset, candidate in occurrences}
    partitions = {role: [] for role in ("methods", "path", "summary", "successStatuses")}
    details: list[dict[str, object]] = []

    def ids_in(start: int, end: int) -> list[str]:
        return [candidate.id for offset, candidate in occurrences if start <= offset < end]

    for ordinal, arguments in enumerate(descriptors, 1):
        methods = direct_factory_arguments(arguments[0][0], "Set.of")
        path = java_literal_concatenation(arguments[1][0])
        summary = java_literal_concatenation(arguments[2][0])
        statuses = direct_factory_arguments(arguments[5][0], "Set.of")
        if methods is None or not methods or path is None or len(path[1]) != 1 or summary is None:
            return None
        method_values: list[str] = []
        for argument, _start, _end in methods:
            value = java_literal_concatenation(argument)
            if value is None or len(value[1]) != 1 or value[0] not in {"GET", "POST", "DELETE"}:
                return None
            method_values.append(value[0])
        if not path[0].startswith("/") or not summary[0].strip():
            return None
        status_arguments = statuses if statuses is not None else ((arguments[5][0], 0, len(arguments[5][0])),)
        status_values: list[int] = []
        for status, _start, _end in status_arguments:
            if re.fullmatch(r"\s*[0-9](?:_?[0-9])*\s*", status) is None:
                return None
            value = int(status.strip().replace("_", ""))
            if not 200 <= value < 300:
                return None
            status_values.append(value)
        role_arguments = {
            "methods": arguments[0], "path": arguments[1],
            "summary": arguments[2], "successStatuses": arguments[5],
        }
        role_ids = {role: ids_in(argument[1], argument[2])
                    for role, argument in role_arguments.items()}
        if any(not role_ids[role] for role in role_ids):
            return None
        for role, identifiers in role_ids.items():
            partitions[role].extend(identifiers)
        details.append({
            "ordinal": ordinal, "path": path[0], "summary": summary[0],
            "statusValues": status_values, "candidateIds": role_ids,
        })
    return partitions, details, by_id


def exact_import_identity(source: str, qualified: str) -> bool:
    """Require one exact normal import and no competing/local declaration of its simple name."""
    code = strip_c_comments_and_literals(source)
    simple = qualified.rsplit(".", 1)[-1]
    imports = re.findall(
        r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;", code)
    matching = [item for item in imports if item.rsplit(".", 1)[-1] == simple]
    return matching == [qualified] and re.search(
        rf"\b(?:class|record|enum|interface)\s+{re.escape(simple)}\b"
        rf"|@interface\s+{re.escape(simple)}\b", code,
    ) is None


def same_package_type_identity(source: str, type_symbol: str, qualified: str) -> bool:
    """Require a same-package simple type with no import, local type, or direct-value shadow."""
    package, simple = qualified.rsplit(".", 1)
    code = strip_c_comments_and_literals(source)
    imports = re.findall(
        r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;", code)
    return java_package(source) == package \
        and not any(item.rsplit(".", 1)[-1] == simple for item in imports) \
        and java_has_no_simple_name_shadow(source, type_symbol, {simple})


def java_has_no_simple_name_shadow(source: str, type_symbol: str,
                                   names: set[str]) -> bool:
    """Reject local type/direct-value/static-import bindings for reviewed simple type names."""
    code = strip_c_comments_and_literals(source)
    for name in names:
        if re.search(
                rf"\b(?:class|record|enum|interface)\s+{re.escape(name)}\b"
                rf"|@interface\s+{re.escape(name)}\b", code,
        ) is not None or java_type_declares_field(source, type_symbol, name):
            return False
    static_imports = re.findall(
        r"(?m)^\s*import\s+static\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.\*)"
        r"\s*;|^\s*import\s+static\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;",
        code,
    )
    imported = [left or right for left, right in static_imports]
    return not any(item.endswith(".*") or item.rsplit(".", 1)[-1] in names for item in imported)


def java_has_exact_junit_assertions(source: str, type_symbol: str,
                                    names: set[str]) -> bool:
    """Bind reviewed assertion calls to exact JUnit methods and reject local method/value shadows."""
    code = strip_c_comments_and_literals(source)
    static_imports = re.findall(
        r"(?m)^\s*import\s+static\s+"
        r"([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\.\*)?)\s*;", code)
    if any(item.endswith(".*") for item in static_imports):
        return False
    for name in names:
        matching = [item for item in static_imports if item.rsplit(".", 1)[-1] == name]
        if matching != [f"org.junit.jupiter.api.Assertions.{name}"] \
                or java_direct_method_declaration_count(source, type_symbol, name) != 0 \
                or java_type_declares_field(source, type_symbol, name):
            return False
    return True


def java_span_uses_only_simple_receiver(source: str, span: tuple[int, int] | None,
                                        name: str) -> bool:
    """Require every occurrence of one supported imported name to be a dotted receiver."""
    if span is None:
        return False
    code = strip_c_comments_and_literals(source)[slice(*span)]
    return all(re.match(r"\s*\.", code[match.end():]) is not None
               for match in re.finditer(rf"\b{re.escape(name)}\b", code))


def java_direct_return_expression(source: str, type_symbol: str, method: str) -> str | None:
    """Return the one direct return expression from a supported method, without comments."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    actual = strip_c_comments(source[slice(*span)])
    code = strip_c_comments_and_literals(source[slice(*span)])
    depths = java_brace_depths(code)
    returns = [match for match in re.finditer(r"\breturn\b", code)
               if depths[match.start()] == 1]
    if len(returns) != 1:
        return None
    start = returns[0].end()
    semicolon = next((offset for offset in range(start, len(code))
                      if code[offset] == ";" and depths[offset] == 1), None)
    if semicolon is None:
        return None
    return normalized(actual[start:semicolon])


def java_direct_field_has_annotation(source: str, type_symbol: str, field: str,
                                     annotation: str) -> bool:
    span = java_type_span(source, type_symbol)
    if span is None:
        return False
    code = strip_c_comments_and_literals(source)[slice(*span)]
    depths = java_brace_depths(code)
    return any(depths[match.start()] == 1 for match in re.finditer(
        rf"@{re.escape(annotation)}\s+(?:[A-Za-z_$][\w$<>?,.\[\]]*\s+)+{re.escape(field)}\s*;",
        code,
    ))


def java_int_expression_value(expression: str, resolver) -> int | None:
    """Evaluate the small checked Java integer expression grammar used by wire-bound constants."""
    tokens = re.findall(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*|[0-9](?:_?[0-9])*|[()+*/-]",
                        expression)
    if "".join(tokens) != re.sub(r"\s+", "", expression):
        return None
    position = 0

    def checked(value: int) -> int:
        if value < -2_147_483_648 or value > 2_147_483_647:
            raise ValueError
        return value

    def atom() -> int:
        nonlocal position
        if position >= len(tokens):
            raise ValueError
        token = tokens[position]
        position += 1
        if token == "(":
            value = add()
            if position >= len(tokens) or tokens[position] != ")":
                raise ValueError
            position += 1
            return value
        if re.fullmatch(r"[0-9](?:_?[0-9])*", token):
            return checked(int(token.replace("_", "")))
        resolved = resolver(token)
        if resolved is None:
            raise ValueError
        return checked(resolved)

    def multiply() -> int:
        nonlocal position
        value = atom()
        while position < len(tokens) and tokens[position] in {"*", "/"}:
            operator = tokens[position]
            position += 1
            right = atom()
            if operator == "/":
                if right == 0:
                    raise ValueError
                value = (abs(value) // abs(right)) * (-1 if (value < 0) != (right < 0) else 1)
            else:
                value *= right
            value = checked(value)
        return value

    def add() -> int:
        nonlocal position
        value = multiply()
        while position < len(tokens) and tokens[position] in {"+", "-"}:
            operator = tokens[position]
            position += 1
            right = multiply()
            value = checked(value + right if operator == "+" else value - right)
        return value

    try:
        value = add()
        return value if position == len(tokens) else None
    except ValueError:
        return None


def public_static_final_int_expression(source: str, type_symbol: str, field: str) -> str | None:
    initializer = java_static_final_initializer(source, type_symbol, field)
    span = java_type_span(source, type_symbol)
    if initializer is None or span is None:
        return None
    code = strip_c_comments_and_literals(source)[slice(*span)]
    depths = java_brace_depths(code)
    declarations = [match for match in re.finditer(
        rf"\bpublic\s+static\s+final\s+int\s+{re.escape(field)}\s*=", code,
    ) if depths[match.start()] == 1]
    return initializer[0] if len(declarations) == 1 else None


def route_bound_values(root: Path) -> dict[str, int] | None:
    sources = {
        "StableEdgeId": (STABLE_EDGE_ID_PATH, "StableEdgeId"),
        "EdgeTraversalWireBudget": (EDGE_WIRE_BUDGET_PATH, "EdgeTraversalWireBudget"),
    }
    if any(current_source_owner(root, f"{path.as_posix()}#{type_symbol}") is None
           for path, type_symbol in sources.values()):
        return None
    texts = {name: (root / path).read_text(encoding="utf-8")
             for name, (path, _type) in sources.items()}
    if not same_package_type_identity(
            texts["EdgeTraversalWireBudget"], "EdgeTraversalWireBudget",
            "ai.ravenroot.api.application.StableEdgeId"):
        return None
    cache: dict[str, int] = {}
    active: set[str] = set()

    def resolve(qualified: str, current: str | None = None) -> int | None:
        name = qualified if "." in qualified else f"{current}.{qualified}"
        if name in cache:
            return cache[name]
        if name in active or "." not in name:
            return None
        owner, field = name.split(".", 1)
        if owner not in sources or "." in field:
            return None
        expression = public_static_final_int_expression(texts[owner], owner, field)
        if expression is None:
            return None
        active.add(name)
        value = java_int_expression_value(expression, lambda token: resolve(token, owner))
        active.remove(name)
        if value is not None:
            cache[name] = value
        return value

    required = {qualified for fields in ROUTE_BOUND_CANDIDATES.values() for qualified in fields}
    result = {name: resolve(name) for name in required}
    return None if any(value is None for value in result.values()) else {
        name: int(value) for name, value in result.items()
    }


def route_table_consumer_errors(root: Path, authority: dict[str, object]) -> list[str]:
    errors: list[str] = []
    consumer_digests = authority.get("consumerBodyDigests")
    required_digests = {
        "routeDescriptorValidation", "openApiGenerate", "openApiPathEntry",
        "openApiOperationEntry", "openApiSuccessResponse",
    }
    if not isinstance(consumer_digests, dict) or set(consumer_digests) != required_digests:
        return ["RouteTable authority requires exact typed consumer body digests"]

    descriptor = (root / ROUTE_DESCRIPTOR_PATH).read_text(encoding="utf-8")
    expected_components = (
        "methods", "path", "summary", "authenticated", "registersContext", "successStatuses",
        "wireErrorCodes", "assistantPosture", "sideEffectFree",
    )
    compact = java_compact_constructor_span(descriptor, "RouteDescriptor")
    compact_code = normalized(strip_c_comments_and_literals(
        descriptor[slice(*compact)] if compact is not None else ""))
    if java_package(descriptor) != "ai.ravenroot.server.spec" \
            or java_record_components(descriptor, "RouteDescriptor") != expected_components \
            or java_span_digest(descriptor, compact) != consumer_digests["routeDescriptorValidation"]:
        errors.append("RouteTable RouteDescriptor typed component/validation digest has drifted")
    for required in (
        "path == null || path.isBlank()", "summary == null || summary.isBlank()",
        "status < 200 || status >= 300", "methods = Set.copyOf(methods)",
        "successStatuses = Set.copyOf(successStatuses)",
    ):
        if normalized(required) not in compact_code:
            errors.append(f"RouteTable RouteDescriptor validation lost {required}")
    descriptor_code = normalized(strip_c_comments_and_literals(descriptor))
    forwarding = normalized(
        "public RouteDescriptor(Set<String> methods, String path, String summary, "
        "boolean authenticated, boolean registersContext, int successStatus, "
        "List<String> wireErrorCodes, AssistantPosture assistantPosture, boolean sideEffectFree) { "
        "this(methods, path, summary, authenticated, registersContext, Set.of(successStatus), "
        "wireErrorCodes, assistantPosture, sideEffectFree); }")
    if forwarding not in descriptor_code:
        errors.append("RouteTable RouteDescriptor convenience constructor lost positional forwarding")

    generator = (root / OPENAPI_GENERATOR_PATH).read_text(encoding="utf-8")
    authoritative_methods = ("generate", "pathEntry", "operationEntry", "successResponse")
    receiver_names = {"JsonStrings", "Collectors"}
    receiver_values_are_unshadowed = all(
        java_span_uses_only_simple_receiver(
            generator, java_method_span(generator, "OpenApiSpecGenerator", method), receiver)
        for method in authoritative_methods for receiver in receiver_names
    )
    if not same_package_type_identity(
            generator, "OpenApiSpecGenerator", "ai.ravenroot.server.spec.RouteDescriptor"):
        errors.append("RouteTable OpenAPI consumer does not resolve the same-package RouteDescriptor type")
    if not exact_import_identity(generator, "ai.ravenroot.server.audit.JsonStrings") \
            or not exact_import_identity(generator, "java.util.List") \
            or not exact_import_identity(generator, "java.util.stream.Collectors") \
            or not java_has_no_simple_name_shadow(
                generator, "OpenApiSpecGenerator", receiver_names) \
            or not receiver_values_are_unshadowed:
        errors.append("RouteTable OpenAPI consumer import/receiver identity has drifted")
    generate_span = java_method_span(generator, "OpenApiSpecGenerator", "generate")
    generate_source = generator[slice(*generate_span)] if generate_span else ""
    generate_code = normalized(strip_c_comments_and_literals(generate_source))
    if java_method_header(generator, "OpenApiSpecGenerator", "generate") != \
            "public static String generate(List<RouteDescriptor> routes)" \
            or java_method_digest(generator, "OpenApiSpecGenerator", "generate") != \
            consumer_digests["openApiGenerate"]:
        errors.append("RouteTable OpenAPI generate signature/body has drifted")
    publication_chain = (
        "json.append(routes.stream().sorted(java.util.Comparator.comparing(RouteDescriptor::path))"
        ".map(OpenApiSpecGenerator::pathEntry).collect(Collectors.joining()));")
    generate_compact = re.sub(r"\s+", "", strip_c_comments_and_literals(generate_source))
    if publication_chain not in generate_compact:
        errors.append("RouteTable OpenAPI generate lost the routes-to-pathEntry append chain")
    if normalized("return json.toString()") not in generate_code:
        errors.append("RouteTable OpenAPI generate lost return json.toString()")
    for role, method, header, required in (
        ("openApiPathEntry", "pathEntry", "private static String pathEntry(RouteDescriptor route)",
         ("route.methods().stream().sorted().map(method -> operationEntry(route, method))",
          "JsonStrings.escape(route.path())", "operations")),
        ("openApiOperationEntry", "operationEntry",
         "private static String operationEntry(RouteDescriptor route, String method)",
         ("method.toLowerCase(java.util.Locale.ROOT)", "JsonStrings.escape(route.summary())",
          "route.successStatuses().stream().sorted().forEach(status -> responses.add(successResponse(route, method, status)))")),
    ):
        span = java_method_span(generator, "OpenApiSpecGenerator", method)
        code = normalized(strip_c_comments_and_literals(generator[slice(*span)] if span else ""))
        if java_method_header(generator, "OpenApiSpecGenerator", method) != header \
                or java_method_digest(generator, "OpenApiSpecGenerator", method) != consumer_digests[role]:
            errors.append(f"RouteTable typed consumer {method} signature/body has drifted")
        for expression in required:
            if normalized(expression) not in code:
                errors.append(f"RouteTable typed consumer {method} lost {expression}")
    success_span = java_method_span(generator, "OpenApiSpecGenerator", "successResponse")
    success_return = java_direct_return_expression(
        generator, "OpenApiSpecGenerator", "successResponse")
    status_prefix = normalized(
        '"          \\"" + status + "\\": {\\"description\\": \\"success\\"" +')
    if java_method_header(generator, "OpenApiSpecGenerator", "successResponse") != \
            "private static String successResponse(RouteDescriptor route, String method, int status)" \
            or java_method_digest(generator, "OpenApiSpecGenerator", "successResponse") != \
            consumer_digests["openApiSuccessResponse"] \
            or success_return is None or not success_return.startswith(status_prefix) \
            or normalized("schema == null ?") not in success_return \
            or normalized("+ schema +") not in success_return:
        errors.append("RouteTable OpenAPI successResponse lost status serialization")
    return errors


def route_publication_test_errors(root: Path, authority: dict[str, object]) -> list[str]:
    evidence = authority.get("publicationTestAuthority")
    required = {"testBodyDigest", "checkedInSpecBodyDigest"}
    if not isinstance(evidence, dict) or set(evidence) != required:
        return ["RouteTable authority requires exact publication test evidence"]
    source = (root / ROUTE_TABLE_TEST_PATH).read_text(encoding="utf-8")
    errors: list[str] = []
    test_type = "RouteTableSpecServerAgreementTest"
    method = "theCheckedInSpecMatchesWhatTheTableGeneratesRightNow"
    helper = "checkedInSpec"
    if java_package(source) != "ai.ravenroot.server.spec" \
            or not java_test_type_is_directly_runnable(source, test_type) \
            or not exact_import_identity(source, "org.junit.jupiter.api.Test") \
            or not exact_import_identity(source, "org.junit.jupiter.api.io.TempDir") \
            or not exact_import_identity(source, "java.nio.file.Path") \
            or not java_direct_field_has_annotation(source, test_type, "uiDirectory", "TempDir") \
            or not same_package_type_identity(
                source, test_type, "ai.ravenroot.server.spec.RouteTable") \
            or not same_package_type_identity(
                source, test_type, "ai.ravenroot.server.spec.OpenApiSpecGenerator") \
            or not java_has_exact_junit_assertions(
                source, test_type, {"assertEquals", "assertTrue"}):
        errors.append("RouteTable publication test type/import/TempDir identity has drifted")
    if java_method_header(source, test_type, method) != f"void {method}() throws Exception" \
            or java_method_annotations(source, test_type, method) != ("@Test",) \
            or java_method_digest(source, test_type, method) != evidence["testBodyDigest"]:
        errors.append("RouteTable publication parity test is not an exact runnable @Test")
    span = java_method_span(source, test_type, method)
    code = normalized(strip_c_comments_and_literals(source[slice(*span)] if span else ""))
    for expression in (
        "OpenApiSpecGenerator.generate(RouteTable.ALL)", "checkedInSpec()",
        "assertEquals(generatedNow, onDisk",
    ):
        if normalized(expression) not in code:
            errors.append(f"RouteTable publication parity test lost {expression}")
    helper_span = java_method_span(source, test_type, helper)
    helper_code = normalized(strip_c_comments_and_literals(
        source[slice(*helper_span)] if helper_span else ""))
    if java_method_header(source, test_type, helper) != \
            "private static String checkedInSpec() throws IOException" \
            or java_method_digest(source, test_type, helper) != evidence["checkedInSpecBodyDigest"] \
            or "getResourceAsStream(" not in helper_code or "readAllBytes()" not in helper_code:
        errors.append("RouteTable checkedInSpec helper closure has drifted")
    return errors


def route_bound_test_errors(root: Path, authority: dict[str, object]) -> list[str]:
    evidence = authority.get("boundTestBodyDigests")
    required = {
        "StableEdgeIdContractTest": {
            "acceptsTheExactUtf8BoundWithoutChangingIdentityAndRejectsOneByteMore",
            "auxiliaryReserveIsEnforcedAsOneCombinedEscapedByteBudget",
        },
        "StableEdgeIdWireContractTest": {
            "worstCaseEscapedMaximumFitsTheCompleteRuntimeClientFrame",
            "saturatedLiveAndLogFieldsStillFitWithTheMaximumEscapedIdentity",
            "saturatedDurableProjectionAndPayloadStayInsideTheirExplicitBounds",
        },
    }
    if not isinstance(evidence, dict) or set(evidence) != set(required):
        return ["RouteTable authority requires exact typed-bound test evidence"]
    errors: list[str] = []
    for test_type, methods in required.items():
        relative = STABLE_EDGE_TEST_PATH if test_type == "StableEdgeIdContractTest" \
            else STABLE_EDGE_WIRE_TEST_PATH
        source = (root / relative).read_text(encoding="utf-8")
        recorded = evidence.get(test_type)
        type_names = {"StableEdgeId", "EdgeTraversalWireBudget"}
        same_package = test_type == "StableEdgeIdContractTest"
        imports_are_exact = (java_package(source) == "ai.ravenroot.api.application"
                             and same_package_type_identity(
                                 source, test_type,
                                 "ai.ravenroot.api.application.StableEdgeId")
                             and same_package_type_identity(
                                 source, test_type,
                                 "ai.ravenroot.api.application.EdgeTraversalWireBudget")
                             if same_package else
                             exact_import_identity(source, "ai.ravenroot.api.application.StableEdgeId")
                             and exact_import_identity(
                                 source, "ai.ravenroot.api.application.EdgeTraversalWireBudget"))
        if not isinstance(recorded, dict) or set(recorded) != methods \
                or not java_test_type_is_directly_runnable(source, test_type) \
                or not exact_import_identity(source, "org.junit.jupiter.api.Test") \
                or not imports_are_exact \
                or not java_has_no_simple_name_shadow(source, test_type, type_names) \
                or not java_has_exact_junit_assertions(
                    source, test_type, {"assertEquals", "assertThrows", "assertTrue"}):
            errors.append(f"RouteTable typed-bound test authority {test_type} is incomplete")
            continue
        for method in methods:
            if java_method_header(source, test_type, method) != f"void {method}()" \
                    or java_method_annotations(source, test_type, method) != ("@Test",) \
                    or java_method_digest(source, test_type, method) != recorded[method]:
                errors.append(f"RouteTable typed-bound test {test_type}.{method} has drifted")
                continue
            span = java_method_span(source, test_type, method)
            code = normalized(strip_c_comments_and_literals(
                source[slice(*span)] if span is not None else ""))
            structural = {
                "acceptsTheExactUtf8BoundWithoutChangingIdentityAndRejectsOneByteMore": (
                    "StableEdgeId.MAX_UTF8_BYTES", "assertEquals", "assertThrows"),
                "auxiliaryReserveIsEnforcedAsOneCombinedEscapedByteBudget": (
                    "EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",
                    "requireLiveProjection", "requireDurableProjection", "assertThrows"),
                "worstCaseEscapedMaximumFitsTheCompleteRuntimeClientFrame": (
                    "StableEdgeId.MAX_UTF8_BYTES", "StableEdgeId.SSE_FRAME_MAX_BYTES",
                    "liveFrame.length", "assertTrue"),
                "saturatedLiveAndLogFieldsStillFitWithTheMaximumEscapedIdentity": (
                    "EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",
                    "StableEdgeId.SSE_FRAME_MAX_BYTES", "liveFrame.length", "logLine.length"),
                "saturatedDurableProjectionAndPayloadStayInsideTheirExplicitBounds": (
                    "EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",
                    "StableEdgeId.SSE_FRAME_MAX_BYTES", "durableFrame.length", "assertEquals"),
            }[method]
            for expression in structural:
                if expression not in code:
                    errors.append(
                        f"RouteTable typed-bound test {test_type}.{method} lost {expression}")
    return errors


def route_table_authority_errors(root: Path, authorities: object,
                                 entries: dict[str, dict[str, object]],
                                 discovered: dict[str, Candidate]) -> list[str]:
    """Verify the closed RouteTable retained-candidate family and publication evidence."""
    reviewed = [entry for entry in entries.values()
                if entry.get("path") == ROUTE_TABLE_PATH.as_posix()
                and entry.get("status") != "pending-review"]
    if not reviewed:
        return [] if authorities in (None, {}) else ["RouteTable authority exists without reviewed rows"]
    if not isinstance(authorities, dict) or set(authorities) != {ROUTE_TABLE_AUTHORITY_ID}:
        return ["reviewed RouteTable rows require the one closed RouteTable authority"]
    authority = authorities[ROUTE_TABLE_AUTHORITY_ID]
    required = {
        "kind", "candidateIdsByRole", "descriptorCandidateIds", "consumerBodyDigests",
        "publicationTestAuthority", "boundTestBodyDigests", "publishedBoundClauses",
    }
    if not isinstance(authority, dict) or set(authority) != required \
            or authority.get("kind") != "java-route-descriptor-publication-v1":
        return ["RouteTable authority has an unsupported or incomplete shape"]
    required_sources = {
        ROUTE_TABLE_PATH: "RouteTable", ROUTE_DESCRIPTOR_PATH: "RouteDescriptor",
        OPENAPI_GENERATOR_PATH: "OpenApiSpecGenerator",
        ROUTE_TABLE_TEST_PATH: "RouteTableSpecServerAgreementTest",
        STABLE_EDGE_ID_PATH: "StableEdgeId", EDGE_WIRE_BUDGET_PATH: "EdgeTraversalWireBudget",
        STABLE_EDGE_TEST_PATH: "StableEdgeIdContractTest",
        STABLE_EDGE_WIRE_TEST_PATH: "StableEdgeIdWireContractTest",
    }
    if any(current_source_owner(root, f"{path.as_posix()}#{symbol}") is None
           for path, symbol in required_sources.items()):
        return ["RouteTable authority has a missing tracked source/test owner"]
    source = (root / ROUTE_TABLE_PATH).read_text(encoding="utf-8")
    if not same_package_type_identity(
            source, "RouteTable", "ai.ravenroot.server.spec.RouteDescriptor"):
        return ["RouteTable.ALL does not resolve the same-package RouteDescriptor type"]
    parsed = route_table_candidate_partitions(source)
    if parsed is None:
        return ["RouteTable.ALL is not the supported direct RouteDescriptor table"]
    partitions, details, source_candidates = parsed
    errors: list[str] = []
    expected_counts = {"methods": 60, "path": 53, "summary": 341, "successStatuses": 54}
    if len(details) != 53 or {role: len(ids) for role, ids in partitions.items()} != expected_counts:
        errors.append("RouteTable authority no longer has the reviewed 53/508 positional shape")
    recorded = authority["candidateIdsByRole"]
    if not isinstance(recorded, dict) or set(recorded) != set(expected_counts) \
            or any(recorded.get(role) != partitions[role] for role in expected_counts):
        errors.append("RouteTable authority candidate positional partitions have drifted")
    descriptor_evidence = [
        {"ordinal": detail["ordinal"], "path": detail["path"],
         "candidateIds": detail["candidateIds"]}
        for detail in details
    ]
    if authority["descriptorCandidateIds"] != descriptor_evidence:
        errors.append("RouteTable authority descriptor ordinal/path candidate positions have drifted")
    all_ids = [identifier for values in partitions.values() for identifier in values]
    if len(all_ids) != len(set(all_ids)) or set(all_ids) != set(source_candidates):
        errors.append("RouteTable authority does not partition every RouteTable candidate exactly once")
    expected_classification = {
        **{identifier: "protocol-or-format-invariant"
           for role in ("methods", "path", "successStatuses") for identifier in partitions[role]},
        **{identifier: "published-contract-description" for identifier in partitions["summary"]},
    }
    for identifier, classification in expected_classification.items():
        entry = entries.get(identifier)
        if entry is None or entry.get("classification") != classification \
                or entry.get("status") != "retained" \
                or entry.get("retainedAuthority") != ROUTE_TABLE_AUTHORITY_ID:
            errors.append(f"RouteTable candidate {identifier} lacks its exact retained positional authority")
        if identifier not in discovered or discovered[identifier].path != ROUTE_TABLE_PATH.as_posix():
            errors.append(f"RouteTable candidate {identifier} is absent from current discovery")
    if set(expected_classification) != {str(entry.get("id")) for entry in reviewed}:
        errors.append("RouteTable reviewed rows do not equal the complete supported candidate family")

    clauses = authority["publishedBoundClauses"]
    expected_clauses = {identifier: list(fields)
                        for identifier, fields in ROUTE_BOUND_CANDIDATES.items()}
    if clauses != expected_clauses:
        errors.append("RouteTable published bound clauses do not use exact candidate-specific authorities")
    values = route_bound_values(root)
    by_path = {str(detail["path"]): str(detail["summary"]) for detail in details}
    if values is None:
        errors.append("RouteTable typed wire-bound constants are not resolvable")
    else:
        max_id = values["StableEdgeId.MAX_UTF8_BYTES"]
        auxiliary = values["EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES"]
        frame = values["StableEdgeId.SSE_FRAME_MAX_BYTES"]
        typed_clauses = {
            "/v1/events": (
                f"edgeId is accepted unchanged up to {max_id} strict UTF-8 bytes;",
                f"all auxiliary traversal strings share a {auxiliary}-byte escaped budget",
                f"the complete frame below {frame} bytes.",
            ),
            "/v1/events/recent": (
                f"edgeId is accepted unchanged up to {max_id} strict UTF-8 bytes; "
                f"auxiliary traversal strings share a {auxiliary}-byte escaped budget",
                f"keeping each frame below {frame} bytes.",
            ),
        }
        for path, expected in typed_clauses.items():
            summary = by_path.get(path, "")
            for clause in expected:
                if summary.count(clause) != 1:
                    errors.append(f"RouteTable {path} summary lost typed bound clause: {clause}")
        for identifier, fields in ROUTE_BOUND_CANDIDATES.items():
            expected_path = ROUTE_BOUND_PATHS[identifier]
            detail = next((item for item in details
                           if item["path"] == expected_path
                           and identifier in item["candidateIds"]["summary"]), None)
            candidate = source_candidates.get(identifier)
            if detail is None or candidate is None or any(
                    str(values[field]) not in candidate.expression for field in fields):
                errors.append(
                    f"RouteTable typed bound candidate {identifier} is not the exact {expected_path} clause")
    errors.extend(route_table_consumer_errors(root, authority))
    errors.extend(route_publication_test_errors(root, authority))
    errors.extend(route_bound_test_errors(root, authority))
    return errors


def assistant_limit_binding_call(source: str, component: str) -> tuple[str, int, int] | None:
    call = java_constructor_component_call(
        source, "AssistantConfiguration", "fromEnvironment", "AssistantConfiguration",
        ASSISTANT_LIMIT_COMPONENTS, component,
    )
    return call


def java_identifier_write_count(code: str, identifier: str) -> int:
    """Count direct/compound writes in one already masked Java span."""
    name = re.escape(identifier)
    writes = re.findall(
        rf"\b{name}\s*(?:>>>=|>>=|<<=|=(?!=)|[+\-*/%&|^]=|\+\+|--)"
        rf"|(?:\+\+|--)\s*\b{name}\b",
        code,
    )
    return len(writes)


def assistant_limit_source_specs(source: str) -> list[dict[str, object]] | None:
    """Derive the checker-owned two symbol-bound AssistantConfiguration components."""
    if java_package(source) != "ai.ravenroot.server.assistant" \
            or java_record_components(source, "AssistantConfiguration") != ASSISTANT_LIMIT_COMPONENTS \
            or java_method_header(source, "AssistantConfiguration", "fromEnvironment") != \
            "public static AssistantConfiguration fromEnvironment(Map<String, String> environment)" \
            or not exact_import_identity(source, "java.util.Map") \
            or not java_has_no_simple_name_shadow(
                source, "AssistantConfiguration", {"Map"}):
        return None
    factory_span = java_method_span(source, "AssistantConfiguration", "fromEnvironment")
    factory_code = strip_c_comments_and_literals(source[slice(*factory_span)] if factory_span else "")
    if len(re.findall(r"\bboundedPositiveInteger\s*\(", factory_code)) != 2 \
            or len(re.findall(
                r"\bMap\s*<\s*String\s*,\s*String\s*>\s+env\s*=\s*"
                r"environment\s*==\s*null\s*\?\s*Map\.of\s*\(\s*\)\s*:\s*environment\s*;",
                factory_code,
            )) != 1:
        return None
    protected_symbols = {
        str(setting[key]) for setting in ASSISTANT_LIMIT_SETTINGS
        for key in ("environmentSymbol", "defaultSymbol")
    }
    if java_identifier_write_count(factory_code, "env") != 1 \
            or java_identifier_write_count(factory_code, "environment") != 0 \
            or any(java_identifier_write_count(factory_code, symbol) != 0
                   for symbol in protected_symbols):
        return None
    result: list[dict[str, object]] = []
    for expected in ASSISTANT_LIMIT_SETTINGS:
        call = assistant_limit_binding_call(source, str(expected["component"]))
        if call is None:
            return None
        argument, start, end = call
        pattern = re.fullmatch(
            r"boundedPositiveInteger\(env\.get\(([A-Za-z_$][\w$]*)\),\s*"
            r"([A-Za-z_$][\w$]*),\s*([A-Za-z_$][\w$]*)\)", argument,
        )
        if pattern is None or pattern.group(1) != pattern.group(2):
            return None
        env_symbol, default_symbol = pattern.group(1), pattern.group(3)
        env_initializer = java_static_final_initializer(source, "AssistantConfiguration", env_symbol)
        default_initializer = java_static_final_initializer(
            source, "AssistantConfiguration", default_symbol)
        type_span = java_type_span(source, "AssistantConfiguration")
        type_code = strip_c_comments_and_literals(source)[slice(*type_span)] \
            if type_span is not None else ""
        type_depths = java_brace_depths(type_code)
        env_declarations = [match for match in re.finditer(
            rf"\bpublic\s+static\s+final\s+String\s+{re.escape(env_symbol)}\s*=", type_code,
        ) if type_depths[match.start()] == 1]
        if env_initializer is None or default_initializer is None \
                or len(env_declarations) != 1 \
                or not re.fullmatch(rf'"{re.escape(str(expected["environment"]))}"', env_initializer[0]) \
                or public_static_final_int_expression(
                    source, "AssistantConfiguration", default_symbol) is None:
            return None
        default_value = java_int_expression_value(default_initializer[0], lambda _token: None)
        derived = dict(expected)
        derived.update({
            "call": argument, "callStart": start, "callEnd": end,
            "environmentSymbol": env_symbol, "environmentSpan": env_initializer[1:],
            "defaultSymbol": default_symbol, "defaultExpression": default_initializer[0],
            "defaultSpan": default_initializer[1:], "defaultValue": default_value,
        })
        if any(derived[key] != expected[key] for key in (
                "environmentSymbol", "defaultSymbol", "defaultValue")):
            return None
        result.append(derived)
    return result


def assistant_limit_carrier_errors(root: Path, spec: dict[str, object], evidence: object,
                                   entries: dict[str, dict[str, object]],
                                   discovered: dict[str, Candidate]) -> tuple[list[str], set[str]]:
    required = {"environment", "expectedCandidateIds"}
    setting = str(spec["setting"])
    if not isinstance(evidence, dict) or set(evidence) != required \
            or evidence.get("environment") != spec["environment"]:
        return ([f"{setting}: assistant carrier evidence has an unsupported shape"], set())
    groups = evidence["expectedCandidateIds"]
    if not isinstance(groups, dict) or set(groups) != set(ASSISTANT_CARRIER_PATHS):
        return ([f"{setting}: assistant carrier evidence must include every checker-owned group"], set())
    errors: list[str] = []
    accounted: set[str] = set()
    environment = str(spec["environment"])
    for group, paths in ASSISTANT_CARRIER_PATHS.items():
        actual = sorted(candidate.id for candidate in discovered.values()
                        if candidate.path in paths and candidate.kind == "environment-binding"
                        and candidate.expression == environment)
        if groups.get(group) != actual:
            errors.append(f"{setting}: assistant {group} candidate set has drifted")
        accounted.update(actual)
        if any(entries.get(identifier, {}).get("setting") != setting for identifier in actual):
            errors.append(f"{setting}: assistant carrier candidate is absent or assigned elsewhere")

    helm_field = str(spec["helmField"])
    maximum = int(spec["defaultValue"])
    values_source = (root / "deploy/helm/ravenroot/values.yaml").read_text(encoding="utf-8")
    if yaml_scalar_at_path(values_source, f"assistant.{helm_field}") != '""':
        errors.append(f"{setting}: Helm value must be an explicit blank default")
    try:
        schema = json.loads((root / "deploy/helm/ravenroot/values.schema.json").read_text(
            encoding="utf-8"))
        assistant = schema["properties"]["assistant"]
        expected_fields = {str(item["helmField"]) for item in ASSISTANT_LIMIT_SETTINGS}
        required_fields = assistant.get("required")
        properties = assistant.get("properties")
        if set(assistant) != {"type", "additionalProperties", "required", "properties"} \
                or assistant.get("type") != "object" \
                or assistant.get("additionalProperties") is not False \
                or not isinstance(required_fields, list) or len(required_fields) != 2 \
                or set(required_fields) != expected_fields \
                or not isinstance(properties, dict) or set(properties) != expected_fields:
            raise ValueError("unsupported assistant schema object")
        leaf = properties[helm_field]
        branches = leaf["oneOf"]
        if not isinstance(branches, list) or len(branches) != 2:
            raise ValueError("unsupported assistant schema branch set")
        integer = next(item for item in branches if item.get("type") == "integer")
        reference = next(item for item in branches if "$ref" in item)
        resolved_reference = resolved_json_schema_value(schema, reference)
        expected_reference = {
            "$ref": "#/definitions/graphBlank",
            "resolved": {
                "type": "string",
                "pattern": "^[\t-\r\x1c- \u1680\u2000-\u2006\u2008-\u200a"
                           "\u2028-\u2029\u205f\u3000]*$",
            },
        }
        if set(leaf) != {"x-ravenroot-environment", "oneOf"} \
                or branches != [integer, reference] \
                or helm_field not in assistant["required"] \
                or leaf.get("x-ravenroot-environment") != environment \
                or integer != {"type": "integer", "minimum": 1, "maximum": maximum} \
                or resolved_reference != expected_reference:
            errors.append(f"{setting}: Helm schema binding/range/blank reference has drifted")
    except (KeyError, TypeError, ValueError, StopIteration, json.JSONDecodeError):
        errors.append(f"{setting}: Helm schema binding/range/blank reference is unsupported")
    template = (root / "deploy/helm/ravenroot/templates/deployment.yaml").read_text(encoding="utf-8")
    expected_template = (f"- name: {environment}\n"
                         f"              value: {{{{ include \"ravenroot.graphLimitValue\" "
                         f".Values.assistant.{helm_field} }}}}")
    if template.count(expected_template) != 1:
        errors.append(f"{setting}: Helm template environment-to-value mapping has drifted")
    raw = (root / "deploy/kubernetes/ravenroot.yaml").read_text(encoding="utf-8")
    if len(re.findall(
            rf"(?m)^\s*- name:\s*{re.escape(environment)}\s*$\n\s*value:\s*\"\"\s*$", raw)) != 1:
        errors.append(f"{setting}: raw Kubernetes blank carrier has drifted")
    for relative in (Path("compose.yaml"), Path("docs/examples/assistant/compose.override.yaml")):
        text = (root / relative).read_text(encoding="utf-8")
        if text.count(f"{environment}: ${{{environment}:-}}") != 1:
            errors.append(f"{setting}: {relative.as_posix()} blank forwarding has drifted")
    return errors, accounted


def assistant_limit_conversion_errors(root: Path, spec: dict[str, object], conversion: object) \
        -> list[str]:
    required = {
        "kind", "issue", "beforeRevision", "afterRevision", "path", "ownerType", "method",
        "constructorType", "component", "componentIndex", "beforeArgument", "afterArgument",
        "environmentSymbol", "environment", "defaultSymbol",
    }
    setting = str(spec["setting"])
    if not isinstance(conversion, dict) or set(conversion) != required \
            or conversion.get("kind") != "java-constructor-binding-conversion-v1":
        return [f"{setting}: assistant conversion authority has an unsupported shape"]
    path = ASSISTANT_CONFIGURATION_PATH.as_posix()
    errors: list[str] = []
    transition, before_source, after_source = revision_transition_errors(
        root, setting, conversion, path=path, symbol="AssistantConfiguration", label="conversion")
    errors.extend(transition)
    before = str(conversion["beforeRevision"])
    after = str(conversion["afterRevision"])
    if commit_exists(root, before) and commit_exists(root, after):
        parent = subprocess.run(
            ["git", "rev-parse", f"{after}^"], cwd=root, capture_output=True, text=True)
        if parent.returncode != 0 or parent.stdout.strip() != before:
            errors.append(f"{setting}: assistant conversion revisions must be direct parent/child")
    metadata = {
        "path": path, "ownerType": "AssistantConfiguration", "method": "fromEnvironment",
        "constructorType": "AssistantConfiguration", "component": spec["component"],
        "componentIndex": spec["componentIndex"], "environmentSymbol": spec["environmentSymbol"],
        "environment": spec["environment"], "defaultSymbol": spec["defaultSymbol"],
    }
    if any(conversion.get(key) != value for key, value in metadata.items()):
        errors.append(f"{setting}: assistant conversion metadata does not match its source family")
    if before_source is not None and after_source is not None:
        before_call = assistant_limit_binding_call(before_source, str(spec["component"]))
        after_call = assistant_limit_binding_call(after_source, str(spec["component"]))
        expected_before = str(spec["defaultSymbol"])
        expected_after = (
            f"boundedPositiveInteger(env.get({spec['environmentSymbol']}), "
            f"{spec['environmentSymbol']}, {spec['defaultSymbol']})")
        if before_call is None or before_call[0] != expected_before \
                or conversion.get("beforeArgument") != expected_before \
                or after_call is None or after_call[0] != expected_after \
                or conversion.get("afterArgument") != expected_after:
            errors.append(f"{setting}: assistant conversion constructor arguments have drifted")
        before_env = java_static_final_initializer(
            before_source, "AssistantConfiguration", str(spec["environmentSymbol"]))
        after_env = java_static_final_initializer(
            after_source, "AssistantConfiguration", str(spec["environmentSymbol"]))
        before_default = java_static_final_initializer(
            before_source, "AssistantConfiguration", str(spec["defaultSymbol"]))
        after_default = java_static_final_initializer(
            after_source, "AssistantConfiguration", str(spec["defaultSymbol"]))
        if before_env is not None or after_env is None \
                or after_env[0] != f'"{spec["environment"]}"' \
                or before_default is None or after_default is None \
                or before_default[0] != after_default[0]:
            errors.append(f"{setting}: assistant conversion declaration/default transition has drifted")
    return errors


def assistant_limit_resolver_errors(root: Path, resolver: object) -> list[str]:
    required = {
        "kind", "path", "type", "factoryMethod", "factoryBodyDigest", "integerMethod",
        "integerBodyDigest", "dependencyBodyDigests", "testPath", "testType", "testBodyDigests",
        "testHelperBodyDigests",
    }
    if not isinstance(resolver, dict) or set(resolver) != required \
            or resolver.get("kind") != "java-symbol-bounded-positive-integer-resolver-v1":
        return ["assistant operational limits require one exact resolver authority"]
    errors: list[str] = []
    path = ASSISTANT_CONFIGURATION_PATH
    test_path = ASSISTANT_CONFIGURATION_TEST_PATH
    if resolver.get("path") != path.as_posix() or resolver.get("type") != "AssistantConfiguration" \
            or resolver.get("factoryMethod") != "fromEnvironment" \
            or resolver.get("integerMethod") != "boundedPositiveInteger":
        errors.append("assistant resolver source identity has drifted")
    source = (root / path).read_text(encoding="utf-8")
    if resolver.get("factoryBodyDigest") != java_method_digest(
            source, "AssistantConfiguration", "fromEnvironment") \
            or resolver.get("integerBodyDigest") != java_method_digest(
                source, "AssistantConfiguration", "boundedPositiveInteger"):
        errors.append("assistant resolver factory/helper digest has drifted")
    dependencies = resolver.get("dependencyBodyDigests")
    expected_dependencies = {
        method: java_method_digest(source, "AssistantConfiguration", method)
        for method in ("trimmed", "boundedIntegerRefusal")
    }
    integer_span = java_method_span(source, "AssistantConfiguration", "boundedPositiveInteger")
    integer_code = normalized(strip_c_comments(
        source[slice(*integer_span)] if integer_span else ""))
    expected_integer_code = normalized("""
        boundedPositiveInteger(String value, String variable, int defaultAndMaximum) {
            String normalized = trimmed(value);
            if (normalized == null) { return defaultAndMaximum; }
            int parsed;
            try { parsed = Integer.parseInt(normalized); }
            catch (NumberFormatException invalid) {
                throw boundedIntegerRefusal(variable, defaultAndMaximum);
            }
            if (parsed < 1 || parsed > defaultAndMaximum) {
                throw boundedIntegerRefusal(variable, defaultAndMaximum);
            }
            return parsed;
        }
    """)
    if dependencies != expected_dependencies \
            or java_method_header(source, "AssistantConfiguration", "boundedPositiveInteger") != \
            "private static int boundedPositiveInteger(String value, String variable, int defaultAndMaximum)" \
            or integer_code != expected_integer_code:
        errors.append("assistant resolver helper closure/contract has drifted")
    trimmed_span = java_method_span(source, "AssistantConfiguration", "trimmed")
    trimmed_code = normalized(strip_c_comments(
        source[slice(*trimmed_span)] if trimmed_span else ""))
    refusal = java_direct_return_expression(
        source, "AssistantConfiguration", "boundedIntegerRefusal")
    expected_trimmed_code = normalized("""
        trimmed(String value) {
            if (value == null) { return null; }
            String stripped = value.strip();
            return stripped.isEmpty() ? null : stripped;
        }
    """)
    normal_imports = re.findall(
        r"(?m)^\s*import\s+(?!static\s)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;",
        strip_c_comments_and_literals(source),
    )
    java_lang_names = {"Integer", "NumberFormatException", "IllegalArgumentException"}
    if trimmed_code != expected_trimmed_code \
            or java_method_header(source, "AssistantConfiguration", "trimmed") != \
            "private static String trimmed(String value)" \
            or refusal is None \
            or refusal != normalized(
                'new IllegalArgumentException(variable + " must be a whole number from 1 to " + maximum)') \
            or java_method_header(source, "AssistantConfiguration", "boundedIntegerRefusal") != \
            "private static IllegalArgumentException boundedIntegerRefusal(String variable, int maximum)" \
            or any(item.rsplit(".", 1)[-1] in java_lang_names for item in normal_imports) \
            or not java_has_no_simple_name_shadow(
                source, "AssistantConfiguration", java_lang_names):
        errors.append("assistant resolver blank/refusal dependency structure has drifted")
    if resolver.get("testPath") != test_path.as_posix() \
            or resolver.get("testType") != "AssistantConfigurationTest":
        errors.append("assistant resolver test identity has drifted")
        return errors
    test_source = (root / test_path).read_text(encoding="utf-8")
    roles = (
        "assistantOperationalLimitsDefaultAndTightenIndependently",
        "invalidAssistantOperationalLimitsAreCauseFreeAndDoNotEchoValues",
        "compactConstructorKeepsItsCompatibilityFallbacks",
    )
    recorded = resolver.get("testBodyDigests")
    helper_recorded = resolver.get("testHelperBodyDigests")
    if not isinstance(recorded, dict) or set(recorded) != set(roles) \
            or not java_test_type_is_directly_runnable(test_source, "AssistantConfigurationTest") \
            or not exact_import_identity(test_source, "org.junit.jupiter.api.Test") \
            or not java_has_exact_junit_assertions(
                test_source, "AssistantConfigurationTest",
                {"assertEquals", "assertFalse", "assertNull", "assertThrows", "assertTrue"}):
        errors.append("assistant resolver runnable test authority is incomplete")
    for method in roles:
        if java_method_header(test_source, "AssistantConfigurationTest", method) != f"void {method}()" \
                or java_method_annotations(test_source, "AssistantConfigurationTest", method) != ("@Test",) \
                or not isinstance(recorded, dict) \
                or recorded.get(method) != java_method_digest(
                    test_source, "AssistantConfigurationTest", method):
            errors.append(f"assistant resolver test role {method} has drifted")
    default_span = java_method_span(
        test_source, "AssistantConfigurationTest",
        "assistantOperationalLimitsDefaultAndTightenIndependently")
    default_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*default_span)] if default_span else ""))
    required_default_clauses = (
        "AssistantConfiguration.fromEnvironment(Map.of())",
        "AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS",
        "defaults.maxOutputTokens()",
        "AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS",
        "defaults.maxToolIterations()",
        "for (String blank : new String[]",
        "blankConfiguration.maxOutputTokens()",
        "blankConfiguration.maxToolIterations()",
        "outputOnly.maxOutputTokens()", "outputOnly.maxToolIterations()",
        "iterationsOnly.maxOutputTokens()", "iterationsOnly.maxToolIterations()",
        "maxima.maxOutputTokens()", "maxima.maxToolIterations()",
    )
    invalid_span = java_method_span(
        test_source, "AssistantConfigurationTest",
        "invalidAssistantOperationalLimitsAreCauseFreeAndDoNotEchoValues")
    invalid_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*invalid_span)] if invalid_span else ""))
    if any(normalized(clause) not in default_code for clause in required_default_clauses) \
            or invalid_code.count("assertInvalidLimit(") != 2 \
            or "AssistantConfiguration.MAX_OUTPUT_TOKENS_VARIABLE" not in invalid_code \
            or "AssistantConfiguration.MAX_TOOL_ITERATIONS_VARIABLE" not in invalid_code \
            or "AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS" not in invalid_code \
            or "AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS" not in invalid_code:
        errors.append("assistant resolver runnable test clauses have drifted")
    helper_method = "assertInvalidLimit"
    helper_span = java_method_span(test_source, "AssistantConfigurationTest", helper_method)
    helper_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*helper_span)] if helper_span else ""))
    if helper_recorded != {helper_method: java_method_digest(
            test_source, "AssistantConfigurationTest", helper_method)} \
            or java_method_header(test_source, "AssistantConfigurationTest", helper_method) != \
            "private static void assertInvalidLimit(String variable, int maximum, String... invalidValues)" \
            or any(expression not in helper_code for expression in (
                "for (String invalid : invalidValues)",
                "AssistantConfiguration.fromEnvironment(Map.of(variable, invalid))",
                "variable + + maximum", "failure.getCause()", "failure.getMessage().contains(",
            )):
        errors.append("assistant resolver invalid-value test helper closure has drifted")
    return errors


def assistant_limit_consumer_errors(root: Path, consumer: object) -> list[str]:
    required = {"path", "type", "method", "bodyDigest", "testPath", "testType", "testBodyDigest"}
    if not isinstance(consumer, dict) or set(consumer) != required:
        return ["assistant operational limits require exact live consumer evidence"]
    errors: list[str] = []
    source = (root / ASSISTANT_SERVICE_PATH).read_text(encoding="utf-8")
    if consumer.get("path") != ASSISTANT_SERVICE_PATH.as_posix() \
            or consumer.get("type") != "AssistantService" or consumer.get("method") != "send" \
            or consumer.get("bodyDigest") != java_method_digest(source, "AssistantService", "send") \
            or not exact_import_identity(
                source, "ai.ravenroot.server.assistant.provider.AssistantProvider") \
            or not java_has_no_simple_name_shadow(
                source, "AssistantService", {"AssistantProvider"}):
        errors.append("assistant live consumer source identity/digest has drifted")
    span = java_method_span(source, "AssistantService", "send")
    actual = source[slice(*span)] if span else ""
    code = strip_c_comments_and_literals(actual)
    loops = list(re.finditer(
        r"for\s*\(\s*int\s+iteration\s*=\s*0\s*;\s*iteration\s*<\s*"
        r"configuration\.maxToolIterations\s*\(\s*\)\s*;\s*iteration\+\+\s*\)\s*\{", code))
    if len(loops) != 1:
        errors.append("assistant live consumer has no exact active configured provider loop")
    else:
        opening = code.find("{", loops[0].start())
        closing = matching_delimiter(code, opening, "{", "}")
        loop_actual = actual[opening + 1:closing] if closing is not None else ""
        loop_code = code[opening + 1:closing] if closing is not None else ""
        calls = list(re.finditer(r"\bturnProvider\.complete\s*\(", loop_code))
        if len(calls) != 1:
            errors.append("assistant live consumer provider call is not uniquely inside the configured loop")
        else:
            call_open = loop_code.find("(", calls[0].start())
            complete = split_java_arguments(loop_actual, loop_code, call_open)
            request = complete[0][0][0] if complete is not None and len(complete[0]) == 1 else ""
            request_code = strip_c_comments_and_literals(request)
            request_match = re.match(r"\s*new\s+AssistantProvider\.Request\s*\(", request_code)
            request_args = (split_java_arguments(
                request, request_code, request_code.find("(", request_match.start()))
                if request_match is not None else None)
            if request_args is None or len(request_args[0]) != 5 \
                    or request_args[0][4][0] != "configuration.maxOutputTokens()":
                errors.append("assistant live consumer output limit is not the exact provider request argument")
    if span is None or not java_span_uses_only_simple_receiver(source, span, "configuration"):
        errors.append("assistant live consumer configuration receiver is shadowed or unsupported")
    if normalized("AssistantProvider turnProvider = providerFor(context.subject());") not in \
            normalized(code) or code.count("AssistantProvider turnProvider") != 1:
        errors.append("assistant live consumer provider selection has drifted")

    test_source = (root / ASSISTANT_SERVICE_TEST_PATH).read_text(encoding="utf-8")
    test_type = "AssistantGraphProposalTest"
    method = "configuredOperationalLimitsReachEveryRequestAndStopTheProviderLoop"
    if consumer.get("testPath") != ASSISTANT_SERVICE_TEST_PATH.as_posix() \
            or consumer.get("testType") != test_type \
            or consumer.get("testBodyDigest") != java_method_digest(test_source, test_type, method) \
            or not java_test_type_is_directly_runnable(test_source, test_type) \
            or not exact_import_identity(test_source, "org.junit.jupiter.api.Test") \
            or not java_has_exact_junit_assertions(
                test_source, test_type, {"assertEquals", "assertInstanceOf", "assertTrue"}) \
            or java_method_header(test_source, test_type, method) != f"void {method}()" \
            or java_method_annotations(test_source, test_type, method) != ("@Test",) \
            or not same_package_type_identity(
                test_source, test_type,
                "ai.ravenroot.server.assistant.AssistantHarness") \
            or not same_package_type_identity(
                test_source, test_type,
                "ai.ravenroot.server.assistant.AssistantOutcome"):
        errors.append("assistant live consumer runnable test authority has drifted")
    test_span = java_method_span(test_source, test_type, method)
    test_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*test_span)] if test_span else ""))
    for expression in (
        "readyConfiguration(17, 2)", "callingTool", "answering",
        "AssistantOutcome.Reason.TOOL_LOOP_EXHAUSTED", "assertEquals(2, provider.callCount())",
        "assertEquals(2, provider.received().size())", "request.maxTokens() == 17",
    ):
        if normalized(expression) not in test_code:
            errors.append(f"assistant live consumer test lost {expression}")
    if test_code.count("callingTool(") != 2 or test_code.count("answering(") != 1:
        errors.append("assistant live consumer test lost its two-call/third-sentinel structure")
    chain = re.compile(
        r"new\s+AssistantHarness\.ScriptedProviderView\s*\(\s*\)\s*"
        r"\.callingTool\s*\([^)]*\)\s*\.callingTool\s*\([^)]*\)\s*"
        r"\.answering\s*\([^)]*\)", re.S)
    if len(chain.findall(strip_c_comments(test_source[slice(*test_span)] if test_span else ""))) != 1:
        errors.append("assistant live consumer test lost its ordered provider script")
    return errors


def assistant_limit_compatibility_errors(root: Path, compatibility: object) -> list[str]:
    required = {"constructorBodyDigest", "testBodyDigest"}
    if not isinstance(compatibility, dict) or set(compatibility) != required:
        return ["assistant operational limits require exact compact-constructor compatibility evidence"]
    source = (root / ASSISTANT_CONFIGURATION_PATH).read_text(encoding="utf-8")
    span = java_compact_constructor_span(source, "AssistantConfiguration")
    constructor = normalized(strip_c_comments_and_literals(source[slice(*span)] if span else ""))
    errors: list[str] = []
    if compatibility.get("constructorBodyDigest") != java_span_digest(source, span) \
            or java_identifier_write_count(constructor, "maxOutputTokens") != 1 \
            or java_identifier_write_count(constructor, "maxToolIterations") != 1 \
            or constructor.count(normalized(
                "maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;")) != 1 \
            or constructor.count(normalized(
                "maxToolIterations = maxToolIterations > 0 ? maxToolIterations : DEFAULT_MAX_TOOL_ITERATIONS;")) != 1:
        errors.append("assistant compact-constructor field-specific compatibility has drifted")
    test_source = (root / ASSISTANT_CONFIGURATION_TEST_PATH).read_text(encoding="utf-8")
    method = "compactConstructorKeepsItsCompatibilityFallbacks"
    test_span = java_method_span(test_source, "AssistantConfigurationTest", method)
    test_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*test_span)] if test_span else ""))
    required_test_clauses = (
        "new AssistantConfiguration(true, null, null, null, null, OutboundHttpPolicy.disabled(), Duration.ZERO, 0, -1)",
        "AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS",
        "configuration.maxOutputTokens()",
        "AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS",
        "configuration.maxToolIterations()",
        "AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS + 1",
        "positiveValues.maxOutputTokens()",
        "AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS + 1",
        "positiveValues.maxToolIterations()",
    )
    if compatibility.get("testBodyDigest") != java_method_digest(
            test_source, "AssistantConfigurationTest", method) \
            or any(normalized(clause) not in test_code for clause in required_test_clauses):
        errors.append("assistant compact-constructor compatibility test has drifted")
    return errors


def assistant_limit_authority_errors(root: Path, authorities: object,
                                     entries: dict[str, dict[str, object]],
                                     discovered: dict[str, Candidate]) -> list[str]:
    """Verify the closed two-setting assistant limit family before public classification."""
    source_owner = f"{ASSISTANT_CONFIGURATION_PATH.as_posix()}#AssistantConfiguration"
    if current_source_owner(root, source_owner) is None:
        return [] if authorities in (None, {}) else ["assistant limit authority exists without its source family"]
    source = (root / ASSISTANT_CONFIGURATION_PATH).read_text(encoding="utf-8")
    derived = assistant_limit_source_specs(source)
    if derived is None:
        return ["AssistantConfiguration operational-limit source family has drifted"]
    if not isinstance(authorities, dict) or set(authorities) != {ASSISTANT_LIMIT_FAMILY_ID}:
        return ["AssistantConfiguration operational limits require one closed family authority"]
    authority = authorities[ASSISTANT_LIMIT_FAMILY_ID]
    required = {
        "kind", "settings", "resolverAuthority", "conversionAuthorities", "carrierEvidence",
        "consumerAuthority", "compatibilityAuthority", "platformTestDigest",
    }
    if not isinstance(authority, dict) or set(authority) != required \
            or authority.get("kind") != "assistant-symbol-operational-limits-v1":
        return ["assistant operational-limit family authority has an unsupported shape"]
    errors: list[str] = []
    settings = authority["settings"]
    if not isinstance(settings, list) or len(settings) != 2:
        return ["assistant operational-limit authority must contain exactly two settings"]
    contracts = {str(item.get("setting")): item for item in settings if isinstance(item, dict)}
    if set(contracts) != {str(item["setting"]) for item in ASSISTANT_LIMIT_SETTINGS}:
        return ["assistant operational-limit setting family is incomplete"]
    conversions = authority["conversionAuthorities"]
    carriers = authority["carrierEvidence"]
    if not isinstance(conversions, dict) or set(conversions) != set(contracts) \
            or not isinstance(carriers, dict) or set(carriers) != set(contracts):
        errors.append("assistant conversion/carrier authorities must cover both settings")
    for spec in derived:
        setting = str(spec["setting"])
        contract = contracts[setting]
        if set(contract) != {"setting", "bindingAuthority", "defaultAuthority"}:
            errors.append(f"{setting}: assistant setting authority has an unsupported shape")
            continue
        binding = contract["bindingAuthority"]
        default = contract["defaultAuthority"]
        binding_keys = {
            "kind", "sourceOwner", "method", "constructorType", "component", "componentIndex",
            "helper", "environmentSymbol", "environment", "environmentCandidateId",
            "declarationCandidateIds", "callDigest", "resolverAuthority",
        }
        default_keys = {
            "kind", "owner", "field", "componentIndex", "constant", "sourceExpression",
            "candidateIds", "evaluatedDefault",
        }
        if not isinstance(binding, dict) or set(binding) != binding_keys \
                or binding.get("kind") != "java-symbol-environment-constructor-v1" \
                or not isinstance(default, dict) or set(default) != default_keys \
                or default.get("kind") != "java-static-final-int-default-v1":
            errors.append(f"{setting}: assistant binding/default authority has an unsupported shape")
            continue
        expected_binding = {
            "sourceOwner": source_owner, "method": "fromEnvironment",
            "constructorType": "AssistantConfiguration", "component": spec["component"],
            "componentIndex": spec["componentIndex"], "helper": "boundedPositiveInteger",
            "environmentSymbol": spec["environmentSymbol"], "environment": spec["environment"],
            "resolverAuthority": "assistant-bounded-positive-integer-v1",
        }
        expected_default = {
            "owner": source_owner, "field": spec["component"],
            "componentIndex": spec["componentIndex"], "constant": spec["defaultSymbol"],
            "sourceExpression": spec["defaultExpression"], "evaluatedDefault": spec["defaultValue"],
        }
        if contract.get("setting") != setting \
                or any(binding.get(key) != value for key, value in expected_binding.items()) \
                or any(default.get(key) != value for key, value in expected_default.items()) \
                or binding.get("callDigest") != hashlib.sha256(
                    str(spec["call"]).encode("utf-8")).hexdigest():
            errors.append(f"{setting}: assistant symbol binding/default metadata has drifted")
        env_ids = candidate_ids_in_source_span(
            ASSISTANT_CONFIGURATION_PATH, source, *spec["environmentSpan"],
            "environment-binding", str(spec["environment"]), discovered)
        declaration_ids = sorted(
            candidate.id for candidate in discovered.values()
            if candidate.path == ASSISTANT_CONFIGURATION_PATH.as_posix()
            and spec["environmentSpan"][0] <= next(
                (offset for offset, item in java_source_candidates(ASSISTANT_CONFIGURATION_PATH, source)
                 if item.id == candidate.id), -1) < spec["environmentSpan"][1]
        )
        default_ids = candidate_ids_in_source_span(
            ASSISTANT_CONFIGURATION_PATH, source, *spec["defaultSpan"],
            "fixed-declaration", str(spec["defaultSymbol"]), discovered)
        if binding.get("environmentCandidateId") not in env_ids or env_ids != [
                binding.get("environmentCandidateId")]:
            errors.append(f"{setting}: assistant environment declaration binding has drifted")
        if binding.get("declarationCandidateIds") != declaration_ids \
                or default.get("candidateIds") != default_ids:
            errors.append(f"{setting}: assistant declaration/default candidate partition has drifted")
        carrier_errors, carrier_ids = assistant_limit_carrier_errors(
            root, spec, carriers.get(setting) if isinstance(carriers, dict) else None,
            entries, discovered)
        errors.extend(carrier_errors)
        assigned = set(declaration_ids) | set(default_ids) | carrier_ids
        if any(entries.get(identifier, {}).get("setting") != setting for identifier in assigned):
            errors.append(f"{setting}: assistant proof candidates are absent or assigned elsewhere")
        errors.extend(assistant_limit_conversion_errors(
            root, spec, conversions.get(setting) if isinstance(conversions, dict) else None))
    errors.extend(assistant_limit_resolver_errors(root, authority["resolverAuthority"]))
    errors.extend(assistant_limit_consumer_errors(root, authority["consumerAuthority"]))
    errors.extend(assistant_limit_compatibility_errors(root, authority["compatibilityAuthority"]))
    platform = (root / ASSISTANT_PLATFORM_TEST_PATH).read_text(encoding="utf-8")
    if authority["platformTestDigest"] != hashlib.sha256(platform.encode("utf-8")).hexdigest() \
            or any(platform.count(
                f"{spec['environment']} {spec['component']} {spec['defaultSymbol']} "
                f"assistant.{spec['helmField']}") != 1 for spec in ASSISTANT_LIMIT_SETTINGS):
        errors.append("assistant platform carrier test source evidence has drifted")
    return errors


def inventory_errors(root: Path, document: dict[str, object], candidates: tuple[Candidate, ...]) -> list[str]:
    errors: list[str] = []
    raw_entries = document["entries"]
    assert isinstance(raw_entries, list)
    entries: dict[str, dict[str, object]] = {}
    evidence_records = document.get("evidenceRecords", {})
    if not isinstance(evidence_records, dict):
        errors.append("inventory evidenceRecords must be an object")
        evidence_records = {}
    for entry in raw_entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            errors.append("inventory entry has no string id")
            continue
        identifier = str(entry["id"])
        if identifier in entries:
            errors.append(f"duplicate inventory id: {identifier}")
        entries[identifier] = entry

    discovered = {candidate.id: candidate for candidate in candidates}
    for identifier, candidate in discovered.items():
        entry = entries.get(identifier)
        if entry is None:
            errors.append(
                f"unclassified operational candidate: {candidate.path}:{candidate.line} "
                f"{candidate.symbol} {candidate.kind} {candidate.role} {candidate.expression}; "
                f"evidence: {candidate.evidence}"
            )
            continue
        for key, value in candidate.source_fields().items():
            if entry.get(key) != value:
                errors.append(f"stale inventory metadata for {identifier}: {key} is {entry.get(key)!r}, expected {value!r}")
        if evidence_records.get(candidate.evidence_digest) != candidate.evidence:
            errors.append(f"stale or missing full evidence for {identifier}: {candidate.evidence_digest}")
        status = entry.get("status")
        classification = entry.get("classification")
        rationale = entry.get("rationale")
        if status not in STATUSES:
            errors.append(f"{identifier}: invalid status {status!r}")
        if status == "pending-review":
            if classification is not None:
                errors.append(f"{identifier}: pending-review candidate must not guess a classification")
        elif classification not in CLASSIFICATIONS:
            errors.append(f"{identifier}: reviewed candidate has invalid classification {classification!r}")
        elif status not in CLASSIFICATION_STATUSES[classification]:
            errors.append(f"{identifier}: {status!r} is invalid for classification {classification!r}")
        if status != "pending-review" and classification != "test-fixture" \
                and (not isinstance(rationale, str) or not rationale.strip()):
            errors.append(f"{identifier}: classification rationale is required")
        if candidate.surface == "test-fixture" and (
                classification != "test-fixture" or status != "retained"):
            errors.append(f"{identifier}: test-fixture surface must remain a retained test-fixture")
        if classification == "test-fixture" and candidate.surface != "test-fixture":
            errors.append(f"{identifier}: only a test-fixture surface may use the test-fixture classification")
        if classification == "published-contract-description" and (
                candidate.path != ROUTE_TABLE_PATH.as_posix()
                or entry.get("retainedAuthority") != ROUTE_TABLE_AUTHORITY_ID):
            errors.append(
                f"{identifier}: published-contract-description requires the closed RouteTable authority")
        if classification == "operator-configurable" and status != "pending-review":
            for field in ("setting", "owner", "field", "default", "validation", "scope", "pinning",
                          "coverage"):
                if not isinstance(entry.get(field), str) or not str(entry[field]).strip():
                    errors.append(f"{identifier}: reviewed operator setting requires {field}")
            bindings = entry.get("bindings")
            if not isinstance(bindings, list) or any(
                    not isinstance(binding, str) or not binding.strip() for binding in bindings):
                errors.append(f"{identifier}: reviewed operator setting requires a string bindings array")
            default_evidence = entry.get("defaultEvidence")
            if not isinstance(default_evidence, list) or not default_evidence or any(
                    not isinstance(evidence_id, str) or not evidence_id.strip()
                    for evidence_id in default_evidence):
                errors.append(f"{identifier}: reviewed operator setting requires defaultEvidence candidate ids")
            owner = str(entry.get("owner", ""))
            if current_source_owner(root, owner) is None:
                errors.append(f"{identifier}: owner is not a tracked in-repository path#symbol: {owner}")
            elif not current_source_field(root, owner, str(entry.get("field", ""))):
                errors.append(f"{identifier}: field is not declared by its typed owner: {entry.get('field')}")
            if status == "converted":
                conversion = entry.get("conversion")
                required = ("issue", "beforeRevision", "afterRevision", "path", "symbol",
                            "binding", "bindingSymbol", "field", "beforeExpression", "afterExpression")
                if not isinstance(conversion, dict) or any(
                        not isinstance(conversion.get(field), str) or not str(conversion[field]).strip()
                        for field in required):
                    errors.append(
                        f"{identifier}: converted setting requires setting-specific source-verifiable "
                        "conversion provenance"
                    )
                else:
                    transition, before_source, after_source = revision_transition_errors(
                        root, identifier, conversion, path=str(conversion["path"]),
                        symbol=str(conversion["symbol"]), label="conversion",
                    )
                    errors.extend(transition)
                    errors.extend(conversion_evidence_errors(
                        identifier, entry, conversion, before_source, after_source,
                    ))
                    if str(conversion["field"]) != str(entry.get("field", "")):
                        errors.append(f"{identifier}: conversion field does not match the setting field")
        if status == "deferred" and not re.search(r"(?:#\d+|https://)", str(entry.get("followUp", ""))):
            errors.append(f"{identifier}: deferred candidate requires a concrete linked followUp")

    for identifier, entry in entries.items():
        if identifier not in discovered:
            errors.append(f"stale inventory entry: {identifier} ({entry.get('path', 'unknown path')})")

    retired_entries = document.get("retiredEntries", [])
    assert isinstance(retired_entries, list)
    retired_ids: set[str] = set()
    for entry in retired_entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            errors.append("retired inventory entry has no string id")
            continue
        identifier = str(entry["id"])
        if identifier in retired_ids or identifier in entries:
            errors.append(f"duplicate active/retired inventory id: {identifier}")
        retired_ids.add(identifier)
        if not isinstance(entry.get("retirementRationale"), str) \
                or not str(entry["retirementRationale"]).strip():
            errors.append(f"{identifier}: retired entry requires retirementRationale")
        if entry.get("status") == "duplicate-removed":
            removal = entry.get("removal")
            required = ("issue", "beforeRevision", "afterRevision", "replacementOwner")
            if not isinstance(removal, dict) or any(
                    not isinstance(removal.get(field), str) or not str(removal[field]).strip()
                    for field in required):
                errors.append(f"{identifier}: duplicate removal requires source-verifiable removal provenance")
            elif removal.get("kind") == "yaml-default-authority-v1":
                errors.extend(yaml_default_removal_errors(root, identifier, entry, removal, entries))
            else:
                transition, before_source, after_source = revision_transition_errors(
                    root, identifier, removal, path=str(entry.get("path", "")),
                    symbol=str(entry.get("symbol", "")), label="removal",
                )
                # A removed duplicate may delete its authority or change that source, but cannot leave
                # the exact old expression in place and merely relabel its inventory row.
                errors.extend(error for error in transition
                              if "path/symbol is absent from afterRevision" not in error)
                if before_source is not None and after_source is not None \
                        and str(entry.get("evidence", "")) in after_source:
                    errors.append(f"{identifier}: removed duplicate evidence remains in afterRevision")
                replacement = str(removal["replacementOwner"])
                if "#" not in replacement:
                    errors.append(f"{identifier}: replacementOwner must be path#symbol")
                else:
                    replacement_path, replacement_symbol = replacement.rsplit("#", 1)
                    replacement_source = committed_source(
                        root, str(removal["afterRevision"]), replacement_path,
                    )
                    if replacement_source is None or not re.search(
                            rf"\b{re.escape(replacement_symbol)}\b", replacement_source):
                        errors.append(f"{identifier}: replacementOwner is absent from afterRevision")

    migration_history = document.get("migrationHistory", [])
    assert isinstance(migration_history, list)
    for migration in migration_history:
        required = ("fromSchema", "toSchema", "sourceRevision", "sourcePath", "sourceFileDigest",
                    "candidateCount", "statusCounts", "rationale")
        if not isinstance(migration, dict) or any(field not in migration for field in required):
            errors.append("inventory migration record is incomplete")
            continue
        source_revision = str(migration["sourceRevision"])
        source_path = str(migration["sourcePath"])
        source = committed_source(root, source_revision, source_path)
        if source is None:
            errors.append(f"inventory migration source is not locally resolvable: {source_revision}:{source_path}")
            continue
        digest = hashlib.sha256(source.encode("utf-8")).hexdigest()
        if digest != migration["sourceFileDigest"]:
            errors.append(f"inventory migration source digest mismatch: {source_revision}:{source_path}")
        try:
            source_document = json.loads(source)
        except json.JSONDecodeError:
            errors.append(f"inventory migration source is not JSON: {source_revision}:{source_path}")
            continue
        source_entries = source_document.get("entries", [])
        source_counts = Counter(str(entry.get("status")) for entry in source_entries
                                if isinstance(entry, dict))
        if len(source_entries) != migration["candidateCount"] or dict(sorted(source_counts.items())) != migration["statusCounts"]:
            errors.append(f"inventory migration source counts mismatch: {source_revision}:{source_path}")

    authorities: dict[str, tuple[str, tuple[object, ...]]] = {}
    authority_fields = ("owner", "field", "default", "validation", "scope", "pinning", "coverage")
    for identifier, entry in entries.items():
        if entry.get("classification") != "operator-configurable" or entry.get("status") == "pending-review":
            continue
        owner = str(entry.get("owner", "")).strip()
        setting = str(entry.get("setting", "")).strip()
        if not setting:
            continue
        metadata = tuple(entry.get(field) for field in authority_fields) + (
            tuple(entry.get("bindings", [])), tuple(entry.get("defaultEvidence", [])),
            json.dumps(entry.get("bindingAuthority"), sort_keys=True),
            json.dumps(entry.get("defaultAuthority"), sort_keys=True),
            json.dumps(entry.get("schemaEvidence"), sort_keys=True),
            json.dumps(entry.get("coverageEvidence"), sort_keys=True),
            json.dumps(entry.get("carrierEvidence"), sort_keys=True),
        )
        previous = authorities.get(setting)
        if previous is not None and previous[1] != metadata:
            errors.append(
                f"inconsistent configuration authority metadata for {setting}: "
                f"{previous[0]} and {identifier}"
            )
        else:
            authorities[setting] = (identifier, metadata)

    resolver_authorities = document.get("resolverAuthorities")
    if resolver_authorities is not None:
        errors.extend(resolver_authority_errors(root, resolver_authorities))
    errors.extend(route_table_authority_errors(
        root, document.get("routeTableAuthorities"), entries, discovered,
    ))
    errors.extend(assistant_limit_authority_errors(
        root, document.get("assistantLimitAuthorities"), entries, discovered,
    ))

    tracked_paths = set(tracked_files(root))
    representatives: dict[str, dict[str, object]] = {}
    for setting in authorities:
        setting_entries = [entry for entry in entries.values() if entry.get("setting") == setting]
        setting_ids = {str(entry["id"]) for entry in setting_entries}
        representative = entries[authorities[setting][0]]
        representatives[setting] = representative
        bindings = {str(binding) for entry in setting_entries for binding in entry.get("bindings", [])}
        if representative.get("bindingAuthority") is None:
            evidenced_bindings = {str(entry.get("expression")) for entry in setting_entries
                                  if entry.get("kind") == "environment-binding"}
            for binding in sorted(bindings - evidenced_bindings):
                errors.append(f"{setting}: binding {binding} has no same-setting environment-binding candidate")
        errors.extend(binding_authority_errors(
            root, setting, representative, setting_entries, entries, discovered,
            resolver_authorities,
        ))
        errors.extend(default_authority_errors(root, setting, representative, entries, discovered))
        errors.extend(schema_evidence_errors(setting, representative, entries, discovered, evidence_records))
        errors.extend(graph_platform_coverage_errors(
            root, setting, representative, entries, discovered, evidence_records, tracked_paths,
        ))
        for entry in setting_entries:
            evidence_ids = entry.get("defaultEvidence", [])
            if isinstance(evidence_ids, list):
                for evidence_id in evidence_ids:
                    if evidence_id not in setting_ids:
                        errors.append(f"{entry['id']}: defaultEvidence {evidence_id} is not assigned to {setting}")
    errors.extend(environment_resolver_group_errors(root, representatives, resolver_authorities))
    return errors


def render_report(document: dict[str, object]) -> str:
    entries = document["entries"]
    assert isinstance(entries, list)
    typed = [entry for entry in entries if isinstance(entry, dict)]
    statuses = Counter(str(entry.get("status")) for entry in typed)
    classifications = Counter(str(entry.get("classification")) for entry in typed
                              if entry.get("classification") is not None)
    surfaces = Counter(str(entry.get("surface")) for entry in typed)
    reviewed = len(typed) - statuses["pending-review"]
    operator_entries = [entry for entry in typed if entry.get("classification") == "operator-configurable"]
    operator_settings = {str(entry["setting"]) for entry in operator_entries if entry.get("setting")}
    converted_settings = {str(entry["setting"]) for entry in operator_entries
                          if entry.get("status") == "converted" and entry.get("setting")}
    operator = len(operator_settings)
    converted = len(converted_settings)
    retired = document.get("retiredEntries", [])
    assert isinstance(retired, list)
    migrations = document.get("migrationHistory", [])
    assert isinstance(migrations, list)
    duplicate_settings = {str(entry["setting"]) for entry in retired if isinstance(entry, dict)
                          and entry.get("status") == "duplicate-removed" and entry.get("setting")}
    duplicates = len(duplicate_settings)
    deferred = statuses["deferred"]
    hardcoded = statuses["confirmed-hardcoded"]
    complete = statuses["pending-review"] == 0 and deferred == 0 and hardcoded == 0
    lines = [
        "# Operational configuration audit", "",
        "<!-- Generated by scripts/audit_operational_configuration.py; do not edit directly. -->", "",
        "This report is generated from the checked operational-configuration inventory. The bounded",
        "lexical scanner records candidates matched by its documented patterns; a candidate is not an",
        "operator setting until its semantic classification says so.", "",
        f"**Audit state:** {'complete' if complete else 'in progress'}. "
        f"{statuses['pending-review']} candidate(s) still require semantic review, {deferred} are deferred, "
        f"and {hardcoded} confirmed hard-coded candidates remain unresolved.", "",
        "## Coverage", "",
        "Scanned production surfaces: Java `src/main`, browser `src` and `public`, runtime/release Python",
        "and shell scripts, Dockerfiles, Compose, Helm, raw deployment manifests, and the two supported",
        "Compose override examples. The scanner also records testkit `src/main` and script verification",
        "constants as test fixtures.", "",
        "Excluded from production counts: ordinary `src/test`, UI test/e2e fixtures, generated build output",
        "(`target`, `dist`), dependency trees (`node_modules`), audit-tool implementation, ordinary prose,",
        "and vendored content.", "",
        "Each row represents one atomic fixed value or binding. Its full containing expression is retained as",
        "evidence without excerpt truncation. Candidate identity is `path + containing symbol + candidate",
        "kind/semantic role + normalized atomic value + normalized full-expression digest + lexical duplicate",
        "index`; source line remains checked metadata.", "",
        "The scanner is deliberately lexical: it covers declared constants, known policy constructors,",
        "and `get`, `await`, `tryAcquire`, `tryLock`, `waitFor`, and `awaitTermination` calls whose supported",
        "forms use a simple or fully qualified explicit `TimeUnit` constant. Other method names, dynamic or",
        "statically imported units, receiver-type inference, and values assembled only through reflection,",
        "generated sources, or arbitrary data flow remain outside this bounded pattern. Environment bindings,",
        "deployment scalars, and container identity/port directives are covered separately;",
        "semantic review and focused source inventories remain required for those boundaries.", "",
        "## Reproducible counts", "",
        "| Measure | Count |", "|---|---:|",
        f"| Atomic operational candidates discovered | {len(typed)} |",
        f"| Reviewed | {reviewed} |",
        f"| Pending review | {statuses['pending-review']} |",
        f"| Confirmed hard-coded candidates awaiting remediation | {hardcoded} |",
        f"| Unique confirmed operator-configurable parameters | {operator} |",
        f"| Unique parameters converted to centralized configuration | {converted} |",
        f"| Duplicate authorities removed | {duplicates} |",
        f"| Retained security ceilings or defaults | {classifications['security-ceiling-or-default']} |",
        f"| Retained protocol or format invariants | {classifications['protocol-or-format-invariant']} |",
        f"| Retained derived values | {classifications['derived']} |",
        f"| Test fixtures | {classifications['test-fixture']} |",
        f"| Intentionally deferred | {deferred} |", "",
        f"Retired source candidates preserved in inventory history: {len(retired)}.", "",
        f"Checked inventory-schema migrations: {len(migrations)}. Validation requires the recorded source",
        "revision to be present locally; CI must fetch that history before enabling this gate.", "",
        "Surface counts are derived from the same inventory:", "",
    ]
    lines.extend(f"- `{name}`: {count}" for name, count in sorted(surfaces.items()))
    lines.extend(("", "## Operator settings", "",
        "Every reviewed operator setting must name one typed owner, bindings, default, validation,",
                  "scope, pinning policy, and deployment/reference coverage. Pending candidates do not appear",
                  "in this table.", "",
                  "| Setting | State | Owner | Field | Bindings | Default | Validation | Scope | Pinning | Coverage |", "|---|---|---|---|---|---|---|---|---|---|"))
    if operator_entries:
        canonical: dict[str, list[dict[str, object]]] = {}
        for entry in operator_entries:
            canonical.setdefault(str(entry["setting"]), []).append(entry)
        for setting, setting_entries in sorted(canonical.items()):
            entry = setting_entries[0]
            item_states = {str(item["status"]) for item in setting_entries}
            states = "converted" if "converted" in item_states else ", ".join(sorted(item_states))
            bindings = ", ".join(f"`{value}`" for value in entry.get("bindings", []))
            lines.append("| {setting} | {status} | `{owner}` | `{field}` | {bindings} | {default} | {validation} | {scope} | {pinning} | {coverage} |".format(
                setting=setting, status=states, owner=entry.get("owner", ""),
                field=entry.get("field", ""),
                bindings=bindings or "none", default=entry.get("default", ""), validation=entry.get("validation", ""),
                scope=entry.get("scope", ""), pinning=entry.get("pinning", ""),
                coverage=entry.get("coverage", "")))
    else:
        lines.append("| _None reviewed yet_ |  |  |  |  |  |  |  |  |  |")
    lines.extend(("", "## Deferred values", "", "| Candidate | Follow-up | Rationale |", "|---|---|---|"))
    deferred_entries = [entry for entry in typed if entry.get("status") == "deferred"]
    if deferred_entries:
        for entry in deferred_entries:
            lines.append(f"| `{entry['path']}:{entry['line']}` | {entry.get('followUp', 'missing')} | {entry['rationale']} |")
    else:
        lines.append("| _None classified yet_ |  |  |")
    pending_files = Counter(str(entry["path"]) for entry in typed if entry.get("status") == "pending-review")
    lines.extend(("", "## Pending-review distribution", "",
                  "The machine-readable inventory retains every pending expression and its digest. This compact",
                  "view identifies where semantic review remains without copying thousands of source excerpts",
                  "into the operator report.", "", "| Source | Candidates |", "|---|---:|"))
    for path, count in sorted(pending_files.items()):
        lines.append(f"| `{path}` | {count} |")
    lines.extend(("", "## Reviewed candidate ledger", "",
                  "The machine-readable inventory is authoritative; this table shows reviewed non-fixture entries.", "",
                  "| ID | Source | Surface | State | Classification | Rationale |", "|---|---|---|---|---|---|"))
    reviewed_entries = [entry for entry in typed if entry.get("status") != "pending-review"
                        and entry.get("classification") != "test-fixture"]
    for entry in sorted(reviewed_entries, key=lambda item: (str(item["path"]), int(item["line"]), str(item["id"]))):
        rationale = str(entry["rationale"]).replace("|", "\\|").replace("\n", " ")
        lines.append(f"| `{entry['id']}` | `{entry['path']}:{entry['line']}` `{entry['symbol']}` | "
                     f"{entry['surface']} | {entry['status']} | {entry.get('classification') or '—'} | {rationale} |")
    if not reviewed_entries:
        lines.append("| _No reviewed non-fixture candidates yet_ |  |  |  |  |  |")
    lines.extend(("", "## Validation", "",
                  "Run `python3 scripts/audit_operational_configuration.py --check`. It rejects a new",
                  "unclassified value, binding, directive, or inline operational call; a changed classified",
                  "expression; stale inventory metadata; invalid classification/provenance; inconsistent setting",
                  "authorities; unresolved hard-coded settings; or report drift.", ""))
    return "\n".join(lines)


def refresh_inventory(root: Path, inventory_path: Path = INVENTORY, report_path: Path = REPORT,
                      *, accept_retired_pending: bool = False) -> tuple[list[str], dict[str, int]]:
    """Refresh source metadata without discarding a semantic review decision.

    A changed expression has a new stable ID. Pending entries may be retired only through the
    explicit command flag; a reviewed entry additionally needs an in-inventory ``retirement``
    object with ``approved: true`` and a nonblank rationale. Every retired row stays in history.
    """
    document = load_inventory(inventory_path, allow_previous_schema=True)
    raw_entries = document["entries"]
    assert isinstance(raw_entries, list)
    migrating_schema = document.get("schemaVersion") != SCHEMA_VERSION
    migration_record: dict[str, object] | None = None
    if migrating_schema:
        revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                                  capture_output=True, text=True).stdout.strip()
        source_path = inventory_path.resolve().relative_to(root.resolve()).as_posix()
        committed = subprocess.run(["git", "show", f"{revision}:{source_path}"], cwd=root,
                                   capture_output=True)
        if committed.returncode != 0 or committed.stdout != inventory_path.read_bytes():
            raise ValueError("schema migration requires the source inventory to be committed unchanged")
        source_counts = Counter(str(entry.get("status")) for entry in raw_entries
                                if isinstance(entry, dict))
        migration_record = {
            "fromSchema": document.get("schemaVersion"), "toSchema": SCHEMA_VERSION,
            "sourceRevision": revision, "sourcePath": source_path,
            "sourceFileDigest": hashlib.sha256(committed.stdout).hexdigest(),
            "candidateCount": len(raw_entries), "statusCounts": dict(sorted(source_counts.items())),
            "rationale": "Candidate identity schema changed; pending and mechanical fixture rows were reissued without claiming semantic review.",
        }
    old = {str(entry["id"]): entry for entry in raw_entries if isinstance(entry, dict) and "id" in entry}
    candidates = discover(root)
    discovered = {candidate.id: candidate for candidate in candidates}
    removed = [entry for identifier, entry in old.items() if identifier not in discovered]
    errors: list[str] = []
    for entry in removed:
        identifier = str(entry["id"])
        if entry.get("classification") == "test-fixture" and entry.get("status") == "retained":
            continue
        if entry.get("status") == "pending-review" and accept_retired_pending:
            continue
        retirement = entry.get("retirement")
        approved = isinstance(retirement, dict) and retirement.get("approved") is True \
            and isinstance(retirement.get("rationale"), str) and str(retirement["rationale"]).strip()
        if approved:
            continue
        if entry.get("status") == "pending-review":
            errors.append(
                f"refresh would retire pending candidate {identifier} {entry.get('path')}:{entry.get('line')}; "
                "review the diff and rerun with --accept-retired-pending"
            )
        else:
            errors.append(
                f"refresh would retire reviewed candidate {identifier} {entry.get('path')}:{entry.get('line')}; "
                "add retirement.approved=true and retirement.rationale to that exact inventory entry"
            )
    if errors:
        return errors, {"added": sum(identifier not in old for identifier in discovered),
                        "retired": len(removed), "preserved": sum(identifier in old for identifier in discovered)}

    merged: list[dict[str, object]] = []
    added = 0
    updated = 0
    for candidate in candidates:
        entry = old.get(candidate.id)
        if entry is None:
            merged.append(candidate.inventory_entry())
            added += 1
            continue
        preserved = dict(entry)
        preserved.pop("evidence", None)
        if preserved.get("status") == "pending-review":
            preserved.pop("rationale", None)
        source_fields = candidate.source_fields()
        if any(preserved.get(key) != value for key, value in source_fields.items()):
            updated += 1
        preserved.update(source_fields)
        merged.append(preserved)

    retired_history = list(document.get("retiredEntries", []))
    semantic_removed = [entry for entry in removed if entry.get("status") != "pending-review"
                        and entry.get("classification") != "test-fixture"]
    archive_rows = semantic_removed if migrating_schema else removed
    for entry in archive_rows:
        archived = dict(entry)
        retirement = archived.pop("retirement", None)
        if entry.get("classification") == "test-fixture":
            rationale = (f"Candidate identity retired during inventory schema v{SCHEMA_VERSION} migration."
                         if migrating_schema else "Mechanically classified test-fixture candidate retired after source refresh.")
        elif entry.get("status") == "pending-review":
            rationale = "Pending candidate retired after an explicitly accepted source refresh."
        else:
            assert isinstance(retirement, dict)
            rationale = str(retirement["rationale"]).strip()
        archived["retirementRationale"] = rationale
        if "evidence" not in archived:
            old_evidence = document.get("evidenceRecords", {})
            if isinstance(old_evidence, dict):
                archived["evidence"] = old_evidence.get(str(archived.get("evidenceDigest")), "")
        retired_history.append(archived)

    refreshed = dict(document)
    refreshed["schemaVersion"] = SCHEMA_VERSION
    refreshed["entries"] = merged
    refreshed["retiredEntries"] = retired_history
    migration_history = list(document.get("migrationHistory", []))
    if migration_record is not None:
        migration_history.append(migration_record)
    refreshed["migrationHistory"] = migration_history
    refreshed["evidenceRecords"] = {
        digest: evidence for digest, evidence in sorted({
            candidate.evidence_digest: candidate.evidence for candidate in candidates
        }.items())
    }
    validation_errors = inventory_errors(root, refreshed, candidates)
    if validation_errors:
        return validation_errors, {"added": added, "retired": len(removed),
                                   "preserved": len(merged) - added, "metadataUpdated": updated}
    rendered = render_report(refreshed)
    inventory_temporary = inventory_path.with_suffix(inventory_path.suffix + ".tmp")
    report_temporary = report_path.with_suffix(report_path.suffix + ".tmp")
    inventory_temporary.write_text(json.dumps(refreshed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    report_temporary.write_text(rendered, encoding="utf-8")
    inventory_temporary.replace(inventory_path)
    report_temporary.replace(report_path)
    return [], {"added": added, "retired": len(removed), "preserved": len(merged) - added,
                "metadataUpdated": updated}


def bootstrap(root: Path, inventory_path: Path, report_path: Path) -> None:
    if inventory_path.exists():
        raise ValueError(f"refusing to overwrite existing inventory: {inventory_path}")
    candidates = discover(root)
    document = {
        "schemaVersion": SCHEMA_VERSION,
        "description": "Machine-reviewed fixed operational candidates; counts and report are generated.",
        "entries": [candidate.inventory_entry() for candidate in candidates],
        "evidenceRecords": {
            digest: evidence for digest, evidence in sorted({
                candidate.evidence_digest: candidate.evidence for candidate in candidates
            }.items())
        },
    }
    inventory_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    inventory_path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    report_path.write_text(render_report(document), encoding="utf-8")


def check(root: Path, inventory_path: Path = INVENTORY, report_path: Path = REPORT,
          *, require_complete: bool = True) -> list[str]:
    try:
        document = load_inventory(inventory_path)
    except ValueError as invalid:
        return [str(invalid)]
    candidates = discover(root)
    errors = inventory_errors(root, document, candidates)
    if require_complete:
        entries = document["entries"]
        assert isinstance(entries, list)
        pending = sum(isinstance(entry, dict) and entry.get("status") == "pending-review"
                      for entry in entries)
        deferred = sum(isinstance(entry, dict) and entry.get("status") == "deferred"
                       for entry in entries)
        hardcoded = sum(isinstance(entry, dict) and entry.get("status") == "confirmed-hardcoded"
                        for entry in entries)
        if pending or deferred or hardcoded:
            errors.append(
                f"audit is incomplete: {pending} pending-review, {deferred} deferred, and "
                f"{hardcoded} confirmed-hardcoded candidate(s); "
                "use --check-inventory only while completing reviewed remediation waves"
            )
    expected = render_report(document)
    if not report_path.is_file() or report_path.read_text(encoding="utf-8") != expected:
        errors.append(f"generated audit report has drifted: {report_path.relative_to(root)}")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true",
                      help="require a complete inventory and current generated report (the default)")
    mode.add_argument("--check-inventory", action="store_true",
                      help="check incremental inventory integrity while pending review remains")
    mode.add_argument("--refresh-inventory", action="store_true",
                      help="preserve reviews while adding and retiring changed source candidates")
    mode.add_argument("--bootstrap", action="store_true", help="create the initial inventory and report")
    parser.add_argument("--accept-retired-pending", action="store_true",
                        help="with --refresh-inventory, explicitly archive removed pending entries")
    args = parser.parse_args(argv)
    if args.accept_retired_pending and not args.refresh_inventory:
        parser.error("--accept-retired-pending requires --refresh-inventory")
    root = args.root.resolve()
    inventory = root / "scripts" / INVENTORY.name
    report = root / "docs" / "architecture" / REPORT.name
    try:
        if args.bootstrap:
            bootstrap(root, inventory, report)
            print(f"Bootstrapped {len(discover(root))} operational candidates.")
            return 0
        if args.refresh_inventory:
            errors, summary = refresh_inventory(
                root, inventory, report, accept_retired_pending=args.accept_retired_pending,
            )
            if errors:
                for error in errors:
                    print(f"ERROR: {error}", file=sys.stderr)
                print("Refresh plan: " + ", ".join(f"{key}={value}" for key, value in summary.items()),
                      file=sys.stderr)
                return 1
            print("Refreshed operational inventory ("
                  + ", ".join(f"{key}={value}" for key, value in summary.items()) + ").")
            return 0
        errors = check(root, inventory, report, require_complete=not args.check_inventory)
    except (OSError, ValueError, subprocess.CalledProcessError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    document = load_inventory(inventory)
    print(f"Operational configuration inventory is current ({len(document['entries'])} candidates).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
