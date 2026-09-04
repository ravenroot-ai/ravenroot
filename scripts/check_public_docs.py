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


# --------------------------------------------------------------- superseded claims
#
# Statements the product has since reversed, which keep being left behind in prose that was written
# when they were true. They are checked mechanically because a human sweep has already missed them
# twice, once in a file the same commit was editing: the phrases sit in Javadoc and in reference
# tables rather than in the document that describes the feature, so nothing brings a reader of the
# change past them.
#
# Each entry is the phrase to look for and why its presence is a defect. Keep the phrases narrow: a
# pattern that also matches a true statement makes the allowlist grow until it means nothing.
SUPERSEDED_CLAIMS = {
    "never durable":
        "an operator hold is durable when it is taken at a boundary the runtime can write down",
    "restart forgets":
        "a restart no longer forgets every hold; it forgets only the ones never written down",
    "does not survive a restart":
        "a durable hold survives a restart and stays resumable and cancellable",
    "no durable pause state":
        "a durable hold is a first-class stored record with its own schema",
    "durable pause state can be added later":
        "it was added, and it required a schema addition for the hold record",
    "durable pause state can be introduced later":
        "it was introduced, and it required a schema addition for the hold record",
    "same in-memory traversal":
        "a hold resumed after a restart continues in a different process, rebuilt from the pinned graph",
    "written to no store":
        "a hold at a writable boundary is committed with the transitions that record the traversal as waiting",
}

# Where such a phrase is still true, and why. A bare list of paths would be a rubber stamp on the
# first conflict, so each exemption states the statement it is protecting; a reader deciding whether
# to add one has to be able to say the same kind of sentence.
#
# Every entry must still match something. An exemption whose text has been rewritten is removed by
# the check itself rather than left to accumulate, which is what stops the list from outliving its
# reasons.
CLAIM_EXEMPTIONS = {
    ("docs/operator-guide/persistence-lifecycle.md", "restart forgets"):
        "describes the process-local residual: a hold at a boundary that cannot be written down is "
        "still forgotten, and this is the page that tells an operator which is which",
    ("adr/0020-artifact-execution-admission.md", "restart forgets"):
        "about the artifact registry and its revocation state, which are genuinely in-memory and "
        "have nothing to do with holds",
    ("ravenroot/ravenroot-persistence-sqlite/src/test/java/ai/ravenroot/persistence/sqlite/"
     "SqliteEmbedRegistrationStoreTest.java", "restart forgets"):
        "asserts that an embed revocation must survive a restart; the phrase is the defect the test "
        "exists to refuse, not a claim about pauses",
    ("ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/application/"
     "ExecutionEvent.java", "never durable"):
        "describes ExecutionEvent#authorMessage, the author-facing failure text, which really is "
        "live-stream only and is written to no store; nothing to do with holds",
}


def superseded_claim_errors(files: list[Path]) -> list[str]:
    errors: list[str] = []
    matched_exemptions: set[tuple[str, str]] = set()
    for document in files:
        relative = document.relative_to(ROOT).as_posix()
        flattened, lines = _flatten(document.read_text(encoding="utf-8"))
        for phrase, reason in SUPERSEDED_CLAIMS.items():
            start = flattened.find(phrase)
            if start < 0:
                continue
            if (relative, phrase) in CLAIM_EXEMPTIONS:
                matched_exemptions.add((relative, phrase))
                continue
            errors.append(
                f"{relative}:{lines[start]}: states a superseded claim: \"{phrase}\" -- {reason}. "
                f"Correct the statement, or add an exemption to CLAIM_EXEMPTIONS in "
                f"{Path(__file__).name} saying why it is still true here."
            )
    for entry in sorted(set(CLAIM_EXEMPTIONS) - matched_exemptions):
        errors.append(
            f"{entry[0]}: stale exemption for \"{entry[1]}\": the phrase is no longer there, so the "
            f"exemption is protecting nothing. Remove it from CLAIM_EXEMPTIONS."
        )
    return errors


def _flatten(text: str) -> tuple[str, list[int]]:
    """Lower-cased text with comment markers and line breaks removed, plus each character's line.

    Javadoc and Markdown both wrap, so a phrase is regularly split across lines by a ``*`` or a list
    marker. Matching the raw text would miss exactly the occurrences that are hardest to spot by
    eye, which are the ones this check exists for. The parallel line numbers are kept so a failure
    still names where to look.
    """
    flattened: list[str] = []
    lines: list[int] = []
    for number, line in enumerate(text.splitlines(), 1):
        stripped = re.sub(r"^\s*(/\*\*|\*/|\*|//|#+|>|[-*+]\s)\s?", "", line)
        for character in stripped.lower():
            if character.isspace():
                if flattened and flattened[-1] == " ":
                    continue
                flattened.append(" ")
            else:
                flattened.append(character)
            lines.append(number)
        if flattened and flattened[-1] != " ":
            flattened.append(" ")
            lines.append(number)
    return "".join(flattened), lines


def tracked_prose_files() -> list[Path]:
    """Every tracked Markdown and Java file, because the claims live in both."""
    result = subprocess.run(
        ["git", "ls-files", "-z", "*.md", "*.java"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    )
    return sorted(
        ROOT / name for name in result.stdout.split("\0")
        if name and (ROOT / name).is_file()
    )


def render_mermaid(files: list[Path], puppeteer_config: Path | None = None) -> list[str]:
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
            command = [str(renderer), "--input", str(input_file), "--output", str(output_file)]
            if puppeteer_config is not None:
                command.extend(["--puppeteerConfigFile", str(puppeteer_config)])
            result = subprocess.run(
                command,
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
    parser.add_argument("--puppeteer-config", type=Path)
    args = parser.parse_args()
    files = markdown_files()
    errors = local_link_errors(files) + adr_errors()
    errors += superseded_claim_errors(tracked_prose_files())
    if args.render_mermaid:
        config = args.puppeteer_config
        if config is not None and not config.is_absolute():
            config = ROOT / config
        if config is not None and not config.is_file():
            errors.append(f"Puppeteer configuration does not exist: {config}")
        else:
            errors.extend(render_mermaid(files, config))
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print(f"Validated {len(files)} public Markdown files, the ADR collection, and "
          f"{len(tracked_prose_files())} tracked Markdown and Java files for superseded claims.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
