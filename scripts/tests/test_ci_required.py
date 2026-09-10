"""Contract tests for the gate that decides whether a CI event verified anything."""

from __future__ import annotations

import unittest

from scripts.ci_required import (
    CLASSIFICATION_JOB,
    job_blocks,
    E2E_SHARDS,
    GATED_JOBS,
    GATE_JOB,
    POLICY_JOBS,
    PRODUCT_JOBS,
    REQUIRED_BY_TIER,
    WORKFLOW,
    parse_results,
    required_contexts,
    verify_results,
    verify_workflow,
)


def results_for(tier: str, **overrides: str) -> dict[str, str]:
    """Build the result payload a correct run of this tier would report."""
    required = REQUIRED_BY_TIER[tier]
    results = {CLASSIFICATION_JOB: "success"}
    results.update({job: "success" if job in required else "skipped" for job in GATED_JOBS})
    results.update(overrides)
    return results


class VerifyResultsTest(unittest.TestCase):
    def test_a_correct_run_of_every_tier_is_accepted(self) -> None:
        for tier in REQUIRED_BY_TIER:
            with self.subTest(tier=tier):
                self.assertEqual(verify_results(tier, results_for(tier)), [])

    def test_a_required_job_that_was_skipped_is_refused(self) -> None:
        """Criterion 3. A job that did not run cannot vouch for the change."""
        for job in sorted(REQUIRED_BY_TIER["full"]):
            with self.subTest(job=job):
                problems = verify_results("full", results_for("full", **{job: "skipped"}))
                self.assertTrue(
                    any(job in problem and "SKIPPED" in problem for problem in problems),
                    f"{job} was skipped and the gate did not refuse: {problems}",
                )

    def test_the_historical_defect_is_refused(self) -> None:
        """The exact state that made every pull request into `dev` green without verifying it.

        The heavy jobs were gated on a tier the event never received, so they were skipped, and the
        gate's own table agreed they should be. Whatever the classification says, a `full` event in
        which the functional suite never ran is not a pass.
        """
        skipped_suite = {job: "skipped" for job in PRODUCT_JOBS}
        problems = verify_results("full", results_for("full", **skipped_suite))
        self.assertTrue(problems)
        for job in PRODUCT_JOBS:
            self.assertTrue(any(job in problem for problem in problems), job)

    def test_a_required_job_that_failed_or_was_cancelled_is_refused(self) -> None:
        for result in ("failure", "cancelled", "unknown"):
            with self.subTest(result=result):
                problems = verify_results("full", results_for("full", **{"full-ui-e2e": result}))
                self.assertTrue(any("full-ui-e2e" in problem for problem in problems))

    def test_a_failing_classification_leaves_an_unusable_tier(self) -> None:
        for tier in ("", "fast", "unexpected"):
            with self.subTest(tier=tier):
                self.assertTrue(verify_results(tier, results_for("full")))

    def test_an_unobserved_job_is_refused(self) -> None:
        results = results_for("full")
        del results["full-backend-tests"]
        problems = verify_results("full", results)
        self.assertTrue(any("full-backend-tests" in problem for problem in problems))

    def test_an_undeclared_job_is_refused(self) -> None:
        problems = verify_results("full", results_for("full", **{"newly-added-job": "success"}))
        self.assertTrue(any("newly-added-job" in problem for problem in problems))

    def test_a_job_that_ran_outside_its_tier_is_refused(self) -> None:
        """A disagreement between the workflow and the topology is never resolved in favour of green."""
        problems = verify_results("promotion", results_for("promotion", **{"full-ui-e2e": "success"}))
        self.assertTrue(any("full-ui-e2e" in problem for problem in problems))
        problems = verify_results("docs", results_for("docs", **{"full-backend-tests": "success"}))
        self.assertTrue(any("full-backend-tests" in problem for problem in problems))

    def test_the_promotion_carries_no_functional_job(self) -> None:
        """Criterion 2. `dev` to `main` re-verifies nothing the pull requests already verified."""
        self.assertEqual(REQUIRED_BY_TIER["promotion"], frozenset())
        self.assertEqual(required_contexts("promotion"), [CLASSIFICATION_JOB, GATE_JOB])
        problems = verify_results("promotion", results_for("promotion"))
        self.assertEqual(problems, [])

    def test_a_pull_request_into_dev_demands_the_end_to_end_suite(self) -> None:
        """Criterion 1, as far as a unit test can carry it; the pull request demonstrates the rest."""
        for job in ("full-ui-e2e", "full-ui-e2e-shard", "full-ui-e2e-harness", "full-ui-audit"):
            self.assertIn(job, REQUIRED_BY_TIER["full"])


class ParseResultsTest(unittest.TestCase):
    def test_reads_the_needs_payload_github_supplies(self) -> None:
        payload = '{"a": {"result": "success", "outputs": {}}, "b": {"result": "skipped"}}'
        self.assertEqual(parse_results(payload), {"a": "success", "b": "skipped"})

    def test_a_result_free_entry_is_unknown_rather_than_absent(self) -> None:
        self.assertEqual(parse_results('{"a": {}}'), {"a": "unknown"})

    def test_rejects_a_payload_that_is_not_an_object(self) -> None:
        with self.assertRaises(ValueError):
            parse_results("[]")


class VerifyWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contents = WORKFLOW.read_text(encoding="utf-8")

    def test_the_committed_workflow_matches_the_topology(self) -> None:
        self.assertEqual(verify_workflow(self.contents), [])

    def test_a_re_gated_job_is_refused(self) -> None:
        """The drift that caused this issue, caught at its source rather than after the merge."""
        broken = self.contents.replace(
            "  full-ui-e2e-shard:\n    name: full-ui-e2e-shard"
            " (${{ matrix.shard }}/${{ strategy.job-total }})\n"
            "    needs: [release-classification, full-ui-build]\n"
            "    if: needs.release-classification.outputs.tier == 'full'",
            "  full-ui-e2e-shard:\n    name: full-ui-e2e-shard"
            " (${{ matrix.shard }}/${{ strategy.job-total }})\n"
            "    needs: [release-classification, full-ui-build]\n"
            "    if: needs.release-classification.outputs.tier == 'nightly'",
            1,
        )
        self.assertNotEqual(broken, self.contents)
        self.assertTrue(any("full-ui-e2e-shard" in problem for problem in verify_workflow(broken)))

    def test_a_job_dropped_from_the_gate_dependencies_is_refused(self) -> None:
        broken = self.contents.replace("      - full-backend-tests\n", "", 1)
        self.assertNotEqual(broken, self.contents)
        problems = verify_workflow(broken)
        self.assertTrue(any("full-backend-tests" in problem for problem in problems))

    def test_a_gate_that_could_be_skipped_is_refused(self) -> None:
        broken = self.contents.replace(
            "  ci-required:\n    name: ci-required\n    if: always()\n",
            "  ci-required:\n    name: ci-required\n    if: success()\n",
            1,
        )
        self.assertNotEqual(broken, self.contents)
        self.assertTrue(any(GATE_JOB in problem for problem in verify_workflow(broken)))

    def test_a_literal_shard_denominator_is_refused(self) -> None:
        """A count that falls behind the matrix leaves whole shards unexecuted and everything green."""
        broken = self.contents.replace(
            "--shard=${{ matrix.shard }}/${{ strategy.job-total }}",
            f"--shard=${{{{ matrix.shard }}}}/{E2E_SHARDS}",
            1,
        )
        self.assertNotEqual(broken, self.contents)
        self.assertTrue(any("shard" in problem for problem in verify_workflow(broken)))

    def test_a_job_id_github_accepts_but_the_parser_misses_is_refused(self) -> None:
        """An unobservable job is the same defect as a skipped one: the gate goes green over nothing.

        GitHub allows `_` and uppercase in a job identifier. A parser matching less than GitHub does
        would let such a job be added to `ci.yml`, stay out of the gate's `needs`, and fail while
        `ci-required` reported success.
        """
        for job_id in ("full_new_check", "fullNewCheck"):
            with self.subTest(job_id=job_id):
                broken = self.contents + (
                    f"\n  {job_id}:\n"
                    f"    name: {job_id}\n"
                    "    runs-on: ubuntu-24.04\n"
                    "    steps:\n"
                    "      - run: exit 1\n"
                )
                problems = verify_workflow(broken)
                self.assertTrue(
                    any(job_id in problem for problem in problems),
                    f"{job_id} is invisible to the gate: {problems}",
                )

    def test_the_supervisor_wiring_is_asserted_by_a_job_the_full_tier_requires(self) -> None:
        """`fast-support-build` carried this through `./dev.sh setup`; it had to move, not vanish."""
        block = job_blocks(self.contents)["full-source-policy"]
        self.assertIn("./dev.sh verify-supervisor", block)
        self.assertIn("full-source-policy", REQUIRED_BY_TIER["full"])

    def test_the_fast_tier_is_gone_rather_than_unreachable(self) -> None:
        """The jobs and the tier are gone. Naming a retired job in a comment is not a survival."""
        self.assertNotIn("'fast'", self.contents)
        defined = set(job_blocks(self.contents))
        for job in ("fast-product-build", "fast-support-build", "fast-tooling-contracts"):
            with self.subTest(job=job):
                self.assertNotIn(job, defined)

    def test_every_gated_job_belongs_to_exactly_one_class(self) -> None:
        self.assertEqual(set(POLICY_JOBS) & set(PRODUCT_JOBS), set())
        self.assertEqual(set(GATED_JOBS), set(POLICY_JOBS) | set(PRODUCT_JOBS))

    def test_the_declared_check_contexts_are_distinct(self) -> None:
        for tier in REQUIRED_BY_TIER:
            with self.subTest(tier=tier):
                contexts = required_contexts(tier)
                self.assertEqual(len(contexts), len(set(contexts)))


if __name__ == "__main__":
    unittest.main()
