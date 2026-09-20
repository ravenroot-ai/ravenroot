"""Structural regression tests for the visible GitHub Actions CI topology.

Which jobs each event requires, and whether `ci.yml` still gates them that way, is decided by
`scripts/ci_required.py` and covered by `test_ci_required.py`. What remains here is the shape of the
jobs themselves: that they stay independently actionable, that builds and tests are separate, and
that every artifact a job consumes is one it explicitly waited for.
"""

from __future__ import annotations

import re
import unittest

from scripts.ci_required import FAST_WORKFLOW, WORKFLOW, declared_needs, job_blocks


class ContinuousIntegrationTopologyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contents = WORKFLOW.read_text(encoding="utf-8")
        cls.jobs = job_blocks(cls.contents)

    def test_full_tier_exposes_independently_actionable_jobs(self) -> None:
        expected = {
            "admission-policy",
            "admission-ui",
            "admission-backend",
            "full-docs-policy",
            "full-python-contracts",
            "full-shell-contracts",
            "full-source-policy",
            "full-ui-audit",
            "full-ui-unit-tests",
            "full-ui-build",
            "full-ui-e2e-harness",
            "full-ui-e2e-shard",
            "full-ui-e2e",
            "backend-build",
            "full-backend-tests",
            "backend-test",
            "full-plugin-boundary",
            "full-api-documentation",
            "full-runtime-auth-smoke",
            "full-runtime-jar-smoke",
            "full-runtime-container-smoke",
            "full-preflight",
            "full-regression",
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
        self.assertNotIn("npm run build", self.jobs["full-ui-e2e-shard"])

    def test_full_tier_requires_runner_deployment_evidence_without_tooling_skips(self) -> None:
        job = self.jobs["full-shell-contracts"]
        step = job.split("- name: Verify supervised runner deployment and independent replica topology", 1)[1]
        self.assertNotIn("if:", step)
        self.assertNotIn("continue-on-error", step)
        required = (
            "command -v helm",
            "docker compose version",
            "python3 -m unittest scripts.tests.test_runner_deployment_contract",
            "./scripts/tests/test_helm_values_contract.sh",
            "./scripts/tests/test_program_timeout_helm_contract.sh",
            "./scripts/tests/test_execution_manifest_pin_helm_contract.sh",
        )
        positions = [step.index(command) for command in required]
        self.assertEqual(sorted(positions), positions)

    def test_verified_artifact_dependencies_are_explicit(self) -> None:
        artifact_consumers = {
            "full-ui-e2e-harness": {"full-preflight", "full-ui-build"},
            "full-ui-e2e-shard": {"full-preflight", "full-ui-build"},
            "backend-test": {"full-preflight", "backend-build"},
            "full-plugin-boundary": {"full-preflight", "backend-build"},
            "full-runtime-auth-smoke": {"full-preflight", "backend-build"},
            "full-runtime-jar-smoke": {"full-preflight", "backend-build"},
            "full-runtime-container-smoke": {
                "full-regression",
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
        self.assertIn("name: ravenroot-ui", self.jobs["full-ui-e2e-shard"])
        self.assertIn("name: ravenroot-backend-build", self.jobs["backend-build"])
        self.assertIn("name: ravenroot-backend-build", self.jobs["backend-test"])
        self.assertIn("name: ravenroot-plugins", self.jobs["full-plugin-boundary"])
        self.assertIn("name: ravenroot-plugins", self.jobs["full-runtime-container-smoke"])

    def test_the_end_to_end_aggregator_observes_the_shards_it_reports_for(self) -> None:
        """`full-ui-e2e` is now a verdict on other jobs, so it must depend on all of them."""
        self.assertEqual(
            declared_needs(self.jobs["full-ui-e2e"]),
            {"release-classification", "full-ui-e2e-harness", "full-ui-e2e-shard"},
        )
        for job in ("full-ui-e2e-harness", "full-ui-e2e-shard"):
            with self.subTest(job=job):
                self.assertIn(f"{job}:$", self.jobs["full-ui-e2e"])

    def test_the_end_to_end_work_is_split_rather_than_reduced(self) -> None:
        """Sharding shortens the wall clock; it must not quietly narrow what runs."""
        shard = self.jobs["full-ui-e2e-shard"]
        self.assertIn("npx playwright test", shard)
        self.assertNotIn("--grep", shard)
        self.assertNotIn("testIgnore", shard)
        self.assertIn("fail-fast: true", shard)
        # The JVM harness is a separate real-process test and must keep running in full.
        self.assertIn(
            "HumanTaskConfirmationBrowserProcessIntegrationTest", self.jobs["full-ui-e2e-harness"]
        )

    def test_every_artifact_upload_name_is_unique(self) -> None:
        """Two jobs uploading one name is an upload failure, and a shard makes that easy to reach."""
        names = re.findall(r"(?m)^          name: ([^\n]+)$", self.contents)
        uploads = [
            name
            for name in names
            if name.startswith(("ravenroot-", "playwright-report", "human-task-confirmation"))
        ]
        self.assertEqual(
            sorted({name for name in uploads if uploads.count(name) > 1}),
            ["ravenroot-backend-build", "ravenroot-plugins", "ravenroot-ui"],
            "only the download side may repeat a producer's artifact name",
        )

    def test_feature_feedback_is_ultralight_and_does_not_repeat_test_suites(self) -> None:
        fast = job_blocks(FAST_WORKFLOW.read_text(encoding="utf-8"))["fast-policy"]
        all_fast = FAST_WORKFLOW.read_text(encoding="utf-8")
        self.assertNotIn("python3 -m unittest", all_fast)
        self.assertNotIn("./scripts/tests/", all_fast)
        self.assertNotIn("npm test", all_fast)
        self.assertNotIn("clean install", all_fast)
        self.assertIn("fetch-depth: 0", fast)

    def test_operational_configuration_audit_runs_once_in_the_full_tier(self) -> None:
        block = self.jobs["full-python-contracts"]
        self.assertIn("fetch-depth: 0", block)
        self.assertEqual(1, block.count("python3 -m unittest scripts.tests.test_audit_operational_configuration"))
        self.assertEqual(1, block.count("python3 scripts/audit_operational_configuration.py --check"))

    def test_expensive_regressions_wait_for_the_light_preflight(self) -> None:
        preflight = declared_needs(self.jobs["full-preflight"])
        self.assertEqual(
            preflight,
            {
                "release-classification", "docs-site", "full-docs-policy", "full-source-policy",
                "full-ui-audit", "full-ui-unit-tests", "full-ui-build", "backend-build",
            },
        )
        for job in (
            "full-python-contracts", "full-shell-contracts", "full-ui-e2e-harness",
            "full-ui-e2e-shard", "full-backend-tests", "backend-test", "full-plugin-boundary",
            "full-api-documentation", "full-runtime-auth-smoke", "full-runtime-jar-smoke",
        ):
            with self.subTest(job=job):
                self.assertIn("full-preflight", declared_needs(self.jobs[job]))

    def test_container_smoke_waits_for_every_parallel_regression(self) -> None:
        self.assertIn("full-regression", declared_needs(self.jobs["full-runtime-container-smoke"]))


if __name__ == "__main__":
    unittest.main()
