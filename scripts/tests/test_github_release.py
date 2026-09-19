"""Rehearse the GitHub Release step against a faked `gh`.

The release workflow's `Create or verify the immutable GitHub prerelease` step has never executed:
every release so far failed earlier in the job, so the exact command contract of
`scripts/github_release.py` has only ever been exercised in production, where it was never reached.
These tests drive `main()` end to end with a stand-in `gh` on PATH and a throwaway repository, so the
create, upload, publish and resume paths, and the fail-closed paths, are proven before a release
depends on them.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts import github_release


REPOSITORY = "ravenroot-ai/ravenroot"
TAG = "v9.9.9-alpha.1"
NOTES = "# Ravenroot 9.9.9-alpha.1\n\nRehearsal notes.\n"

FAKE_GH = '''#!/usr/bin/env python3
"""Minimal stand-in for the subset of `gh` that github_release.py uses."""
import json, os, shutil, sys
from pathlib import Path

state_path = Path(os.environ["FAKE_GH_STATE"])
log_path = Path(os.environ["FAKE_GH_LOG"])
state = json.loads(state_path.read_text())
argv = sys.argv[1:]
with log_path.open("a", encoding="utf-8") as log:
    log.write(" ".join(argv) + "\\n")


def save():
    state_path.write_text(json.dumps(state))


def fail(message):
    sys.stderr.write(message + "\\n")
    raise SystemExit(1)


if argv[:2] == ["api", "--paginate"]:
    releases = [state["release"]] if state.get("release") else []
    sys.stdout.write(json.dumps([releases]))
elif argv[0] == "api":
    url = argv[1]
    for asset in (state.get("release") or {}).get("assets", []):
        if asset["url"] == url:
            sys.stdout.buffer.write(Path(asset["path"]).read_bytes())
            break
    else:
        fail("asset not found")
elif argv[:2] == ["release", "create"]:
    if state.get("release"):
        fail("release already exists")
    if "--verify-tag" in argv and argv[2] not in state.get("tags", []):
        fail("tag does not exist")
    notes = Path(argv[argv.index("--notes-file") + 1]).read_text(encoding="utf-8")
    state["release"] = {
        "tag_name": argv[2],
        "name": argv[argv.index("--title") + 1],
        "body": notes,
        "draft": "--draft" in argv,
        "prerelease": "--prerelease" in argv,
        "assets": [],
    }
    save()
elif argv[:2] == ["release", "upload"]:
    local = Path(argv[3])
    stored = state_path.parent / f"uploaded-{local.name}"
    shutil.copyfile(local, stored)
    state["release"]["assets"].append(
        {"name": local.name, "url": f"assets/{local.name}", "path": str(stored)}
    )
    save()
elif argv[:2] == ["release", "edit"]:
    state["release"]["draft"] = "--draft=false" not in argv
    state["release"]["prerelease"] = "--prerelease" in argv
    save()
else:
    fail(f"unsupported gh invocation: {argv}")
'''


class GitHubReleaseRehearsalTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ravenroot-release-rehearsal-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "repository"
        (self.root / "docs" / "releases").mkdir(parents=True)
        (self.root / "docs" / "releases" / f"{TAG}.md").write_text(NOTES, encoding="utf-8")
        self.commit = self.repository_with_tag()

        self.assets = Path(self.temporary.name) / "release-assets"
        self.assets.mkdir()
        (self.assets / "ravenroot.jar").write_bytes(b"jar bytes")
        (self.assets / "ravenroot-bin.zip").write_bytes(b"zip bytes")

        binaries = Path(self.temporary.name) / "bin"
        binaries.mkdir()
        fake = binaries / "gh"
        fake.write_text(FAKE_GH, encoding="utf-8")
        fake.chmod(0o755)
        self.state = Path(self.temporary.name) / "state.json"
        self.log = Path(self.temporary.name) / "gh.log"
        self.state.write_text(json.dumps({"release": None, "tags": [TAG]}))
        self.log.write_text("")
        self.environment = mock.patch.dict(
            os.environ,
            {
                "PATH": f"{binaries}{os.pathsep}{os.environ['PATH']}",
                "GITHUB_REPOSITORY": REPOSITORY,
                "FAKE_GH_STATE": str(self.state),
                "FAKE_GH_LOG": str(self.log),
            },
        )
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.patched_root = mock.patch.object(github_release, "ROOT", self.root)
        self.patched_root.start()
        self.addCleanup(self.patched_root.stop)

    def repository_with_tag(self) -> str:
        def git(*arguments: str) -> str:
            return subprocess.run(
                ["git", *arguments],
                cwd=self.root,
                check=True,
                capture_output=True,
                text=True,
                env={**os.environ, "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_SYSTEM": "/dev/null"},
            ).stdout.strip()

        git("init", "--quiet")
        git("config", "user.email", "rehearsal@example.invalid")
        git("config", "user.name", "Rehearsal")
        git("add", ".")
        git("commit", "--quiet", "--message", "notes")
        git("tag", "--annotate", TAG, "--message", TAG)
        return git("rev-parse", f"{TAG}^{{commit}}")

    def run_main(self, *, commit: str | None = None) -> int:
        arguments = [
            "github_release.py",
            "--tag",
            TAG,
            "--commit",
            commit or self.commit,
            "--assets",
            str(self.assets),
        ]
        with mock.patch.object(sys, "argv", arguments):
            return github_release.main()

    def commands(self) -> list[str]:
        return [line for line in self.log.read_text(encoding="utf-8").splitlines() if line]

    def current(self) -> dict:
        return json.loads(self.state.read_text())["release"]

    def test_absent_release_is_created_as_a_published_prerelease_with_every_asset(self):
        self.assertEqual(0, self.run_main())
        document = self.current()
        self.assertEqual(TAG, document["tag_name"])
        self.assertEqual(f"Ravenroot {TAG[1:]}", document["name"])
        self.assertEqual(NOTES, document["body"])
        self.assertTrue(document["prerelease"])
        self.assertFalse(document["draft"])
        self.assertEqual(
            {"ravenroot.jar", "ravenroot-bin.zip"},
            {asset["name"] for asset in document["assets"]},
        )
        commands = self.commands()
        creation = next(command for command in commands if command.startswith("release create"))
        for expected in ("--verify-tag", "--draft", "--latest=false", "--prerelease"):
            self.assertIn(expected, creation)
        self.assertLess(
            commands.index(creation),
            min(index for index, command in enumerate(commands) if command.startswith("release upload")),
            "assets must be uploaded into the draft, never into a published release",
        )
        self.assertTrue(any(command.startswith("release edit") for command in commands))

    def test_complete_release_is_verified_without_creating_or_uploading_anything(self):
        self.assertEqual(0, self.run_main())
        self.log.write_text("")
        self.assertEqual(0, self.run_main())
        self.assertEqual([], [c for c in self.commands() if not c.startswith("api")])

    def test_tag_that_does_not_peel_to_the_authorized_commit_is_refused(self):
        self.assertEqual(1, self.run_main(commit="b" * 40))
        self.assertEqual([], self.commands())
        self.assertIsNone(self.current())

    def test_published_release_missing_an_asset_fails_closed(self):
        self.assertEqual(0, self.run_main())
        state = json.loads(self.state.read_text())
        state["release"]["assets"] = [
            asset for asset in state["release"]["assets"] if asset["name"] != "ravenroot.jar"
        ]
        self.state.write_text(json.dumps(state))
        self.assertEqual(1, self.run_main())

    def test_release_whose_notes_differ_from_the_reviewed_notes_fails_closed(self):
        self.assertEqual(0, self.run_main())
        state = json.loads(self.state.read_text())
        state["release"]["body"] = "different notes"
        self.state.write_text(json.dumps(state))
        self.assertEqual(1, self.run_main())


if __name__ == "__main__":
    unittest.main()
