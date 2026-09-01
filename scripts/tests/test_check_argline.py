from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import check_argline  # noqa: E402


REPO_ROOT = SCRIPTS.parent


def real_repo_copy() -> tempfile.TemporaryDirectory[str]:
    """A working copy of the real repository tree as it stands right now: tracked files plus
    untracked-but-not-gitignored files, from the working tree rather than HEAD.

    Deliberately NOT ``git archive HEAD``: this guard's subject, ravenroot/pom.xml, is exactly
    the file an uncommitted fix edits, and archiving HEAD would silently test the pre-fix
    committed content instead of what is actually on disk -- a false pass or a false fail
    depending on which fixture text moved, either way not the real target. ``git ls-files -co``
    (cached + others, minus ignored) mirrors what a CI checkout of this branch would contain
    once committed, without requiring a commit to test against.
    """
    temporary = tempfile.TemporaryDirectory()
    root = Path(temporary.name)
    listing = subprocess.run(
        ["git", "ls-files", "-co", "--exclude-standard", "-z"],
        cwd=REPO_ROOT,
        check=True,
        capture_output=True,
    )
    relative_paths = [p for p in listing.stdout.decode("utf-8").split("\0") if p]
    for relative in relative_paths:
        source = REPO_ROOT / relative
        if not source.is_file():
            continue
        destination = root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
    # discover_poms shells out to `git ls-files`, so the copy must itself be a git working tree
    # or that call fails outright (exit 128, "not a git repository") rather than misreporting --
    # a bare `git init` is enough; `git ls-files -co` needs a repository to run in, not a commit,
    # since `-o` (untracked) walks the working tree directly.
    subprocess.run(["git", "init", "-q"], cwd=root, check=True)
    return temporary


class ArgLineGuardTests(unittest.TestCase):
    def test_real_reactor_has_exactly_one_composable_argline(self) -> None:
        """GREEN case: the real, current reactor is clean. Checked only after the RED cases
        below are proven to fail correctly, so the method demonstrates both outcomes.
        """
        with real_repo_copy() as root:
            root_path = Path(root)
            poms = check_argline.discover_poms(root_path)
            errors = check_argline.check(root_path, poms)
        self.assertEqual([], errors, errors)
        # A regression guard on the guard itself: if this repository's module count ever drops
        # to 1, the walk is broken (matching nothing), not thorough.
        self.assertGreater(len(poms), 5, poms)

    def test_red_a_second_literal_argline_in_a_child_module_is_caught(self) -> None:
        """RED case, reproducing the exact clobber verified empirically against the real reactor:
        a module that adds its own <argLine> (the natural thing a contributor
        reaching for 'I need one more JVM flag' would write) silently drops the parent's locale
        pin. That loss is invisible to Maven; this guard is what makes it visible.
        """
        with real_repo_copy() as root:
            root_path = Path(root)
            child_pom = root_path / "ravenroot/ravenroot-application-api/pom.xml"
            text = child_pom.read_text(encoding="utf-8")
            self.assertNotIn("<argLine>", text, "fixture assumption drifted: child already declares argLine")
            # Anchored on </project> alone -- the root element's closing tag, present exactly
            # once in any well-formed pom.xml -- rather than on whatever happens to precede it
            # (this module's pom now ends "...</build>\n</project>", not "...</dependencies>\n
            # </project>" as it did when this anchor was first written: the enforce-vendor-free-sdk
            # rule added a <build> block after <dependencies>). Anchoring on the fixed, structural
            # end of the file survives that kind of unrelated edit; anchoring on a specific sibling
            # sequence does not.
            clobbered = text.replace(
                "</project>",
                (
                    "    <build>\n"
                    "        <plugins>\n"
                    "            <plugin>\n"
                    "                <groupId>org.apache.maven.plugins</groupId>\n"
                    "                <artifactId>maven-surefire-plugin</artifactId>\n"
                    "                <configuration>\n"
                    "                    <argLine>-Dprobe=child-clobber</argLine>\n"
                    "                </configuration>\n"
                    "            </plugin>\n"
                    "        </plugins>\n"
                    "    </build>\n"
                    "</project>"
                ),
            )
            self.assertNotEqual(text, clobbered, "replacement anchor not found; fixture drifted")
            child_pom.write_text(clobbered, encoding="utf-8")
            poms = check_argline.discover_poms(root_path)
            errors = check_argline.check(root_path, poms)
        self.assertTrue(
            any(
                "ravenroot/ravenroot-application-api/pom.xml" in e and "silently replaces" in e
                for e in errors
            ),
            errors,
        )

    def test_red_a_second_argline_declaration_in_the_authoritative_pom_is_caught(self) -> None:
        """RED case: even inside the one file allowed to declare <argLine>, a second declaration
        (e.g. from a pasted duplicate <plugin> block) must be caught, not silently let the later
        one win.
        """
        with real_repo_copy() as root:
            root_path = Path(root)
            pom = root_path / check_argline.AUTHORITATIVE_POM
            text = pom.read_text(encoding="utf-8")
            occurrences = len(check_argline.ARG_LINE_RE.findall(check_argline.strip_comments(text)))
            self.assertEqual(1, occurrences, "fixture assumption drifted: expected exactly one real <argLine>")
            duplicated = text + "\n<!-- duplicate for test --><argLine>-Dprobe=duplicate</argLine>\n"
            pom.write_text(duplicated, encoding="utf-8")
            errors = check_argline.check(root_path, check_argline.discover_poms(root_path))
        self.assertTrue(any("more than once" in e for e in errors), errors)

    def test_red_a_regressed_bare_literal_argline_is_caught(self) -> None:
        """RED case: someone 'simplifies' the authoritative <argLine> back to the original bare
        literal, dropping the composability tokens. Catch the regression even though this
        specific pom is still the only file declaring <argLine>.
        """
        with real_repo_copy() as root:
            root_path = Path(root)
            pom = root_path / check_argline.AUTHORITATIVE_POM
            text = pom.read_text(encoding="utf-8")
            composable = "<argLine>${ravenroot.surefire.extraArgLine} ${ravenroot.surefire.localeArgLine}</argLine>"
            self.assertIn(composable, text, "fixture assumption drifted: composable argLine text changed")
            regressed = text.replace(
                composable,
                "<argLine>-Duser.language=${test.locale.language} -Duser.country=${test.locale.country}</argLine>",
            )
            pom.write_text(regressed, encoding="utf-8")
            errors = check_argline.check(root_path, check_argline.discover_poms(root_path))
        self.assertTrue(
            any("ravenroot.surefire.extraArgLine" in e and "regressed" in e for e in errors), errors
        )

    def test_argline_mentioned_only_in_a_comment_is_not_a_false_positive(self) -> None:
        """A pom.xml that merely talks about <argLine> in an explanatory XML comment (as
        ravenroot/pom.xml itself does) must not be flagged as declaring a second one.

        The injected comment carries a full, self-closed-pair <argLine>...</argLine> mention, not
        just a bare opening fragment. Mutation testing showed that a comment with only an opening
        "<argLine>" and no matching close cannot exercise strip_comments() at all, because
        ARG_LINE_RE itself already requires the pair and never matches the fragment -- disabling
        strip_comments() alone left this test green, silently proving nothing about it. A full pair
        is a false positive strip_comments() must be the one thing standing between it and
        check()'s "declares its own <argLine>" error.)
        """
        with real_repo_copy() as root:
            root_path = Path(root)
            child_pom = root_path / "ravenroot/ravenroot-application-api/pom.xml"
            text = child_pom.read_text(encoding="utf-8")
            # Same </project>-only anchor as the other RED cases in this file -- see the comment
            # in test_red_a_second_literal_argline_in_a_child_module_is_caught for why.
            annotated = text.replace(
                "</project>",
                (
                    "<!-- e.g. <argLine>-Dexample=1</argLine> in the parent pom, "
                    "see the locale pin there -->\n</project>"
                ),
            )
            self.assertNotEqual(text, annotated, "replacement anchor not found; fixture drifted")
            child_pom.write_text(annotated, encoding="utf-8")
            errors = check_argline.check(root_path, check_argline.discover_poms(root_path))
        self.assertEqual([], errors, errors)

    def test_red_a_blanked_composable_property_is_caught_with_no_argline_anywhere(self) -> None:
        """RED case: a child pom that blanks ravenroot.surefire.localeArgLine in its own
        <properties> drops the locale pin with NO
        <argLine> element anywhere in the file -- confirmed against a real forked JVM (en/US
        became the machine's it/IT) under BUILD SUCCESS, no warning. ARG_LINE_RE alone cannot see
        this; PROPERTY_RE_BY_NAME exists because of it.
        """
        with real_repo_copy() as root:
            root_path = Path(root)
            child_pom = root_path / "ravenroot/ravenroot-application-api/pom.xml"
            text = child_pom.read_text(encoding="utf-8")
            self.assertNotIn("ravenroot.surefire.localeArgLine", text, "fixture assumption drifted")
            # Same </project>-only anchor as the other RED cases in this file -- see the comment
            # in test_red_a_second_literal_argline_in_a_child_module_is_caught for why.
            injected = (
                "    <properties>\n"
                "        <ravenroot.surefire.localeArgLine></ravenroot.surefire.localeArgLine>\n"
                "    </properties>\n"
                "</project>"
            )
            self.assertNotIn("<argLine", injected, "fixture accidentally added an argLine")
            blanked = text.replace("</project>", injected)
            self.assertNotEqual(text, blanked, "replacement anchor not found; fixture drifted")
            child_pom.write_text(blanked, encoding="utf-8")
            errors = check_argline.check(root_path, check_argline.discover_poms(root_path))
        self.assertTrue(
            any(
                "ravenroot/ravenroot-application-api/pom.xml" in e
                and "ravenroot.surefire.localeArgLine" in e
                and "redeclares" in e
                for e in errors
            ),
            errors,
        )

    def test_red_an_argline_with_a_combine_self_attribute_is_caught(self) -> None:
        """RED case: a bare <argLine>(.*?)</argLine> pattern misses an opening tag that carries
        an attribute (e.g. combine.self="override", a real Maven Dom-merge control attribute a
        contributor might copy from documentation). Verified empirically that
        the un-hardened regex missed exactly this form; ARG_LINE_RE now tolerates it.
        """
        with real_repo_copy() as root:
            root_path = Path(root)
            child_pom = root_path / "ravenroot/ravenroot-application-api/pom.xml"
            text = child_pom.read_text(encoding="utf-8")
            # Same </project>-only anchor as the other RED cases in this file -- see the comment
            # in test_red_a_second_literal_argline_in_a_child_module_is_caught for why.
            attributed = text.replace(
                "</project>",
                (
                    "    <build>\n"
                    "        <pluginManagement>\n"
                    "            <plugins>\n"
                    "                <plugin>\n"
                    "                    <artifactId>maven-surefire-plugin</artifactId>\n"
                    "                    <configuration>\n"
                    '                        <argLine combine.self="override">-Dprobe=pm</argLine>\n'
                    "                    </configuration>\n"
                    "                </plugin>\n"
                    "            </plugins>\n"
                    "        </pluginManagement>\n"
                    "    </build>\n"
                    "</project>"
                ),
            )
            self.assertNotEqual(text, attributed, "replacement anchor not found; fixture drifted")
            child_pom.write_text(attributed, encoding="utf-8")
            errors = check_argline.check(root_path, check_argline.discover_poms(root_path))
        self.assertTrue(
            any(
                "ravenroot/ravenroot-application-api/pom.xml" in e and "silently replaces" in e
                for e in errors
            ),
            errors,
        )

    def test_a_populated_nested_git_worktree_is_not_scanned(self) -> None:
        """Regression pin for nested git working trees, each of which may carry a different
        ravenroot/pom.xml. A raw filesystem walk cannot tell a nested repository's module apart
        from this repository's own and reads the nested one's history as this reactor's state --
        confirmed: 11 false positives against the canonical checkout, invisible in CI only because
        actions/checkout leaves gitlink directories empty.

        Reproduced here with an actual nested git repository (a gitlink, not a name-matched
        directory) rather than the developer machine's specific worktree layout, so the control is
        portable and runs the same way in CI. RED first: a naive rglob walk (the old
        implementation's shape) finds the nested pre-fix pom and would flag it. GREEN: the real,
        git-aware discover_poms does not descend into the gitlink at all.
        """
        with real_repo_copy() as root:
            root_path = Path(root)
            nested = root_path / "vendor" / "nested-repo"
            nested.mkdir(parents=True)
            subprocess.run(["git", "init", "-q"], cwd=nested, check=True)
            nested_pom = nested / "pom.xml"
            nested_pom.write_text(
                "<project><build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>"
                "<configuration><argLine>-Duser.language=it -Duser.country=IT</argLine>"
                "</configuration></plugin></plugins></build></project>",
                encoding="utf-8",
            )
            subprocess.run(["git", "add", "-A"], cwd=nested, check=True)
            subprocess.run(
                ["git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "nested"],
                cwd=nested,
                check=True,
            )
            # RED control: a naive recursive filesystem walk -- the shape of the original,
            # rejected discover_poms -- does find the nested pom. This proves the fixture actually
            # reproduces the hazard rather than trivially passing because nothing is there.
            naive_walk = tuple(
                sorted(p.relative_to(root_path) for p in root_path.rglob("pom.xml"))
            )
            self.assertIn(nested_pom.relative_to(root_path), naive_walk, "fixture does not reproduce the hazard")

            # Register the nested repository as a gitlink in the outer repository: `git add` on a directory that itself
            # contains a `.git` records a gitlink (mode 160000), not the directory's contents.
            subprocess.run(["git", "add", "vendor/nested-repo"], cwd=root_path, check=True)
            tree_entry = subprocess.run(
                ["git", "ls-files", "-s", "vendor/nested-repo"],
                cwd=root_path,
                check=True,
                capture_output=True,
                text=True,
            ).stdout
            self.assertTrue(tree_entry.startswith("160000"), f"fixture did not become a gitlink: {tree_entry!r}")

            # GREEN: the real discover_poms, git-aware, does not descend into it, and the full
            # composite check() -- not just discover_poms in isolation -- passes clean even
            # though the nested repository's own pom.xml is a genuine, uncomposable clobber
            # (bare <argLine>, wrong locale). This is the exact "canonical checkout" shape from
            # this outer tree carries the fix, a nested tree does not, and only the
            # outer tree's own state may determine the outer guard's verdict.
            poms = check_argline.discover_poms(root_path)
            errors = check_argline.check(root_path, poms)
        self.assertNotIn(nested_pom.relative_to(root_path), poms, poms)
        self.assertFalse(any("nested-repo" in str(p) for p in poms), poms)
        self.assertEqual([], errors, errors)

    def test_red_locale_override_still_wired_is_a_pinned_acceptance_criterion(self) -> None:
        """Locale-override contract: -Dtest.locale.language/-Dtest.locale.country must
        keep overriding the forked JVM's locale exactly as before this change. Verified once by
        hand against a real fork (mvn surefire:test -Dtest.locale.language=it
        -Dtest.locale.country=IT produced user.language=it); this test pins it permanently and
        statically so a future edit that quietly drops the reference is caught without anyone
        having to remember to fork a JVM again.
        """
        with real_repo_copy() as root:
            root_path = Path(root)
            pom = root_path / check_argline.AUTHORITATIVE_POM
            text = pom.read_text(encoding="utf-8")
            wired = (
                "<ravenroot.surefire.localeArgLine>-Duser.language=${test.locale.language} "
                "-Duser.country=${test.locale.country}</ravenroot.surefire.localeArgLine>"
            )
            self.assertIn(wired, text, "fixture assumption drifted: locale property definition text changed")
            unwired = text.replace(
                wired,
                "<ravenroot.surefire.localeArgLine>-Duser.language=${test.locale.language}</ravenroot.surefire.localeArgLine>",
            )
            self.assertNotEqual(text, unwired)
            pom.write_text(unwired, encoding="utf-8")
            errors = check_argline.check(root_path, check_argline.discover_poms(root_path))
        self.assertTrue(
            any(
                "test.locale.country" in e and "locale-override contract" in e
                for e in errors
            ),
            errors,
        )

    def test_command_line_success_is_reproducible(self) -> None:
        result = subprocess.run(
            [sys.executable, str(SCRIPTS / "check_argline.py"), "--root", str(REPO_ROOT)],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertIn("argLine composability checks passed.", result.stdout)


if __name__ == "__main__":
    unittest.main()
