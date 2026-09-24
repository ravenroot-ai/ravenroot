#!/usr/bin/env python3
"""Select Maven regression modules from the exact Git range under test.

The backend build deliberately remains a whole-reactor compile.  This selector only narrows the
separate regression job when a changed path belongs to a known leaf module.  Uncertainty is a full
reactor run: a false positive would omit tests, while a false negative only costs CI time.
"""

from __future__ import annotations

import argparse
import subprocess
from dataclasses import dataclass
from pathlib import PurePosixPath


ALL_MODULES = ""

# Changes to these surfaces can affect consumers throughout the runtime graph.  They intentionally
# select the complete reactor rather than trying to maintain an incomplete downstream graph.
FULL_REACTOR_PREFIXES = (
    "ravenroot/ravenroot-application-api/",
    "ravenroot/ravenroot-core/",
    "ravenroot/ravenroot-pekko/",
    "ravenroot/ravenroot-akka/",
    "ravenroot/ravenroot-programming-graalvm/",
    "ravenroot/ravenroot-server/",
    "ravenroot/ravenroot-cli/",
    "ravenroot/ravenroot-distribution/",
)

MAVEN_TOPOLOGY_PATHS = {
    "ravenroot/pom.xml",
    "ravenroot/ravenroot-extensions/pom.xml",
}

KNOWN_LEAF_MODULES = {
    "ravenroot-api-testkit",
    "ravenroot-engine-testkit",
    "ravenroot-node-starter",
    "ravenroot-persistence-postgresql",
    "ravenroot-persistence-sqlite",
    "ravenroot-persistence-testkit",
    "ravenroot-plugin-bundle",
    "ravenroot-sandbox-supervisor-testkit",
    "ravenroot-observability-otel",
    "ravenroot-extensions/ravenroot-mail",
    "ravenroot-extensions/ravenroot-telegram",
    "ravenroot-extensions/ravenroot-discord",
    "ravenroot-extensions/ravenroot-amqp091",
    "ravenroot-extensions/ravenroot-kafka",
    "ravenroot-extensions/ravenroot-filesystem",
    "ravenroot-extensions/ravenroot-git-workspace",
    "ravenroot-extensions/ravenroot-ocr",
    "ravenroot-extensions/ravenroot-spel",
    "ravenroot-extensions/ravenroot-jdbc",
    "ravenroot-extensions/ravenroot-openapi-client",
    "ravenroot-extensions/ravenroot-openapi-server",
    "ravenroot-extensions/ravenroot-object-storage",
    "ravenroot-extensions/ravenroot-websocket",
    "ravenroot-extensions/ravenroot-ai",
    "ravenroot-extensions/ravenroot-github",
    "ravenroot-extensions/ravenroot-slack",
    "ravenroot-extensions/ravenroot-teams",
    "ravenroot-extensions/ravenroot-matrix",
    "ravenroot-extensions/ravenroot-mattermost",
    "ravenroot-extensions/ravenroot-extensions-all",
}


@dataclass(frozen=True)
class Scope:
    """One conservative Maven regression selection."""

    mode: str
    projects: tuple[str, ...]
    reason: str


def changed_paths(base_sha: str, head_sha: str) -> list[str]:
    """Read the exact changed paths, treating renames as removed plus added paths."""
    if not base_sha or not head_sha:
        raise ValueError("both base and head commits are required")
    result = subprocess.run(
        ["git", "diff", "--no-renames", "--name-only", base_sha, head_sha],
        check=True,
        capture_output=True,
        text=True,
    )
    return [path for path in result.stdout.splitlines() if path]


def module_for_path(path: str) -> str | None:
    """Return a known Maven leaf for *path*, or None when its ownership is not provable."""
    candidate = PurePosixPath(path)
    if len(candidate.parts) < 3 or candidate.parts[0] != "ravenroot":
        return None
    if candidate.parts[1] == "ravenroot-extensions":
        if len(candidate.parts) < 4:
            return None
        module = "/".join(candidate.parts[1:3])
    else:
        module = candidate.parts[1]
    return module if module in KNOWN_LEAF_MODULES else None


def select(event_name: str, paths: list[str]) -> Scope:
    """Choose a safe scope for one CI event and its exact changed paths."""
    if event_name == "schedule":
        return Scope("all", (), "scheduled all-reactor regression")
    if event_name not in {"pull_request", "workflow_dispatch", "merge_group"}:
        return Scope("all", (), f"{event_name or 'unknown'} event is not range-scoped")
    if not paths:
        return Scope("all", (), "empty range cannot prove a leaf-only change")

    modules: set[str] = set()
    for path in paths:
        if path in MAVEN_TOPOLOGY_PATHS or path.startswith(FULL_REACTOR_PREFIXES):
            return Scope("all", (), f"{path} changes shared runtime or Maven topology")
        module = module_for_path(path)
        if module is None:
            return Scope("all", (), f"{path} is not a recognised backend leaf")
        modules.add(module)

    # V2 embed registrations span SQLite persistence and the server composition root.  `-am` brings
    # their upstream dependencies only; object storage (and its MinIO integration test) is not a
    # dependency of either selected project and must not be dragged into this focused regression.
    if "ravenroot-persistence-sqlite" in modules:
        modules.add("ravenroot-server")

    return Scope("selected", tuple(sorted(modules)), "known backend leaf modules")


def write_github_output(scope: Scope) -> None:
    from os import environ

    output = environ.get("GITHUB_OUTPUT")
    if not output:
        return
    with open(output, "a", encoding="utf-8") as handle:
        handle.write(f"mode={scope.mode}\n")
        handle.write(f"projects={','.join(scope.projects)}\n")
        handle.write(f"reason={scope.reason}\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    arguments = parser.parse_args()
    try:
        scope = select(arguments.event, changed_paths(arguments.base, arguments.head))
    except (ValueError, subprocess.CalledProcessError) as exc:
        raise SystemExit(f"Backend scope selection failed closed: {exc}") from exc
    print(f"backend-test scope: {scope.mode}; projects={','.join(scope.projects) or 'reactor'}; {scope.reason}")
    write_github_output(scope)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
