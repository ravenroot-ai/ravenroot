#!/usr/bin/env python3
"""Deterministically guard Ravenroot's composable surefire ``argLine`` wiring.

``ravenroot/pom.xml`` pins the forked test JVM's locale via a single, composable ``<argLine>``
element built from two properties (``ravenroot.surefire.extraArgLine``,
``ravenroot.surefire.localeArgLine``). Maven's plugin-configuration inheritance replaces a scalar
text element declared by a second ``<plugin>`` block or a child module's own ``pom.xml`` wholesale
rather than appending to it: there is no parent-side XML trick that prevents this, so a second
``<argLine>`` anywhere in the reactor silently drops the locale pin, with no warning and no error
from Maven itself. This guard supplies that warning and error deterministically instead of requiring
a lost test run to reveal the problem.

Run ``python3 scripts/check_argline.py`` from the repository root. Does not invoke Maven and does
not fork a JVM: it is a static text guard over the committed ``pom.xml`` files, deliberately fast
enough to run on every push.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

# The one file allowed to declare a literal <argLine>, and the tokens its value must contain to
# prove the composable wiring has not regressed back to a bare literal that a CLI override or a
# sibling module can silently discard or replace.
AUTHORITATIVE_POM = Path("ravenroot/pom.xml")
REQUIRED_TOKENS = ("${ravenroot.surefire.extraArgLine}", "${ravenroot.surefire.localeArgLine}")

# The two properties AUTHORITATIVE_POM's <argLine> is built from. Property inheritance in Maven
# is the same scalar-override mechanism as plugin configuration: a child pom's own <properties>
# entry with one of these names fully replaces the inherited value for ${} interpolation, exactly
# like a second <argLine> element does, EXCEPT it leaves no <argLine> text anywhere for
# ARG_LINE_RE to find. Confirmed empirically against the real reactor:
# a child pom declaring only
#   <properties><ravenroot.surefire.localeArgLine></ravenroot.surefire.localeArgLine></properties>
# drops the locale pin from the forked JVM (en/US became the machine's it/IT) under BUILD SUCCESS,
# no warning, and this guard reported green before this check existed. This gap belongs to this
# change, not a follow-up: the property indirection that makes composition possible is exactly
# what opened it.
COMPOSABLE_PROPERTIES = ("ravenroot.surefire.extraArgLine", "ravenroot.surefire.localeArgLine")

COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)
# \b[^>]* tolerates an attribute on the opening tag (e.g. <argLine combine.self="override">):
# a bare <argLine>(.*?)</argLine> pattern verifiably misses that form -- confirmed empirically,
# not assumed; the behavior is pinned against this exact regex. combine.self is
# redundant here (a plain
# scalar element already overrides by default, verified separately against a real Maven build),
# but a contributor copying a Maven-docs example would write the attribute anyway, and the guard
# must fire on what they actually write, not on the minimal form that happens to trigger it.
ARG_LINE_RE = re.compile(r"<argLine\b[^>]*>(.*?)</argLine>", re.DOTALL)


def _property_element_re(name: str) -> re.Pattern[str]:
    """A regex matching a property element by exact tag name, open-close or self-closed, tolerant
    of an attribute on the opening tag -- the same shape as ARG_LINE_RE, for the same reason.
    """
    escaped = re.escape(name)
    return re.compile(rf"<{escaped}\b[^>]*?/>|<{escaped}\b[^>]*>.*?</{escaped}>", re.DOTALL)


PROPERTY_RE_BY_NAME = {name: _property_element_re(name) for name in COMPOSABLE_PROPERTIES}

# The locale override requirement is that -Dtest.locale.language / -Dtest.locale.country keep
# overriding the forked JVM's locale. That override is wired
# through ravenroot.surefire.localeArgLine's own definition, not through <argLine> directly, so
# REQUIRED_TOKENS (which only looks at <argLine>'s text) cannot see a regression here on its own.
LOCALE_OVERRIDE_TOKENS = ("${test.locale.language}", "${test.locale.country}")
LOCALE_PROPERTY_RE = re.compile(
    r"<ravenroot\.surefire\.localeArgLine\b[^>]*>(.*?)</ravenroot\.surefire\.localeArgLine>",
    re.DOTALL,
)

# Belt-and-suspenders only: build output this repository's .gitignore already keeps out of
# `git ls-files`. Not the mechanism that keeps this guard inside repository boundaries -- see
# discover_poms.
EXCLUDED_PARTS = ("target", "node_modules")


def discover_poms(root: Path) -> tuple[Path, ...]:
    """Enumerate every pom.xml this repository's own git index knows about, deterministically, so
    a new module is covered by this guard the moment it exists, with no list to remember to
    extend.

    Deliberately ``git ls-files``, not ``root.rglob("pom.xml")``. A repository may contain nested
    working trees or gitlinks, each carrying its own ravenroot/pom.xml.
    A filesystem walk cannot tell those apart from this repository's own modules and silently
    scores this repository's guard against other checkouts' history -- confirmed: it read 11
    false positives from nested trees while a nested-worktree-free CI checkout stayed green. A
    gitlink (mode 160000) is opaque to `git ls-files` by construction: the entry names
    the nested repository's directory but git never descends into it, because that content belongs
    to a different repository's object store. That is a structural guarantee, not a name-based
    blocklist that the next differently-named worktree or nested checkout would silently defeat.
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
        if relative.name != "pom.xml":
            continue
        if any(part in EXCLUDED_PARTS for part in relative.parts):
            continue
        found.append(relative)
    return tuple(sorted(found))


def strip_comments(text: str) -> str:
    """Remove XML comments before scanning so prose that mentions ``<argLine>`` inside an
    explanatory comment is never mistaken for a second declaration.
    """
    return COMMENT_RE.sub("", text)


def check(root: Path, poms: tuple[Path, ...] | None = None) -> list[str]:
    root = root.resolve()
    if poms is None:
        poms = discover_poms(root)
    errors: list[str] = []
    authoritative_seen = False
    for relative in poms:
        path = root / relative
        if not path.is_file():
            errors.append(f"missing pom: {relative}")
            continue
        text = strip_comments(path.read_text(encoding="utf-8"))
        if relative != AUTHORITATIVE_POM:
            for name, pattern in PROPERTY_RE_BY_NAME.items():
                if pattern.search(text):
                    errors.append(
                        f"{relative}: redeclares the {name} property, which silently replaces "
                        f"{AUTHORITATIVE_POM}'s value for Maven's ${{}} interpolation without any "
                        "<argLine> element appearing anywhere in this file (confirmed against the "
                        "real reactor: this alone drops the locale pin from the forked JVM under "
                        "BUILD SUCCESS with no warning); set -D"
                        f"{name}=... on the command line instead of declaring this property here"
                    )
        matches = ARG_LINE_RE.findall(text)
        if not matches:
            continue
        if relative != AUTHORITATIVE_POM:
            errors.append(
                f"{relative}: declares its own <argLine>, which silently replaces "
                f"{AUTHORITATIVE_POM}'s locale pin instead of adding to it (Maven's plugin "
                "configuration inheritance overrides a scalar element wholesale); use "
                "-Dravenroot.surefire.extraArgLine=... or add to that property instead of "
                "declaring a second <argLine>"
            )
            continue
        authoritative_seen = True
        if len(matches) > 1:
            errors.append(f"{relative}: declares <argLine> more than once ({len(matches)} time(s))")
        for value in matches:
            missing = [token for token in REQUIRED_TOKENS if token not in value]
            if missing:
                errors.append(
                    f"{relative}: <argLine> value {value.strip()!r} is missing "
                    f"{', '.join(missing)}; the composable wiring has regressed to (or been "
                    "replaced by) a bare literal"
                )
        locale_property = LOCALE_PROPERTY_RE.search(text)
        if locale_property is None:
            errors.append(
                f"{relative}: ravenroot.surefire.localeArgLine property definition is missing "
                "entirely; -Dtest.locale.language/-Dtest.locale.country would no longer reach "
                "the forked JVM (locale-override contract)"
            )
        else:
            missing_locale = [t for t in LOCALE_OVERRIDE_TOKENS if t not in locale_property.group(1)]
            if missing_locale:
                errors.append(
                    f"{relative}: ravenroot.surefire.localeArgLine no longer references "
                    f"{', '.join(missing_locale)}; -Dtest.locale.language/-Dtest.locale.country "
                    "would stop overriding the forked JVM's locale (locale-override contract)"
                )
    if not authoritative_seen:
        errors.append(f"{AUTHORITATIVE_POM}: expected <argLine> declaration is missing entirely")
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
    poms = discover_poms(root)
    errors = check(root, poms)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(
        f"argLine composability checks passed. ({len(poms)} pom.xml file(s) scanned; "
        f"1 authoritative <argLine> in {AUTHORITATIVE_POM})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
