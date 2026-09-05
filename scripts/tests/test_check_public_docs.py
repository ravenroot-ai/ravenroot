"""Regression tests for public documentation validation."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("check_public_docs", ROOT / "scripts" / "check_public_docs.py")
assert SPEC and SPEC.loader
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


VALID_ADR = """# {title}

- Status: Accepted
- Date: 2026-09-04

## Context

Context.

## Decision

Decision.

## Consequences

Consequences.
"""


def write_adr(directory: Path, name: str) -> None:
    (directory / name).write_text(VALID_ADR.format(title=name), encoding="utf-8")


class CheckPublicDocsTest(unittest.TestCase):
    def test_adr_prefixes_are_unique_without_rejecting_non_numbered_or_nested_documents(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            adr = root / "adr"
            adr.mkdir()
            (adr / "README.md").write_text("# Index\n", encoding="utf-8")
            (adr / "CURATION-MANIFEST.md").write_text("# Manifest\n", encoding="utf-8")
            (adr / "EVIDENCE.md").write_text("# Evidence\n", encoding="utf-8")
            nested = adr / "guidance"
            nested.mkdir()
            write_adr(nested, "0001-nested-context.md")
            (adr / "0001-directory.md").mkdir()
            write_adr(adr, "0001-first-decision.md")
            write_adr(adr, "0002-second-decision.md")

            with mock.patch.object(CHECK, "ROOT", root):
                errors = CHECK.adr_errors()

        self.assertEqual([], errors)

    def test_duplicate_adr_prefix_reports_every_conflicting_path_in_sorted_order(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            adr = root / "adr"
            adr.mkdir()
            (adr / "README.md").write_text("# Index\n", encoding="utf-8")
            (adr / "CURATION-MANIFEST.md").write_text("# Manifest\n", encoding="utf-8")
            write_adr(adr, "0002-unique.md")
            write_adr(adr, "0001-third.md")
            write_adr(adr, "0001-first.md")
            write_adr(adr, "0001-second.md")

            with mock.patch.object(CHECK, "ROOT", root):
                errors = CHECK.adr_errors()

        self.assertEqual(
            [
                "adr: duplicate ADR prefix 0001: "
                "adr/0001-first.md, adr/0001-second.md, adr/0001-third.md"
            ],
            errors,
        )

    def test_mermaid_failure_retains_actionable_output_before_stack_tail(self) -> None:
        document = ROOT / "docs" / "example.md"
        completed = subprocess.CompletedProcess(
            args=["mmdc"],
            returncode=1,
            stdout="renderer context\n",
            stderr="Could not find Chrome (ver. 152.0.7977.54).\n    at finalStackFrame\n",
        )

        with (
            mock.patch.object(CHECK, "MERMAID_FENCE") as fence,
            mock.patch.object(CHECK.Path, "is_file", return_value=True),
            mock.patch.object(CHECK.subprocess, "run", return_value=completed) as run,
            mock.patch.object(CHECK.Path, "read_text", return_value="graph TD; A-->B"),
        ):
            fence.findall.return_value = ["graph TD; A-->B"]
            config = ROOT / "scripts" / "mermaid-renderer" / "puppeteer-ci.json"
            errors = CHECK.render_mermaid([document], config)

        self.assertEqual(len(errors), 1)
        self.assertIn("Could not find Chrome (ver. 152.0.7977.54).", errors[0])
        self.assertIn("finalStackFrame", errors[0])
        self.assertIn("renderer context", errors[0])
        self.assertIn("exited with status 1", errors[0])
        command = run.call_args.args[0]
        self.assertEqual(command[-2:], ["--puppeteerConfigFile", str(config)])

    def test_hosted_puppeteer_configuration_is_minimal_and_explicit(self) -> None:
        config = ROOT / "scripts" / "mermaid-renderer" / "puppeteer-ci.json"
        self.assertEqual(json.loads(config.read_text(encoding="utf-8")), {"args": ["--no-sandbox"]})

    def test_hosted_browser_cache_stays_outside_the_checkout(self) -> None:
        workflow = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        cache_setting = "PUPPETEER_CACHE_DIR: ${{ runner.temp }}/ravenroot-puppeteer"
        self.assertEqual(workflow.count(cache_setting), 2)
        self.assertNotIn("PUPPETEER_CACHE_DIR: ${{ github.workspace }}", workflow)


if __name__ == "__main__":
    unittest.main()
