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

SCHEMA_VERSION = 1
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
KNOWN_OPERATIONAL_CALL = re.compile(
    r"(?i)(Duration\.of(?:Nanos|Millis|Seconds|Minutes|Hours|Days)|new\s+(?:PayloadLimits|GraphExecutionLimits|GraphMlLimits|Limits|Semaphore|"
    r"ArrayBlockingQueue|ThreadPoolExecutor)|newScheduledThreadPool|newFixedThreadPool|withStash|"
    r"orTimeout|completeOnTimeout|readNBytes|sleep|setTimeout|setInterval|getOrDefault)\s*\("
)
ASSIGNMENT_NAME = re.compile(r"(?s)\b([A-Za-z_$][\w$]*)\s*=\s*[^=]")
STATIC_FINAL = re.compile(r"\bstatic\s+final\b")
JS_DECLARATION = re.compile(r"\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=")
PY_ASSIGNMENT = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?![=])")
YAML_SCALAR = re.compile(r'''^\s*["']?([A-Za-z_][A-Za-z0-9_.-]*)["']?\s*:\s*(\S.*)$''')
ENVIRONMENT_BINDING = re.compile(r"RAVENROOT_[A-Z0-9_]+")


@dataclass(frozen=True)
class Candidate:
    id: str
    path: str
    line: int
    symbol: str
    kind: str
    expression: str
    expression_digest: str
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
            "rationale": "Pending semantic review; no configurable or invariant classification has been inferred from spelling alone.",
        }

    def source_fields(self) -> dict[str, object]:
        return {
            "id": self.id,
            "path": self.path,
            "line": self.line,
            "symbol": self.symbol,
            "kind": self.kind,
            "expression": self.expression,
            "expressionDigest": self.expression_digest,
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
    if text == "scripts/operational-configuration-inventory.json":
        # The inventory describes the scan; including it would recursively inventory its own
        # source metadata and make every regeneration multiply the candidate set.
        return None
    if relative.name in {"Dockerfile", "Dockerfile.ci"}:
        return "deployment"
    if text == "compose.yaml" or text.startswith("deploy/"):
        return "deployment"
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
    if text.startswith("ravenroot/scripts/"):
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


def code_candidates(relative: Path, text: str, surface_name: str) -> list[tuple[int, str, str, str]]:
    suffix = relative.suffix
    code = strip_c_comments(text)
    markers = symbol_markers(code, suffix)
    rows: list[tuple[int, str, str, str]] = []
    for start, _end, raw in statement_spans(code):
        expression = normalized(raw)
        fixed_match = FIXED.search(raw)
        if not expression or fixed_match is None:
            continue
        candidate_offset = start + fixed_match.start()
        kind: str | None = None
        label = "literal"
        assignment = JS_DECLARATION.search(expression) if suffix in {".js", ".mjs", ".ts"} \
            else ASSIGNMENT_NAME.search(expression)
        if STATIC_FINAL.search(expression) or (suffix in {".js", ".mjs", ".ts"}
                                                and expression.lstrip().startswith("const ")):
            kind = "fixed-declaration"
            if assignment:
                label = assignment.group(1)
        elif assignment and OPERATIONAL_WORD.search(assignment.group(1)):
            kind = "operational-declaration"
            label = assignment.group(1)
        elif KNOWN_OPERATIONAL_CALL.search(expression):
            kind = "inline-operational-call"
            call = KNOWN_OPERATIONAL_CALL.search(expression)
            assert call is not None
            label = normalized(call.group(1)).replace(" ", "-")
        elif ENVIRONMENT_BINDING.search(expression) and ("getOrDefault" in expression or "DEFAULT" in expression):
            kind = "binding-default"
            label = ENVIRONMENT_BINDING.search(expression).group(0)  # type: ignore[union-attr]
        if kind:
            rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                         kind + ":" + label, expression))
    return rows


def line_candidates(relative: Path, text: str, surface_name: str) -> list[tuple[int, str, str, str]]:
    rows: list[tuple[int, str, str, str]] = []
    suffix = relative.suffix
    markers = symbol_markers(text, suffix)
    for index, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith(("#", "//")):
            continue
        label: str | None = None
        kind: str | None = None
        if suffix in {".yaml", ".yml", ".json"}:
            match = YAML_SCALAR.match(raw)
            if match and FIXED.search(match.group(2)):
                label = match.group(1)
                kind = "configuration-scalar"
        elif suffix in {".py", ".sh"} or relative.name.startswith("Dockerfile"):
            match = PY_ASSIGNMENT.match(raw)
            shell = re.match(r"^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$", raw)
            name = match.group(1) if match else shell.group(1) if shell else None
            if name and (name.isupper() or OPERATIONAL_WORD.search(name)) and FIXED.search(raw):
                label = name
                kind = "script-default"
            elif (re.search(r"\b(?:sleep|timeout)\s+[0-9]", raw)
                  or (OPERATIONAL_WORD.search(raw) and FIXED.search(raw))):
                label = "inline"
                kind = "inline-script-operational"
        if kind and label:
            offset = sum(len(item) + 1 for item in text.splitlines()[:index - 1])
            rows.append((offset, containing_symbol(markers, offset), kind + ":" + label, normalized(raw)))
    return rows


def discover(root: Path) -> tuple[Candidate, ...]:
    provisional: list[tuple[str, int, str, str, str, str, bool]] = []
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
        for offset, symbol_name, kind_label, expression in found:
            provisional.append((relative.as_posix(), line_number(text, offset), symbol_name,
                                kind_label, expression, surface_name, surface_name == "test-fixture"))

    occurrences: Counter[tuple[str, str, str, str]] = Counter()
    candidates: list[Candidate] = []
    for path, line, symbol_name, kind_label, expression, surface_name, fixture in sorted(provisional):
        kind, label = kind_label.split(":", 1)
        key = (path, symbol_name, kind_label, expression)
        occurrence = occurrences[key]
        occurrences[key] += 1
        material = "\0".join((path, symbol_name, kind, label, expression, str(occurrence)))
        digest = hashlib.sha256(expression.encode("utf-8")).hexdigest()
        identifier = "oc-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:20]
        excerpt = expression if len(expression) <= 320 else expression[:317] + "..."
        candidates.append(Candidate(identifier, path, line, symbol_name, kind, excerpt, digest,
                                    surface_name, fixture))
    return tuple(candidates)


def load_inventory(path: Path = INVENTORY) -> dict[str, object]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise ValueError(f"missing inventory: {path.relative_to(ROOT)}") from None
    if document.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError(f"inventory schemaVersion must be {SCHEMA_VERSION}")
    entries = document.get("entries")
    if not isinstance(entries, list):
        raise ValueError("inventory entries must be an array")
    return document


def inventory_errors(root: Path, document: dict[str, object], candidates: tuple[Candidate, ...]) -> list[str]:
    errors: list[str] = []
    raw_entries = document["entries"]
    assert isinstance(raw_entries, list)
    entries: dict[str, dict[str, object]] = {}
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
                f"{candidate.symbol} {candidate.kind} {candidate.expression}"
            )
            continue
        for key, value in candidate.source_fields().items():
            if entry.get(key) != value:
                errors.append(f"stale inventory metadata for {identifier}: {key} is {entry.get(key)!r}, expected {value!r}")
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
        if not isinstance(rationale, str) or not rationale.strip():
            errors.append(f"{identifier}: classification rationale is required")
        if classification == "operator-configurable" and status != "pending-review":
            for field in ("owner", "default", "validation", "scope", "pinning"):
                if not isinstance(entry.get(field), str) or not str(entry[field]).strip():
                    errors.append(f"{identifier}: reviewed operator setting requires {field}")
            bindings = entry.get("bindings")
            if not isinstance(bindings, list):
                errors.append(f"{identifier}: reviewed operator setting requires a bindings array")
        if status == "deferred" and not re.search(r"(?:#\d+|https://)", str(entry.get("followUp", ""))):
            errors.append(f"{identifier}: deferred candidate requires a concrete linked followUp")

    for identifier, entry in entries.items():
        if identifier not in discovered:
            errors.append(f"stale inventory entry: {identifier} ({entry.get('path', 'unknown path')})")

    owners: dict[str, str] = {}
    for identifier, entry in entries.items():
        if entry.get("classification") != "operator-configurable" or entry.get("status") == "pending-review":
            continue
        owner = str(entry.get("owner", "")).strip()
        setting = str(entry.get("setting", identifier)).strip()
        authority = f"{setting}\0{owner}"
        previous = owners.get(setting)
        if previous is not None and previous != authority:
            errors.append(f"duplicate configuration authority for {setting}: {previous.split(chr(0), 1)[1]} and {owner}")
        owners[setting] = authority
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
    operator = classifications["operator-configurable"]
    converted = statuses["converted"]
    duplicates = statuses["duplicate-removed"]
    deferred = statuses["deferred"]
    complete = statuses["pending-review"] == 0 and deferred == 0
    lines = [
        "# Operational configuration audit", "",
        "<!-- Generated by scripts/audit_operational_configuration.py; do not edit directly. -->", "",
        "This report is generated from the checked operational-configuration inventory. The scanner",
        "finds a reviewable superset of fixed values; a candidate is not an operator setting until its",
        "semantic classification says so.", "",
        f"**Audit state:** {'complete' if complete else 'in progress'}. "
        f"{statuses['pending-review']} candidate(s) still require semantic review and {deferred} are deferred.", "",
        "## Coverage", "",
        "Scanned production surfaces: Java `src/main`, browser `src` and `public`, runtime/release Python",
        "and shell scripts, Dockerfiles, Compose, Helm, raw deployment manifests, and the two supported",
        "Compose override examples. The scanner also records testkit `src/main` constants as test fixtures.", "",
        "Excluded from production counts: ordinary `src/test`, UI test/e2e fixtures, generated build output",
        "(`target`, `dist`), dependency trees (`node_modules`), ordinary prose, and vendored content.", "",
        "Candidate identity is `path + containing symbol + candidate kind/role + normalized expression",
        "+ lexical duplicate index`. The source line is checked metadata but is not part of the stable ID.", "",
        "## Reproducible counts", "",
        "| Measure | Count |", "|---|---:|",
        f"| Candidate fixed expressions discovered | {len(typed)} |",
        f"| Reviewed | {reviewed} |",
        f"| Pending review | {statuses['pending-review']} |",
        f"| Confirmed operator-configurable parameters | {operator} |",
        f"| Converted to centralized parameters | {converted} |",
        f"| Duplicate authorities removed | {duplicates} |",
        f"| Retained security ceilings or defaults | {classifications['security-ceiling-or-default']} |",
        f"| Retained protocol or format invariants | {classifications['protocol-or-format-invariant']} |",
        f"| Retained derived values | {classifications['derived']} |",
        f"| Test fixtures | {classifications['test-fixture']} |",
        f"| Intentionally deferred | {deferred} |", "",
        "Surface counts are derived from the same inventory:", "",
    ]
    lines.extend(f"- `{name}`: {count}" for name, count in sorted(surfaces.items()))
    lines.extend(("", "## Operator settings", "",
                  "Every reviewed operator setting must name one typed owner, bindings, default, validation,",
                  "scope, and pinning policy. Pending candidates do not appear in this table.", "",
                  "| Setting | State | Owner | Bindings | Default | Validation | Scope | Pinning |", "|---|---|---|---|---|---|---|---|"))
    operator_entries = [entry for entry in typed if entry.get("classification") == "operator-configurable"]
    if operator_entries:
        for entry in sorted(operator_entries, key=lambda item: str(item.get("setting", item["id"]))):
            bindings = ", ".join(f"`{value}`" for value in entry.get("bindings", []))
            lines.append("| {setting} | {status} | `{owner}` | {bindings} | {default} | {validation} | {scope} | {pinning} |".format(
                setting=entry.get("setting", entry["id"]), status=entry["status"], owner=entry.get("owner", ""),
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
                  "unclassified declaration or inline operational call, a changed classified expression, stale",
                  "inventory metadata, invalid classification data, duplicate setting authorities, or report drift.", ""))
    return "\n".join(lines)


def bootstrap(root: Path, inventory_path: Path, report_path: Path) -> None:
    if inventory_path.exists():
        raise ValueError(f"refusing to overwrite existing inventory: {inventory_path}")
    candidates = discover(root)
    document = {
        "schemaVersion": SCHEMA_VERSION,
        "description": "Machine-reviewed fixed operational candidates; counts and report are generated.",
        "entries": [candidate.inventory_entry() for candidate in candidates],
    }
    inventory_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    inventory_path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    report_path.write_text(render_report(document), encoding="utf-8")


def check(root: Path, inventory_path: Path = INVENTORY, report_path: Path = REPORT) -> list[str]:
    try:
        document = load_inventory(inventory_path)
    except ValueError as invalid:
        return [str(invalid)]
    candidates = discover(root)
    errors = inventory_errors(root, document, candidates)
    expected = render_report(document)
    if not report_path.is_file() or report_path.read_text(encoding="utf-8") != expected:
        errors.append(f"generated audit report has drifted: {report_path.relative_to(root)}")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--bootstrap", action="store_true", help="create the initial inventory and report")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    inventory = root / "scripts" / INVENTORY.name
    report = root / "docs" / "architecture" / REPORT.name
    try:
        if args.bootstrap:
            bootstrap(root, inventory, report)
            print(f"Bootstrapped {len(discover(root))} operational candidates.")
            return 0
        errors = check(root, inventory, report)
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
