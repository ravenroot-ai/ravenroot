#!/usr/bin/env python3
"""Validate the fail-closed Dependabot routing contract."""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

try:
    import yaml
except ModuleNotFoundError as exc:
    raise SystemExit("Dependabot routing verification requires PyYAML for python3") from exc


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


class WorkflowLoader(yaml.BaseLoader):
    """Decode workflow scalars as strings and reject ambiguous mapping declarations."""

    def compose_node(self, parent, index):
        event = self.peek_event()
        if event.anchor is not None or event.tag is not None:
            raise RoutingPolicyError("workflow aliases, anchors and explicit tags are unsupported")
        return super().compose_node(parent, index)

    def construct_mapping(self, node, deep=False):
        fields = {}
        for key, value in node.value:
            if not isinstance(key, yaml.ScalarNode):
                raise RoutingPolicyError("workflow mapping keys must be scalars")
            name = key.value.casefold()
            if name == "<<" or name in fields:
                raise RoutingPolicyError(f"merged or duplicate workflow key {key.value!r}")
            if name in ("ref", "candidate_ref") and key.value != name:
                raise RoutingPolicyError(f"unsupported candidate input spelling {key.value!r}")
            if name == "uses":
                # Keep the reviewed block layout. Check decoded keys first so that quoting
                # or escaping the entire declaration cannot conceal an executable action.
                if (key.value != "uses" or key.style or node.flow_style
                        or key.start_mark.column not in (4, 8)
                        or not isinstance(value, yaml.ScalarNode) or value.style
                        or value.start_mark.line != key.start_mark.line
                        or not re.fullmatch(r"[-A-Za-z0-9_./@]+", value.value)):
                    raise RoutingPolicyError("unsupported action declaration")
            fields[name] = self.construct_object(value, deep=deep)
        return fields


def check_checkout_bindings(contents: str, expected_ref: str, context: str) -> dict:
    """Verify every executable checkout against its own decoded step inputs."""
    layout_error = f"every {context} checkout must use an observable supported step layout"
    try:
        workflow = yaml.load(contents, Loader=WorkflowLoader)
    except (RoutingPolicyError, yaml.YAMLError) as exc:
        raise RoutingPolicyError(f"{layout_error}: {exc}") from exc
    jobs = workflow.get("jobs") if isinstance(workflow, dict) else None
    if not isinstance(jobs, dict):
        raise RoutingPolicyError(layout_error)
    observed = False
    for name, job in jobs.items():
        if not isinstance(job, dict) or not isinstance(job.get("steps", []), list):
            raise RoutingPolicyError(layout_error)
        for step in job.get("steps", []):
            if not isinstance(step, dict):
                raise RoutingPolicyError(layout_error)
            action = step.get("uses", "").partition("@")[0].casefold()
            if action != "actions/checkout" and not action.startswith("actions/checkout/"):
                continue
            observed = True
            inputs = step.get("with")
            if not isinstance(inputs, dict) or inputs.get("ref") != expected_ref:
                raise RoutingPolicyError(f"every {context} checkout must use its own candidate ref ({name})")
    if not observed:
        raise RoutingPolicyError(layout_error)
    return jobs


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
    expected_ref = "${{ inputs.merge_sha || github.sha }}"
    jobs = check_checkout_bindings(contents, expected_ref, "ordinary CI")
    caller = jobs.get("full-configurator", {})
    inputs = caller.get("with")
    if (caller.get("uses") != "./.github/workflows/configurator.yml"
            or not isinstance(inputs, dict) or inputs.get("candidate_ref") != expected_ref):
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
