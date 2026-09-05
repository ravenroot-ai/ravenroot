#!/usr/bin/env python3
"""Fail-closed authorization and tag validation for Ravenroot releases."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.check_product_version import authoritative_version, errors as version_errors
from scripts.classify_main_change import RELEASE_LABELS, documentation_only


ROOT = Path(__file__).resolve().parents[1]
INITIAL_VERSION = "0.1.0-alpha.1"
TAG = re.compile(
    r"^v(?P<major>0|[1-9][0-9]*)\.(?P<minor>0|[1-9][0-9]*)\."
    r"(?P<patch>0|[1-9][0-9]*)(?:-(?P<prerelease>[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)


class ReleaseContractError(ValueError):
    """Raised when a release authorization or tag fails closed."""


@dataclass(frozen=True, order=True)
class ReleaseVersion:
    major: int
    minor: int
    patch: int
    prerelease: tuple[str, ...] = ()

    @classmethod
    def parse(cls, value: str) -> "ReleaseVersion":
        match = TAG.fullmatch(f"v{value}")
        if not match:
            raise ReleaseContractError(f"{value!r} is not an accepted release version")
        prerelease = tuple((match.group("prerelease") or "").split("."))
        if prerelease == ("",):
            prerelease = ()
        for identifier in prerelease:
            if identifier.isdigit() and len(identifier) > 1 and identifier.startswith("0"):
                raise ReleaseContractError("numeric prerelease identifiers must not have leading zeroes")
        return cls(
            int(match.group("major")),
            int(match.group("minor")),
            int(match.group("patch")),
            prerelease,
        )

    def __str__(self) -> str:
        base = f"{self.major}.{self.minor}.{self.patch}"
        return base if not self.prerelease else f"{base}-{'.'.join(self.prerelease)}"

    def semantic_key(self) -> tuple[object, ...]:
        identifiers = tuple(
            (0, int(identifier)) if identifier.isdigit() else (1, identifier)
            for identifier in self.prerelease
        )
        return self.major, self.minor, self.patch, not self.prerelease, identifiers


def parse_tag(tag: str) -> ReleaseVersion:
    match = TAG.fullmatch(tag)
    if not match:
        raise ReleaseContractError(f"{tag!r} is not an accepted v<SemVer> tag")
    return ReleaseVersion.parse(tag[1:])


def validate_event(event_name: str, ref_type: str, ref_name: str, requested_tag: str) -> dict[str, str]:
    if event_name not in {"push", "workflow_dispatch"}:
        raise ReleaseContractError(f"{event_name!r} is not an authorized release event")
    if ref_type != "tag":
        raise ReleaseContractError("release publication requires an existing tag ref")
    if requested_tag != ref_name:
        raise ReleaseContractError("the requested recovery tag differs from the workflow tag ref")
    parse_tag(ref_name)
    return {"tag": ref_name}


def expected_next(previous: ReleaseVersion, intent: str) -> ReleaseVersion:
    if intent == "patch":
        return ReleaseVersion(previous.major, previous.minor, previous.patch + 1, previous.prerelease)
    if intent == "minor":
        return ReleaseVersion(previous.major, previous.minor + 1, 0, previous.prerelease)
    if intent == "major":
        return ReleaseVersion(previous.major + 1, 0, 0, previous.prerelease)
    raise ReleaseContractError(f"release:{intent} does not authorize a version transition")


def run_git(*arguments: str, check: bool = True) -> str:
    completed = subprocess.run(
        ["git", *arguments], cwd=ROOT, check=check, capture_output=True, text=True
    )
    return completed.stdout.strip()


def version_at(commit: str) -> str:
    pom = run_git("show", f"{commit}:ravenroot/pom.xml")
    match = re.search(r"<version>([^<]+)</version>", pom)
    if not match:
        raise ReleaseContractError(f"{commit} has no authoritative Maven version")
    return match.group(1)


def release_tags_merged_into(commit: str) -> list[tuple[ReleaseVersion, str]]:
    result: list[tuple[ReleaseVersion, str]] = []
    for tag in run_git("tag", "--merged", commit, "--list", "v*").splitlines():
        try:
            result.append((parse_tag(tag), tag))
        except ReleaseContractError:
            continue
    return sorted(result, key=lambda item: item[0].semantic_key())


def selected_pull_request(path: Path, head_sha: str) -> tuple[str, str]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise ReleaseContractError("associated pull requests response is not a list")
    candidates = [
        item
        for item in payload
        if item.get("merge_commit_sha") == head_sha
        and item.get("merged_at")
        and item.get("base", {}).get("ref") == "main"
        and item.get("head", {}).get("repo", {}).get("full_name") == "ravenroot-ai/ravenroot"
    ]
    if len(candidates) != 1:
        raise ReleaseContractError("the exact main commit must map to one merged internal pull request")
    candidate = candidates[0]
    labels = {
        label.get("name") for label in candidate.get("labels", []) if isinstance(label, dict)
    }
    selected = sorted(RELEASE_LABELS.intersection(labels))
    if len(selected) != 1:
        raise ReleaseContractError("the merged pull request must have exactly one release intent label")
    head_ref = candidate.get("head", {}).get("ref", "")
    if head_ref != "dev" and not head_ref.startswith("hotfix/"):
        raise ReleaseContractError("the merged pull request is not from dev or a protected hotfix branch")
    intent = selected[0].removeprefix("release:")
    if head_ref.startswith("hotfix/") and intent != "patch":
        raise ReleaseContractError("hotfix branches may authorize only release:patch")
    return intent, head_ref


def require_release_notes(version: str) -> None:
    path = ROOT / "docs" / "releases" / f"v{version}.md"
    if not path.is_file() or not path.read_text(encoding="utf-8").strip():
        raise ReleaseContractError(f"reviewed release notes are missing: docs/releases/v{version}.md")


def authorize_main(
    *, before: str, head: str, prs_json: Path, allow_existing_exact_tag: bool = False
) -> dict[str, str]:
    if run_git("rev-parse", "HEAD") != head:
        raise ReleaseContractError("the checked-out commit differs from the main push commit")
    parents = run_git("show", "-s", "--format=%P", head).split()
    if len(parents) != 2 or parents[0] != before:
        raise ReleaseContractError("release authorization requires the exact merge commit on main")

    label_intent, _ = selected_pull_request(prs_json, head)
    old_version = version_at(before)
    new_version = authoritative_version()
    findings = version_errors(new_version)
    if findings:
        raise ReleaseContractError("; ".join(findings))

    changed = run_git("diff", "--no-renames", "--name-only", before, head).splitlines()
    published = release_tags_merged_into(before)
    if new_version == old_version and documentation_only(changed):
        immutable_intent = "none"
    elif not published:
        if new_version != INITIAL_VERSION:
            raise ReleaseContractError(
                f"the first release must be {INITIAL_VERSION}"
            )
        immutable_intent = "minor"
    else:
        previous = published[-1][0]
        if old_version != str(previous):
            raise ReleaseContractError(
                f"main version {old_version} differs from latest immutable release {previous}"
            )
        candidate = ReleaseVersion.parse(new_version)
        transitions = {
            intent: expected_next(previous, intent) for intent in ("patch", "minor", "major")
        }
        matching = [intent for intent, expected in transitions.items() if candidate == expected]
        if len(matching) != 1:
            expected = ", ".join(f"release:{intent}={version}" for intent, version in transitions.items())
            raise ReleaseContractError(
                f"version {new_version} is not an authorized transition after {previous}; expected {expected}"
            )
        immutable_intent = matching[0]

    if label_intent != immutable_intent:
        raise ReleaseContractError(
            f"release:{label_intent} does not match immutable release:{immutable_intent} content"
        )
    if immutable_intent == "none":
        return {"intent": "none", "should_release": "false", "tag": ""}

    tag = f"v{new_version}"
    tag_exists = subprocess.run(
        ["git", "show-ref", "--verify", "--quiet", f"refs/tags/{tag}"], cwd=ROOT
    ).returncode == 0
    if tag_exists:
        if not allow_existing_exact_tag:
            raise ReleaseContractError(f"{tag} already exists and release versions are immutable")
        if run_git("rev-parse", f"refs/tags/{tag}^{{commit}}") != head:
            raise ReleaseContractError(f"{tag} identifies different immutable content")
    require_release_notes(new_version)
    return {"intent": immutable_intent, "should_release": "true", "tag": tag}


def validate_tag_authorization(tag: str, prs_json: Path) -> dict[str, str]:
    parse_tag(tag)
    head = run_git("rev-parse", f"refs/tags/{tag}^{{commit}}")
    parents = run_git("show", "-s", "--format=%P", head).split()
    if len(parents) != 2:
        raise ReleaseContractError("the tagged commit is not a reviewed main merge commit")
    result = authorize_main(
        before=parents[0],
        head=head,
        prs_json=prs_json,
        allow_existing_exact_tag=True,
    )
    if result["tag"] != tag:
        raise ReleaseContractError("the authorized release tag differs from the supplied tag")
    return result


def validate_tag(tag: str, main_ref: str) -> dict[str, str]:
    version = str(parse_tag(tag))
    ref_type = run_git("cat-file", "-t", f"refs/tags/{tag}")
    if ref_type != "tag":
        raise ReleaseContractError("release tags must be annotated, never lightweight")
    commit = run_git("rev-parse", f"refs/tags/{tag}^{{commit}}")
    if run_git("rev-parse", "HEAD") != commit:
        raise ReleaseContractError("the checkout is not the peeled release tag commit")
    contained = subprocess.run(
        ["git", "merge-base", "--is-ancestor", commit, main_ref], cwd=ROOT
    ).returncode == 0
    if not contained:
        raise ReleaseContractError(f"{tag} does not belong to protected main")
    product_version = authoritative_version()
    if product_version != version:
        raise ReleaseContractError(
            f"tag {tag} does not match authoritative product version {product_version}"
        )
    findings = version_errors(product_version)
    if findings:
        raise ReleaseContractError("; ".join(findings))
    require_release_notes(version)
    return {"tag": tag, "version": version, "commit": commit}


def write_outputs(values: dict[str, str]) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    commands = result.add_subparsers(dest="command", required=True)
    authorize = commands.add_parser("authorize-main")
    authorize.add_argument("--before", required=True)
    authorize.add_argument("--head", required=True)
    authorize.add_argument("--prs-json", type=Path, required=True)
    validate = commands.add_parser("validate-tag")
    validate.add_argument("--tag", required=True)
    validate.add_argument("--main-ref", default="origin/main")
    tag_authorization = commands.add_parser("validate-tag-authorization")
    tag_authorization.add_argument("--tag", required=True)
    tag_authorization.add_argument("--prs-json", type=Path, required=True)
    event = commands.add_parser("validate-event")
    event.add_argument("--event-name", required=True)
    event.add_argument("--ref-type", required=True)
    event.add_argument("--ref-name", required=True)
    event.add_argument("--requested-tag", required=True)
    return result


def main() -> int:
    arguments = parser().parse_args()
    try:
        if arguments.command == "authorize-main":
            values = authorize_main(
                before=arguments.before, head=arguments.head, prs_json=arguments.prs_json
            )
        elif arguments.command == "validate-tag":
            values = validate_tag(arguments.tag, arguments.main_ref)
        elif arguments.command == "validate-tag-authorization":
            values = validate_tag_authorization(arguments.tag, arguments.prs_json)
        else:
            values = validate_event(
                arguments.event_name,
                arguments.ref_type,
                arguments.ref_name,
                arguments.requested_tag,
            )
    except (ReleaseContractError, json.JSONDecodeError, subprocess.CalledProcessError) as exc:
        print(f"Release contract failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(values, sort_keys=True))
    write_outputs(values)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
