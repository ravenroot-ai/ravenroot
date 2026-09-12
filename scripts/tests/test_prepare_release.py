"""Contract tests for turning a release label into the release preparation."""

from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts import release_contract
from scripts.prepare_release import prepare
from scripts.release_contract import ReleaseContractError, parse_tag, release_transition


PREVIOUS = "0.1.0-alpha.1"


def git(root: Path, *arguments: str) -> None:
    subprocess.run(["git", *arguments], cwd=root, check=True, capture_output=True)


def fixture(fragments: dict[str, str]) -> Path:
    """A minimal checkout carrying every surface the procedure names, released at PREVIOUS."""
    root = Path(tempfile.mkdtemp())
    files = {
        "ravenroot/pom.xml": f"<project><version>{PREVIOUS}</version></project>\n",
        "ravenroot/ravenroot-core/pom.xml": (
            f"<project><parent><version>{PREVIOUS}</version></parent></project>\n"
        ),
        "ravenroot-sample/pom.xml": (
            f"<project><version>{PREVIOUS}</version>"
            f"<properties><ravenroot.version>{PREVIOUS}</ravenroot.version></properties></project>\n"
        ),
        "ravenroot/ravenroot-ui/package.json": f'{{\n  "name": "ui",\n  "version": "{PREVIOUS}"\n}}\n',
        "ravenroot/ravenroot-ui/package-lock.json": json.dumps(
            {"name": "ui", "version": PREVIOUS, "packages": {"": {"name": "ui", "version": PREVIOUS}}},
            indent=2,
        )
        + "\n",
        "deploy/helm/ravenroot/Chart.yaml": f'name: ravenroot\nversion: {PREVIOUS}\nappVersion: "{PREVIOUS}"\n',
        "README.md": (
            f"<version>{PREVIOUS}</version>\n\nRavenroot begins its public lifecycle at `{PREVIOUS}`.\n"
        ),
        "docs/integrator-guide/extension-pack.md": (
            f"<ravenroot.version>{PREVIOUS}</ravenroot.version>\n-Dravenroot.version={PREVIOUS}\n"
        ),
        "docs/_data/navigation.yml": (
            f"  - title: Releases\n    - title: Ravenroot {PREVIOUS} release notes\n"
            f"      url: /releases/v{PREVIOUS}.html\n"
        ),
        ".changes/README.md": "# Change fragments\n",
        **{f".changes/{name}": body for name, body in fragments.items()},
    }
    for relative, contents in files.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding="utf-8")
    git(root, "init", "-q")
    git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "add", ".")
    git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "base")
    git(root, "tag", f"v{PREVIOUS}")
    return root


FRAGMENTS = {
    "1.feature.md": "Adds a capability.",
    "2.breaking.md": "Changes a contract.\n\n**Migration.** Do this instead.",
    "3.known-issue.md": "Ships a known defect, corrected next release.",
}


class PrepareReleaseTest(unittest.TestCase):
    def test_a_minor_label_moves_every_surface_to_the_authorized_version(self) -> None:
        root = fixture(FRAGMENTS)
        summary = prepare(root, "minor")
        self.assertEqual(summary["version"], "0.2.0-alpha.1")
        for relative in (
            "ravenroot/pom.xml",
            "ravenroot/ravenroot-core/pom.xml",
            "ravenroot-sample/pom.xml",
            "ravenroot/ravenroot-ui/package.json",
            "ravenroot/ravenroot-ui/package-lock.json",
            "deploy/helm/ravenroot/Chart.yaml",
            "docs/integrator-guide/extension-pack.md",
        ):
            with self.subTest(surface=relative):
                contents = (root / relative).read_text(encoding="utf-8")
                self.assertNotIn(PREVIOUS, contents)
                self.assertIn("0.2.0-alpha.1", contents)
        readme = (root / "README.md").read_text(encoding="utf-8")
        self.assertIn("<version>0.2.0-alpha.1</version>", readme)
        self.assertIn(f"lifecycle at `{PREVIOUS}`", readme, "history is not a version surface")

    def test_each_intent_selects_its_own_transition(self) -> None:
        for intent, version in (("patch", "0.1.1-alpha.1"), ("minor", "0.2.0-alpha.1"), ("major", "1.0.0-alpha.1")):
            with self.subTest(intent=intent):
                self.assertEqual(prepare(fixture(FRAGMENTS), intent)["version"], version)

    def test_the_notes_group_every_fragment_and_the_fragments_are_consumed(self) -> None:
        root = fixture(FRAGMENTS)
        prepare(root, "minor")
        notes = (root / "docs/releases/v0.2.0-alpha.1.md").read_text(encoding="utf-8")
        headings = [line for line in notes.splitlines() if line.startswith("## ")]
        self.assertEqual(headings, ["## Known issues", "## Breaking changes", "## Features"])
        self.assertIn("- Ships a known defect, corrected next release.", notes)
        self.assertIn("  **Migration.** Do this instead.", notes)
        self.assertFalse([line for line in notes.splitlines() if line != line.rstrip()], "trailing whitespace")
        self.assertEqual(sorted(p.name for p in (root / ".changes").iterdir()), ["README.md"])
        navigation = (root / "docs/_data/navigation.yml").read_text(encoding="utf-8")
        self.assertLess(navigation.index("0.2.0-alpha.1 release notes"), navigation.index(f"{PREVIOUS} release notes"))

    def test_it_refuses_rather_than_guesses(self) -> None:
        with self.subTest("a second run"):
            root = fixture(FRAGMENTS)
            prepare(root, "minor")
            with self.assertRaises(ReleaseContractError):
                prepare(root, "minor")
        with self.subTest("nothing to release"):
            with self.assertRaises(ReleaseContractError):
                prepare(fixture({}), "minor")
        with self.subTest("an unknown fragment kind"):
            with self.assertRaises(ReleaseContractError):
                prepare(fixture({"1.chore.md": "Tidies up."}), "minor")
        with self.subTest("an intent that is not a release"):
            with self.assertRaises(ReleaseContractError):
                prepare(fixture(FRAGMENTS), "none")
        with self.subTest("a version that is not the latest release"):
            root = fixture(FRAGMENTS)
            pom = root / "ravenroot/pom.xml"
            pom.write_text(pom.read_text().replace(PREVIOUS, "0.1.5-alpha.1"))
            with self.assertRaises(ReleaseContractError):
                prepare(root, "minor")
            self.assertTrue((root / ".changes/1.feature.md").exists(), "a refusal changes nothing")


PUBLISHED = [(parse_tag(f"v{PREVIOUS}"), f"v{PREVIOUS}")]


class ReleaseTransitionTest(unittest.TestCase):
    def test_the_301_promotion_is_refused(self) -> None:
        """A `release:minor` promotion that never moved the version, exactly as #301 merged."""
        with self.assertRaises(ReleaseContractError) as refused:
            release_transition(
                old_version=PREVIOUS, new_version=PREVIOUS, changed=["ravenroot/pom.xml"], published=PUBLISHED
            )
        self.assertIn("release:minor=0.2.0-alpha.1", str(refused.exception))

    def test_a_prepared_minor_is_a_minor(self) -> None:
        self.assertEqual(
            release_transition(
                old_version=PREVIOUS, new_version="0.2.0-alpha.1", changed=["ravenroot/pom.xml"], published=PUBLISHED
            ),
            "minor",
        )

    def test_a_documentation_only_promotion_carries_no_release(self) -> None:
        self.assertEqual(
            release_transition(
                old_version=PREVIOUS, new_version=PREVIOUS, changed=["docs/index.md"], published=PUBLISHED
            ),
            "none",
        )


class CheckPromotionTest(unittest.TestCase):
    def run_check(self, labels: set[str], new_version: str) -> dict[str, str]:
        versions = {"base": PREVIOUS, "head": new_version}
        with mock.patch.object(release_contract, "version_at", side_effect=versions.__getitem__), \
             mock.patch.object(release_contract, "run_git", return_value="ravenroot/pom.xml"), \
             mock.patch.object(release_contract, "release_tags_merged_into", return_value=PUBLISHED), \
             mock.patch.object(release_contract, "version_errors", return_value=[]), \
             mock.patch.object(release_contract, "require_release_notes"):
            return release_contract.check_promotion(base="base", head="head", labels=labels)

    def test_a_label_the_content_does_not_carry_is_refused_before_merge(self) -> None:
        for labels, version in (
            ({"release:minor"}, PREVIOUS),
            ({"release:patch"}, "0.2.0-alpha.1"),
            ({"release:major"}, "0.2.0-alpha.1"),
        ):
            with self.subTest(labels=labels, version=version):
                with self.assertRaises(ReleaseContractError):
                    self.run_check(labels, version)

    def test_a_matching_label_passes(self) -> None:
        self.assertEqual(self.run_check({"release:minor"}, "0.2.0-alpha.1")["intent"], "minor")

    def test_exactly_one_release_label_is_required(self) -> None:
        for labels in (set(), {"release:minor", "release:patch"}):
            with self.subTest(labels=labels):
                with self.assertRaises(ReleaseContractError):
                    self.run_check(labels, "0.2.0-alpha.1")


if __name__ == "__main__":
    unittest.main()
