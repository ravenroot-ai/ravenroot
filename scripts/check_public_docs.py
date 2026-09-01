#!/usr/bin/env python3
"""Validate the public Markdown corpus and repository ADR collection."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
PUBLIC_ROOTS = (ROOT / "README.md", ROOT / "docs", ROOT / "adr")
ADR_REQUIRED_SECTIONS = ("Context", "Decision", "Consequences")
ADR_REQUIRED_FIELDS = ("Status", "Date")
MARKDOWN_LINK = re.compile(r"(?<!!)\[[^]]*]\(([^)]+)\)")
MERMAID_FENCE = re.compile(r"```mermaid\s*\n(.*?)```", re.DOTALL)


def markdown_files() -> list[Path]:
    files: list[Path] = []
    for root in PUBLIC_ROOTS:
        if root.is_file():
            files.append(root)
        elif root.is_dir():
            files.extend(root.rglob("*.md"))
    return sorted(files)


def local_link_errors(files: list[Path]) -> list[str]:
    errors: list[str] = []
    for document in files:
        text = document.read_text(encoding="utf-8")
        for raw_target in MARKDOWN_LINK.findall(text):
            target = raw_target.strip().split(maxsplit=1)[0].strip("<>")
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc or target.startswith(("mailto:", "#")):
                continue
            resolved = (document.parent / unquote(parsed.path)).resolve()
            try:
                resolved.relative_to(ROOT)
            except ValueError:
                errors.append(f"{document.relative_to(ROOT)}: local link escapes the repository: {target}")
                continue
            if not resolved.exists():
                errors.append(f"{document.relative_to(ROOT)}: broken local link: {target}")
    return errors


def adr_errors() -> list[str]:
    errors: list[str] = []
    manifest = ROOT / "adr/CURATION-MANIFEST.md"
    index = ROOT / "adr/README.md"
    if not manifest.is_file():
        errors.append("adr/CURATION-MANIFEST.md is missing")
    if not index.is_file():
        errors.append("adr/README.md is missing")
    for document in sorted((ROOT / "adr").glob("[0-9][0-9][0-9][0-9]-*.md")):
        text = document.read_text(encoding="utf-8")
        if not text.startswith("# "):
            errors.append(f"{document.relative_to(ROOT)}: ADR must start with a level-one title")
        for field in ADR_REQUIRED_FIELDS:
            if not re.search(rf"(?mi)^-\s+{re.escape(field)}:\s*\S", text):
                errors.append(f"{document.relative_to(ROOT)}: missing {field} metadata")
        for section in ADR_REQUIRED_SECTIONS:
            if not re.search(rf"(?mi)^##\s+{re.escape(section)}\s*$", text):
                errors.append(f"{document.relative_to(ROOT)}: missing {section} section")
    return errors


def render_mermaid(files: list[Path]) -> list[str]:
    errors: list[str] = []
    renderer = ROOT / "scripts/mermaid-renderer/node_modules/.bin/mmdc"
    diagrams = [
        (document, index, source.strip())
        for document in files
        for index, source in enumerate(MERMAID_FENCE.findall(document.read_text(encoding="utf-8")), 1)
    ]
    if diagrams and not renderer.is_file():
        return ["Mermaid diagrams exist but the pinned renderer is not installed"]
    with tempfile.TemporaryDirectory() as temporary:
        temp = Path(temporary)
        for document, index, source in diagrams:
            input_file = temp / f"diagram-{index}.mmd"
            output_file = temp / f"diagram-{index}.svg"
            input_file.write_text(source + "\n", encoding="utf-8")
            result = subprocess.run(
                [str(renderer), "--input", str(input_file), "--output", str(output_file)],
                cwd=ROOT,
                capture_output=True,
                text=True,
            )
            if result.returncode:
                output = "\n".join(
                    section.strip()
                    for section in (result.stderr, result.stdout)
                    if section and section.strip()
                )
                detail = output or "renderer produced no diagnostic output"
                errors.append(
                    f"{document.relative_to(ROOT)} diagram {index}: "
                    f"Mermaid renderer exited with status {result.returncode}\n{detail}"
                )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--render-mermaid", action="store_true")
    args = parser.parse_args()
    files = markdown_files()
    errors = local_link_errors(files) + adr_errors()
    if args.render_mermaid:
        errors.extend(render_mermaid(files))
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print(f"Validated {len(files)} public Markdown files and the ADR collection.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
