#!/usr/bin/env python3
"""Inventory fixed operational candidates and reject unreviewed source drift.

The scanner is deliberately lexical.  It does not claim that every number is an
operator setting; it finds a stable, reviewable superset and leaves that semantic
decision in ``scripts/operational-configuration-inventory.json``.  The inventory
and generated report are the source of the issue's counts.
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
    r"lease|drain|age|port|path|directory|dir|memory|heap|cpu|replica|probe|health)"
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
ASSIGNMENT_NAME = re.compile(r"(?s)\b([A-Za-z_$][\w$]*)\s*=\s*[^=]")
STATIC_FINAL = re.compile(r"\bstatic\s+final\b")
JS_DECLARATION = re.compile(r"\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=")
PY_ASSIGNMENT = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?![=])")
YAML_SCALAR = re.compile(r'''^\s*["']?([A-Za-z_][A-Za-z0-9_.-]*)["']?\s*:\s*(\S.*)$''')
ENVIRONMENT_BINDING = re.compile(r"RAVENROOT_[A-Z0-9_]+")
PROPERTY_BINDING = re.compile(r'''"(ravenroot\.[A-Za-z0-9_.-]*)"''')
SYSTEM_PROPERTY_READ = re.compile(r"\bSystem\.getProperty\s*\(\s*([^,)]+)")


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
        elif char in {'"', "'", "`"}:
            quote = char
            state = "string"
            out.append(char)
        else:
            out.append(char)
        index += 1
    return "".join(out)


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
    if not re.search(rf"\b{re.escape(owner_symbol)}\b", source):
        return None
    return relative, owner_symbol


def committed_source(root: Path, revision: str, path: str) -> str | None:
    relative = Path(path)
    if relative.is_absolute() or ".." in relative.parts:
        return None
    result = subprocess.run(["git", "show", f"{revision}:{relative.as_posix()}"], cwd=root,
                            capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else None


def revision_transition_errors(root: Path, identifier: str, provenance: dict[str, object],
                               *, path: str, symbol: str, label: str) -> tuple[list[str], str | None, str | None]:
    errors: list[str] = []
    before = str(provenance["beforeRevision"])
    after = str(provenance["afterRevision"])
    if before == after:
        errors.append(f"{identifier}: {label} revisions must be distinct")
    for revision_field, revision in (("beforeRevision", before), ("afterRevision", after)):
        if subprocess.run(["git", "cat-file", "-e", f"{revision}^{{commit}}"], cwd=root,
                          capture_output=True).returncode != 0:
            errors.append(f"{identifier}: {label} {revision_field} is not a local commit")
    if not errors and subprocess.run(["git", "merge-base", "--is-ancestor", before, after], cwd=root,
                                     capture_output=True).returncode != 0:
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
        if classification == "operator-configurable" and status != "pending-review":
            for field in ("setting", "owner", "default", "validation", "scope", "pinning"):
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
            if status == "converted":
                conversion = entry.get("conversion")
                required = ("issue", "beforeRevision", "afterRevision", "path", "symbol")
                if not isinstance(conversion, dict) or any(
                        not isinstance(conversion.get(field), str) or not str(conversion[field]).strip()
                        for field in required):
                    errors.append(f"{identifier}: converted setting requires source-verifiable conversion provenance")
                else:
                    transition, _before, _after = revision_transition_errors(
                        root, identifier, conversion, path=str(conversion["path"]),
                        symbol=str(conversion["symbol"]), label="conversion",
                    )
                    errors.extend(transition)
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
        source = subprocess.run(["git", "show", f"{source_revision}:{source_path}"], cwd=root,
                                capture_output=True)
        if source.returncode != 0:
            errors.append(f"inventory migration source is not locally resolvable: {source_revision}:{source_path}")
            continue
        digest = hashlib.sha256(source.stdout).hexdigest()
        if digest != migration["sourceFileDigest"]:
            errors.append(f"inventory migration source digest mismatch: {source_revision}:{source_path}")
        try:
            source_document = json.loads(source.stdout)
        except json.JSONDecodeError:
            errors.append(f"inventory migration source is not JSON: {source_revision}:{source_path}")
            continue
        source_entries = source_document.get("entries", [])
        source_counts = Counter(str(entry.get("status")) for entry in source_entries
                                if isinstance(entry, dict))
        if len(source_entries) != migration["candidateCount"] or dict(sorted(source_counts.items())) != migration["statusCounts"]:
            errors.append(f"inventory migration source counts mismatch: {source_revision}:{source_path}")

    authorities: dict[str, tuple[str, tuple[object, ...]]] = {}
    authority_fields = ("status", "owner", "default", "validation", "scope", "pinning")
    for identifier, entry in entries.items():
        if entry.get("classification") != "operator-configurable" or entry.get("status") == "pending-review":
            continue
        owner = str(entry.get("owner", "")).strip()
        setting = str(entry.get("setting", "")).strip()
        if not setting:
            continue
        metadata = tuple(entry.get(field) for field in authority_fields) + (
            tuple(entry.get("bindings", [])), tuple(entry.get("defaultEvidence", [])),
            json.dumps(entry.get("conversion"), sort_keys=True),
        )
        previous = authorities.get(setting)
        if previous is not None and previous[1] != metadata:
            errors.append(
                f"inconsistent configuration authority metadata for {setting}: "
                f"{previous[0]} and {identifier}"
            )
        else:
            authorities[setting] = (identifier, metadata)

    for setting in authorities:
        setting_entries = [entry for entry in entries.values() if entry.get("setting") == setting]
        setting_ids = {str(entry["id"]) for entry in setting_entries}
        bindings = {str(binding) for entry in setting_entries for binding in entry.get("bindings", [])}
        evidenced_bindings = {str(entry.get("expression")) for entry in setting_entries
                              if entry.get("kind") == "environment-binding"}
        for binding in sorted(bindings - evidenced_bindings):
            errors.append(f"{setting}: binding {binding} has no same-setting environment-binding candidate")
        for entry in setting_entries:
            evidence_ids = entry.get("defaultEvidence", [])
            if isinstance(evidence_ids, list):
                for evidence_id in evidence_ids:
                    if evidence_id not in setting_ids:
                        errors.append(f"{entry['id']}: defaultEvidence {evidence_id} is not assigned to {setting}")
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
        "This report is generated from the checked operational-configuration inventory. The scanner",
        "finds a reviewable superset of fixed values; a candidate is not an operator setting until its",
        "semantic classification says so.", "",
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
        "kind/semantic role + normalized atomic value + lexical duplicate index`; source line and full evidence",
        "are checked metadata rather than identity.", "",
        "The scanner is deliberately lexical: it covers declared constants, known policy constructors and",
        "timeout APIs, environment bindings, deployment scalars, and container identity/port directives. It",
        "does not infer values assembled only through reflection, generated sources, or arbitrary data flow;",
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
                  "scope, and pinning policy. Pending candidates do not appear in this table.", "",
                  "| Setting | State | Owner | Bindings | Default | Validation | Scope | Pinning |", "|---|---|---|---|---|---|---|---|"))
    if operator_entries:
        canonical: dict[str, list[dict[str, object]]] = {}
        for entry in operator_entries:
            canonical.setdefault(str(entry["setting"]), []).append(entry)
        for setting, setting_entries in sorted(canonical.items()):
            entry = setting_entries[0]
            states = ", ".join(sorted({str(item["status"]) for item in setting_entries}))
            bindings = ", ".join(f"`{value}`" for value in entry.get("bindings", []))
            lines.append("| {setting} | {status} | `{owner}` | {bindings} | {default} | {validation} | {scope} | {pinning} |".format(
                setting=setting, status=states, owner=entry.get("owner", ""),
                bindings=bindings or "none", default=entry.get("default", ""), validation=entry.get("validation", ""),
                scope=entry.get("scope", ""), pinning=entry.get("pinning", "")))
    else:
        lines.append("| _None reviewed yet_ |  |  |  |  |  |  |  |")
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
