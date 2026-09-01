"""Regression tests for public documentation validation."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("check_public_docs", ROOT / "scripts" / "check_public_docs.py")
assert SPEC and SPEC.loader
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class CheckPublicDocsTest(unittest.TestCase):
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
            mock.patch.object(CHECK.subprocess, "run", return_value=completed),
            mock.patch.object(CHECK.Path, "read_text", return_value="graph TD; A-->B"),
        ):
            fence.findall.return_value = ["graph TD; A-->B"]
            errors = CHECK.render_mermaid([document])

        self.assertEqual(len(errors), 1)
        self.assertIn("Could not find Chrome (ver. 152.0.7977.54).", errors[0])
        self.assertIn("finalStackFrame", errors[0])
        self.assertIn("renderer context", errors[0])
        self.assertIn("exited with status 1", errors[0])


if __name__ == "__main__":
    unittest.main()
