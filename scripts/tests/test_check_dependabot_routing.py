import unittest

from scripts.check_dependabot_routing import (
    ROOT,
    RoutingPolicyError,
    check_dependabot_config,
    check_ci_workflow,
    check_configurator_workflow,
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


class StructuralCheckoutBindingTest(unittest.TestCase):
    def setUp(self):
        self.ci = (ROOT / '.github/workflows/ci.yml').read_text()
        self.configurator = (ROOT / '.github/workflows/configurator.yml').read_text()
        self.ref = '          ref: ${{ inputs.merge_sha || github.sha }}\n'

    def test_reusable_candidate_ref_does_not_count_as_an_ordinary_checkout(self):
        self.assertIn('candidate_ref: ${{ inputs.merge_sha || github.sha }}', self.ci)
        check_ci_workflow(self.ci)
        check_configurator_workflow(self.configurator)

    def test_missing_real_ref_cannot_be_compensated_by_candidate_or_fake_refs(self):
        broken = self.ci.replace(self.ref, '', 1)
        variants = [broken, broken.replace('    steps:\n',
            '    steps:\n      - name: Unrelated input\n        uses: example/action@v1\n        with:\n' + self.ref, 1),
            broken.replace('    steps:\n', '    steps:\n      - run: |\n            echo "ref: ${{ inputs.merge_sha || github.sha }}"\n', 1)]
        for contents in variants:
            with self.subTest(contents=contents[:20]), self.assertRaisesRegex(RoutingPolicyError, 'every ordinary CI checkout'):
                check_ci_workflow(contents)

    def test_checkout_ref_must_be_unique_and_inside_its_own_with_mapping(self):
        variants = [self.ci.replace(self.ref, self.ref * 2, 1),
                    self.ci.replace(self.ref, '        env:\n' + self.ref, 1),
                    self.ci.replace(self.ref, '          ref: ${{ github.sha }}\n', 1)]
        for contents in variants:
            with self.subTest(contents=contents[:20]), self.assertRaisesRegex(RoutingPolicyError, 'every ordinary CI checkout'):
                check_ci_workflow(contents)

    def test_reusable_caller_cannot_drop_or_redirect_candidate_input(self):
        binding = '      candidate_ref: ${{ inputs.merge_sha || github.sha }}\n'
        for replacement in ('', '      candidate_ref: ${{ github.sha }}\n'):
            with self.subTest(replacement=replacement), self.assertRaisesRegex(RoutingPolicyError, 'reusable Configurator'):
                check_ci_workflow(self.ci.replace(binding, replacement, 1))

    def test_reusable_callee_cannot_borrow_sibling_or_duplicate_refs(self):
        binding = '          ref: ${{ inputs.candidate_ref || github.sha }}\n'
        for replacement in ('', binding * 2, '          ref: ${{ github.sha }}\n',
                            '        env:\n' + binding):
            with self.subTest(replacement=replacement), self.assertRaisesRegex(RoutingPolicyError, 'every Configurator checkout'):
                check_configurator_workflow(self.configurator.replace(binding, replacement, 1))

    def test_supported_reusable_consumer_remains_secretless_and_read_only(self):
        with self.assertRaisesRegex(RoutingPolicyError, 'secretless and non-publishing'):
            check_configurator_workflow(self.configurator.replace('  contents: read', '  contents: write', 1))
        with self.assertRaisesRegex(RoutingPolicyError, 'secretless and non-publishing'):
            check_configurator_workflow(self.configurator + '\n# ${{ secrets.EXAMPLE }}\n')


class ReusablePermissionShorthandTest(unittest.TestCase):
    def test_reusable_call_cannot_use_secret_inheritance_or_write_all(self):
        ci = (ROOT / '.github/workflows/ci.yml').read_text()
        for addition in ('    secrets: inherit\n', '    permissions: write-all\n'):
            changed = ci.replace('    uses: ./.github/workflows/configurator.yml\n',
                                 '    uses: ./.github/workflows/configurator.yml\n' + addition, 1)
            with self.subTest(addition=addition), self.assertRaisesRegex(RoutingPolicyError, 'secretless and non-publishing'):
                check_ci_workflow(changed)


class UnsupportedCheckoutLayoutTest(unittest.TestCase):
    def setUp(self):
        self.ci = (ROOT / '.github/workflows/ci.yml').read_text()
        self.ref = '          ref: ${{ inputs.merge_sha || github.sha }}\n'

    def test_reindented_valid_yaml_checkout_cannot_disappear_from_both_counts(self):
        start = self.ci.index('  docs-site:\n')
        end = self.ci.index('  release-classification:\n', start)
        original = self.ci[start:end]
        prefix, steps = original.split('    steps:\n', 1)
        for candidate in (steps, steps.replace(self.ref, '', 1),
                          steps.replace(self.ref, '          ref: ${{ github.sha }}\n', 1)):
            changed = prefix + '    steps:\n' + '\n'.join('  ' + line if line else line for line in candidate.split('\n'))
            with self.subTest(candidate=candidate[:20]), self.assertRaisesRegex(RoutingPolicyError, 'observable supported step layout'):
                check_ci_workflow(self.ci.replace(original, changed, 1))

    def test_flow_style_and_quoted_or_encoded_action_values_fail_closed(self):
        action = '        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1'
        for replacement in ('        uses: "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"',
                            '        uses: "actions/\\u0063heckout@3d3c42e5aac5ba805825da76410c181273ba90b1"',
                            '        {uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1}'):
            with self.subTest(replacement=replacement), self.assertRaises(RoutingPolicyError):
                check_ci_workflow(self.ci.replace(action, replacement, 1))
