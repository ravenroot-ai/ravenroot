"""Contract tests for the gate that decides whether a CI event verified anything."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.classify_main_change import ROUTED_INPUTS
from scripts.ci_required import (
    ALLOWED_TIERS_BY_EVENT,
    CLASSIFICATION_JOB,
    FAST_GATE_JOB,
    FAST_JOBS,
    FAST_WORKFLOW,
    CLASSIFICATION_GUARD,
    FULL_FUNCTIONAL_JOBS,
    ROUTED_INPUT_BINDINGS,
    ROUTING_GUARD,
    WORKFLOW_DIRECTORY,
    job_blocks,
    verify_event,
    verify_dispatch_routing,
    verify_full_coverage_on_dev_pull_requests,
    verify_promotion_evidence,
    verify_fast_results,
    verify_fast_workflow,
    verify_single_publisher,
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

    def test_the_admission_tier_is_retired(self) -> None:
        """Point 4: nothing may classify an event into the old cheap diagnostic tier any more."""
        self.assertNotIn("admission", REQUIRED_BY_TIER)
        for tiers in ALLOWED_TIERS_BY_EVENT.values():
            self.assertNotIn("admission", tiers)
        for job in ("full-ui-e2e", "full-ui-e2e-shard", "full-backend-tests"):
            self.assertIn(job, REQUIRED_BY_TIER["full"])

    def test_postmerge_repeats_no_functional_job(self) -> None:
        self.assertEqual(REQUIRED_BY_TIER["postmerge"], frozenset())


class PromotionEvidenceTest(unittest.TestCase):
    SHA = "9e75c71c061bdc7390dace58be761d21db4b4ad3"

    def run_of(self, event, conclusion="success", sha=None, path=".github/workflows/ci.yml", branch="dev"):
        return {"event": event, "conclusion": conclusion, "head_sha": sha or self.SHA, "path": path,
                "head_branch": branch}

    def test_a_full_run_that_passed_on_the_commit_is_evidence(self) -> None:
        for event, branch in (("merge_group", "gh-readonly-queue/dev/pr-313-191accac"),
                              ("workflow_dispatch", "dev")):
            with self.subTest(event=event):
                self.assertEqual(
                    verify_promotion_evidence({"workflow_runs": [self.run_of(event, branch=branch)]}, self.SHA), [])

    def test_only_a_run_on_dev_or_its_queue_is_evidence(self) -> None:
        """A dispatch elsewhere can test another commit than the one it is recorded on (routing inputs)."""
        for branch in ("feature/issue_310", "dependabot/maven/x", "main", "gh-readonly-queue/main/pr-1-a", None):
            with self.subTest(branch=branch):
                runs = [self.run_of("workflow_dispatch", branch=branch)]
                self.assertTrue(verify_promotion_evidence({"workflow_runs": runs}, self.SHA))

    def test_the_bare_postmerge_push_is_not_full_tier_evidence(self) -> None:
        self.assertTrue(
            verify_promotion_evidence({"workflow_runs": [self.run_of("push", branch="dev")]}, self.SHA)
        )

    def test_the_301_case_is_refused(self) -> None:
        """Only pull-request runs, or a full run of another commit: nothing verified this one."""
        for runs in (
            [self.run_of("pull_request")],
            [self.run_of("push", sha="0" * 40)],
            [self.run_of("push", conclusion="failure")],
            [self.run_of("workflow_dispatch", conclusion=None)],
            [self.run_of("push", path=".github/workflows/ci-fast.yml")],
            [],
        ):
            with self.subTest(runs=runs):
                self.assertTrue(verify_promotion_evidence({"workflow_runs": runs}, self.SHA))

    def test_missing_or_unbound_evidence_is_refused(self) -> None:
        self.assertTrue(verify_promotion_evidence(None, self.SHA))
        self.assertTrue(verify_promotion_evidence({"workflow_runs": [self.run_of("push")]}, ""))


class DispatchRoutingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.contents = WORKFLOW.read_text(encoding="utf-8")
        blocks = job_blocks(self.contents)
        self.classification, self.gate = blocks["release-classification"], blocks[GATE_JOB]

    def test_both_guards_run_before_any_checkout(self) -> None:
        self.assertEqual(verify_dispatch_routing(self.classification, self.gate), [])
        self.assertEqual(verify_workflow(self.contents), [])

    def test_a_missing_or_displaced_guard_is_refused(self) -> None:
        """A guard after the checkout would run from the tree merge_sha names."""
        for guard in (ROUTING_GUARD, CLASSIFICATION_GUARD):
            with self.subTest(guard=guard.splitlines()[0]):
                removed = self.contents.replace(guard, "", 1)
                self.assertTrue(verify_workflow(removed))
        checkout = "      - name: Check out repository history\n"
        displaced = self.classification.replace(ROUTING_GUARD, "", 1).replace(checkout, checkout + ROUTING_GUARD, 1)
        self.assertTrue(verify_dispatch_routing(displaced, self.gate))
        weakened = self.classification.replace("inputs.merge_sha }}\n", "}}\n", 1)
        self.assertTrue(verify_dispatch_routing(weakened, self.gate))

    def test_an_unwired_routing_input_is_refused(self) -> None:
        for binding in ROUTED_INPUT_BINDINGS:
            with self.subTest(binding=binding):
                self.assertTrue(any(binding in problem for problem in verify_workflow(self.contents.replace(binding, ""))))

    def test_the_bindings_are_the_classifier_s_and_the_workflow_s_inputs(self) -> None:
        bound = {line.split(": ", 1)[0]: line.split("inputs.", 1)[1].rstrip(" }") for line in ROUTED_INPUT_BINDINGS}
        self.assertEqual(bound, {variable: name for name, variable in ROUTED_INPUTS.items()})
        triggers = self.contents.split("\njobs:\n", 1)[0]
        for name in ROUTED_INPUTS:
            self.assertIn(f"\n      {name}:\n", triggers)
            self.assertIn(f"${{{{ inputs.{name} }}}}", ROUTING_GUARD)


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
            "    needs: [release-classification, full-preflight, full-ui-build]\n"
            "    if: needs.release-classification.outputs.tier == 'full'",
            "  full-ui-e2e-shard:\n    name: full-ui-e2e-shard"
            " (${{ matrix.shard }}/${{ strategy.job-total }})\n"
            "    needs: [release-classification, full-preflight, full-ui-build]\n"
            "    if: needs.release-classification.outputs.tier == 'nightly'",
            1,
        )
        self.assertNotEqual(broken, self.contents)
        self.assertTrue(any("full-ui-e2e-shard" in problem for problem in verify_workflow(broken)))

    def test_a_job_dropped_from_the_gate_dependencies_is_refused(self) -> None:
        gate = job_blocks(self.contents)[GATE_JOB]
        broken = self.contents.replace(gate, gate.replace("      - full-backend-tests\n", "", 1), 1)
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

    def test_the_admission_tier_is_gone_rather_than_unreachable(self) -> None:
        """Point 4: nothing reaches `admission` any more, so its jobs and condition are removed too."""
        self.assertNotIn("'admission'", self.contents)
        defined = set(job_blocks(self.contents))
        for job in ("admission-policy", "admission-ui", "admission-backend"):
            with self.subTest(job=job):
                self.assertNotIn(job, defined)

    def test_every_gated_job_belongs_to_exactly_one_class(self) -> None:
        self.assertEqual(set(POLICY_JOBS) & set(PRODUCT_JOBS), set())
        self.assertEqual(set(GATED_JOBS), set(POLICY_JOBS) | set(PRODUCT_JOBS))
        self.assertEqual(set(GATED_JOBS), FULL_FUNCTIONAL_JOBS)

    def test_the_declared_check_contexts_are_distinct(self) -> None:
        for tier in REQUIRED_BY_TIER:
            with self.subTest(tier=tier):
                contexts = required_contexts(tier)
                self.assertEqual(len(contexts), len(set(contexts)))


class VerifyEventTest(unittest.TestCase):
    """The tier is checked against the event, independently of the classifier that produced it."""

    def test_every_modelled_event_accepts_its_tiers(self) -> None:
        repo = "ravenroot-ai/ravenroot"
        for key, tiers in ALLOWED_TIERS_BY_EVENT.items():
            event, ref, head = (*key, None)[:3]
            head_ref = {"dev": "dev", "hotfix": "hotfix/x"}.get(head, "")
            for tier in tiers:
                with self.subTest(key=key, tier=tier):
                    base, pushed = (ref, "x") if event == "pull_request" else ("", ref or "x")
                    self.assertEqual(verify_event(event, base, pushed, tier, head_ref, repo, repo), [])

    def test_the_promotion_is_granted_to_this_repositorys_dev_alone(self) -> None:
        """Independently of the classifier: a promotion from any other head is refused."""
        repo = "ravenroot-ai/ravenroot"
        self.assertEqual(verify_event("pull_request", "main", "x", "promotion", "dev", repo, repo), [])
        self.assertEqual(verify_event("pull_request", "main", "x", "full", "hotfix/cve", repo, repo), [])
        for head_ref, head_repository in (("feature/x", repo), ("dev", "fork/ravenroot"), ("hotfix/x", "fork/ravenroot")):
            with self.subTest(head=f"{head_repository}:{head_ref}"):
                self.assertTrue(verify_event("pull_request", "main", "x", "promotion", head_ref, head_repository, repo))
        self.assertTrue(verify_event("pull_request", "main", "x", "promotion", "hotfix/cve", repo, repo),
                        "a hotfix never passed through dev: it may not borrow a promotion's green")

    def test_each_dev_stage_accepts_only_its_own_tier(self) -> None:
        for event, base, ref, expected in (
            ("pull_request", "dev", "feature/x", "full"),
            ("push", "", "dev", "postmerge"),
            ("workflow_dispatch", "", "feature/x", "full"),
            ("merge_group", "", "gh-readonly-queue/dev/pr-1", "full"),
            ("schedule", "", "main", "full"),
        ):
            for tier in ("full", "postmerge", "promotion", "docs", "admission", "fast", ""):
                if tier == expected:
                    continue
                with self.subTest(event=event, tier=tier):
                    self.assertTrue(verify_event(event, base, ref, tier))

    def test_the_promotion_cannot_be_classified_as_anything_else(self) -> None:
        for tier in ("full", "docs", ""):
            with self.subTest(tier=tier):
                self.assertTrue(verify_event("pull_request", "main", "dev", tier))

    def test_an_unmodelled_event_is_refused_rather_than_assumed(self) -> None:
        for event, base, ref in (
            ("pull_request_target", "dev", "feature/x"),
            ("push", "", "feature/x"),
            ("pull_request", "feature/y", "feature/x"),
            ("", "", ""),
        ):
            with self.subTest(event=event, base=base, ref=ref):
                self.assertTrue(verify_event(event, base, ref, "full"))


class FullCoverageOnDevPullRequestsTest(unittest.TestCase):
    """Point 4's structural guard: no tier missing part of the full suite may be reachable here.

    This is not merely a comment or a convention checked by other tests incidentally — it is a
    standing assertion over the tables themselves, so a future edit that reintroduces a partial tier
    for a pull-request head into `dev` fails this check even before the workflow or an event is
    considered.
    """

    def test_the_committed_tables_carry_full_coverage(self) -> None:
        self.assertEqual(verify_full_coverage_on_dev_pull_requests(), [])

    def test_reintroducing_a_partial_tier_here_is_refused(self) -> None:
        """The exact regression point 4 exists to close, caught structurally rather than by review."""
        partial_tier = "admission"
        self.assertNotIn(partial_tier, REQUIRED_BY_TIER)
        original_allowed = ALLOWED_TIERS_BY_EVENT[("pull_request", "dev")]
        try:
            REQUIRED_BY_TIER[partial_tier] = frozenset({"full-source-policy"})
            ALLOWED_TIERS_BY_EVENT[("pull_request", "dev")] = frozenset({partial_tier})
            problems = verify_full_coverage_on_dev_pull_requests()
            self.assertTrue(any(partial_tier in problem for problem in problems), problems)
        finally:
            del REQUIRED_BY_TIER[partial_tier]
            ALLOWED_TIERS_BY_EVENT[("pull_request", "dev")] = original_allowed

    def test_an_event_key_unrelated_to_a_dev_pull_request_is_not_constrained(self) -> None:
        """The guard is scoped to `("pull_request", "dev")`; a promotion carries no functional job."""
        self.assertEqual(REQUIRED_BY_TIER["promotion"], frozenset())
        self.assertIn("promotion", ALLOWED_TIERS_BY_EVENT[("pull_request", "main", "dev")])
        self.assertEqual(verify_full_coverage_on_dev_pull_requests(), [])


def workflow_directory(files: dict[str, str]) -> Path:
    directory = Path(tempfile.mkdtemp())
    for name, contents in files.items():
        (directory / name).write_text(contents, encoding="utf-8")
    return directory


class SinglePublisherTest(unittest.TestCase):
    """The written constraint: only ci.yml may publish `ci-required`."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.fast = FAST_WORKFLOW.read_text(encoding="utf-8")

    def test_the_committed_workflows_respect_it(self) -> None:
        self.assertEqual(verify_single_publisher(WORKFLOW_DIRECTORY), [])

    def test_a_fast_aggregator_named_ci_required_is_refused(self) -> None:
        """The exact regression the constraint exists to prevent."""
        broken = self.fast.replace(f"  {FAST_GATE_JOB}:\n    name: {FAST_GATE_JOB}\n",
                                   f"  {FAST_GATE_JOB}:\n    name: {GATE_JOB}\n", 1)
        self.assertNotEqual(broken, self.fast)
        problems = verify_single_publisher(workflow_directory({"ci-fast.yml": broken}))
        self.assertTrue(any(GATE_JOB in problem for problem in problems), problems)
        self.assertTrue(verify_fast_workflow(broken))

    def test_a_skippable_ci_required_job_anywhere_else_is_refused(self) -> None:
        """A skipped job counts as passed for a required check, so `if: false` is no defence."""
        other = (
            "name: Other\non:\n  push:\n    branches: ['feature/**']\njobs:\n"
            f"  {GATE_JOB}:\n    if: false\n    runs-on: ubuntu-24.04\n    steps:\n      - run: true\n"
        )
        problems = verify_single_publisher(workflow_directory({"other.yml": other}))
        self.assertTrue(any("other.yml" in problem for problem in problems), problems)

    def test_a_name_expression_that_can_render_ci_required_is_refused(self) -> None:
        other = (
            "name: Other\non:\n  push:\n    branches: ['feature/**']\njobs:\n"
            "  gate:\n    name: ${{ github.ref == 'x' && 'ci-required' || 'gate' }}\n"
            "    runs-on: ubuntu-24.04\n    steps:\n      - run: true\n"
        )
        self.assertTrue(verify_single_publisher(workflow_directory({"other.yaml": other})))


class FastWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fast = FAST_WORKFLOW.read_text(encoding="utf-8")

    def test_the_committed_fast_workflow_matches_its_topology(self) -> None:
        self.assertEqual(verify_fast_workflow(self.fast), [])

    def test_a_fast_run_on_pull_requests_is_refused(self) -> None:
        broken = self.fast.replace(
            "on:\n  push:\n    branches: ['feature/**']\n",
            "on:\n  push:\n    branches: ['feature/**']\n  pull_request:\n    branches: [dev]\n", 1)
        self.assertNotEqual(broken, self.fast)
        self.assertTrue(verify_fast_workflow(broken))

    def test_a_fast_gate_that_could_be_skipped_is_refused(self) -> None:
        broken = self.fast.replace(f"    name: {FAST_GATE_JOB}\n    if: always()\n",
                                   f"    name: {FAST_GATE_JOB}\n    if: success()\n", 1)
        self.assertNotEqual(broken, self.fast)
        self.assertTrue(verify_fast_workflow(broken))

    def test_an_undeclared_fast_job_is_refused(self) -> None:
        broken = self.fast + (
            "\n  fast-extra:\n    name: fast-extra\n    runs-on: ubuntu-24.04\n"
            "    steps:\n      - run: true\n"
        )
        self.assertTrue(any("fast-extra" in problem for problem in verify_fast_workflow(broken)))

    def test_every_fast_job_must_succeed(self) -> None:
        passing = {job: "success" for job in FAST_JOBS}
        self.assertEqual(verify_fast_results(passing), [])
        for job in sorted(FAST_JOBS):
            for result in ("skipped", "failure", "cancelled"):
                with self.subTest(job=job, result=result):
                    self.assertTrue(verify_fast_results({**passing, job: result}))


class TriggerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contents = WORKFLOW.read_text(encoding="utf-8")

    def test_a_missing_merge_group_trigger_is_refused(self) -> None:
        """Without it every queued pull request times out waiting for ci-required."""
        broken = self.contents.replace("  merge_group:\n    types: [checks_requested]\n", "", 1)
        self.assertNotEqual(broken, self.contents)
        self.assertTrue(any("merge_group" in problem for problem in verify_workflow(broken)))

    def test_a_dispatchable_lighter_tier_is_refused(self) -> None:
        broken = self.contents.replace("        options: [full]\n",
                                       "        options: [full, docs]\n", 1)
        self.assertNotEqual(broken, self.contents)
        self.assertTrue(any("tier" in problem for problem in verify_workflow(broken)))

    def test_the_full_tier_on_work_branch_pushes_is_refused(self) -> None:
        broken = self.contents.replace("  push:\n    branches: [dev, main]\n",
                                       "  push:\n    branches: [dev, main, 'feature/**']\n", 1)
        self.assertNotEqual(broken, self.contents)
        self.assertTrue(any("push" in problem for problem in verify_workflow(broken)))

    def test_the_committed_pull_request_trigger_names_dev_and_main(self) -> None:
        """Point 4's proof: a pull request's own event must publish ci-required for it."""
        self.assertEqual(verify_workflow(self.contents), [])
        self.assertIn("  pull_request:\n    branches: [dev, main]\n", self.contents)

    def test_a_pull_request_trigger_missing_dev_is_refused(self) -> None:
        """The exact regression point 4's proof exists to close: `dev` dropped from the trigger.

        Removing this trigger for `dev` was tried and proven wrong: a dispatched run's check suite
        never enters a pull request's rollup, so `ci-required` was not red but absent, and the pull
        request could never be queued. Losing `dev` here again must be refused.
        """
        broken = self.contents.replace(
            "  pull_request:\n    branches: [dev, main]\n",
            "  pull_request:\n    branches: [main]\n", 1,
        )
        self.assertNotEqual(broken, self.contents)
        self.assertTrue(any("pull_request" in problem for problem in verify_workflow(broken)))

    def test_a_feature_branch_trigger_on_ci_yml_is_refused(self) -> None:
        """`feature/**` belongs to ci-fast.yml alone; ci.yml running the full tier on it is refused."""
        broken = self.contents.replace(
            "  merge_group:\n    types: [checks_requested]\n",
            "  merge_group:\n    types: [checks_requested]\n"
            "  push_2:\n    branches: ['feature/**']\n",
            1,
        )
        self.assertNotEqual(broken, self.contents)
        self.assertTrue(any("feature/" in problem for problem in verify_workflow(broken)))

    def test_a_comment_naming_feature_branches_does_not_trip_the_check(self) -> None:
        """The on: block's own explanatory prose names `feature/**`; it must not be mistaken for it."""
        commented = self.contents.replace(
            "  merge_group:\n    types: [checks_requested]\n",
            "  # feature/** must never reach this workflow; ci-fast.yml alone runs it.\n"
            "  merge_group:\n    types: [checks_requested]\n",
            1,
        )
        self.assertNotEqual(commented, self.contents)
        self.assertEqual(verify_workflow(commented), [])

    def test_a_trailing_comment_naming_feature_branches_still_trips_the_check(self) -> None:
        """Only a whole comment line is stripped; a trailing one is not, so this still fires.

        `#` can open inside a quoted scalar, so truncating a line at its first `#` is unsafe in
        general; this documents that known, accepted limitation rather than a bug to fix.
        """
        commented = self.contents.replace(
            "  merge_group:\n    types: [checks_requested]\n",
            "  merge_group:\n    types: [checks_requested]  # not feature/**\n",
            1,
        )
        self.assertNotEqual(commented, self.contents)
        self.assertTrue(any("feature/" in problem for problem in verify_workflow(commented)))


if __name__ == "__main__":
    unittest.main()
