import unittest

from scripts.check_dependabot_routing import (
    ROOT,
    RoutingPolicyError,
    check_dependabot_config,
    check_repository,
    check_routing_workflow,
)


class DependabotRoutingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = (ROOT / ".github/dependabot.yml").read_text(encoding="utf-8")
        cls.workflow = (ROOT / ".github/workflows/route-dependabot.yml").read_text(
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

    def test_routing_rejects_checkout_or_other_actions(self):
        changed = self.workflow.replace(
            "    steps:\n", "    steps:\n      - uses: actions/checkout@untrusted\n", 1
        )
        with self.assertRaisesRegex(RoutingPolicyError, "must not check out"):
            check_routing_workflow(changed)

    def test_routing_rejects_broader_write_permissions(self):
        changed = self.workflow.replace(
            "      pull-requests: write", "      contents: write\n      pull-requests: write", 1
        )
        with self.assertRaisesRegex(RoutingPolicyError, "write permissions changed"):
            check_routing_workflow(changed)

    def test_routing_requires_authentic_dependabot_metadata(self):
        changed = self.workflow.replace(
            "      github.event.pull_request.user.login == 'dependabot[bot]' &&\n", "", 1
        )
        with self.assertRaisesRegex(RoutingPolicyError, "user.login"):
            check_routing_workflow(changed)


if __name__ == "__main__":
    unittest.main()
