"""Structural regression tests for the visible GitHub Actions CI topology."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"


def job_blocks(contents: str) -> dict[str, str]:
    """Return top-level job blocks without adding a YAML dependency to repository tooling."""
    jobs_marker = contents.index("\njobs:\n") + len("\njobs:\n")
    body = contents[jobs_marker:]
    matches = list(re.finditer(r"(?m)^  ([a-z0-9-]+):\n", body))
    return {
        match.group(1): body[match.start() : matches[index + 1].start() if index + 1 < len(matches) else None]
        for index, match in enumerate(matches)
    }


def declared_needs(block: str) -> set[str]:
    """Read either the inline or block-list needs syntax used by this workflow."""
    inline = re.search(r"(?m)^    needs: \[([^]]*)]$", block)
    if inline:
        return {item.strip() for item in inline.group(1).split(",") if item.strip()}

    lines = block.splitlines()
    for index, line in enumerate(lines):
        if line == "    needs:":
            needs: set[str] = set()
            for candidate in lines[index + 1 :]:
                match = re.fullmatch(r"      - ([a-z0-9-]+)", candidate)
                if not match:
                    break
                needs.add(match.group(1))
            return needs
    return set()


class ContinuousIntegrationTopologyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contents = WORKFLOW.read_text(encoding="utf-8")
        cls.jobs = job_blocks(cls.contents)

    def test_full_tier_exposes_independently_actionable_jobs(self) -> None:
        expected = {
            "full-docs-policy",
            "full-python-contracts",
            "full-shell-contracts",
            "full-source-policy",
            "full-ui-audit",
            "full-ui-unit-tests",
            "full-ui-build",
            "full-ui-e2e",
            "backend-build",
            "full-backend-tests",
            "backend-test",
            "full-plugin-boundary",
            "full-api-documentation",
            "full-runtime-auth-smoke",
            "full-runtime-jar-smoke",
            "full-runtime-container-smoke",
        }
        self.assertTrue(expected.issubset(self.jobs))
        self.assertTrue({"full-policy", "full-ui", "full-runtime"}.isdisjoint(self.jobs))
        self.assertNotIn("name: full-backend\n", self.contents)

    def test_builds_and_tests_are_distinct(self) -> None:
        self.assertIn("name: full-backend-build", self.jobs["backend-build"])
        self.assertIn("name: full-support-modules", self.jobs["backend-test"])
        self.assertIn("-DskipTests clean install", self.jobs["backend-build"])
        self.assertNotIn("clean verify", self.jobs["backend-build"])
        self.assertIn("clean verify", self.jobs["full-backend-tests"])
        self.assertIn('= ravenroot-distribution ] && continue', self.jobs["backend-build"])
        self.assertIn("npm run build", self.jobs["full-ui-build"])
        self.assertIn("npm test", self.jobs["full-ui-unit-tests"])
        self.assertNotIn("npm test", self.jobs["full-ui-build"])
        self.assertNotIn("npm run build", self.jobs["full-ui-e2e"])

    def test_verified_artifact_dependencies_are_explicit(self) -> None:
        artifact_consumers = {
            "full-ui-e2e": {"full-ui-build"},
            "backend-test": {"backend-build"},
            "full-plugin-boundary": {"backend-build"},
            "full-runtime-auth-smoke": {"backend-build"},
            "full-runtime-jar-smoke": {"backend-build"},
            "full-runtime-container-smoke": {
                "full-ui-build",
                "backend-build",
                "full-plugin-boundary",
            },
        }
        for consumer, producers in artifact_consumers.items():
            with self.subTest(consumer=consumer):
                self.assertEqual(
                    declared_needs(self.jobs[consumer]),
                    {"release-classification", *producers},
                )
        self.assertIn("name: ravenroot-ui", self.jobs["full-ui-build"])
        self.assertIn("name: ravenroot-ui", self.jobs["full-ui-e2e"])
        self.assertIn("name: ravenroot-backend-build", self.jobs["backend-build"])
        self.assertIn("name: ravenroot-backend-build", self.jobs["backend-test"])
        self.assertIn("name: ravenroot-plugins", self.jobs["full-plugin-boundary"])
        self.assertIn("name: ravenroot-plugins", self.jobs["full-runtime-container-smoke"])

    def test_required_gate_observes_every_visible_full_job(self) -> None:
        required_needs = declared_needs(self.jobs["ci-required"])
        visible_full_jobs = {
            job for job, block in self.jobs.items() if re.search(r"(?m)^    name: full-", block)
        }
        self.assertEqual(visible_full_jobs, visible_full_jobs.intersection(required_needs))
        for job in visible_full_jobs:
            with self.subTest(job=job):
                self.assertGreaterEqual(
                    self.jobs["ci-required"].count(job),
                    2,
                    f"{job} must be both a dependency and an explicitly checked tier result",
                )

    def test_full_jobs_preserve_event_tier_routing(self) -> None:
        policy_jobs = {
            "full-docs-policy",
            "full-python-contracts",
            "full-shell-contracts",
            "full-source-policy",
        }
        for job in policy_jobs:
            with self.subTest(job=job):
                self.assertIn("needs.release-classification.outputs.tier != 'fast'", self.jobs[job])
        for job, block in self.jobs.items():
            if re.search(r"(?m)^    name: full-", block) and job not in policy_jobs:
                with self.subTest(job=job):
                    self.assertIn("needs.release-classification.outputs.tier == 'full'", block)


if __name__ == "__main__":
    unittest.main()
