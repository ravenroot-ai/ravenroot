import subprocess
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts import release_contract
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
    def test_release_checksum_manifest_has_canonical_filename_separator(self):
        repository = Path(__file__).resolve().parents[2]
        workflow = (repository / ".github/workflows/release.yml").read_text(encoding="utf-8")
        command = (
            "find . -maxdepth 1 -type f ! -name SHA256SUMS -print0 "
            "| sort -z | xargs -0 sha256sum | sed 's#  \\./#  #' > SHA256SUMS"
        )
        self.assertIn("sed 's#  \\./#  #'", workflow)

        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "artifact.jar").write_bytes(b"artifact")
            (directory / "release archive.zip").write_bytes(b"archive")
            subprocess.run(command, cwd=directory, shell=True, check=True, executable="/bin/bash")
            manifest = (directory / "SHA256SUMS").read_text(encoding="utf-8")
            self.assertNotIn("   ", manifest)
            completed = subprocess.run(
                ["sha256sum", "--check", "SHA256SUMS"],
                cwd=directory,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr)

    def test_publishable_module_boundary_excludes_non_shipping_projects(self):
        artifacts = set(publishable_artifacts())
        self.assertEqual(artifacts, set(PUBLISHABLE_ARTIFACTS))
        self.assertEqual(len(PUBLISHABLE_ARTIFACTS), 37)
        self.assertIn("ravenroot-extensions-all", artifacts)
        self.assertIn("ravenroot-discord", artifacts)
        self.assertIn("ravenroot-git-workspace", artifacts)
        self.assertIn("ravenroot-github", artifacts)
        self.assertIn("ravenroot-slack", artifacts)
        self.assertIn("ravenroot-matrix", artifacts)
        self.assertIn("ravenroot-mattermost", artifacts)
        self.assertIn("ravenroot-teams", artifacts)
        self.assertIn("ravenroot-persistence-postgresql", artifacts)
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


class PreparedUnreleasedRecoveryTest(unittest.TestCase):
    previous = ReleaseVersion.parse("0.4.1-alpha.1")
    target = "0.5.0-alpha.1"
    base = "prepared-main"
    prior_main = "previous-main"
    promoted_dev = "prepared-dev"
    recovery_head = "recovery-main"

    def prior_promotion(
        self,
        *,
        promoted_dev: str | None = None,
        labels: tuple[str, ...] = ("release:minor",),
        base_ref: str = "main",
        head_ref: str = "dev",
        repository: str = "ravenroot-ai/ravenroot",
    ):
        return [{
            "merge_commit_sha": self.base,
            "merged_at": "2026-09-27T00:00:00Z",
            "base": {"ref": base_ref},
            "head": {
                "ref": head_ref,
                "sha": promoted_dev or self.promoted_dev,
                "repo": {"full_name": repository},
            },
            "labels": [{"name": label} for label in labels],
        }]

    def recovery(self, document, *, versions=None, tag_exists=False, tag_head=None,
                 allow_existing_exact_tag=False, base_parents=None):
        versions = versions or {
            self.base: self.target,
            self.recovery_head: self.target,
            self.prior_main: str(self.previous),
            self.promoted_dev: self.target,
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "prior.json"
            path.write_text(json.dumps(document), encoding="utf-8")

            def fake_git(*arguments, **_kwargs):
                if arguments == ("show", "-s", "--format=%P", self.base):
                    return base_parents or f"{self.prior_main} {self.promoted_dev}"
                if arguments == ("cat-file", "-e", f"{self.base}:docs/releases/v{self.target}.md"):
                    return ""
                if arguments == ("rev-parse", f"refs/tags/v{self.target}^{{commit}}"):
                    return tag_head or self.recovery_head
                raise AssertionError(arguments)

            with mock.patch.object(release_contract, "release_tags_merged_into", return_value=[(self.previous, f"v{self.previous}")]), \
                 mock.patch.object(release_contract, "version_at", side_effect=versions.__getitem__), \
                 mock.patch.object(release_contract, "run_git", side_effect=fake_git), \
                 mock.patch.object(release_contract, "subprocess") as process:
                process.run.return_value = subprocess.CompletedProcess([], 0 if tag_exists else 1)
                return release_contract.prepared_unreleased_minor_recovery(
                    base=self.base,
                    head=self.recovery_head,
                    prior_prs_json=path,
                    allow_existing_exact_tag=allow_existing_exact_tag,
                )

    def test_accepts_the_exact_unpublished_minor_recovery(self):
        self.assertEqual("minor", self.recovery(self.prior_promotion()))

    def test_refuses_missing_prior_promotion_evidence(self):
        with self.assertRaisesRegex(ReleaseContractError, "requires prior promotion evidence"):
            release_contract.prepared_unreleased_minor_recovery(
                base=self.base, head=self.recovery_head, prior_prs_json=None
            )

    def test_refuses_an_existing_or_different_target_tag(self):
        with self.assertRaisesRegex(ReleaseContractError, "already exists"):
            self.recovery(self.prior_promotion(), tag_exists=True)
        self.assertEqual(
            "minor",
            self.recovery(self.prior_promotion(), tag_exists=True, allow_existing_exact_tag=True),
        )
        with self.assertRaisesRegex(ReleaseContractError, "different immutable content"):
            self.recovery(
                self.prior_promotion(), tag_exists=True, tag_head="other-main",
                allow_existing_exact_tag=True,
            )

    def test_refuses_wrong_expected_version_and_prior_promotion_identity(self):
        versions = {
            self.base: self.target,
            self.recovery_head: self.target,
            self.prior_main: str(self.previous),
            self.promoted_dev: "0.4.2-alpha.1",
        }
        with self.assertRaisesRegex(ReleaseContractError, "does not bridge"):
            self.recovery(self.prior_promotion(), versions=versions)
        with self.assertRaisesRegex(ReleaseContractError, "exact merged internal dev promotion"):
            self.recovery(self.prior_promotion(promoted_dev="other-dev"))

    def test_refuses_wrong_target_versions_and_a_non_merge_base(self):
        wrong_base = {
            self.base: "0.5.1-alpha.1",
            self.recovery_head: self.target,
            self.prior_main: str(self.previous),
            self.promoted_dev: self.target,
        }
        wrong_head = {**wrong_base, self.base: self.target, self.recovery_head: "0.5.1-alpha.1"}
        for versions in (wrong_base, wrong_head):
            with self.subTest(versions=versions), self.assertRaisesRegex(
                ReleaseContractError, "exact expected minor version"
            ):
                self.recovery(self.prior_promotion(), versions=versions)
        with self.assertRaisesRegex(ReleaseContractError, "not a main merge"):
            self.recovery(self.prior_promotion(), base_parents=self.prior_main)

    def test_refuses_foreign_ambiguous_or_conflicting_historical_proof(self):
        foreign_variants = (
            self.prior_promotion(base_ref="develop"),
            self.prior_promotion(head_ref="feature/recovery"),
            self.prior_promotion(repository="foreign/ravenroot"),
            self.prior_promotion(labels=("release:patch",)),
            self.prior_promotion(labels=("release:minor", "release:patch")),
            self.prior_promotion() * 2,
        )
        for document in foreign_variants:
            with self.subTest(document=document), self.assertRaisesRegex(
                ReleaseContractError, "exact merged internal dev promotion|exactly release:minor"
            ):
                self.recovery(document)


class OrdinaryTransitionRoutingTest(unittest.TestCase):
    previous = ReleaseVersion.parse("0.4.1-alpha.1")

    def test_patch_minor_and_major_labels_follow_the_normal_transition_contract(self):
        versions = {
            "patch": "0.4.2-alpha.1",
            "minor": "0.5.0-alpha.1",
            "major": "1.0.0-alpha.1",
        }
        for intent, candidate in versions.items():
            with self.subTest(intent=intent), \
                 mock.patch.object(release_contract, "version_at", side_effect=lambda ref: {
                     "base": str(self.previous), "head": candidate
                 }[ref]), \
                 mock.patch.object(release_contract, "version_errors", return_value=[]), \
                 mock.patch.object(release_contract, "release_tags_merged_into", return_value=[
                     (self.previous, f"v{self.previous}")
                 ]), \
                 mock.patch.object(release_contract, "run_git", return_value="ravenroot/pom.xml"), \
                 mock.patch.object(release_contract, "require_release_notes"):
                self.assertEqual(
                    {"intent": intent, "version": candidate},
                    release_contract.check_promotion(
                        base="base", head="head", labels={f"release:{intent}"}
                    ),
                )

    def test_main_authorization_preserves_patch_minor_and_major_routing(self):
        versions = {
            "patch": "0.4.2-alpha.1",
            "minor": "0.5.0-alpha.1",
            "major": "1.0.0-alpha.1",
        }
        for intent, candidate in versions.items():
            with self.subTest(intent=intent), \
                 mock.patch.object(release_contract, "selected_pull_request", return_value=(intent, "dev")), \
                 mock.patch.object(release_contract, "version_at", return_value=str(self.previous)), \
                 mock.patch.object(release_contract, "authoritative_version", return_value=candidate), \
                 mock.patch.object(release_contract, "version_errors", return_value=[]), \
                 mock.patch.object(release_contract, "release_tags_merged_into", return_value=[
                     (self.previous, f"v{self.previous}")
                 ]), \
                 mock.patch.object(release_contract, "require_target_tag_available") as tag, \
                 mock.patch.object(release_contract, "require_release_notes"), \
                 mock.patch.object(
                     release_contract, "run_git",
                     side_effect=lambda *args: {
                         ("rev-parse", "HEAD"): "head",
                         ("show", "-s", "--format=%P", "head"): "before dev-parent",
                         ("diff", "--no-renames", "--name-only", "before", "head"): "ravenroot/pom.xml",
                     }[args],
                 ):
                result = authorize_main(before="before", head="head", prs_json=Path("current.json"))
                self.assertEqual(intent, result["intent"])
                self.assertEqual(f"v{candidate}", result["tag"])
                self.assertFalse(tag.call_args.args[2])


class RecoveryCliBoundaryTest(unittest.TestCase):
    """Exercise each public recovery command against an isolated repository history."""

    target = "0.5.0-alpha.1"

    @staticmethod
    def git(directory: Path, *arguments: str) -> str:
        return subprocess.run(
            ["git", *arguments], cwd=directory, check=True, capture_output=True, text=True
        ).stdout.strip()

    @staticmethod
    def contract(directory: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, "scripts/release_contract.py", *arguments], cwd=directory,
            check=False, capture_output=True, text=True,
        )

    @classmethod
    def write_product(cls, directory: Path, version: str, *, release_notes: bool) -> None:
        (directory / "ravenroot").mkdir(exist_ok=True)
        (directory / "ravenroot" / "pom.xml").write_text(
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
            "<modelVersion>4.0.0</modelVersion><groupId>ai.ravenroot</groupId>"
            f"<artifactId>ravenroot</artifactId><version>{version}</version></project>",
            encoding="utf-8",
        )
        ui = directory / "ravenroot" / "ravenroot-ui"
        ui.mkdir(exist_ok=True)
        package = {"version": version, "packages": {"": {"version": version}}}
        (ui / "package.json").write_text(json.dumps(package), encoding="utf-8")
        (ui / "package-lock.json").write_text(json.dumps(package), encoding="utf-8")
        chart = directory / "deploy" / "helm" / "ravenroot"
        chart.mkdir(parents=True, exist_ok=True)
        (chart / "Chart.yaml").write_text(
            f"version: {version}\nappVersion: \"{version}\"\n", encoding="utf-8"
        )
        (directory / "README.md").write_text(f"<version>{version}</version>\n", encoding="utf-8")
        if release_notes:
            notes = directory / "docs" / "releases"
            notes.mkdir(parents=True, exist_ok=True)
            (notes / f"v{version}.md").write_text("# Fixture release\n", encoding="utf-8")

    @classmethod
    def commit_product(cls, directory: Path, message: str) -> str:
        cls.git(directory, "add", ".")
        cls.git(directory, "commit", "-qm", message)
        return cls.git(directory, "rev-parse", "HEAD")

    def test_recovery_commands_fail_closed_against_a_real_isolated_git_fixture(self):
        source = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Path(temporary) / "ravenroot"
            fixture.mkdir()
            self.git(fixture, "init", "-q")
            self.git(fixture, "config", "user.name", "fixture")
            self.git(fixture, "config", "user.email", "fixture@example.invalid")
            scripts = fixture / "scripts"
            scripts.mkdir()
            for name in ("release_contract.py", "check_product_version.py", "classify_main_change.py"):
                shutil.copy2(source / "scripts" / name, scripts / name)
            previous = "0.4.1-alpha.1"
            self.write_product(fixture, previous, release_notes=False)
            previous_main = self.commit_product(fixture, "previous release")
            self.git(fixture, "tag", "-a", f"v{previous}", "-m", "fixture", previous_main)
            self.write_product(fixture, self.target, release_notes=True)
            prepared_dev = self.commit_product(fixture, "prepared development release")
            prepared_main = self.git(
                fixture, "commit-tree", "HEAD^{tree}", "-p", previous_main, "-p", prepared_dev,
                "-m", "prepared main promotion",
            )
            synthetic = self.git(
                fixture, "commit-tree", "HEAD^{tree}", "-p", prepared_main, "-p", prepared_dev,
                "-m", "recovery fixture",
            )
            self.git(fixture, "checkout", "--detach", synthetic)
            prior = [{
                "merge_commit_sha": prepared_main,
                "merged_at": "2026-09-27T10:30:16Z",
                "base": {"ref": "main"},
                "head": {"ref": "dev", "sha": prepared_dev,
                         "repo": {"full_name": "ravenroot-ai/ravenroot"}},
                "labels": [{"name": "release:minor"}],
            }]
            current = [{
                "merge_commit_sha": synthetic,
                "merged_at": "2026-09-27T12:00:00Z",
                "base": {"ref": "main"},
                "head": {"ref": "dev", "repo": {"full_name": "ravenroot-ai/ravenroot"}},
                "labels": [{"name": "release:minor"}],
            }]
            prior_path = fixture / "prior.json"
            current_path = fixture / "current.json"
            prior_path.write_text(json.dumps(prior), encoding="utf-8")
            current_path.write_text(json.dumps(current), encoding="utf-8")

            promotion = self.contract(
                fixture, "check-promotion", "--base", prepared_main, "--head", synthetic,
                "--labels", '["release:minor"]', "--prior-prs-json", str(prior_path),
            )
            self.assertEqual(0, promotion.returncode, promotion.stderr)
            self.assertEqual({"intent": "minor", "version": self.target}, json.loads(promotion.stdout))
            missing = self.contract(
                fixture, "check-promotion", "--base", prepared_main, "--head", synthetic,
                "--labels", '["release:minor"]',
            )
            self.assertNotEqual(0, missing.returncode)
            self.assertIn("requires prior promotion evidence", missing.stderr)

            authorization = self.contract(
                fixture, "authorize-main", "--before", prepared_main, "--head", synthetic,
                "--prs-json", str(current_path), "--prior-prs-json", str(prior_path),
            )
            self.assertEqual(0, authorization.returncode, authorization.stderr)
            self.assertEqual("v0.5.0-alpha.1", json.loads(authorization.stdout)["tag"])

            self.git(
                fixture, "-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid",
                "tag", "-a", f"v{self.target}", "-m", "fixture", synthetic,
            )
            already_tagged = self.contract(
                fixture, "authorize-main", "--before", prepared_main, "--head", synthetic,
                "--prs-json", str(current_path), "--prior-prs-json", str(prior_path),
            )
            self.assertNotEqual(0, already_tagged.returncode)
            self.assertIn("already exists", already_tagged.stderr)
            post_tag = self.contract(
                fixture, "validate-tag-authorization", "--tag", f"v{self.target}",
                "--prs-json", str(current_path), "--prior-prs-json", str(prior_path),
            )
            self.assertEqual(0, post_tag.returncode, post_tag.stderr)
            self.assertEqual("minor", json.loads(post_tag.stdout)["intent"])

            self.git(fixture, "tag", "-d", f"v{self.target}")
            alternate = self.git(
                fixture, "commit-tree", "HEAD^{tree}", "-p", prepared_main, "-p", prepared_dev,
                "-m", "different recovery fixture",
            )
            self.git(
                fixture, "tag", "-a", f"v{self.target}", "-m", "different fixture", alternate,
            )
            self.assertNotEqual(synthetic, self.git(fixture, "rev-parse", f"v{self.target}^{{commit}}"))
            mismatched = self.contract(
                fixture, "authorize-main", "--before", prepared_main, "--head", synthetic,
                "--prs-json", str(current_path), "--prior-prs-json", str(prior_path),
            )
            self.assertNotEqual(0, mismatched.returncode)
            self.assertIn("already exists", mismatched.stderr)


class PreparedRecoveryCallerTest(unittest.TestCase):
    def test_main_authorization_passes_prior_evidence_to_recovery(self):
        prior = Path("prior.json")
        with mock.patch.object(release_contract, "run_git", side_effect=["head", "before dev"]), \
             mock.patch.object(release_contract, "selected_pull_request", return_value=("minor", "dev")), \
             mock.patch.object(release_contract, "authoritative_version", return_value="0.5.0-alpha.1"), \
             mock.patch.object(release_contract, "version_at", return_value="0.5.0-alpha.1"), \
             mock.patch.object(release_contract, "version_errors", return_value=[]), \
             mock.patch.object(release_contract, "authorized_release_intent", return_value="minor") as intent, \
             mock.patch.object(release_contract, "require_target_tag_available"), \
             mock.patch.object(release_contract, "require_release_notes"):
            result = authorize_main(before="before", head="head", prs_json=Path("current.json"),
                                    prior_prs_json=prior)
        self.assertEqual({"intent": "minor", "should_release": "true", "tag": "v0.5.0-alpha.1"}, result)
        self.assertEqual(prior, intent.call_args.kwargs["prior_prs_json"])
        self.assertFalse(intent.call_args.kwargs["allow_existing_exact_tag"])

    def test_tag_authorization_allows_only_the_tagged_recovery_head(self):
        prior = Path("prior.json")
        with mock.patch.object(release_contract, "run_git", side_effect=["head", "before dev"]), \
             mock.patch.object(release_contract, "authorize_main", return_value={
                 "intent": "minor", "should_release": "true", "tag": "v0.5.0-alpha.1"
             }) as authorize:
            result = validate_tag_authorization("v0.5.0-alpha.1", Path("current.json"), prior)
        self.assertEqual("v0.5.0-alpha.1", result["tag"])
        self.assertEqual(prior, authorize.call_args.kwargs["prior_prs_json"])
        self.assertTrue(authorize.call_args.kwargs["allow_existing_exact_tag"])


if __name__ == "__main__":
    unittest.main()
