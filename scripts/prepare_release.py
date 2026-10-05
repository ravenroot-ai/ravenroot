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
UI_RELEASE_EXAMPLES = (
    Path("docs/operator-guide/kubernetes-installation.md"),
    Path("docs/operator-guide/kubernetes-ui-only.md"),
    Path("docs/examples/kubernetes/full.yaml"),
    Path("docs/examples/kubernetes/ui-only.yaml"),
)


def bump_ui_examples(root: Path, previous: str, target: str) -> dict[Path, str]:
    """Keep new installation coordinates current, including their first upcoming release."""
    updates = {}
    upcoming = str(expected_next(parse_tag(f"v{previous}"), "minor"))
    pattern = r"(?<![A-Za-z0-9])\d+\.\d+\.\d+-(?:alpha|beta|rc)\.\d+(?![A-Za-z0-9])"
    for relative in UI_RELEASE_EXAMPLES:
        path = root / relative
        if not path.exists():  # Older release checkouts predate independent UI delivery.
            continue
        contents = path.read_text(encoding="utf-8")
        versions = set(re.findall(pattern, contents))
        if not versions or versions.difference({previous, target, upcoming}):
            raise ReleaseContractError(f"{relative}: unexpected installation version coordinates")
        updates[path] = re.sub(pattern, target, contents)
    return updates


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


def replaced_exactly(path: Path, contents: str, old: str, new: str,
                     expected: int | None = None) -> str:
    """Return one validated replacement without changing a release surface yet."""
    found = contents.count(old)
    if found == 0 or (expected is not None and found != expected):
        raise ReleaseContractError(f"{path}: expected {expected or 'some'} of {old!r}, found {found}")
    return contents.replace(old, new)


def bump_surfaces(root: Path, previous: str, target: str) -> None:
    """Move every surface the maintainer procedure names, plus the snippets derived from them."""
    updates: dict[Path, str] = {}
    for relative in git(root, "ls-files", "*pom.xml").splitlines():
        path = root / relative
        contents = path.read_text(encoding="utf-8")
        updated = contents.replace(f"<version>{previous}</version>", f"<version>{target}</version>")
        updated = updated.replace(
            f"<ravenroot.version>{previous}</ravenroot.version>",
            f"<ravenroot.version>{target}</ravenroot.version>",
        )
        updates[path] = updated

    package_path = root / UI_PACKAGE
    updates[package_path] = replaced_exactly(
        package_path, package_path.read_text(encoding="utf-8"),
        f'"version": "{previous}"', f'"version": "{target}"', 1,
    )
    lock_path = root / UI_LOCKFILE
    updates[lock_path] = replaced_exactly(
        lock_path, lock_path.read_text(encoding="utf-8"),
        f'"version": "{previous}"', f'"version": "{target}"', 2,
    )
    lock = json.loads(updates[lock_path])
    if lock.get("version") != target or lock.get("packages", {}).get("", {}).get("version") != target:
        raise ReleaseContractError(f"{UI_LOCKFILE}: the root package version did not move to {target}")

    chart_path = root / HELM_CHART
    chart = replaced_exactly(
        chart_path, chart_path.read_text(encoding="utf-8"),
        f"version: {previous}\n", f"version: {target}\n", 1,
    )
    updates[chart_path] = replaced_exactly(
        chart_path, chart, f'appVersion: "{previous}"', f'appVersion: "{target}"', 1,
    )
    # The README also records that the lifecycle began at the first release; only the install
    # coordinates follow the current version.
    readme_path = root / README
    updates[readme_path] = replaced_exactly(
        readme_path, readme_path.read_text(encoding="utf-8"),
        f"<version>{previous}</version>", f"<version>{target}</version>",
    )
    guide_path = root / EXTENSION_PACK_GUIDE
    guide = replaced_exactly(
        guide_path, guide_path.read_text(encoding="utf-8"),
        f"<ravenroot.version>{previous}</ravenroot.version>",
        f"<ravenroot.version>{target}</ravenroot.version>",
        1,
    )
    updates[guide_path] = replaced_exactly(
        guide_path, guide, f"-Dravenroot.version={previous}", f"-Dravenroot.version={target}", 1,
    )
    updates.update(bump_ui_examples(root, previous, target))
    for path, contents in updates.items():
        path.write_text(contents, encoding="utf-8")


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


def navigation_with_notes(root: Path, target: str) -> str:
    """Render the navigation insertion and reject a missing anchor before release writes."""
    navigation = (root / NAVIGATION).read_text(encoding="utf-8")
    match = re.search(r"(?m)^    - title: Ravenroot \S+ release notes$", navigation)
    if not match:
        raise ReleaseContractError(f"{NAVIGATION}: no release notes entry to place the new one before")
    entry = f"    - title: Ravenroot {target} release notes\n      url: /releases/v{target}.html\n"
    return navigation[: match.start()] + entry + navigation[match.start() :]


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
    updated_navigation = navigation_with_notes(root, target)
    bump_surfaces(root, str(previous), target)
    notes.parent.mkdir(parents=True, exist_ok=True)
    notes.write_text(rendered, encoding="utf-8")
    (root / NAVIGATION).write_text(updated_navigation, encoding="utf-8")
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
