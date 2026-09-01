from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import check_no_raw_control_bytes  # noqa: E402


REPO_ROOT = SCRIPTS.parent


def real_repo_copy() -> tempfile.TemporaryDirectory[str]:
    """A working copy of the real repository tree as it stands right now: tracked files plus
    untracked-but-not-gitignored files, from the working tree rather than HEAD.

    Same reasoning as ``test_check_argline.real_repo_copy`` (duplicated rather than imported: each
    script's test module is self-contained, matching this directory's own convention): a fixture
    file this guard's own check mutates on disk must reflect what is actually checked out, not what
    HEAD says, and ``check_no_raw_control_bytes.discover_sources`` shells out to ``git ls-files``, so
    the copy has to be its own git working tree (a bare ``git init`` is enough) or that call fails
    outright rather than misreporting.
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
    subprocess.run(["git", "init", "-q"], cwd=root, check=True)
    return temporary


class NoRawControlBytesGuardTests(unittest.TestCase):
    def test_real_reactor_has_no_raw_control_bytes(self) -> None:
        """GREEN case: the real, current reactor is clean. Checked only after the RED cases below
        are proven to fail correctly -- the same method discipline check_argline.py's own suite
        documents, and for the same reason: a guard that has never been seen to fail proves nothing
        about whether it can.
        """
        with real_repo_copy() as root:
            root_path = Path(root)
            sources = check_no_raw_control_bytes.discover_sources(root_path)
            errors = check_no_raw_control_bytes.check(root_path, sources)
        self.assertEqual([], errors, errors)
        # A regression guard on the guard itself: if the discovered file count ever collapses to a
        # handful, the walk is broken (matching almost nothing), not thorough.
        self.assertGreater(len(sources), 100, sources)

    def test_red_a_raw_control_byte_in_a_module_other_than_core_is_caught(self) -> None:
        """RED case that ensures this guard catches a raw control
        byte in ANY module, not merely the one (ravenroot-core) that hosted the original defect and
        the JUnit-based guard this script replaces. That JUnit guard's own root-resolution walked up
        to the first pom.xml containing the parent artifact string, which ravenroot-core's own
        pom.xml also matches in its <parent> block -- so the walk stopped at ravenroot-core and a
        raw byte anywhere else went undetected, and a mutation planted inside ravenroot-core could
        never have caught that gap (it would redden regardless of whether the scan was reactor-wide
        or not). This test plants the byte in ravenroot-pekko instead, a module unrelated to the
        original fix, so a pass here is coverage, not coincidence of path.
        """
        with real_repo_copy() as root:
            root_path = Path(root)
            target = root_path / "ravenroot/ravenroot-pekko/src/main/java/ai/ravenroot/pekko/PekkoExecutionEngine.java"
            self.assertTrue(target.is_file(), "fixture assumption drifted: file moved or renamed")
            data = target.read_bytes()
            self.assertNotIn(b"\x00", data, "fixture assumption drifted: file already contains a raw NUL")
            mutated = data[:200] + b"\x00" + data[200:]
            target.write_bytes(mutated)
            sources = check_no_raw_control_bytes.discover_sources(root_path)
            errors = check_no_raw_control_bytes.check(root_path, sources)
        self.assertTrue(
            any("ravenroot-pekko" in e and "0x00" in e for e in errors),
            errors,
        )

    def test_red_a_raw_control_byte_in_the_module_that_originally_shipped_one_is_still_caught(self) -> None:
        """RED case covering the same file as the original fix, so a regression there is
        still caught -- kept alongside the cross-module case above rather than instead of it, since
        neither alone proves what the other does.
        """
        with real_repo_copy() as root:
            root_path = Path(root)
            target = (
                root_path
                / "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/DefaultGraphDeployment.java"
            )
            self.assertTrue(target.is_file())
            data = target.read_bytes()
            self.assertNotIn(b"\x00", data, "fixture assumption drifted: the fix regressed already")
            mutated = data.replace(b"'\\0'", b"'\x00'", 1)
            self.assertNotEqual(data, mutated, "fixture assumption drifted: '\\0' literal not found")
            target.write_bytes(mutated)
            sources = check_no_raw_control_bytes.discover_sources(root_path)
            errors = check_no_raw_control_bytes.check(root_path, sources)
        self.assertTrue(
            any("DefaultGraphDeployment.java" in e and "0x00" in e for e in errors),
            errors,
        )

    def test_a_legitimate_tab_and_newline_are_not_false_positives(self) -> None:
        """Ordinary source whitespace (tab, LF, CR) must never be flagged -- every real Java file in
        this reactor contains these constantly, so a guard that could not tell them apart from a
        genuine control-byte defect would fail on everything, not on the one thing it exists to
        catch.
        """
        with real_repo_copy() as root:
            root_path = Path(root)
            target = root_path / "ravenroot/ravenroot-pekko/src/main/java/ai/ravenroot/pekko/PekkoExecutionEngine.java"
            data = target.read_bytes()
            self.assertIn(b"\n", data)
            sources = check_no_raw_control_bytes.discover_sources(root_path)
            errors = check_no_raw_control_bytes.check(root_path, sources)
        self.assertEqual([], errors, errors)

    def test_a_non_java_file_with_a_raw_control_byte_is_not_scanned(self) -> None:
        """Scope is *.java only, declared in the module docstring rather than silently assumed --
        this test pins that declared scope so a future edit that widens it does so knowingly rather
        than by accident (see check_no_raw_control_bytes.SCANNED_SUFFIXES).
        """
        with real_repo_copy() as root:
            root_path = Path(root)
            probe = root_path / "scripts" / "probe.txt"
            probe.write_bytes(b"not scanned\x00by design")
            sources = check_no_raw_control_bytes.discover_sources(root_path)
            errors = check_no_raw_control_bytes.check(root_path, sources)
        self.assertEqual([], errors, errors)

    def test_every_byte_below_0x20_is_rejected_except_tab_lf_cr_and_0x7f_is_not(self) -> None:
        """Regression guard on the guard's own predicate: the suite before this test covered
        specific bytes (0x00 via the two
        RED cases above, tab/LF/CR via the false-positive case) but never the predicate as a whole,
        so a change that silently widened or narrowed ``byte < 0x20`` -- exactly the kind of change
        a message-only correction was forbidden to make to the rejected byte set -- could have
        shipped green.

        Parametrized over every byte 0x00-0x1f plus the 0x7f boundary probe, against a minimal
        synthetic .java file rather than ``real_repo_copy``'s full-repository copy: 33 iterations of
        a multi-second repository copy would make this one test slower than the rest of the suite
        combined for no additional coverage, since ``check()`` never touches
        ``discover_sources``'s git plumbing once given an explicit ``sources`` tuple.

        The expected outcome per byte is fixed here **literally** (``{0x09, 0x0A, 0x0D}``), not read
        back from this module's own ``ALLOWED_CONTROL_BYTES`` -- that constant is exactly what an
        accidental softening would change, so deriving the expectation from it would make the test
        follow the mutation instead of catching it. Measured directly: with ``0x0B`` added to
        ``ALLOWED_CONTROL_BYTES`` (a one-byte softening), reading the expected set from the module
        made every one of this suite's seven tests pass -- 0x0B silently left the rejected set with
        nothing red anywhere. The 0x7f probe is unaffected by this and kept exactly as it was:
        0x7f is deliberately in range and deliberately NOT expected to be rejected. Confirmed by
        direct measurement before writing this test: ``check()`` accepts 0x7f (and 0x80, 0xff) today,
        unchanged by the related correction. Asserting REJECT for 0x7f here would misdescribe current
        behaviour and contradict the declared "the rejected byte set does not change" constraint.
        Its role is the opposite of the 0x00-0x1f cases: it pins *non*-rejection, so a future
        accidental widening of the ``< 0x20`` bound (e.g. to ``<= 0x20``) goes red here even though
        every case below it still passes.
        """
        allowed_control_bytes_literal = {0x09, 0x0A, 0x0D}  # tab, LF, CR -- fixed, not read from
        # check_no_raw_control_bytes.ALLOWED_CONTROL_BYTES; see the docstring above.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target_rel = Path("Marker.java")
            target = root / target_rel
            for byte in list(range(0x20)) + [0x7F]:
                target.write_bytes(b"class Marker {\n    // " + bytes([byte]) + b"\n}\n")
                errors = check_no_raw_control_bytes.check(root, (target_rel,))
                if byte in allowed_control_bytes_literal or byte >= 0x20:
                    self.assertEqual([], errors, f"byte 0x{byte:02x} must NOT be rejected: {errors}")
                else:
                    self.assertEqual(
                        1, len(errors), f"byte 0x{byte:02x} must be rejected exactly once: {errors}"
                    )
                    self.assertIn(f"0x{byte:02x}", errors[0])

    def test_the_message_claims_binary_exclusion_only_for_nul(self) -> None:
        """Pins the central factual correction so it cannot silently regress in either direction:
        the rejection message for NUL must claim the measured grep/git binary-exclusion failure
        while the message for every other rejected byte must NOT claim it -- see
        docs/qa/grep-and-git-binary-file-detection.md for the measurement this asserts against.
        Previously both branches made the same, partly false, claim; after a hypothetical revert
        they would again, and this is the test that would catch that.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target_rel = Path("Marker.java")
            target = root / target_rel

            target.write_bytes(b"class Marker {\n    // " + bytes([0x00]) + b"\n}\n")
            nul_errors = check_no_raw_control_bytes.check(root, (target_rel,))
            self.assertEqual(1, len(nul_errors), nul_errors)
            self.assertIn("Binary file", nul_errors[0])

            target.write_bytes(b"class Marker {\n    // " + bytes([0x07]) + b"\n}\n")
            bel_errors = check_no_raw_control_bytes.check(root, (target_rel,))
            self.assertEqual(1, len(bel_errors), bel_errors)
            self.assertNotIn("Binary file", bel_errors[0])


if __name__ == "__main__":
    unittest.main()
