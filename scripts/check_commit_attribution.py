#!/usr/bin/env python3
"""Refuse assistant attribution in the commits an event introduces.

The commit identity rule in AGENTS.md says an automated agent commits under its own agent name and
`agents@ravenroot.ai`, and that no assistant or its tooling adds a `Co-authored-by` trailer or an
attribution footer. Tools add both by default, and a default that has to be switched off in every
tool, on every machine, returns the first time someone forgets. This check is the defence that does
not depend on the tool: it reads the commits themselves.

It examines only the commits the event introduces — the pull request's range, the push's range, the
merge group's range — so history that predates the rule is left alone. It refuses:

* any `Co-authored-by` trailer on a commit authored by `agents@ravenroot.ai`: agents never share
  authorship with the tool that ran them;
* any `Co-authored-by` trailer naming an assistant or tooling address, on any commit;
* an attribution footer such as "Generated with …" in a commit message.

A `Co-authored-by` between people is legitimate and passes.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path


AGENT_EMAIL = "agents@ravenroot.ai"
# Addresses assistants and their tooling use for attribution. Kept short and explicit: a pattern
# broad enough to catch every future tool would also catch people.
TOOL_ADDRESS = re.compile(
    r"@(?:[a-z0-9-]+\.)*(?:anthropic\.com|openai\.com)>?$|copilot[^@]*@users\.noreply\.github\.com>?$",
    re.IGNORECASE,
)
CO_AUTHOR = re.compile(r"(?im)^co-authored-by:\s*(?P<who>.+)$")
FOOTER = re.compile(r"(?im)^.*generated (?:with|by) \[?(?:claude|codex|chatgpt|copilot)")
SEPARATOR = "\x1e"


def git(*arguments: str) -> str:
    return subprocess.run(["git", *arguments], check=True, capture_output=True, text=True).stdout


def commits(base: str, head: str) -> list[tuple[str, str, str]]:
    """(sha, author email, message) for every commit in base..head."""
    raw = git("log", f"--format=%H%x1f%ae%x1f%B{SEPARATOR}", f"{base}..{head}")
    result = []
    for record in raw.split(SEPARATOR):
        record = record.strip("\n")
        if not record:
            continue
        sha, email, message = record.split("\x1f", 2)
        result.append((sha, email, message))
    return result


def problems_in(sha: str, email: str, message: str) -> list[str]:
    problems = []
    for match in CO_AUTHOR.finditer(message):
        who = match.group("who").strip()
        if email.lower() == AGENT_EMAIL:
            problems.append(f"{sha[:12]}: an agent commit carries `Co-authored-by: {who}`")
        elif TOOL_ADDRESS.search(who):
            problems.append(f"{sha[:12]}: `Co-authored-by: {who}` attributes an assistant or its tooling")
    for match in FOOTER.finditer(message):
        problems.append(f"{sha[:12]}: attribution footer `{match.group(0).strip()}`")
    return problems


def event_range(event_name: str, event: dict) -> tuple[str, str] | None:
    """The commits an event introduces, as (base, head), or None when it cannot be told."""
    if event_name == "pull_request":
        pull = event.get("pull_request") or {}
        return (pull.get("base") or {}).get("sha", ""), (pull.get("head") or {}).get("sha", "")
    if event_name == "merge_group":
        group = event.get("merge_group") or {}
        return group.get("base_sha", ""), group.get("head_sha", "")
    if event_name == "push":
        before, after = event.get("before", ""), event.get("after", "")
        if before and set(before) != {"0"}:
            return before, after
    return None


def reachable(revision: str) -> bool:
    return subprocess.run(["git", "cat-file", "-e", f"{revision}^{{commit}}"], capture_output=True).returncode == 0


def default_range() -> tuple[str, str]:
    """Outside an event with a range: the commits this checkout adds to dev."""
    try:
        return git("merge-base", "HEAD", "origin/dev").strip(), "HEAD"
    except subprocess.CalledProcessError:
        return "HEAD~1", "HEAD"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base")
    parser.add_argument("--head")
    arguments = parser.parse_args(argv)

    if arguments.base and arguments.head:
        span = (arguments.base, arguments.head)
    else:
        event: dict = {}
        path = os.environ.get("GITHUB_EVENT_PATH")
        if path and Path(path).is_file():
            event = json.loads(Path(path).read_text(encoding="utf-8"))
        span = event_range(os.environ.get("GITHUB_EVENT_NAME", ""), event) or default_range()
    base, head = span
    if base and not reachable(base):
        # A force-pushed branch leaves the event's `before` unreachable in a fresh clone. The range
        # that matters is then the commits this checkout adds to dev, which is what a work branch
        # introduces anyway.
        base, head = default_range()
    if not base or not head:
        print("check_commit_attribution: the event names no commit range to examine", file=sys.stderr)
        return 1

    problems = [problem for sha, email, message in commits(base, head) for problem in problems_in(sha, email, message)]
    if problems:
        print("Assistant attribution in the commits this change introduces (see AGENTS.md, Commit identity):",
              file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"check_commit_attribution: {base[:12]}..{head[:12]} carries no assistant attribution.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
