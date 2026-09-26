#!/usr/bin/env python3
"""Validate and preflight Ravenroot's immutable MinIO acceptance fixtures."""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Sequence


ROOT = Path(__file__).resolve().parents[3]
MANIFEST = Path(__file__).with_name("minio-fixtures.properties")
PROJECT_REPOSITORY = "ghcr.io/ravenroot-ai/ravenroot-minio-acceptance"
PENDING_DIGEST = "PENDING"
DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")
COMMIT = re.compile(r"[0-9a-f]{40}\Z")
PLATFORM = re.compile(r"linux/(?:amd64|arm64)\Z")
ROLES = ("server", "client")
DOCKERFILE = Path(__file__).with_name("Dockerfile")
SOURCE_DATE_EPOCH = "0"
REGISTRY_USER_ENV = "RAVENROOT_FIXTURE_REGISTRY_USER"
REGISTRY_TOKEN_ENV = "RAVENROOT_FIXTURE_REGISTRY_TOKEN"


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
    upstream_created: str
    packaging_source: str
    packaging_revision: str
    license: str
    platforms: tuple[str, ...]

    @property
    def project_reference(self) -> str:
        if self.project_digest == PENDING_DIGEST:
            raise FixtureError(f"{self.role} project digest has not been promoted")
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


def load_manifest(path: Path = MANIFEST, *, allow_pending: bool = False) -> tuple[Fixture, ...]:
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
        "upstream.source", "upstream.revision", "upstream.created", "packaging.source", "packaging.revision",
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
            upstream_created=_required(values, f"{role}.upstream.created"),
            packaging_source=_required(values, f"{role}.packaging.source"),
            packaging_revision=_required(values, f"{role}.packaging.revision"),
            license=_required(values, f"{role}.license"),
            platforms=tuple(part.strip() for part in _required(values, f"{role}.platforms").split(",")),
        )
        _validate_fixture(fixture, allow_pending=allow_pending)
        fixtures.append(fixture)
    unknown = sorted(set(values) - expected_keys)
    if unknown:
        raise FixtureError(f"unknown fixture properties: {', '.join(unknown)}")
    promoted_digests = {
        fixture.project_digest for fixture in fixtures if fixture.project_digest != PENDING_DIGEST
    }
    if len(promoted_digests) != sum(
        fixture.project_digest != PENDING_DIGEST for fixture in fixtures
    ):
        raise FixtureError("server and client must not share a project digest")
    return tuple(fixtures)


def _validate_fixture(fixture: Fixture, *, allow_pending: bool) -> None:
    if fixture.project_digest == PENDING_DIGEST:
        if not allow_pending:
            raise FixtureError(f"{fixture.role}.project.digest has not been promoted")
    elif not DIGEST.fullmatch(fixture.project_digest):
        raise FixtureError(f"{fixture.role}.project.digest must be an immutable sha256 digest")
    if not DIGEST.fullmatch(fixture.upstream_digest):
        raise FixtureError(f"{fixture.role}.upstream.digest must be an immutable sha256 digest")
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
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", fixture.upstream_created) is None:
        raise FixtureError(f"{fixture.role}.upstream.created must be a canonical UTC timestamp")
    if fixture.license != "AGPL-3.0-only":
        raise FixtureError(f"{fixture.role}.license must preserve the embedded application license")
    if not fixture.platforms or len(set(fixture.platforms)) != len(fixture.platforms):
        raise FixtureError(f"{fixture.role}.platforms must be a non-empty unique list")
    if any(not PLATFORM.fullmatch(platform) for platform in fixture.platforms):
        raise FixtureError(f"{fixture.role}.platforms contains an unsupported platform")
    if "linux/amd64" not in fixture.platforms:
        raise FixtureError(f"{fixture.role}.platforms must include the GitHub runner architecture linux/amd64")


def validate(path: Path = MANIFEST, *, allow_pending: bool = False) -> tuple[Fixture, ...]:
    fixtures = load_manifest(path, allow_pending=allow_pending)
    print(f"MinIO fixture manifest valid: {len(fixtures)} immutable project-owned indexes")
    return fixtures


def _bounded(value: str) -> str:
    sanitized = " ".join(value.replace("\x00", "").split())
    return sanitized[:512] if sanitized else "no diagnostic output"


def _run(arguments: Sequence[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(arguments, check=False, capture_output=True, text=True)


def registry_login_if_configured() -> None:
    user = os.environ.get(REGISTRY_USER_ENV)
    token = os.environ.get(REGISTRY_TOKEN_ENV)
    if user is None and token is None:
        return
    if not user or not token:
        raise FixtureError(f"{REGISTRY_USER_ENV} and {REGISTRY_TOKEN_ENV} must be set together")
    result = subprocess.run(
        ("docker", "login", "ghcr.io", "--username", user, "--password-stdin"),
        input=token, capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        raise FixtureError("fixture registry authentication failed")


def preflight(
    fixtures: Sequence[Fixture], platform: str, pull: bool,
    runner: Callable[[Sequence[str]], subprocess.CompletedProcess[str]] = _run,
) -> None:
    if not PLATFORM.fullmatch(platform):
        raise FixtureError(f"unsupported requested platform: {platform}")
    registry_login_if_configured()
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


def build_command(fixture: Fixture, metadata_file: Path, *, push: bool) -> tuple[str, ...]:
    output = f"type=image,name={fixture.publication_tag},push={'true' if push else 'false'},rewrite-timestamp=true"
    return (
        "docker", "buildx", "build", "--file", str(DOCKERFILE),
        "--platform", ",".join(fixture.platforms),
        "--build-arg", f"BASE_IMAGE={fixture.upstream_reference}",
        "--build-arg", f"FIXTURE_ROLE={fixture.role}",
        "--build-arg", f"UPSTREAM_VERSION={fixture.upstream_version}",
        "--build-arg", f"UPSTREAM_SOURCE={fixture.upstream_source}",
        "--build-arg", f"UPSTREAM_REVISION={fixture.upstream_revision}",
        "--build-arg", f"UPSTREAM_DIGEST={fixture.upstream_digest}",
        "--build-arg", f"UPSTREAM_CREATED={fixture.upstream_created}",
        "--build-arg", f"FIXTURE_LICENSE={fixture.license}",
        "--build-arg", f"SOURCE_DATE_EPOCH={SOURCE_DATE_EPOCH}",
        "--build-arg", "BUILDKIT_MULTI_PLATFORM=1",
        "--provenance=false", "--sbom=false", "--output", output,
        "--metadata-file", str(metadata_file), str(DOCKERFILE.parent),
    )


def build(fixture: Fixture, metadata_file: Path, *, push: bool, github_output: Path | None) -> str:
    registry_login_if_configured()
    arguments = build_command(fixture, metadata_file, push=push)
    print("Executing reproducible fixture build:", shlex.join(arguments))
    result = subprocess.run(arguments, check=False)
    if result.returncode != 0:
        raise FixtureError(f"{fixture.role} fixture build failed with exit code {result.returncode}")
    try:
        metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
        digest = metadata["containerimage.digest"]
    except (OSError, json.JSONDecodeError, KeyError) as failure:
        raise FixtureError(f"{fixture.role} fixture build did not report an image digest") from failure
    if not isinstance(digest, str) or not DIGEST.fullmatch(digest):
        raise FixtureError(f"{fixture.role} fixture build reported an invalid image digest")
    if fixture.project_digest != PENDING_DIGEST and digest != fixture.project_digest:
        raise FixtureError(
            f"{fixture.role} fixture digest drifted: expected {fixture.project_digest}, got {digest}"
        )
    if github_output is not None:
        with github_output.open("a", encoding="utf-8") as output_file:
            output_file.write(f"{fixture.role}_digest={digest}\n")
            output_file.write(f"{fixture.role}_tag={fixture.publication_tag}\n")
            output_file.write(f"{fixture.role}_platforms={','.join(fixture.platforms)}\n")
    print(f"{fixture.role} fixture built at {fixture.publication_tag}@{digest}")
    return digest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    subparsers = parser.add_subparsers(dest="command", required=True)
    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("--allow-pending-project-digests", action="store_true")
    preflight_parser = subparsers.add_parser("preflight")
    preflight_parser.add_argument("--platform", required=True)
    preflight_parser.add_argument("--pull", action="store_true")
    build_parser = subparsers.add_parser("build")
    build_parser.add_argument("--role", choices=ROLES, required=True)
    build_parser.add_argument("--metadata-file", type=Path, required=True)
    build_parser.add_argument("--github-output", type=Path)
    build_parser.add_argument("--push", action="store_true")
    arguments = parser.parse_args()
    try:
        allow_pending = arguments.command == "build" or (
            arguments.command == "validate" and arguments.allow_pending_project_digests
        )
        fixtures = load_manifest(arguments.manifest, allow_pending=allow_pending)
        if arguments.command == "validate":
            print(f"MinIO fixture manifest valid: {len(fixtures)} immutable project-owned indexes")
        elif arguments.command == "preflight":
            preflight(fixtures, arguments.platform, arguments.pull)
        else:
            fixture = next(candidate for candidate in fixtures if candidate.role == arguments.role)
            build(fixture, arguments.metadata_file, push=arguments.push,
                  github_output=arguments.github_output)
    except (FixtureError, OSError) as failure:
        parser.exit(1, f"MinIO fixture verification failed: {failure}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
