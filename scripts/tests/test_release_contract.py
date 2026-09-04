import subprocess
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.release_contract import (
    INITIAL_VERSION,
    ReleaseContractError,
    ReleaseVersion,
    authorize_main,
    expected_next,
    parse_tag,
    selected_pull_request,
    validate_event,
    validate_tag,
    validate_tag_authorization,
)
from scripts.check_product_version import helm_errors
from scripts.central_registry import (
    EXCLUDED_ARTIFACTS,
    PUBLISHABLE_ARTIFACTS,
    central_state,
    local_payloads,
    publishable_artifacts,
)


class ReleaseVersionTest(unittest.TestCase):
    def test_accepts_current_alpha(self):
        self.assertEqual(str(parse_tag("v0.1.0-alpha.1")), INITIAL_VERSION)

    def test_rejects_malformed_tags(self):
        for tag in ("0.1.0", "v01.0.0", "v1.0", "vrelease-1", "v1.0.0+rebuilt"):
            with self.subTest(tag=tag), self.assertRaises(ReleaseContractError):
                parse_tag(tag)

    def test_expected_patch_minor_and_major_transitions(self):
        previous = ReleaseVersion.parse("0.1.2-alpha.1")
        self.assertEqual(str(expected_next(previous, "patch")), "0.1.3-alpha.1")
        self.assertEqual(str(expected_next(previous, "minor")), "0.2.0-alpha.1")
        self.assertEqual(str(expected_next(previous, "major")), "1.0.0-alpha.1")

    def test_release_none_never_authorizes_a_transition(self):
        with self.assertRaises(ReleaseContractError):
            expected_next(ReleaseVersion.parse(INITIAL_VERSION), "none")

    def test_semantic_precedence_orders_numeric_prereleases_and_stable_release(self):
        versions = [
            ReleaseVersion.parse("1.0.0"),
            ReleaseVersion.parse("1.0.0-alpha.10"),
            ReleaseVersion.parse("1.0.0-alpha.2"),
        ]
        ordered = sorted(versions, key=ReleaseVersion.semantic_key)
        self.assertEqual([str(version) for version in ordered], [
            "1.0.0-alpha.2",
            "1.0.0-alpha.10",
            "1.0.0",
        ])

    def test_release_event_is_bound_to_an_existing_semver_tag_ref(self):
        for event in ("push", "workflow_dispatch"):
            self.assertEqual(
                validate_event(event, "tag", "v0.1.0-alpha.1", "v0.1.0-alpha.1"),
                {"tag": "v0.1.0-alpha.1"},
            )

    def test_rejects_non_tag_and_untrusted_release_events(self):
        cases = (
            ("pull_request", "tag", "v0.1.0-alpha.1", "v0.1.0-alpha.1"),
            ("schedule", "tag", "v0.1.0-alpha.1", "v0.1.0-alpha.1"),
            ("push", "branch", "dev", "dev"),
            ("workflow_dispatch", "branch", "main", "v0.1.0-alpha.1"),
            ("workflow_dispatch", "tag", "v0.1.0-alpha.1", "v0.2.0-alpha.1"),
        )
        for values in cases:
            with self.subTest(values=values), self.assertRaises(ReleaseContractError):
                validate_event(*values)

    def test_helm_version_and_app_version_must_match(self):
        self.assertEqual(
            helm_errors(INITIAL_VERSION, 'version: 0.1.0-alpha.1\nappVersion: "0.1.0-alpha.1"\n'),
            [],
        )
        self.assertEqual(len(helm_errors(INITIAL_VERSION, "version: 1.0.0\nappVersion: 2.0.0\n")), 2)


class TagGateTest(unittest.TestCase):
    def git_values(self, *, version: str = INITIAL_VERSION):
        def fake_git(*arguments, **_kwargs):
            if arguments[:2] == ("cat-file", "-t"):
                return "tag"
            if arguments[:2] == ("rev-parse", "refs/tags/v0.1.0-alpha.1^{commit}"):
                return "abc123"
            if arguments[:2] == ("rev-parse", "HEAD"):
                return "abc123"
            raise AssertionError(arguments)

        return fake_git

    @mock.patch("scripts.release_contract.require_release_notes")
    @mock.patch("scripts.release_contract.version_errors", return_value=[])
    @mock.patch("scripts.release_contract.authoritative_version", return_value=INITIAL_VERSION)
    @mock.patch("scripts.release_contract.subprocess.run")
    @mock.patch("scripts.release_contract.run_git")
    def test_rejects_off_main_tag(self, run_git, run_process, *_mocks):
        run_git.side_effect = self.git_values()
        run_process.return_value = subprocess.CompletedProcess([], 1)
        with self.assertRaisesRegex(ReleaseContractError, "protected main"):
            validate_tag("v0.1.0-alpha.1", "origin/main")

    @mock.patch("scripts.release_contract.require_release_notes")
    @mock.patch("scripts.release_contract.version_errors", return_value=[])
    @mock.patch("scripts.release_contract.authoritative_version", return_value="0.1.1-alpha.1")
    @mock.patch("scripts.release_contract.subprocess.run")
    @mock.patch("scripts.release_contract.run_git")
    def test_rejects_tag_version_mismatch(self, run_git, run_process, *_mocks):
        run_git.side_effect = self.git_values(version="0.1.1-alpha.1")
        run_process.return_value = subprocess.CompletedProcess([], 0)
        with self.assertRaisesRegex(ReleaseContractError, "does not match"):
            validate_tag("v0.1.0-alpha.1", "origin/main")

    @mock.patch("scripts.release_contract.require_release_notes")
    @mock.patch("scripts.release_contract.version_errors", return_value=[])
    @mock.patch("scripts.release_contract.authoritative_version", return_value=INITIAL_VERSION)
    @mock.patch("scripts.release_contract.subprocess.run")
    @mock.patch("scripts.release_contract.run_git")
    def test_accepts_annotated_tag_on_main(self, run_git, run_process, *_mocks):
        run_git.side_effect = self.git_values()
        run_process.return_value = subprocess.CompletedProcess([], 0)
        self.assertEqual(
            validate_tag("v0.1.0-alpha.1", "origin/main"),
            {"tag": "v0.1.0-alpha.1", "version": INITIAL_VERSION, "commit": "abc123"},
        )

    @mock.patch("scripts.release_contract.run_git")
    def test_rejects_lightweight_tag(self, run_git):
        run_git.return_value = "commit"
        with self.assertRaisesRegex(ReleaseContractError, "annotated"):
            validate_tag("v0.1.0-alpha.1", "origin/main")

    @mock.patch("scripts.release_contract.run_git")
    def test_manual_tag_without_reviewed_merge_is_not_authorized(self, run_git):
        def fake_git(*arguments, **_kwargs):
            if arguments[:2] == ("rev-parse", "refs/tags/v0.1.0-alpha.1^{commit}"):
                return "manual"
            if arguments[:3] == ("show", "-s", "--format=%P"):
                return "one-parent"
            raise AssertionError(arguments)

        run_git.side_effect = fake_git
        with self.assertRaisesRegex(ReleaseContractError, "reviewed main merge"):
            validate_tag_authorization("v0.1.0-alpha.1", Path("unused"))


class MainAuthorizationTest(unittest.TestCase):
    def pull_request_document(self, labels):
        return [
            {
                "merge_commit_sha": "head",
                "merged_at": "2026-09-02T00:00:00Z",
                "base": {"ref": "main"},
                "head": {
                    "ref": "dev",
                    "repo": {"full_name": "ravenroot-ai/ravenroot"},
                },
                "labels": [{"name": label} for label in labels],
            }
        ]

    def test_rejects_missing_or_duplicate_release_labels(self):
        for labels in ([], ["release:minor", "release:patch"]):
            with self.subTest(labels=labels), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "prs.json"
                path.write_text(json.dumps(self.pull_request_document(labels)), encoding="utf-8")
                with self.assertRaisesRegex(ReleaseContractError, "exactly one"):
                    selected_pull_request(path, "head")

    @mock.patch("scripts.release_contract.require_release_notes")
    @mock.patch("scripts.release_contract.release_tags_merged_into", return_value=[])
    @mock.patch("scripts.release_contract.version_errors", return_value=[])
    @mock.patch("scripts.release_contract.authoritative_version", return_value=INITIAL_VERSION)
    @mock.patch("scripts.release_contract.selected_pull_request", return_value=("minor", "dev"))
    @mock.patch("scripts.release_contract.subprocess.run")
    @mock.patch("scripts.release_contract.run_git")
    def test_rejects_duplicate_immutable_tag(
        self, run_git, run_process, *_mocks
    ):
        def fake_git(*arguments, **_kwargs):
            if arguments[:2] == ("rev-parse", "HEAD"):
                return "head"
            if arguments[:3] == ("show", "-s", "--format=%P"):
                return "before dev-parent"
            if arguments[:2] == ("show", "before:ravenroot/pom.xml"):
                return f"<project><version>{INITIAL_VERSION}</version></project>"
            if arguments[:3] == ("diff", "--no-renames", "--name-only"):
                return "ravenroot/pom.xml"
            raise AssertionError(arguments)

        run_git.side_effect = fake_git
        run_process.return_value = subprocess.CompletedProcess([], 0)
        with self.assertRaisesRegex(ReleaseContractError, "already exists"):
            authorize_main(before="before", head="head", prs_json=Path("unused"))

    @mock.patch("scripts.release_contract.release_tags_merged_into", return_value=[])
    @mock.patch("scripts.release_contract.version_errors", return_value=[])
    @mock.patch("scripts.release_contract.authoritative_version", return_value=INITIAL_VERSION)
    @mock.patch("scripts.release_contract.selected_pull_request", return_value=("none", "dev"))
    @mock.patch("scripts.release_contract.run_git")
    def test_release_none_never_tags_or_publishes(self, run_git, *_mocks):
        def fake_git(*arguments, **_kwargs):
            if arguments[:2] == ("rev-parse", "HEAD"):
                return "head"
            if arguments[:3] == ("show", "-s", "--format=%P"):
                return "before dev-parent"
            if arguments[:2] == ("show", "before:ravenroot/pom.xml"):
                return f"<project><version>{INITIAL_VERSION}</version></project>"
            if arguments[:3] == ("diff", "--no-renames", "--name-only"):
                return "docs/index.md"
            raise AssertionError(arguments)

        run_git.side_effect = fake_git
        self.assertEqual(
            authorize_main(before="before", head="head", prs_json=Path("unused")),
            {"intent": "none", "should_release": "false", "tag": ""},
        )

    @mock.patch("scripts.release_contract.release_tags_merged_into", return_value=[])
    @mock.patch("scripts.release_contract.version_errors", return_value=[])
    @mock.patch("scripts.release_contract.authoritative_version", return_value=INITIAL_VERSION)
    @mock.patch("scripts.release_contract.selected_pull_request", return_value=("patch", "dev"))
    @mock.patch("scripts.release_contract.run_git")
    def test_mutable_label_cannot_change_the_immutable_transition(self, run_git, *_mocks):
        def fake_git(*arguments, **_kwargs):
            if arguments[:2] == ("rev-parse", "HEAD"):
                return "head"
            if arguments[:3] == ("show", "-s", "--format=%P"):
                return "before dev-parent"
            if arguments[:2] == ("show", "before:ravenroot/pom.xml"):
                return f"<project><version>{INITIAL_VERSION}</version></project>"
            if arguments[:3] == ("diff", "--no-renames", "--name-only"):
                return "ravenroot/pom.xml"
            raise AssertionError(arguments)

        run_git.side_effect = fake_git
        with self.assertRaisesRegex(ReleaseContractError, "immutable release:minor"):
            authorize_main(before="before", head="head", prs_json=Path("unused"))


class RepositoryConfigurationTest(unittest.TestCase):
    def test_publishable_module_boundary_excludes_non_shipping_projects(self):
        artifacts = set(publishable_artifacts())
        self.assertEqual(artifacts, set(PUBLISHABLE_ARTIFACTS))
        self.assertEqual(len(PUBLISHABLE_ARTIFACTS), 32)
        self.assertIn("ravenroot-extensions-all", artifacts)
        self.assertIn("ravenroot-git-workspace", artifacts)
        self.assertIn("ravenroot-github", artifacts)
        self.assertIn("ravenroot-slack", artifacts)
        self.assertTrue(EXCLUDED_ARTIFACTS.isdisjoint(artifacts))
        self.assertNotIn("ravenroot-dev-harness", artifacts)
        self.assertNotIn("ravenroot-sample", artifacts)
        self.assertNotIn("ravenroot-adapter-anthropic", artifacts)
        payload_names = {name for name, _path in local_payloads(INITIAL_VERSION)}
        for excluded in EXCLUDED_ARTIFACTS:
            self.assertFalse(any(name.startswith(f"{excluded}-") for name in payload_names))

    @mock.patch("scripts.central_registry.exists")
    def test_duplicate_registry_state_must_be_complete(self, exists):
        exists.side_effect = lambda url: "ravenroot-core" in url
        self.assertEqual(central_state(INITIAL_VERSION)["state"], "partial")
        exists.return_value = True
        exists.side_effect = None
        self.assertEqual(central_state(INITIAL_VERSION)["state"], "complete")

    def test_release_configuration_contract(self):
        from scripts.check_release_configuration import (
            check_documentation,
            check_oci_metadata,
            check_pom,
            check_public_key,
            check_workflows,
        )

        check_pom()
        check_public_key()
        check_workflows()
        check_documentation()
        check_oci_metadata()


if __name__ == "__main__":
    unittest.main()
