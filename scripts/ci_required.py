#!/usr/bin/env python3
"""Decide, for one CI event, which jobs had to run — and refuse anything less.

`ci-required` is the single context the branch rulesets require, so it is the only thing standing
between an unverified change and the integration branch. It previously derived its expectations from
a table written by hand inside `.github/workflows/ci.yml`, next to — but independent of — the `if:`
expressions that actually decide what runs. Two independent copies of one decision drift, and this
one did: every pull request into `dev` was classified `fast`, the heavy jobs were gated on `full`,
and the hand-written table agreed that they were *supposed* to be skipped. `ci-required` reported
success while the suite that should have caught the regression never executed. Pull requests were
green because nothing had run.

So the expectations live here, in one place, and this module is authoritative in both directions:

* it decides which jobs are required for the event, from the tier alone;
* it verifies that `ci.yml` still gates each job exactly the way this table says it does.

Both halves fail closed. A required job reporting `skipped` is a failure, not a pass — that is the
whole point. A job that runs when the table says it should not is equally a failure, because the
disagreement means one of the two is wrong and neither can be trusted to say which. A job present in
the workflow but absent from this table, or absent from the gate's `needs`, fails as well: an
unobserved job is indistinguishable from a job that silently stopped running.

It also checks the tier against the event, independently of the classifier, and enforces one
constraint on every workflow: only ci.yml may publish `ci-required`. The work-branch fast tier in
ci-fast.yml publishes `ci-fast` instead, because a check run belongs to the commit and a skipped job
counts as passed for a required check — a fast run publishing `ci-required` would let a pull request
into `dev` merge without the full tier. `--fast` runs the same checks as that workflow's aggregator.

Run with `--print-contexts TIER` to list the check contexts an event produces, which is what a branch
ruleset's required-checks list has to name.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[1] / ".github" / "workflows" / "ci.yml"
WORKFLOW_DIRECTORY = WORKFLOW.parent
FAST_WORKFLOW = WORKFLOW_DIRECTORY / "ci-fast.yml"

CLASSIFICATION_JOB = "release-classification"
GATE_JOB = "ci-required"

# Playwright's suite is the critical path, so it is split across this many parallel runners. The
# workflow derives the `--shard` denominator from `strategy.job-total` rather than repeating the
# number, so the matrix and the shard argument cannot disagree; this constant only has to describe
# the matrix, and `verify_workflow` holds it to that.
E2E_SHARDS = 4

# Jobs whose gate is the tier, mapped to the check context each publishes. The keys are the workflow
# job identifiers `toJSON(needs)` reports; the values are the names GitHub shows and a ruleset
# requires. Three of them differ, which is precisely why the mapping is written down.
POLICY_JOBS = {
    "docs-site": "docs-site",
    "full-docs-policy": "full-docs-policy",
    "full-python-contracts": "full-python-contracts",
    "full-shell-contracts": "full-shell-contracts",
    "full-source-policy": "full-source-policy",
}

PRODUCT_JOBS = {
    "full-ui-audit": "full-ui-audit",
    "full-ui-unit-tests": "full-ui-unit-tests",
    "full-ui-build": "full-ui-build",
    "full-ui-e2e-harness": "full-ui-e2e-harness",
    "full-ui-e2e-shard": "full-ui-e2e-shard",
    "full-ui-e2e": "full-ui-e2e",
    "backend-build": "full-backend-build",
    "full-backend-tests": "full-backend-tests",
    "backend-test": "full-support-modules",
    "full-plugin-boundary": "full-plugin-boundary",
    "full-api-documentation": "full-api-documentation",
    "full-runtime-auth-smoke": "full-runtime-auth-smoke",
    "full-runtime-jar-smoke": "full-runtime-jar-smoke",
    "full-runtime-container-smoke": "full-runtime-container-smoke",
}

GATED_JOBS = {**POLICY_JOBS, **PRODUCT_JOBS}

# What each tier demands. `promotion` is empty on purpose: `dev` to `main` re-verifies nothing,
# because the behaviour was already verified on the pull requests into `dev`. What guards `main` is
# the security gate, `main-source-policy`, and the classification itself — none of which is gated on
# the tier, and none of which this module observes.
REQUIRED_BY_TIER = {
    "full": frozenset(POLICY_JOBS) | frozenset(PRODUCT_JOBS),
    "docs": frozenset(POLICY_JOBS),
    "promotion": frozenset(),
}

# Which tiers each event may carry. The classifier decides the tier; this is the second, independent
# statement of what it is allowed to decide, so a classification defect that hands an event headed
# for `dev` a lighter tier is refused here instead of trusted. Push events are keyed by the branch
# pushed; pull requests by their base. Anything not listed is refused.
ALLOWED_TIERS_BY_EVENT = {
    ("pull_request", "dev"): frozenset({"full"}),
    ("pull_request", "main"): frozenset({"promotion"}),
    ("push", "dev"): frozenset({"full"}),
    ("push", "main"): frozenset({"full", "docs"}),
    ("workflow_dispatch", ""): frozenset({"full"}),
    ("merge_group", ""): frozenset({"full"}),
}

# The work-branch fast tier lives in its own workflow and is advice, not a gate. It is modelled here
# for one reason: to hold it to the constraint that it never publishes `ci-required`.
FAST_GATE_JOB = "ci-fast"
FAST_JOBS = frozenset({"fast-policy", "fast-ui", "fast-backend"})
FAST_TRIGGER = "on:\n  push:\n    branches: ['feature/**']\n"

POLICY_CONDITION = (
    "contains(fromJSON('[\"docs\",\"full\"]'), needs.release-classification.outputs.tier)"
)
PRODUCT_CONDITION = "needs.release-classification.outputs.tier == 'full'"
# The end-to-end aggregator has to observe a failing shard rather than inherit its skip, so it runs
# whenever the tier calls for it and reaches its own verdict about the shards below it.
AGGREGATOR_CONDITION = f"always() && {PRODUCT_CONDITION}"

EXPECTED_CONDITIONS = {
    **{job: POLICY_CONDITION for job in POLICY_JOBS},
    **{job: PRODUCT_CONDITION for job in PRODUCT_JOBS},
    "full-ui-e2e": AGGREGATOR_CONDITION,
}


def required_contexts(tier: str) -> list[str]:
    """Return the check contexts an event of this tier publishes, shards expanded."""
    contexts: list[str] = [CLASSIFICATION_JOB]
    for job in sorted(REQUIRED_BY_TIER[tier]):
        name = GATED_JOBS[job]
        if job == "full-ui-e2e-shard":
            contexts.extend(f"{name} ({index}/{E2E_SHARDS})" for index in range(1, E2E_SHARDS + 1))
        else:
            contexts.append(name)
    contexts.append(GATE_JOB)
    return contexts


def job_blocks(contents: str) -> dict[str, str]:
    """Return top-level job blocks without adding a YAML dependency to repository tooling."""
    jobs_marker = contents.index("\njobs:\n") + len("\njobs:\n")
    body = contents[jobs_marker:]
    # GitHub accepts `_` and uppercase in a job id. Matching less than GitHub does would let a
    # job exist that this gate cannot see, and an unobservable job is how the gate goes green
    # over work that never ran.
    matches = list(re.finditer(r"(?m)^  ([A-Za-z0-9_-]+):\n", body))
    return {
        match.group(1): body[
            match.start() : matches[index + 1].start() if index + 1 < len(matches) else None
        ]
        for index, match in enumerate(matches)
    }


def declared_needs(block: str) -> set[str]:
    """Read either the inline or block-list needs syntax used by this workflow."""
    inline = re.search(r"(?m)^    needs: \[([^]]*)]$", block)
    if inline:
        return {item.strip() for item in inline.group(1).split(",") if item.strip()}

    lines = block.splitlines()
    for index, line in enumerate(lines):
        if line == "    needs:":
            needs: set[str] = set()
            for candidate in lines[index + 1 :]:
                match = re.fullmatch(r"      - ([A-Za-z0-9_-]+)", candidate)
                if not match:
                    break
                needs.add(match.group(1))
            return needs
    return set()


def declared_condition(block: str) -> str | None:
    """Return the job's `if:` expression, or None when it is unconditional."""
    match = re.search(r"(?m)^    if: (.+)$", block)
    return match.group(1).strip() if match else None


def verify_workflow(contents: str) -> list[str]:
    """Report every way `ci.yml` no longer matches the topology declared above."""
    problems: list[str] = []
    blocks = job_blocks(contents)

    declared = set(blocks)
    expected = {CLASSIFICATION_JOB, GATE_JOB} | set(GATED_JOBS)
    for job in sorted(expected - declared):
        problems.append(f"{job}: declared in scripts/ci_required.py but absent from ci.yml.")
    for job in sorted(declared - expected):
        problems.append(
            f"{job}: defined in ci.yml but absent from scripts/ci_required.py, so {GATE_JOB} "
            "would never observe whether it ran."
        )

    for job in sorted(set(GATED_JOBS) & declared):
        expected_condition = EXPECTED_CONDITIONS[job]
        actual = declared_condition(blocks[job])
        if actual != expected_condition:
            problems.append(
                f"{job}: gated on {actual!r} but scripts/ci_required.py assumes "
                f"{expected_condition!r}. The gate cannot vouch for a job it models incorrectly."
            )

    if CLASSIFICATION_JOB in blocks and declared_condition(blocks[CLASSIFICATION_JOB]) is not None:
        problems.append(
            f"{CLASSIFICATION_JOB}: must stay unconditional; every tier decision depends on it."
        )

    if GATE_JOB in blocks:
        gate = blocks[GATE_JOB]
        if declared_condition(gate) != "always()":
            problems.append(
                f"{GATE_JOB}: must run with `if: always()`, or a skipped dependency would skip the "
                "gate itself and leave the ruleset waiting on a context that never reports."
            )
        observed = declared_needs(gate)
        wanted = {CLASSIFICATION_JOB} | set(GATED_JOBS)
        for job in sorted(wanted - observed):
            problems.append(f"{job}: missing from the {GATE_JOB} `needs` list, so its result is invisible.")
        for job in sorted(observed - wanted):
            problems.append(f"{job}: listed in the {GATE_JOB} `needs` list but not in the topology.")

    problems.extend(verify_triggers(contents))

    shard_block = blocks.get("full-ui-e2e-shard", "")
    expected_matrix = "shard: [" + ", ".join(str(index) for index in range(1, E2E_SHARDS + 1)) + "]"
    if expected_matrix not in shard_block:
        problems.append(
            f"full-ui-e2e-shard: expected the matrix `{expected_matrix}` to match E2E_SHARDS="
            f"{E2E_SHARDS}."
        )
    if "--shard=${{ matrix.shard }}/${{ strategy.job-total }}" not in shard_block:
        problems.append(
            "full-ui-e2e-shard: the Playwright `--shard` denominator must be "
            "`${{ strategy.job-total }}`. A literal count can fall behind the matrix, and the tests "
            "in the shards past it would silently never run."
        )

    return problems


def trigger_block(contents: str) -> str:
    """Return the workflow's `on:` block, from `on:` up to the next top-level key."""
    match = re.search(r"(?ms)^on:\n.*?(?=^\S)", contents)
    return match.group(0) if match else ""


def declared_name(block: str) -> str | None:
    """Return a job's `name:` value — the check context it publishes — or None to use its id."""
    match = re.search(r"(?m)^    name: (.+)$", block)
    return match.group(1).strip() if match else None


def verify_triggers(contents: str) -> list[str]:
    """Hold ci.yml's events to the model: no work-branch pushes, a merge-queue trigger, full dispatch."""
    problems: list[str] = []
    triggers = trigger_block(contents)
    if "  push:\n    branches: [dev, main]\n" not in triggers:
        problems.append(
            "ci.yml: `push` must name exactly `[dev, main]`. Work-branch pushes belong to the fast "
            "tier in ci-fast.yml; the full tier on every push would saturate the runners."
        )
    if "\n  merge_group:\n" not in triggers:
        problems.append(
            "ci.yml: the `merge_group` trigger is missing. Without it ci-required is never reported on "
            "a merge-group commit, and every pull request in a merge queue times out."
        )
    tier_input = re.search(r"(?ms)^      tier:\n(.*?)(?=^      \S|^  \S)", triggers)
    if not tier_input or "options: [full]\n" not in tier_input.group(1):
        problems.append(
            "ci.yml: the dispatch `tier` input must offer `full` alone. A selectable lighter tier would "
            "let ci-required pass on a work-branch commit without the full tier."
        )
    return problems


def verify_single_publisher(directory: Path) -> list[str]:
    """Refuse any workflow other than ci.yml that could publish a `ci-required` context.

    Check runs belong to the commit, and a skipped job counts as passed for a required check. A
    `ci-required` published by any other run — the fast tier above all — would therefore satisfy the
    ruleset for a pull request whose head is that commit, without the full tier having run on it.
    """
    problems: list[str] = []
    for path in sorted(directory.glob("*.y*ml")):
        if path.name == WORKFLOW.name:
            continue
        contents = path.read_text(encoding="utf-8")
        if "\njobs:\n" not in contents:
            continue
        for job, block in job_blocks(contents).items():
            name = declared_name(block)
            if job == GATE_JOB or (name is not None and GATE_JOB in name):
                problems.append(
                    f"{path.name}: job {job!r} would publish {GATE_JOB!r}. Only ci.yml may publish it, "
                    "because only a full-tier run may satisfy it."
                )
    return problems


def verify_fast_workflow(contents: str) -> list[str]:
    """Report every way ci-fast.yml no longer matches the fast topology declared above."""
    problems: list[str] = []
    if not trigger_block(contents).startswith(FAST_TRIGGER) or any(
        event in trigger_block(contents)
        for event in ("pull_request", "merge_group", "workflow_dispatch", "schedule")
    ):
        problems.append(
            "ci-fast.yml: must run on pushes to `feature/**` alone. It is feedback for whoever is "
            "working, not a check any pull request or queue should wait on."
        )
    blocks = job_blocks(contents)
    expected = FAST_JOBS | {FAST_GATE_JOB}
    for job in sorted(expected - set(blocks)):
        problems.append(f"ci-fast.yml: {job} is declared in scripts/ci_required.py but absent.")
    for job in sorted(set(blocks) - expected):
        problems.append(f"ci-fast.yml: {job} is not part of the declared fast topology.")
    for job, block in blocks.items():
        name = declared_name(block) or job
        if name != job:
            problems.append(f"ci-fast.yml: {job} publishes {name!r}; a fast job publishes its own id.")
    gate = blocks.get(FAST_GATE_JOB, "")
    if gate:
        if declared_condition(gate) != "always()":
            problems.append(f"ci-fast.yml: {FAST_GATE_JOB} must run with `if: always()`.")
        if declared_needs(gate) != set(FAST_JOBS):
            problems.append(f"ci-fast.yml: {FAST_GATE_JOB} must wait on exactly {sorted(FAST_JOBS)}.")
    return problems


def verify_fast_results(results: dict[str, str]) -> list[str]:
    """Require every fast job to have succeeded; a skipped one fails here too."""
    problems: list[str] = []
    for job in sorted(FAST_JOBS - set(results)):
        problems.append(f"{job}: no result reported to {FAST_GATE_JOB}.")
    for job in sorted(set(results) - FAST_JOBS):
        problems.append(f"{job}: reported a result but is not part of the fast topology.")
    for job in sorted(FAST_JOBS & set(results)):
        if results[job] != "success":
            problems.append(f"{job}: expected success, got {results[job]}.")
    return problems


def event_key(event_name: str, base_ref: str, ref_name: str) -> tuple[str, str]:
    """Key an event the way ALLOWED_TIERS_BY_EVENT does."""
    if event_name == "pull_request":
        return (event_name, base_ref)
    if event_name == "push":
        return (event_name, ref_name)
    return (event_name, "")


def verify_event(event_name: str, base_ref: str, ref_name: str, tier: str) -> list[str]:
    """Refuse a tier the event is not allowed to carry, or an event the model does not know."""
    key = event_key(event_name, base_ref, ref_name)
    allowed = ALLOWED_TIERS_BY_EVENT.get(key)
    if allowed is None:
        return [
            f"Event {key!r} is not part of the CI model, so no tier can be vouched for on it. "
            "Unrecognised events are refused, never assumed."
        ]
    if tier not in allowed:
        return [
            f"Tier {tier!r} is not permitted for event {key!r}; it may carry only "
            f"{', '.join(sorted(allowed))}. The classification and the model disagree, and the gate "
            "does not settle that in favour of the lighter tier."
        ]
    return []


def verify_results(tier: str, results: dict[str, str]) -> list[str]:
    """Report every way the observed job results fall short of what the event demanded."""
    if tier not in REQUIRED_BY_TIER:
        return [
            f"Unknown CI tier {tier!r}. Expected one of: {', '.join(sorted(REQUIRED_BY_TIER))}. "
            "The classification is what every gate reads, so an unrecognised tier is never a pass."
        ]

    problems: list[str] = []
    observed = set(results)
    expected = {CLASSIFICATION_JOB} | set(GATED_JOBS)
    required = {CLASSIFICATION_JOB} | REQUIRED_BY_TIER[tier]

    for job in sorted(expected - observed):
        problems.append(f"{job}: no result reported to {GATE_JOB}.")
    for job in sorted(observed - expected):
        problems.append(f"{job}: reported a result but is not part of the declared topology.")

    for job in sorted(required & observed):
        result = results[job]
        if result == "skipped":
            problems.append(
                f"{job}: required for tier {tier} and SKIPPED. A job that did not run cannot report "
                "that the change is sound."
            )
        elif result != "success":
            problems.append(f"{job}: required for tier {tier}, expected success, got {result}.")

    for job in sorted((observed & expected) - required):
        result = results[job]
        if result != "skipped":
            problems.append(
                f"{job}: not required for tier {tier} yet reported {result}. The workflow and "
                "scripts/ci_required.py disagree about this job, and neither can settle which is right."
            )

    return problems


def parse_results(raw: str) -> dict[str, str]:
    """Read the `toJSON(needs)` payload into job identifier to result."""
    payload = json.loads(raw or "{}")
    if not isinstance(payload, dict):
        raise ValueError("The needs payload must be a JSON object.")
    return {job: (value or {}).get("result", "unknown") for job, value in payload.items()}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--print-contexts",
        metavar="TIER",
        choices=sorted(REQUIRED_BY_TIER),
        help="List the check contexts an event of this tier publishes, then exit.",
    )
    parser.add_argument(
        "--fast",
        action="store_true",
        help=f"Act as {FAST_GATE_JOB}: require every fast job, and the single-publisher constraint.",
    )
    arguments = parser.parse_args(argv)

    if arguments.print_contexts:
        for context in required_contexts(arguments.print_contexts):
            print(context)
        return 0

    gate = FAST_GATE_JOB if arguments.fast else GATE_JOB
    try:
        results = parse_results(os.environ.get("NEEDS_JSON", "{}"))
    except (json.JSONDecodeError, ValueError) as exc:
        print(f"{gate} could not read the job results: {exc}", file=sys.stderr)
        return 1

    # Both gates hold both workflows to the constraint, so a change that breaks it is refused by
    # whichever of them runs first — including by ci-required on the full run of that change.
    problems = verify_single_publisher(WORKFLOW_DIRECTORY)
    try:
        problems.extend(verify_fast_workflow(FAST_WORKFLOW.read_text(encoding="utf-8")))
    except FileNotFoundError:
        problems.append(f"{FAST_WORKFLOW.name} is missing; the fast topology cannot be verified.")

    if arguments.fast:
        problems.extend(verify_fast_results(results))
        if problems:
            print(f"{FAST_GATE_JOB} refuses:", file=sys.stderr)
            for problem in problems:
                print(f"  - {problem}", file=sys.stderr)
            return 1
        print(f"{FAST_GATE_JOB}: every fast job succeeded.")
        return 0

    tier = os.environ.get("CI_TIER", "")
    problems.extend(
        verify_event(
            os.environ.get("EVENT_NAME", ""),
            os.environ.get("BASE_REF", ""),
            os.environ.get("REF_NAME", ""),
            tier,
        )
    )
    problems.extend(verify_workflow(WORKFLOW.read_text(encoding="utf-8")))
    problems.extend(verify_results(tier, results))

    if problems:
        print(f"ci-required refuses tier {tier!r}:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    executed = sorted(job for job, result in results.items() if result != "skipped")
    print(f"ci-required: tier {tier} satisfied by {len(executed)} executed jobs.")
    for job in executed:
        print(f"  - {job}: {results[job]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
