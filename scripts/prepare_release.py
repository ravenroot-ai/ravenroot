#!/usr/bin/env python3
"""Turn a `release:*` label into the change the promotion needs: version, notes, consumed fragments.

The label on the `dev` to `main` promotion is the instruction to increment the version. This script
produces the one reviewable change that instruction implies, on `dev`, before the promotion merges:

* every version surface moves from the latest release to the version the label authorizes;
* `docs/releases/v<version>.md` is assembled from the unconsumed change fragments, grouped by kind;
* exactly those fragments are removed.

It is deterministic — the same tree and intent always give the same result — and it refuses rather
than guesses: a second run, a checkout whose version is not the latest release, an unknown fragment
kind, or nothing to release are all errors. Its output is merged into `dev` like any other change,
and `release_contract.py check-promotion` then refuses the promotion if the two ever disagree.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import textwrap
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.release_contract import ReleaseContractError, expected_next, parse_tag


INTENTS = ("patch", "minor", "major")

# Rendered in this order. `known-issue` records a defect the release ships with knowingly, so that
# the decision travels with the release instead of depending on someone remembering to add it.
KINDS = (
    ("known-issue", "Known issues"),
    ("breaking", "Breaking changes"),
    ("security", "Security"),
    ("feature", "Features"),
    ("fix", "Fixes"),
    ("docs", "Documentation"),
    ("other", "Other changes"),
)

UI_PACKAGE = Path("ravenroot/ravenroot-ui/package.json")
UI_LOCKFILE = Path("ravenroot/ravenroot-ui/package-lock.json")
HELM_CHART = Path("deploy/helm/ravenroot/Chart.yaml")
README = Path("README.md")
EXTENSION_PACK_GUIDE = Path("docs/integrator-guide/extension-pack.md")
NAVIGATION = Path("docs/_data/navigation.yml")
FRAGMENTS = Path(".changes")


def git(root: Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments], cwd=root, check=True, capture_output=True, text=True
    ).stdout.strip()


def product_version(root: Path) -> str:
    pom = (root / "ravenroot/pom.xml").read_text(encoding="utf-8")
    project = re.sub(r"(?s)<parent>.*?</parent>", "", pom)
    match = re.search(r"<version>([^<]+)</version>", project)
    if not match:
        raise ReleaseContractError("ravenroot/pom.xml has no project version")
    return match.group(1)


def latest_release(root: Path):
    releases = []
    for tag in git(root, "tag", "--merged", "HEAD", "--list", "v*").splitlines():
        try:
            releases.append(parse_tag(tag))
        except ReleaseContractError:
            continue
    if not releases:
        raise ReleaseContractError("no release tag is reachable from HEAD; nothing to increment from")
    return sorted(releases, key=lambda version: version.semantic_key())[-1]


def replace_exactly(path: Path, old: str, new: str, expected: int | None = None) -> None:
    contents = path.read_text(encoding="utf-8")
    found = contents.count(old)
    if found == 0 or (expected is not None and found != expected):
        raise ReleaseContractError(f"{path}: expected {expected or 'some'} of {old!r}, found {found}")
    path.write_text(contents.replace(old, new), encoding="utf-8")


def bump_surfaces(root: Path, previous: str, target: str) -> None:
    """Move every surface the maintainer procedure names, plus the snippets derived from them."""
    for relative in git(root, "ls-files", "*pom.xml").splitlines():
        path = root / relative
        contents = path.read_text(encoding="utf-8")
        updated = contents.replace(f"<version>{previous}</version>", f"<version>{target}</version>")
        updated = updated.replace(
            f"<ravenroot.version>{previous}</ravenroot.version>",
            f"<ravenroot.version>{target}</ravenroot.version>",
        )
        path.write_text(updated, encoding="utf-8")

    replace_exactly(root / UI_PACKAGE, f'"version": "{previous}"', f'"version": "{target}"', 1)
    replace_exactly(root / UI_LOCKFILE, f'"version": "{previous}"', f'"version": "{target}"', 2)
    lock = json.loads((root / UI_LOCKFILE).read_text(encoding="utf-8"))
    if lock.get("version") != target or lock.get("packages", {}).get("", {}).get("version") != target:
        raise ReleaseContractError(f"{UI_LOCKFILE}: the root package version did not move to {target}")

    replace_exactly(root / HELM_CHART, f"version: {previous}\n", f"version: {target}\n", 1)
    replace_exactly(root / HELM_CHART, f'appVersion: "{previous}"', f'appVersion: "{target}"', 1)
    # The README also records that the lifecycle began at the first release; only the install
    # coordinates follow the current version.
    replace_exactly(root / README, f"<version>{previous}</version>", f"<version>{target}</version>")
    replace_exactly(
        root / EXTENSION_PACK_GUIDE,
        f"<ravenroot.version>{previous}</ravenroot.version>",
        f"<ravenroot.version>{target}</ravenroot.version>",
        1,
    )
    replace_exactly(
        root / EXTENSION_PACK_GUIDE, f"-Dravenroot.version={previous}", f"-Dravenroot.version={target}", 1
    )


def collect_fragments(root: Path) -> dict[str, list[tuple[Path, str]]]:
    by_kind: dict[str, list[tuple[Path, str]]] = {kind: [] for kind, _ in KINDS}
    for path in sorted((root / FRAGMENTS).glob("*.md")):
        if path.name == "README.md":
            continue
        kind = path.name[: -len(".md")].rsplit(".", 1)[-1]
        if kind not in by_kind:
            raise ReleaseContractError(f"{path.relative_to(root)}: unknown fragment kind {kind!r}")
        body = path.read_text(encoding="utf-8").strip()
        if not body:
            raise ReleaseContractError(f"{path.relative_to(root)}: empty fragment")
        by_kind[kind].append((path, body))
    if not any(by_kind.values()):
        raise ReleaseContractError("there are no unconsumed change fragments; nothing to release")
    return by_kind


def as_list_item(body: str) -> str:
    lines = body.split("\n")
    return "\n".join(["- " + lines[0], *[("  " + line) if line.strip() else "" for line in lines[1:]]])


def release_notes(previous: str, target: str, by_kind: dict[str, list[tuple[Path, str]]]) -> str:
    intro = [f"Ravenroot {target} delivers the work integrated since `{previous}`."]
    if by_kind["breaking"]:
        intro.append(
            "It contains breaking changes: read *Breaking changes* below before upgrading, together "
            "with the compatibility policy and the operator guides."
        )
    intro.append(
        "Pin every Maven and OCI dependency to this exact version, and use an immutable container "
        "digest where deployment reproducibility matters."
    )
    lines = [f"# Ravenroot {target}", "", textwrap.fill(" ".join(intro), width=100), ""]
    for kind, title in KINDS:
        if by_kind[kind]:
            lines += [f"## {title}", "", *(as_list_item(body) for _, body in by_kind[kind]), ""]
    return "\n".join(lines).rstrip() + "\n"


def link_notes(root: Path, target: str) -> None:
    navigation = (root / NAVIGATION).read_text(encoding="utf-8")
    match = re.search(r"(?m)^    - title: Ravenroot \S+ release notes$", navigation)
    if not match:
        raise ReleaseContractError(f"{NAVIGATION}: no release notes entry to place the new one before")
    entry = f"    - title: Ravenroot {target} release notes\n      url: /releases/v{target}.html\n"
    (root / NAVIGATION).write_text(
        navigation[: match.start()] + entry + navigation[match.start() :], encoding="utf-8"
    )


def prepare(root: Path, intent: str) -> dict[str, object]:
    if intent not in INTENTS:
        raise ReleaseContractError(f"intent must be one of {', '.join(INTENTS)}; got {intent!r}")
    previous = latest_release(root)
    target = str(expected_next(previous, intent))
    current = product_version(root)
    if current == target:
        raise ReleaseContractError(f"{target} is already prepared on this checkout")
    if current != str(previous):
        raise ReleaseContractError(
            f"product version {current} is not the latest release {previous}; refusing to guess the base"
        )
    notes = root / f"docs/releases/v{target}.md"
    if notes.exists():
        raise ReleaseContractError(f"{notes.relative_to(root)} already exists; release notes are immutable")

    # Everything that can refuse runs before the first write, so a refusal leaves the tree untouched.
    by_kind = collect_fragments(root)
    rendered = release_notes(str(previous), target, by_kind)
    bump_surfaces(root, str(previous), target)
    notes.parent.mkdir(parents=True, exist_ok=True)
    notes.write_text(rendered, encoding="utf-8")
    link_notes(root, target)
    for fragments in by_kind.values():
        for path, _ in fragments:
            path.unlink()
    return {
        "previous": str(previous),
        "version": target,
        "intent": intent,
        "consumed": {kind: len(fragments) for kind, fragments in by_kind.items()},
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--intent", required=True, choices=INTENTS)
    arguments = parser.parse_args(argv)
    root = Path(__file__).resolve().parents[1]
    try:
        summary = prepare(root, arguments.intent)
        from scripts.check_product_version import errors

        findings = errors(summary["version"])
        if findings:
            raise ReleaseContractError("; ".join(findings))
    except ReleaseContractError as exc:
        print(f"Release preparation refused: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
