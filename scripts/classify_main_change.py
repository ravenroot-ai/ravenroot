#!/usr/bin/env python3
"""Classify a CI event without trusting mutable pull-request prose.

`ci.yml` no longer triggers on a pull request into `dev`: a review commit is instead verified by one
dispatched `full` run on its exact commit, before the pull request is opened. The merge queue then
verifies the actual integration commit, also with the `full` tier. A routed Dependabot pull request
into `dev` reaches this classifier the same way a genuine pull request would have — `event_name`
"pull_request", `base_ref` "dev" — but only through `ci.yml`'s dispatch inputs, since the native
trigger is gone; it earns `full` as well, because a later merge into `dev` relies on its result being
the complete suite, not a cheap diagnostic one. Once the integration commit reaches `dev`,
`postmerge` deliberately repeats no functional test. `promotion` likewise reuses the full-tier
evidence already bound to the commit, while `docs` covers a content-only push to `main`.
"""

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


# The only tier a manual dispatch may request. Dispatch exists to verify a review candidate on the
# exact commit about to be reviewed, and the result lands on that commit, where a pull request into
# `dev` reads it. A caller able to choose a lighter tier could make `ci-required` pass on a
# work-branch commit without the functional suite ever running on it.
DISPATCHABLE_TIERS = {"full"}

# The Dependabot routing inputs of ci.yml's dispatch, by the variable the classify step passes each in.
ROUTED_INPUTS = {
    "routed_pr_number": "ROUTED_PR_NUMBER",
    "base_sha": "ROUTED_BASE_SHA",
    "head_sha": "ROUTED_HEAD_SHA",
    "merge_sha": "ROUTED_MERGE_SHA",
}


def classify(
    *,
    event_name: str,
    base_ref: str,
    ref_name: str,
    labels: set[str],
    paths: list[str],
    dispatch_tier: str = "",
    head_ref: str = "",
    head_repository: str = "",
    repository: str = "",
    routed_inputs: dict[str, str] | None = None,
) -> dict[str, str]:
    """Return the CI tier and release intent for one event."""
    docs_only = documentation_only(paths)

    if event_name == "workflow_dispatch":
        requested = dispatch_tier or "full"
        if requested not in DISPATCHABLE_TIERS:
            raise ClassificationError(
                f"A dispatched run may request only: {', '.join(sorted(DISPATCHABLE_TIERS))}. "
                f"Refusing {requested!r}: any lighter tier would let ci-required pass on this commit "
                "without the full tier."
            )
        # A routed Dependabot run reaches this classifier as `pull_request`, after its routing run was
        # validated. Here the routing is absent, so its inputs must be too: every job checks out
        # `merge_sha` when it is set, while the run is recorded on the dispatched branch's commit.
        # Accepting it would record a full-tier success on a commit this run never tested — and a
        # promotion reads exactly that success as its evidence.
        supplied = sorted(name for name, value in (routed_inputs or {}).items() if value)
        if supplied:
            raise ClassificationError(
                f"A dispatched run without routing_run_id must not set {', '.join(supplied)}: the run "
                "would test another commit while its result is recorded on this one."
            )
        return {"tier": "full", "release_intent": "integration", "docs_only": str(docs_only).lower()}

    # A merge-group commit is `dev` plus the queued pull request: the integration, tested before the
    # queue advances `dev` to exactly this commit.
    if event_name == "merge_group":
        return {"tier": "full", "release_intent": "integration", "docs_only": str(docs_only).lower()}

    # `ci.yml` no longer triggers on a pull request into `dev`, so this combination can only be the
    # routed Dependabot dispatch, which sets `EVENT_NAME`/`BASE_REF` to replay itself as exactly this
    # event. Its result is what a later merge into `dev` relies on, so it gets the complete suite —
    # the same tier a genuine pull request into `dev` would need if one could still trigger this way.
    if event_name == "pull_request" and base_ref == "dev":
        return {"tier": "full", "release_intent": "integration", "docs_only": str(docs_only).lower()}

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
        # Only `dev` of this repository is a promotion. Its content was verified on `dev`, so the
        # promotion re-runs no functional job — and ci-required, on this tier, still refuses unless
        # a full-tier run passed on this exact commit. A `hotfix/*` branch never passed through
        # `dev`, so it runs the full tier. Anything else targeting `main` is refused here, as
        # `main-source-policy` refuses it: a promotion tier granted to an arbitrary head would give
        # that commit a green ci-required with no functional job behind it.
        same_repository = bool(repository) and head_repository == repository
        if same_repository and head_ref == "dev":
            tier = "promotion"
        elif same_repository and head_ref.startswith("hotfix/"):
            tier = "full"
        else:
            raise ClassificationError(
                f"A pull request into main must come from dev or a hotfix/* branch of {repository or 'this repository'}; "
                f"refusing head {head_repository or '?'}:{head_ref or '?'}."
            )
        return {"tier": tier, "release_intent": intent, "docs_only": str(docs_only).lower()}

    if event_name == "push" and ref_name == "main" and docs_only:
        return {"tier": "docs", "release_intent": "none", "docs_only": "true"}

    # The merge queue has already run the full suite on the exact integration commit. Running it a
    # third time after that commit advances `dev` adds latency and runner load without testing new
    # code. Branch protection prevents an unqueued write, so this tier is intentionally nearly bare.
    if event_name == "push" and ref_name == "dev":
        return {"tier": "postmerge", "release_intent": "integration", "docs_only": str(docs_only).lower()}

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
            dispatch_tier=os.environ.get("DISPATCH_TIER", ""),
            head_ref=os.environ.get("HEAD_REF", ""),
            head_repository=os.environ.get("HEAD_REPOSITORY", ""),
            repository=os.environ.get("REPOSITORY", ""),
            routed_inputs={name: os.environ.get(variable, "") for name, variable in ROUTED_INPUTS.items()},
        )
    except (ClassificationError, subprocess.CalledProcessError) as exc:
        print(f"Release classification failed: {exc}", file=sys.stderr)
        return 1

    print(json.dumps({**result, "paths": paths}, sort_keys=True))
    write_github_outputs(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
