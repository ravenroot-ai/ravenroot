#!/usr/bin/env python3
"""Validate and preflight Ravenroot's immutable MinIO acceptance fixtures."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Sequence


ROOT = Path(__file__).resolve().parents[3]
MANIFEST = Path(__file__).with_name("minio-fixtures.properties")
PROJECT_REPOSITORY = "ghcr.io/ravenroot-ai/ravenroot-minio-fixtures"
DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")
COMMIT = re.compile(r"[0-9a-f]{40}\Z")
PLATFORM = re.compile(r"linux/(?:amd64|arm64)\Z")
ROLES = ("server", "client")


class FixtureError(RuntimeError):
    """A public, non-secret fixture diagnostic."""


@dataclass(frozen=True)
class Fixture:
    role: str
    project_repository: str
    project_digest: str
    upstream_repository: str
    upstream_digest: str
    upstream_version: str
    upstream_source: str
    upstream_revision: str
    packaging_source: str
    packaging_revision: str
    license: str
    platforms: tuple[str, ...]

    @property
    def project_reference(self) -> str:
        return f"{self.project_repository}@{self.project_digest}"

    @property
    def upstream_reference(self) -> str:
        return f"{self.upstream_repository}@{self.upstream_digest}"

    @property
    def publication_tag(self) -> str:
        version = re.sub(r"[^a-zA-Z0-9_.-]+", "-", self.upstream_version).strip("-.").lower()
        return f"{self.project_repository}:{self.role}-{version}"


def _parse_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise FixtureError(f"{path}:{number}: expected key=value")
        key, value = (part.strip() for part in line.split("=", 1))
        if not key or not value or key in values:
            raise FixtureError(f"{path}:{number}: invalid or duplicate property {key!r}")
        values[key] = value
    return values


def _required(values: dict[str, str], key: str) -> str:
    try:
        return values[key]
    except KeyError as failure:
        raise FixtureError(f"missing required fixture property: {key}") from failure


def load_manifest(path: Path = MANIFEST) -> tuple[Fixture, ...]:
    values = _parse_properties(path)
    if _required(values, "schema.version") != "1":
        raise FixtureError("unsupported MinIO fixture manifest schema")
    repository = _required(values, "project.repository")
    if repository != PROJECT_REPOSITORY:
        raise FixtureError(f"project.repository must be {PROJECT_REPOSITORY}")

    fixtures = []
    expected_keys = {"schema.version", "project.repository"}
    suffixes = (
        "project.digest", "upstream.repository", "upstream.digest", "upstream.version",
        "upstream.source", "upstream.revision", "packaging.source", "packaging.revision",
        "license", "platforms",
    )
    for role in ROLES:
        expected_keys.update(f"{role}.{suffix}" for suffix in suffixes)
        fixture = Fixture(
            role=role,
            project_repository=repository,
            project_digest=_required(values, f"{role}.project.digest"),
            upstream_repository=_required(values, f"{role}.upstream.repository"),
            upstream_digest=_required(values, f"{role}.upstream.digest"),
            upstream_version=_required(values, f"{role}.upstream.version"),
            upstream_source=_required(values, f"{role}.upstream.source"),
            upstream_revision=_required(values, f"{role}.upstream.revision"),
            packaging_source=_required(values, f"{role}.packaging.source"),
            packaging_revision=_required(values, f"{role}.packaging.revision"),
            license=_required(values, f"{role}.license"),
            platforms=tuple(part.strip() for part in _required(values, f"{role}.platforms").split(",")),
        )
        _validate_fixture(fixture)
        fixtures.append(fixture)
    unknown = sorted(set(values) - expected_keys)
    if unknown:
        raise FixtureError(f"unknown fixture properties: {', '.join(unknown)}")
    if len({fixture.project_digest for fixture in fixtures}) != len(fixtures):
        raise FixtureError("server and client must not share a project digest")
    return tuple(fixtures)


def _validate_fixture(fixture: Fixture) -> None:
    for label, value in (("project", fixture.project_digest), ("upstream", fixture.upstream_digest)):
        if not DIGEST.fullmatch(value):
            raise FixtureError(f"{fixture.role}.{label}.digest must be an immutable sha256 digest")
    if fixture.project_digest != fixture.upstream_digest:
        raise FixtureError(f"{fixture.role} mirror digest must equal the byte-for-byte upstream index digest")
    if not fixture.upstream_repository.startswith("docker.io/bitnamilegacy/"):
        raise FixtureError(f"{fixture.role}.upstream.repository must name the reviewed archival source")
    if "@" in fixture.upstream_repository or ":" in fixture.upstream_repository:
        raise FixtureError(f"{fixture.role}.upstream.repository must not embed a tag or digest")
    if not fixture.upstream_source.startswith("https://github.com/minio/"):
        raise FixtureError(f"{fixture.role}.upstream.source must name the public MinIO source repository")
    if not fixture.packaging_source.startswith("https://github.com/bitnami/containers/"):
        raise FixtureError(f"{fixture.role}.packaging.source must name the public packaging source")
    if not COMMIT.fullmatch(fixture.upstream_revision):
        raise FixtureError(f"{fixture.role}.upstream.revision must be a full source commit")
    if fixture.license != "AGPL-3.0-only":
        raise FixtureError(f"{fixture.role}.license must preserve the embedded application license")
    if not fixture.platforms or len(set(fixture.platforms)) != len(fixture.platforms):
        raise FixtureError(f"{fixture.role}.platforms must be a non-empty unique list")
    if any(not PLATFORM.fullmatch(platform) for platform in fixture.platforms):
        raise FixtureError(f"{fixture.role}.platforms contains an unsupported platform")
    if "linux/amd64" not in fixture.platforms:
        raise FixtureError(f"{fixture.role}.platforms must include the GitHub runner architecture linux/amd64")


def validate(path: Path = MANIFEST) -> tuple[Fixture, ...]:
    fixtures = load_manifest(path)
    print(f"MinIO fixture manifest valid: {len(fixtures)} immutable project-owned indexes")
    return fixtures


def _bounded(value: str) -> str:
    sanitized = " ".join(value.replace("\x00", "").split())
    return sanitized[:512] if sanitized else "no diagnostic output"


def _run(arguments: Sequence[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(arguments, check=False, capture_output=True, text=True)


def preflight(
    fixtures: Sequence[Fixture], platform: str, pull: bool,
    runner: Callable[[Sequence[str]], subprocess.CompletedProcess[str]] = _run,
) -> None:
    if not PLATFORM.fullmatch(platform):
        raise FixtureError(f"unsupported requested platform: {platform}")
    for fixture in fixtures:
        if platform not in fixture.platforms:
            raise FixtureError(f"{fixture.role} fixture does not declare {platform}")
        inspected = runner(("docker", "manifest", "inspect", fixture.project_reference))
        if inspected.returncode != 0:
            raise FixtureError(
                f"{fixture.role} fixture is unavailable at {fixture.project_reference}: "
                f"{_bounded(inspected.stderr or inspected.stdout)}"
            )
        try:
            manifest = json.loads(inspected.stdout)
        except json.JSONDecodeError as failure:
            raise FixtureError(f"{fixture.role} fixture returned an invalid OCI manifest") from failure
        platforms = {
            f"{entry.get('platform', {}).get('os')}/{entry.get('platform', {}).get('architecture')}"
            for entry in manifest.get("manifests", [])
        }
        if platforms and platform not in platforms:
            raise FixtureError(f"{fixture.role} fixture index does not contain {platform}")
        if pull:
            pulled = runner(("docker", "pull", "--platform", platform, fixture.project_reference))
            if pulled.returncode != 0:
                raise FixtureError(
                    f"{fixture.role} fixture layers are unavailable for {platform}: "
                    f"{_bounded(pulled.stderr or pulled.stdout)}"
                )
        print(f"{fixture.role} fixture ready: {fixture.project_reference} ({platform})")


def emit_github_output(fixtures: Sequence[Fixture], destination: Path) -> None:
    lines = []
    for fixture in fixtures:
        prefix = fixture.role
        lines.extend((
            f"{prefix}_source={fixture.upstream_reference}",
            f"{prefix}_target_tag={fixture.publication_tag}",
            f"{prefix}_target_ref={fixture.project_reference}",
            f"{prefix}_platforms={','.join(fixture.platforms)}",
        ))
    with destination.open("a", encoding="utf-8") as output:
        output.write("\n".join(lines) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("validate")
    preflight_parser = subparsers.add_parser("preflight")
    preflight_parser.add_argument("--platform", required=True)
    preflight_parser.add_argument("--pull", action="store_true")
    output_parser = subparsers.add_parser("github-output")
    output_parser.add_argument("--destination", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        fixtures = load_manifest(arguments.manifest)
        if arguments.command == "validate":
            print(f"MinIO fixture manifest valid: {len(fixtures)} immutable project-owned indexes")
        elif arguments.command == "preflight":
            preflight(fixtures, arguments.platform, arguments.pull)
        else:
            emit_github_output(fixtures, arguments.destination)
    except (FixtureError, OSError) as failure:
        parser.exit(1, f"MinIO fixture verification failed: {failure}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
