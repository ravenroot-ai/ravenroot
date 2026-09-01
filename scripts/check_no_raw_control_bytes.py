#!/usr/bin/env python3
"""Deterministically guard against raw control bytes re-entering a tracked Java source file.

``ravenroot/ravenroot-core/.../runtime/DefaultGraphDeployment.java`` once carried two raw NUL bytes
inside character literals, written directly instead of through the ``'\\0'`` escape -- semantically
identical (compiles the same, derives the same identifiers) but with a tooling cost that has nothing
to do with runtime behaviour: a raw **NUL** byte anywhere in a file makes ``grep`` classify the whole
file as binary and skip it *silently*, and makes ``git diff``/``git grep`` report the file as binary
instead of a line-level match. A call-site search restricted to ``*.java`` files returned zero hits in
a file holding two of them during impact analysis, with no warning that anything had been
skipped -- exactly how a reader concludes a code path never does something it actually does, and
designs on top of that conclusion. This is measured, not assumed -- see
``docs/qa/grep-and-git-binary-file-detection.md`` for the exact commands, tool versions, and which
other control bytes were checked and found *not* to reproduce it.

Every other byte below 0x20 that this guard refuses (tab, LF and CR excepted -- see
``ALLOWED_CONTROL_BYTES``) is refused for a different and weaker reason. On every toolchain
``docs/qa/grep-and-git-binary-file-detection.md`` measured -- BSD grep, GNU grep 3.8 and 3.11, and
git 2.39.5 -- only NUL triggers the grep/git binary-file misclassification described above; a
non-NUL control byte such as BEL (0x07) or ESC (0x1b) was measured to behave as ordinary text
under both ``grep`` and ``git diff``. It is still refused, because a raw control byte has no
legitimate reason to appear in a Java source file and is known to break other tooling -- editors,
terminals, syntax highlighters -- in ways that differ byte by byte and were not individually
catalogued here. Which of the two reasons applies is a property of the byte, not of this guard's
behaviour: the set of bytes refused does not change, only the reason ``check()`` states for a
given one.

Run ``python3 scripts/check_no_raw_control_bytes.py`` from the repository root. Does not invoke Maven
and does not fork a JVM: a static byte-level guard over every ``git``-tracked ``*.java`` file,
deliberately fast enough to run on every push (low tens of milliseconds over this reactor's ~700
source files, measured).

Why *.java only, declared rather than silently assumed: the defect this guards against was
demonstrated in a Java character literal, and the harm that motivated this guard -- grep's and git's
NUL-triggered binary-file misclassification defeating a call-site search -- is sharpest for the
language this repository's own cross-references are written in and searched against. Scanning
``pom.xml``, shell scripts and Markdown as well would not be free: each has its own legitimate
non-ASCII or generated content this guard has not been validated against, and widening scope
without a concrete case in hand risks false positives no more informative than the defect this
guard exists to catch. If a raw control byte is ever found outside a ``.java`` file, that is the
concrete case that should extend ``SCANNED_SUFFIXES`` -- not a guess made now.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

# See the module docstring's "Why *.java only" section for why this is exactly one suffix today.
SCANNED_SUFFIXES = (".java",)

# The only control bytes ordinary source text legitimately contains. Anything else below 0x20 --
# NUL (0x00) included -- has no business appearing raw in a tracked source file.
ALLOWED_CONTROL_BYTES = frozenset({0x09, 0x0A, 0x0D})  # tab, LF, CR

# Belt-and-suspenders only, mirroring check_argline.py's own EXCLUDED_PARTS: build output this
# repository's .gitignore already keeps out of `git ls-files`. Not the mechanism that keeps this
# guard inside repository boundaries -- see discover_sources.
EXCLUDED_PARTS = ("target", "node_modules")


def discover_sources(root: Path) -> tuple[Path, ...]:
    """Enumerate every ``*.java`` file this repository's own git index knows about,
    deterministically, so a new module or a new file is covered by this guard the moment it exists,
    with no list to remember to extend.

    Deliberately ``git ls-files``, not ``root.rglob("*.java")`` -- the same reasoning as
    ``check_argline.discover_poms``: nested working trees may carry their own copy of any file this
    guard cares about. A filesystem walk
    cannot tell those apart from this repository's own sources and would silently score this
    repository's guard against other checkouts' history; a gitlink (mode 160000) is opaque to
    ``git ls-files`` by construction, which is a structural guarantee rather than a name-based
    blocklist the next differently-named worktree would defeat.
    """
    listing = subprocess.run(
        ["git", "ls-files", "-co", "--exclude-standard", "-z"],
        cwd=root,
        check=True,
        capture_output=True,
    )
    found: list[Path] = []
    for raw in listing.stdout.decode("utf-8").split("\0"):
        if not raw:
            continue
        relative = Path(raw)
        if relative.suffix not in SCANNED_SUFFIXES:
            continue
        if any(part in EXCLUDED_PARTS for part in relative.parts):
            continue
        found.append(relative)
    return tuple(sorted(found))


def check(root: Path, sources: tuple[Path, ...] | None = None) -> list[str]:
    root = root.resolve()
    if sources is None:
        sources = discover_sources(root)
    errors: list[str] = []
    for relative in sources:
        path = root / relative
        if not path.is_file():
            errors.append(f"missing source: {relative}")
            continue
        data = path.read_bytes()
        for offset, byte in enumerate(data):
            if byte < 0x20 and byte not in ALLOWED_CONTROL_BYTES:
                if byte == 0x00:
                    # Demonstrated, not assumed: measured against BSD grep and git 2.39.5 (Apple
                    # Git-154) -- see docs/qa/grep-and-git-binary-file-detection.md for the exact
                    # commands, toolchains checked and toolchains explicitly left unmeasured.
                    reason = (
                        "grep and git both key their binary-file detection on NUL specifically: on "
                        "the toolchains measured, a raw NUL makes `grep` report \"Binary file ... "
                        "matches\" instead of the line, and makes `git diff`/`git grep` report the "
                        "file as binary instead of showing the match"
                    )
                else:
                    # The general grep/git binary-file check does NOT fire for this byte on the
                    # toolchains measured -- see the same doc. Refused anyway: no legitimate raw use
                    # in Java source, and known to break other tooling (editors, terminals, syntax
                    # highlighters) in byte-specific ways this guard does not individually catalogue.
                    reason = (
                        "unlike NUL, this byte was measured NOT to trigger grep's or git's "
                        "binary-file detection -- it is refused because a raw control byte has no "
                        "legitimate use in source text and is known to break other tooling in ways "
                        "that differ byte by byte, not because it reproduces NUL's failure"
                    )
                errors.append(
                    f"{relative}: raw control byte 0x{byte:02x} at offset {offset} -- {reason}; "
                    "replace it with its escape sequence instead ('\\0' for NUL, etc.)"
                )
                break  # one report per file is enough to act on; keep scanning the rest
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path.cwd(),
        help="repository root to check (default: current directory)",
    )
    args = parser.parse_args(argv)
    root = args.root.resolve()
    sources = discover_sources(root)
    errors = check(root, sources)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(f"No raw control bytes found. ({len(sources)} *.java file(s) scanned)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
