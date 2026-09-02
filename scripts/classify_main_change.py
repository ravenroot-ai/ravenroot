#!/usr/bin/env python3
"""Classify a CI event without trusting mutable pull-request prose."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import PurePosixPath


RELEASE_LABELS = {
    "release:none",
    "release:patch",
    "release:minor",
    "release:major",
}

ROOT_CONTENT_FILES = {
    "CODE_OF_CONDUCT.md",
    "CONTRIBUTING.md",
    "GOVERNANCE.md",
    "LICENSE",
    "NOTICE",
    "README.md",
    "SECURITY.md",
    "SUPPORT.md",
}


class ClassificationError(ValueError):
    """Raised when an event cannot be classified safely."""


def is_documentation_path(raw_path: str) -> bool:
    """Return whether a changed path is safe for a content-only main merge."""
    path = PurePosixPath(raw_path)
    if raw_path in ROOT_CONTENT_FILES:
        return True
    if path.parts and path.parts[0] in {"docs", "adr"}:
        return True
    if len(path.parts) >= 2 and path.parts[0] == ".github" and path.suffix == ".md":
        return True
    return (
        len(path.parts) == 2
        and path.parts[0] == ".changes"
        and path.name.endswith(".docs.md")
    )


def documentation_only(paths: list[str]) -> bool:
    """Require at least one path and keep the allowlist deliberately narrow."""
    return bool(paths) and all(is_documentation_path(path) for path in paths)


def parse_labels(raw_labels: str) -> set[str]:
    """Parse the GitHub event label JSON supplied by the workflow."""
    try:
        payload = json.loads(raw_labels or "[]")
    except json.JSONDecodeError as exc:
        raise ClassificationError("Pull-request labels are not valid JSON.") from exc

    if payload is None:
        return set()
    if not isinstance(payload, list):
        raise ClassificationError("Pull-request labels must be a JSON list.")

    labels: set[str] = set()
    for item in payload:
        if isinstance(item, str):
            labels.add(item)
        elif isinstance(item, dict) and isinstance(item.get("name"), str):
            labels.add(item["name"])
        else:
            raise ClassificationError("Pull-request label entries must contain a name.")
    return labels


def classify(
    *, event_name: str, base_ref: str, ref_name: str, labels: set[str], paths: list[str]
) -> dict[str, str]:
    """Return the CI tier and release intent for one event."""
    docs_only = documentation_only(paths)

    if event_name == "pull_request" and base_ref == "dev":
        return {"tier": "fast", "release_intent": "integration", "docs_only": str(docs_only).lower()}

    if event_name == "pull_request" and base_ref == "main":
        selected = sorted(RELEASE_LABELS.intersection(labels))
        if len(selected) != 1:
            raise ClassificationError(
                "Pull requests to main require exactly one of: " + ", ".join(sorted(RELEASE_LABELS))
            )

        intent = selected[0].removeprefix("release:")
        if intent == "none" and not docs_only:
            rejected = ", ".join(path for path in paths if not is_documentation_path(path))
            raise ClassificationError(
                "release:none is restricted to documentation and public content paths. "
                f"Non-content paths: {rejected or '(none detected)'}"
            )
        if intent != "none" and docs_only:
            raise ClassificationError(
                "A documentation-only pull request to main must use release:none; "
                "it must not advance the product version."
            )
        return {
            "tier": "docs" if intent == "none" else "full",
            "release_intent": intent,
            "docs_only": str(docs_only).lower(),
        }

    if event_name == "push" and ref_name == "main" and docs_only:
        return {"tier": "docs", "release_intent": "none", "docs_only": "true"}

    return {"tier": "full", "release_intent": "integration", "docs_only": str(docs_only).lower()}


def changed_paths(base_sha: str, head_sha: str) -> list[str]:
    """Read changed paths from Git while treating renames as two explicit paths."""
    if not base_sha or not head_sha or set(base_sha) == {"0"}:
        return []
    result = subprocess.run(
        ["git", "diff", "--no-renames", "--name-only", base_sha, head_sha],
        check=True,
        capture_output=True,
        text=True,
    )
    return [line for line in result.stdout.splitlines() if line]


def write_github_outputs(values: dict[str, str]) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if not output_path:
        return
    with open(output_path, "a", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def main() -> int:
    try:
        paths = changed_paths(os.environ.get("BASE_SHA", ""), os.environ.get("HEAD_SHA", ""))
        result = classify(
            event_name=os.environ.get("EVENT_NAME", ""),
            base_ref=os.environ.get("BASE_REF", ""),
            ref_name=os.environ.get("REF_NAME", ""),
            labels=parse_labels(os.environ.get("PR_LABELS", "[]")),
            paths=paths,
        )
    except (ClassificationError, subprocess.CalledProcessError) as exc:
        print(f"Release classification failed: {exc}", file=sys.stderr)
        return 1

    print(json.dumps({**result, "paths": paths}, sort_keys=True))
    write_github_outputs(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
