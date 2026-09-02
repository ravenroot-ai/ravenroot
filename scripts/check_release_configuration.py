#!/usr/bin/env python3
"""Verify release metadata, publication boundaries, and workflow authority."""

from __future__ import annotations

import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.central_registry import PUBLISHABLE_ARTIFACTS, publishable_artifacts


ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
FINGERPRINT = "31841485DE6D55A504CAC4B2DB18FAA6B85083EA"
SECRET_NAMES = {
    "CENTRAL_TOKEN",
    "CENTRAL_USERNAME",
    "MAVEN_GPG_PASSPHRASE",
    "MAVEN_GPG_PRIVATE_KEY",
}


def required_text(root: ET.Element, path: str, expected: str) -> None:
    value = root.findtext(path, namespaces=NS)
    if value != expected:
        raise ValueError(f"{path} must be {expected!r}, found {value!r}")


def check_pom() -> None:
    project = ET.parse(ROOT / "ravenroot/pom.xml").getroot()
    required_text(project, "m:url", "https://ravenroot.ai")
    required_text(project, "m:licenses/m:license/m:name", "Apache License, Version 2.0")
    required_text(
        project,
        "m:scm/m:connection",
        "scm:git:https://github.com/ravenroot-ai/ravenroot.git",
    )
    required_text(project, "m:organization/m:name", "Ravenroot")
    required_text(project, "m:developers/m:developer/m:id", "ravenroot-maintainers")
    required_text(project, "m:distributionManagement/m:repository/m:id", "central")

    profiles = {profile.findtext("m:id", namespaces=NS): profile for profile in project.findall("m:profiles/m:profile", NS)}
    release = profiles.get("release")
    if release is None:
        raise ValueError("release Maven profile is missing")
    plugins = {
        plugin.findtext("m:artifactId", namespaces=NS): plugin
        for plugin in release.findall("m:build/m:plugins/m:plugin", NS)
    }
    required_plugins = {
        "maven-source-plugin",
        "maven-javadoc-plugin",
        "maven-gpg-plugin",
        "central-publishing-maven-plugin",
        "cyclonedx-maven-plugin",
    }
    missing = required_plugins.difference(plugins)
    if missing:
        raise ValueError(f"release Maven plugins are missing: {sorted(missing)}")
    central = plugins["central-publishing-maven-plugin"]
    excluded = {
        element.text
        for element in central.findall("m:configuration/m:excludeArtifacts/m:excludeArtifact", NS)
    }
    configured_boundary = {"ravenroot-node-starter", "ravenroot-sandbox-supervisor-testkit"}
    if excluded != configured_boundary:
        raise ValueError(f"Central exclusions differ from the reviewed boundary: {sorted(excluded)}")
    reactor = reactor_artifacts(ROOT / "ravenroot/pom.xml")
    actual_boundary = reactor.difference(excluded)
    if actual_boundary != set(PUBLISHABLE_ARTIFACTS):
        raise ValueError(
            "default reactor minus Central exclusions differs from the explicit publication allowlist: "
            f"{sorted(actual_boundary.symmetric_difference(PUBLISHABLE_ARTIFACTS))}"
        )
    if publishable_artifacts() != PUBLISHABLE_ARTIFACTS:
        raise ValueError("publishable Maven artifact order or boundary changed")


def reactor_artifacts(pom: Path) -> set[str]:
    project = ET.parse(pom).getroot()
    artifact = project.findtext("m:artifactId", namespaces=NS)
    if not artifact:
        raise ValueError(f"reactor POM has no artifactId: {pom.relative_to(ROOT)}")
    result = {artifact}
    for module in project.findall("m:modules/m:module", NS):
        if module.text:
            result.update(reactor_artifacts(pom.parent / module.text.strip() / "pom.xml"))
    return result


def check_public_key() -> None:
    key = ROOT / "docs/security/release-signing-key.asc"
    result = subprocess.run(
        ["gpg", "--batch", "--show-keys", "--with-colons", str(key)],
        check=True,
        capture_output=True,
        text=True,
    )
    fingerprints = [
        line.split(":")[9] for line in result.stdout.splitlines() if line.startswith("fpr:")
    ]
    if not fingerprints or fingerprints[0] != FINGERPRINT:
        raise ValueError("tracked release public key has an unexpected fingerprint")


def check_action_pins(path: Path, contents: str) -> None:
    for line_number, line in enumerate(contents.splitlines(), start=1):
        match = re.search(r"\buses:\s*([^\s#]+)", line)
        if not match:
            continue
        reference = match.group(1)
        if reference.startswith("./"):
            continue
        if not re.fullmatch(r"[^@]+@[0-9a-f]{40}", reference):
            raise ValueError(f"{path}:{line_number}: action is not pinned to a commit SHA")


def check_workflows() -> None:
    workflow_directory = ROOT / ".github/workflows"
    workflows = {path.name: path.read_text(encoding="utf-8") for path in workflow_directory.glob("*.yml")}
    for name, contents in workflows.items():
        check_action_pins(workflow_directory / name, contents)

    ci = workflows["ci.yml"]
    if "secrets." in ci or "packages: write" in ci or "environment:\n      name: release" in ci:
        raise ValueError("ordinary CI must not possess release credentials or package authority")

    authorize = workflows["authorize-release.yml"]
    if "secrets." in authorize or "environment:" in authorize or "pull_request_target" in authorize:
        raise ValueError("main-side authorization must remain secretless")
    if "branches: [main]" not in authorize or "release:none" in authorize:
        raise ValueError("main-side authorization trigger or fail-closed implementation changed")

    publication = workflows["release.yml"]
    validate, separator, publish = publication.partition("\n  publish:\n")
    if not separator:
        raise ValueError("release workflow has no separately protected publish job")
    if "secrets." in validate or "packages: write" in validate or "environment:" in validate:
        raise ValueError("non-secret release gates gained publication authority")
    if "environment:\n      name: release" not in publish:
        raise ValueError("publication job does not enter the protected release environment")
    referenced = set(re.findall(r"secrets\.([A-Z0-9_]+)", publish))
    if referenced != SECRET_NAMES:
        raise ValueError(f"release environment secret contract changed: {sorted(referenced)}")
    for required in (
        "tags:\n      - \"v*\"",
        "workflow_dispatch:",
        "test \"$GITHUB_REF_TYPE\" = tag",
        "validate-tag-authorization",
        "packages: write",
        "id-token: write",
        "central_registry.py compare-local",
        "skopeo copy --all",
        "SOURCE_DATE_EPOCH",
        "local_image_digest",
        "https://spdx.dev/Document",
        "https://slsa.dev/provenance/v1",
        "push-to-registry: true",
    ):
        if required not in publication:
            raise ValueError(f"release workflow contract is missing: {required}")


def check_documentation() -> None:
    documentation = (ROOT / "docs/governance/releasing.md").read_text(encoding="utf-8")
    for expected in (*sorted(SECRET_NAMES), FINGERPRINT, "ghcr.io/ravenroot-ai/ravenroot", "ai.ravenroot"):
        if expected not in documentation:
            raise ValueError(f"release operator documentation is missing {expected}")


def check_oci_metadata() -> None:
    dockerfile = (ROOT / "Dockerfile.ci").read_text(encoding="utf-8")
    for label in (
        "org.opencontainers.image.source",
        "org.opencontainers.image.revision",
        "org.opencontainers.image.version",
        "org.opencontainers.image.documentation",
        'org.opencontainers.image.licenses="Apache-2.0"',
    ):
        if label not in dockerfile:
            raise ValueError(f"OCI release metadata is missing {label}")
    release_workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
    if "buildkit-syft-scanner:stable-1@sha256:" not in release_workflow:
        raise ValueError("OCI SBOM generator is not pinned by digest")


def main() -> int:
    try:
        check_pom()
        check_public_key()
        check_workflows()
        check_documentation()
        check_oci_metadata()
    except (KeyError, OSError, ValueError, subprocess.CalledProcessError) as exc:
        print(f"Release configuration check failed: {exc}", file=sys.stderr)
        return 1
    print("Release metadata, boundaries, credentials, and workflow contracts are valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
