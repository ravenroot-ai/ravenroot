#!/usr/bin/env python3
"""Validate the fail-closed Dependabot routing contract."""

from __future__ import annotations

import hashlib
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
PINNED_WORKFLOWS = {
    "authorize-dependabot.yml": "99b182673d357c731613b69c9caa3dc3c0f7fdcfc07cd8b02a09696ca98afa6d",
    "route-dependabot.yml": "673cc5de549f1eab9be3bb1dabb1ea42137ad753bb0a295d848a850f618466db",
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


def check_pinned_workflow(name: str, contents: str) -> None:
    expected = PINNED_WORKFLOWS[name]
    actual = hashlib.sha256(contents.encode("utf-8")).hexdigest()
    if actual != expected:
        raise RoutingPolicyError(
            f"{name} must receive explicit security review before its pinned contract changes"
        )


def workflow_jobs(contents: str) -> dict[str, str]:
    """Read the repository's block-style job declarations, refusing duplicate identities."""
    if "\njobs:\n" not in contents:
        raise RoutingPolicyError("workflow has no observable jobs mapping")
    body = contents.split("\njobs:\n", 1)[1]
    matches = list(re.finditer(r"(?m)^  ([A-Za-z0-9_-]+):\n", body))
    jobs = {match.group(1): body[match.start():matches[index + 1].start()
            if index + 1 < len(matches) else None] for index, match in enumerate(matches)}
    if len(jobs) != len(matches):
        raise RoutingPolicyError("workflow job identities must be unique")
    return jobs


def own_input(block: str, mapping_indent: int, name: str) -> str | None:
    """Return one input from this block's own with mapping, never another step or env."""
    lines = block.splitlines()
    starts = [index for index, line in enumerate(lines)
              if line == " " * mapping_indent + "with:"]
    if len(starts) != 1:
        return None
    values = []
    for line in lines[starts[0] + 1:]:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if len(line) - len(line.lstrip()) <= mapping_indent:
            break
        match = re.fullmatch(" " * (mapping_indent + 2) + re.escape(name) + r":\s*(.+)", line)
        if match:
            values.append(scalar(match.group(1)))
    return values[0] if len(values) == 1 else None


def check_checkout_bindings(contents: str, expected_ref: str, context: str) -> None:
    """Bind every actual checkout step to its own ref, using the supported block layout.

    A candidate_ref field, shell string, sibling step, or unrelated with mapping cannot supply
    a checkout's missing input. Unknown checkout layouts fail closed rather than being ignored.
    """
    observed = 0
    for job, block in workflow_jobs(contents).items():
        starts = list(re.finditer(r"(?m)^      - ", block))
        for index, start in enumerate(starts):
            step = block[start.start():starts[index + 1].start() if index + 1 < len(starts) else None]
            actions = re.findall(r"(?m)^(?:      - |        )uses: (actions/checkout@[^\s]+)(?: +#.*)?$", step)
            if not actions:
                continue
            observed += len(actions)
            if len(actions) != 1 or own_input(step, 8, "ref") != expected_ref:
                raise RoutingPolicyError(f"every {context} checkout must use its own candidate ref ({job})")
    # This detector deliberately does not share the observer's indentation or step grammar.
    # Quoted, flow-style, reindented, or otherwise unsupported checkout references must be
    # refused, never disappear from both sides of an equality. Extra references in comments or
    # shell examples also fail closed until their source layout receives explicit review.
    declared = contents.count("actions/checkout")
    declared_uses = len(re.findall(r"(?<![\w-])['\"]?uses['\"]?\s*:", contents))
    supported_uses = len(re.findall(
        r"(?m)^(?:    |      - |        )uses: [-A-Za-z0-9_./@]+(?: +#.*)?$", contents))
    if not observed or observed != declared or declared_uses != supported_uses:
        raise RoutingPolicyError(f"every {context} checkout must use an observable supported step layout")


def check_secretless(contents: str) -> None:
    write_permissions = re.findall(r"(?m)^\s+[a-z-]+: write\s*$", contents)
    if "secrets." in contents or write_permissions or re.search(
            r"(?m)^\s*permissions: write-all\s*$|^\s+secrets:", contents):
        raise RoutingPolicyError("ordinary pull request CI must remain secretless and non-publishing")


def check_ci_workflow(contents: str) -> None:
    check_secretless(contents)

    required = (
        "routing_run_id:",
        "routed_pr_number:",
        "merge_sha:",
        "Validate an explicitly routed Dependabot run",
        "actions: read",
        "pull-requests: read",
        '.path == ".github/workflows/authorize-dependabot.yml"',
        '.name == "authorize-dependabot-routing" and .conclusion == "success"',
        '.base.ref == "dev"',
        '.user.login == "dependabot[bot]"',
        'test "$GITHUB_SHA" = "$ROUTED_HEAD_SHA"',
        'test "$(git rev-parse HEAD)" = "$ROUTED_MERGE_SHA"',
        'test "$(git rev-parse HEAD^1)" = "$ROUTED_BASE_SHA"',
        'test "$(git rev-parse HEAD^2)" = "$ROUTED_HEAD_SHA"',
        "inputs.routing_run_id != '' && 'pull_request' || github.event_name",
        "inputs.routing_run_id != '' && 'dev' || github.base_ref",
    )
    for fragment in required:
        if fragment not in contents:
            raise RoutingPolicyError(f"ordinary CI routing contract is missing: {fragment}")
    check_checkout_bindings(contents, "${{ inputs.merge_sha || github.sha }}", "ordinary CI")
    caller = workflow_jobs(contents).get("full-configurator", "")
    if not re.search(r"(?m)^    uses: \./\.github/workflows/configurator\.yml$", caller) \
            or own_input(caller, 4, "candidate_ref") != "${{ inputs.merge_sha || github.sha }}":
        raise RoutingPolicyError("reusable Configurator must receive the exact routed candidate ref")


def check_configurator_workflow(contents: str) -> None:
    """The supported CI call propagates its candidate; standalone events retain github.sha."""
    check_secretless(contents)
    check_checkout_bindings(contents, "${{ inputs.candidate_ref || github.sha }}", "Configurator")


def check_repository(root: Path = ROOT) -> None:
    check_dependabot_config((root / ".github/dependabot.yml").read_text(encoding="utf-8"))
    for name in PINNED_WORKFLOWS:
        check_pinned_workflow(
            name, (root / ".github/workflows" / name).read_text(encoding="utf-8")
        )
    check_ci_workflow((root / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
    check_configurator_workflow((root / ".github/workflows/configurator.yml").read_text(encoding="utf-8"))


def main() -> int:
    try:
        check_repository()
    except (KeyError, OSError, RoutingPolicyError) as exc:
        print(f"Dependabot routing check failed: {exc}", file=sys.stderr)
        return 1
    print("Dependabot updates are routed to secretless dev pull request CI.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
