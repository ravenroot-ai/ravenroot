#!/usr/bin/env python3
"""Validate the fail-closed Dependabot routing contract."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_UPDATES = {
    ("github-actions", "/"),
    ("docker", "/"),
    ("docker", "/ravenroot/ravenroot-extensions/ravenroot-ocr/container"),
    ("maven", "/ravenroot"),
    ("maven", "/ravenroot-adapter-anthropic"),
    ("maven", "/ravenroot-adapter-openai-compatible"),
    ("maven", "/ravenroot-dev-harness"),
    ("maven", "/ravenroot-sample"),
    ("npm", "/ravenroot/ravenroot-ui"),
    ("npm", "/scripts/mermaid-renderer"),
}


class RoutingPolicyError(ValueError):
    """Raised when dependency routing no longer follows repository policy."""


def scalar(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    return value


def parse_updates(contents: str) -> list[dict[str, str]]:
    updates: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    for line in contents.splitlines():
        start = re.fullmatch(r"  - package-ecosystem:\s*(.+?)\s*", line)
        if start:
            current = {"package-ecosystem": scalar(start.group(1))}
            updates.append(current)
            continue
        if current is None:
            continue
        field = re.fullmatch(r"    (directory|target-branch):\s*(.+?)\s*", line)
        if field:
            current[field.group(1)] = scalar(field.group(2))
        interval = re.fullmatch(r"      interval:\s*(.+?)\s*", line)
        if interval:
            current["interval"] = scalar(interval.group(1))
    return updates


def check_dependabot_config(contents: str) -> None:
    if not re.search(r"(?m)^version:\s*2\s*$", contents):
        raise RoutingPolicyError("Dependabot configuration must use version 2")

    updates = parse_updates(contents)
    actual = {(item.get("package-ecosystem", ""), item.get("directory", "")) for item in updates}
    if len(actual) != len(updates):
        raise RoutingPolicyError("Dependabot update entries must be unique")
    if actual != EXPECTED_UPDATES:
        missing = sorted(EXPECTED_UPDATES - actual)
        unexpected = sorted(actual - EXPECTED_UPDATES)
        raise RoutingPolicyError(
            f"Dependabot update roots changed; missing={missing}, unexpected={unexpected}"
        )
    for item in updates:
        if item.get("target-branch") != "dev":
            raise RoutingPolicyError(
                f"{item['package-ecosystem']} update in {item['directory']} must target dev"
            )
        if item.get("interval") != "weekly":
            raise RoutingPolicyError(
                f"{item['package-ecosystem']} update in {item['directory']} must run weekly"
            )


def check_routing_workflow(contents: str) -> None:
    required = (
        "pull_request_target:",
        "branches: [main]",
        "types: [opened, reopened, edited]",
        "permissions: {}",
        "github.event.pull_request.base.ref == 'main'",
        "github.event.pull_request.user.login == 'dependabot[bot]'",
        "github.event.pull_request.head.repo.full_name == github.repository",
        "startsWith(github.event.pull_request.head.ref, 'dependabot/')",
        "GH_TOKEN: ${{ github.token }}",
        "PR_NUMBER: ${{ github.event.pull_request.number }}",
        "gh api --method PATCH",
        '"repos/$GITHUB_REPOSITORY/pulls/$PR_NUMBER"',
        "-f base=dev --silent",
    )
    for fragment in required:
        if fragment not in contents:
            raise RoutingPolicyError(f"Dependabot routing workflow is missing: {fragment}")

    if "uses:" in contents or "actions/checkout" in contents:
        raise RoutingPolicyError("Dependabot routing must not check out or execute pull request code")
    if "secrets." in contents:
        raise RoutingPolicyError("Dependabot routing must not reference repository secrets")
    write_permissions = re.findall(r"(?m)^\s+([a-z-]+): write\s*$", contents)
    if write_permissions != ["pull-requests"]:
        raise RoutingPolicyError(
            f"Dependabot routing write permissions changed: {write_permissions}"
        )


def check_ci_workflow(contents: str) -> None:
    pull_request_trigger = contents.partition("\n  push:\n")[0]
    if "types: [opened, synchronize, reopened, edited, labeled, unlabeled]" not in pull_request_trigger:
        raise RoutingPolicyError("ordinary pull request CI must run after a base-branch edit")
    if "secrets." in contents or "packages: write" in contents:
        raise RoutingPolicyError("ordinary pull request CI must remain secretless and non-publishing")


def check_repository(root: Path = ROOT) -> None:
    check_dependabot_config((root / ".github/dependabot.yml").read_text(encoding="utf-8"))
    check_routing_workflow(
        (root / ".github/workflows/route-dependabot.yml").read_text(encoding="utf-8")
    )
    check_ci_workflow((root / ".github/workflows/ci.yml").read_text(encoding="utf-8"))


def main() -> int:
    try:
        check_repository()
    except (OSError, RoutingPolicyError) as exc:
        print(f"Dependabot routing check failed: {exc}", file=sys.stderr)
        return 1
    print("Dependabot updates are routed to secretless dev pull request CI.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
