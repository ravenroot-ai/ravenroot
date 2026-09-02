#!/usr/bin/env python3
"""Read-only Maven Central state and immutable retry verification."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CENTRAL = "https://repo1.maven.org/maven2"
EXCLUDED_ARTIFACTS = {
    "ravenroot-akka",
    "ravenroot-node-starter",
    "ravenroot-sandbox-supervisor-testkit",
}
PUBLISHABLE_ARTIFACTS = (
    "ravenroot-ai",
    "ravenroot-amqp091",
    "ravenroot-api-testkit",
    "ravenroot-application-api",
    "ravenroot-cli",
    "ravenroot-core",
    "ravenroot-distribution",
    "ravenroot-engine-testkit",
    "ravenroot-extensions",
    "ravenroot-filesystem",
    "ravenroot-jdbc",
    "ravenroot-kafka",
    "ravenroot-mail",
    "ravenroot-object-storage",
    "ravenroot-observability-otel",
    "ravenroot-ocr",
    "ravenroot-openapi-client",
    "ravenroot-openapi-server",
    "ravenroot-parent",
    "ravenroot-pekko",
    "ravenroot-persistence-sqlite",
    "ravenroot-persistence-testkit",
    "ravenroot-plugin-bundle",
    "ravenroot-programming-graalvm",
    "ravenroot-server",
    "ravenroot-spel",
    "ravenroot-telegram",
    "ravenroot-websocket",
)


class RegistryError(ValueError):
    """Raised when immutable registry state is incomplete or mismatched."""


def publishable_artifacts() -> tuple[str, ...]:
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    available: set[str] = set()
    for pom in (ROOT / "ravenroot").rglob("pom.xml"):
        project = ET.parse(pom).getroot()
        artifact = project.findtext("m:artifactId", namespaces=namespace)
        group = project.findtext("m:groupId", namespaces=namespace)
        if group is None:
            group = project.findtext("m:parent/m:groupId", namespaces=namespace)
        if group == "ai.ravenroot" and artifact:
            available.add(artifact)
    missing = set(PUBLISHABLE_ARTIFACTS).difference(available)
    if missing:
        raise RegistryError(f"reviewed Maven publications have no POM: {sorted(missing)}")
    return PUBLISHABLE_ARTIFACTS


def central_url(artifact: str, version: str, filename: str | None = None) -> str:
    name = filename or f"{artifact}-{version}.pom"
    return f"{CENTRAL}/ai/ravenroot/{artifact}/{version}/{name}"


def exists(url: str) -> bool:
    request = urllib.request.Request(url, method="HEAD")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status == 200
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return False
        raise


def central_state(version: str) -> dict[str, object]:
    artifacts = publishable_artifacts()
    present = tuple(artifact for artifact in artifacts if exists(central_url(artifact, version)))
    if not present:
        state = "absent"
    elif len(present) == len(artifacts):
        state = "complete"
    else:
        state = "partial"
    return {
        "state": state,
        "present": present,
        "missing": tuple(artifact for artifact in artifacts if artifact not in present),
    }


def local_payloads(version: str) -> list[tuple[str, Path]]:
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    payloads: list[tuple[str, Path]] = []
    publishable = set(publishable_artifacts())
    for pom in sorted((ROOT / "ravenroot").rglob("pom.xml")):
        project = ET.parse(pom).getroot()
        artifact = project.findtext("m:artifactId", namespaces=namespace)
        if artifact not in publishable:
            continue
        payloads.append((f"{artifact}-{version}.pom", pom))
        packaging = project.findtext("m:packaging", default="jar", namespaces=namespace)
        if packaging != "jar":
            continue
        final_name = project.findtext("m:build/m:finalName", namespaces=namespace)
        final_name = final_name or f"{artifact}-{version}"
        target = pom.parent / "target"
        payloads.extend(
            (
                (f"{artifact}-{version}.jar", target / f"{final_name}.jar"),
                (f"{artifact}-{version}-sources.jar", target / f"{final_name}-sources.jar"),
                (f"{artifact}-{version}-javadoc.jar", target / f"{final_name}-javadoc.jar"),
            )
        )
        if artifact == "ravenroot-distribution":
            payloads.append(
                (f"{artifact}-{version}-bin.zip", target / f"{final_name}-bin.zip")
            )
    payloads.append(
        (
            f"ravenroot-parent-{version}-cyclonedx.json",
            ROOT / "ravenroot/target/ravenroot-sbom.json",
        )
    )
    return payloads


def compare_local(version: str) -> None:
    for filename, local in local_payloads(version):
        if not local.is_file():
            raise RegistryError(f"tagged build payload is missing: {local.relative_to(ROOT)}")
        artifact = filename.removesuffix(f"-{version}.pom") if filename.endswith(".pom") else None
        if artifact is None:
            artifact = next(
                (
                    candidate
                    for candidate in publishable_artifacts()
                    if filename.startswith(f"{candidate}-{version}")
                ),
                None,
            )
        if artifact is None:
            raise RegistryError(f"cannot map local payload to a Central artifact: {filename}")
        remote_url = central_url(artifact, version, filename)
        try:
            with urllib.request.urlopen(remote_url, timeout=60) as response:
                remote = response.read()
        except urllib.error.HTTPError as exc:
            raise RegistryError(f"published payload is missing: {filename}") from exc
        local_digest = hashlib.sha256(local.read_bytes()).hexdigest()
        remote_digest = hashlib.sha256(remote).hexdigest()
        if local_digest != remote_digest:
            raise RegistryError(f"published payload differs from the tagged build: {filename}")


def write_output(key: str, value: str) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as output:
            output.write(f"{key}={value}\n")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    commands = result.add_subparsers(dest="command", required=True)
    state = commands.add_parser("state")
    state.add_argument("--version", required=True)
    compare = commands.add_parser("compare-local")
    compare.add_argument("--version", required=True)
    return result


def main() -> int:
    arguments = parser().parse_args()
    try:
        if arguments.command == "state":
            result = central_state(arguments.version)
            print(json.dumps(result, sort_keys=True))
            write_output("central_state", str(result["state"]))
            if result["state"] == "partial":
                raise RegistryError("Maven Central contains only part of this immutable version")
        else:
            compare_local(arguments.version)
            print("The published Maven payloads match the tagged build.")
    except (RegistryError, OSError, urllib.error.URLError) as exc:
        print(f"Central registry verification failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
