#!/usr/bin/env python3
"""Create or resume a GitHub Release without replacing immutable assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class GitHubReleaseError(ValueError):
    """Raised when an existing GitHub Release does not match local content."""


def gh(*arguments: str, check: bool = True, capture: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["gh", *arguments],
        cwd=ROOT,
        check=check,
        capture_output=capture,
        text=True,
    )


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def release(tag: str) -> dict[str, object] | None:
    repository = os.environ["GITHUB_REPOSITORY"]
    result = gh(
        "api",
        "--paginate",
        "--slurp",
        f"repos/{repository}/releases?per_page=100",
        check=False,
    )
    if result.returncode != 0:
        raise GitHubReleaseError(result.stderr.strip())
    pages = json.loads(result.stdout)
    matches = [
        item
        for page in pages
        if isinstance(page, list)
        for item in page
        if isinstance(item, dict) and item.get("tag_name") == tag
    ]
    if len(matches) > 1:
        raise GitHubReleaseError("multiple GitHub Releases use the immutable tag")
    return matches[0] if matches else None


def create_release(tag: str, notes: Path, prerelease: bool) -> None:
    arguments = [
        "release",
        "create",
        tag,
        "--verify-tag",
        "--title",
        f"Ravenroot {tag[1:]}",
        "--notes-file",
        str(notes),
        "--draft",
        "--latest=false",
    ]
    if prerelease:
        arguments.append("--prerelease")
    gh(*arguments, capture=False)


def verify_release(
    document: dict[str, object], tag: str, notes: Path, prerelease: bool, *, allow_draft: bool
) -> None:
    if document.get("tag_name") != tag:
        raise GitHubReleaseError("existing GitHub Release has the wrong tag")
    if bool(document.get("draft")) and not allow_draft:
        raise GitHubReleaseError("existing GitHub Release is unexpectedly a draft")
    if bool(document.get("prerelease")) != prerelease:
        raise GitHubReleaseError("existing GitHub Release has the wrong prerelease state")
    if document.get("name") != f"Ravenroot {tag[1:]}":
        raise GitHubReleaseError("existing GitHub Release has the wrong title")
    if str(document.get("body", "")).strip() != notes.read_text(encoding="utf-8").strip():
        raise GitHubReleaseError("existing GitHub Release has different reviewed notes")


def reconcile_assets(document: dict[str, object], directory: Path, *, allow_upload: bool) -> None:
    assets = {
        str(asset["name"]): asset
        for asset in document.get("assets", [])
        if isinstance(asset, dict) and "name" in asset
    }
    local_assets = sorted(path for path in directory.iterdir() if path.is_file())
    local_names = {path.name for path in local_assets}
    unexpected = set(assets).difference(local_names)
    if unexpected:
        raise GitHubReleaseError(
            f"existing GitHub Release has unexpected assets: {sorted(unexpected)}"
        )
    with tempfile.TemporaryDirectory(prefix="ravenroot-release-assets-") as temporary:
        temporary_directory = Path(temporary)
        for local in local_assets:
            remote = assets.get(local.name)
            if remote is None:
                if not allow_upload:
                    raise GitHubReleaseError(
                        f"published GitHub Release is missing immutable asset: {local.name}"
                    )
                gh("release", "upload", str(document["tag_name"]), str(local), capture=False)
                continue
            downloaded = temporary_directory / local.name
            with downloaded.open("wb") as destination:
                completed = subprocess.run(
                    [
                        "gh",
                        "api",
                        str(remote["url"]),
                        "--header",
                        "Accept: application/octet-stream",
                    ],
                    cwd=ROOT,
                    check=False,
                    stdout=destination,
                    stderr=subprocess.PIPE,
                )
            if completed.returncode != 0:
                raise GitHubReleaseError(completed.stderr.decode("utf-8", errors="replace"))
            if digest(downloaded) != digest(local):
                raise GitHubReleaseError(
                    f"existing GitHub Release asset differs from the tagged build: {local.name}"
                )


def publish_draft(tag: str, prerelease: bool) -> None:
    arguments = ["release", "edit", tag, "--draft=false", "--latest=false"]
    arguments.append("--prerelease" if prerelease else "--prerelease=false")
    gh(*arguments, capture=False)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--tag", required=True)
    result.add_argument("--commit", required=True)
    result.add_argument("--assets", type=Path, required=True)
    return result


def main() -> int:
    arguments = parser().parse_args()
    notes = ROOT / "docs" / "releases" / f"{arguments.tag}.md"
    prerelease = "-" in arguments.tag
    try:
        peeled = subprocess.run(
            ["git", "rev-parse", f"{arguments.tag}^{{commit}}"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        if peeled != arguments.commit:
            raise GitHubReleaseError("release tag does not peel to the authorized commit")
        document = release(arguments.tag)
        if document is None:
            create_release(arguments.tag, notes, prerelease)
            document = release(arguments.tag)
            if document is None:
                raise GitHubReleaseError("GitHub Release was not visible after creation")
        draft = bool(document.get("draft"))
        verify_release(document, arguments.tag, notes, prerelease, allow_draft=True)
        reconcile_assets(document, arguments.assets, allow_upload=draft)
        document = release(arguments.tag)
        if document is None:
            raise GitHubReleaseError("GitHub Release disappeared during reconciliation")
        reconcile_assets(document, arguments.assets, allow_upload=False)
        if draft:
            publish_draft(arguments.tag, prerelease)
            document = release(arguments.tag)
            if document is None:
                raise GitHubReleaseError("GitHub Release was not visible after publication")
        verify_release(document, arguments.tag, notes, prerelease, allow_draft=False)
        reconcile_assets(document, arguments.assets, allow_upload=False)
    except (GitHubReleaseError, KeyError, OSError, subprocess.CalledProcessError) as exc:
        print(f"GitHub Release verification failed: {exc}", file=sys.stderr)
        return 1
    print(f"GitHub Release {arguments.tag} is complete and immutable.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
