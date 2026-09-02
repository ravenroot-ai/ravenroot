import unittest

from scripts.check_dependabot_routing import (
    ROOT,
    RoutingPolicyError,
    check_dependabot_config,
    check_ci_workflow,
    check_pinned_workflow,
    check_repository,
)


class DependabotRoutingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = (ROOT / ".github/dependabot.yml").read_text(encoding="utf-8")
        cls.authorization = (ROOT / ".github/workflows/authorize-dependabot.yml").read_text(
            encoding="utf-8"
        )
        cls.routing = (ROOT / ".github/workflows/route-dependabot.yml").read_text(
            encoding="utf-8"
        )

    def test_repository_contract_is_valid(self):
        check_repository()

    def test_every_version_update_must_target_dev(self):
        changed = self.config.replace("target-branch: dev", "target-branch: main", 1)
        with self.assertRaisesRegex(RoutingPolicyError, "must target dev"):
            check_dependabot_config(changed)

    def test_all_dependency_roots_are_required(self):
        start = self.config.index("  - package-ecosystem: npm\n")
        changed = self.config[:start]
        with self.assertRaisesRegex(RoutingPolicyError, "update roots changed"):
            check_dependabot_config(changed)

    def test_authorization_rejects_extra_executable_steps(self):
        changed = self.authorization.replace(
            "    steps:\n", "    steps:\n      - run: echo unreviewed\n", 1
        )
        with self.assertRaisesRegex(RoutingPolicyError, "explicit security review"):
            check_pinned_workflow("authorize-dependabot.yml", changed)

    def test_authorization_requires_authentic_dependabot_metadata(self):
        changed = self.authorization.replace(
            "      github.event.pull_request.user.login == 'dependabot[bot]' &&\n", "", 1
        )
        with self.assertRaisesRegex(RoutingPolicyError, "explicit security review"):
            check_pinned_workflow("authorize-dependabot.yml", changed)

    def test_routing_rejects_checkout_or_other_steps(self):
        changed = self.routing.replace(
            "    steps:\n", "    steps:\n      - uses: actions/checkout@untrusted\n", 1
        )
        with self.assertRaisesRegex(RoutingPolicyError, "explicit security review"):
            check_pinned_workflow("route-dependabot.yml", changed)

    def test_routing_rejects_broader_write_permissions(self):
        changed = self.routing.replace(
            "      pull-requests: write", "      contents: write\n      pull-requests: write", 1
        )
        with self.assertRaisesRegex(RoutingPolicyError, "explicit security review"):
            check_pinned_workflow("route-dependabot.yml", changed)

    def test_routing_requires_source_job_and_current_pr_revalidation(self):
        for fragment in (
            '.name == "authorize-dependabot-routing" and .conclusion == "success"',
            '.user.login == "dependabot[bot]" and',
        ):
            with self.subTest(fragment=fragment):
                changed = self.routing.replace(fragment, "true", 1)
                with self.assertRaisesRegex(RoutingPolicyError, "explicit security review"):
                    check_pinned_workflow("route-dependabot.yml", changed)

    def test_dispatched_ci_requires_authorized_current_pull_request(self):
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        changed = ci.replace('.user.login == "dependabot[bot]" and', "true and", 1)
        with self.assertRaisesRegex(RoutingPolicyError, "routing contract is missing"):
            check_ci_workflow(changed)

    def test_ordinary_ci_rejects_write_permissions(self):
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        changed = ci.replace("      actions: read", "      actions: write", 1)
        with self.assertRaisesRegex(RoutingPolicyError, "secretless and non-publishing"):
            check_ci_workflow(changed)

    def test_every_dispatched_ci_job_checks_out_the_merge_commit(self):
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        changed = ci.replace("          ref: ${{ inputs.merge_sha || github.sha }}\n", "", 1)
        with self.assertRaisesRegex(RoutingPolicyError, "every ordinary CI checkout"):
            check_ci_workflow(changed)


if __name__ == "__main__":
    unittest.main()
