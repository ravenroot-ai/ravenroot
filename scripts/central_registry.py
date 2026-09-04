#!/usr/bin/env python3
"""Read-only Maven Central state and immutable retry verification."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CENTRAL = "https://repo1.maven.org/maven2"
PORTAL = "https://central.sonatype.com/api/v1/publisher"
CHECKSUMS = ("md5", "sha1", "sha256", "sha512")
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
    "ravenroot-extensions-all",
    "ravenroot-filesystem",
    "ravenroot-git-workspace",
    "ravenroot-github",
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


def artifact_for(filename: str, version: str) -> str:
    if filename.endswith(f"-{version}.pom"):
        return filename.removesuffix(f"-{version}.pom")
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
    return artifact


def bundle_path(artifact: str, version: str, filename: str) -> str:
    return f"ai/ravenroot/{artifact}/{version}/{filename}"


def local_signature(filename: str, payload: Path) -> Path:
    if payload.name == "pom.xml":
        return payload.parent / "target" / f"{filename}.asc"
    return Path(f"{payload}.asc")


def write_zip_entry(bundle: zipfile.ZipFile, name: str, contents: bytes) -> None:
    entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    entry.compress_type = zipfile.ZIP_DEFLATED
    entry.external_attr = 0o100644 << 16
    bundle.writestr(entry, contents)


def build_bundle(path: Path, version: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as bundle:
        for filename, local in local_payloads(version):
            if not local.is_file():
                raise RegistryError(f"tagged build payload is missing: {local.relative_to(ROOT)}")
            signature = local_signature(filename, local)
            if not signature.is_file():
                raise RegistryError(f"tagged build signature is missing: {signature.relative_to(ROOT)}")
            primary = bundle_path(artifact_for(filename, version), version, filename)
            payload = local.read_bytes()
            write_zip_entry(bundle, primary, payload)
            write_zip_entry(bundle, f"{primary}.asc", signature.read_bytes())
            for algorithm in CHECKSUMS:
                write_zip_entry(
                    bundle,
                    f"{primary}.{algorithm}",
                    hashlib.new(algorithm, payload).hexdigest().encode("ascii"),
                )


def verify_signatures(pairs: list[tuple[bytes, bytes]]) -> None:
    with tempfile.TemporaryDirectory(prefix="ravenroot-central-signature-") as directory:
        root = Path(directory)
        root.chmod(0o700)
        imported = subprocess.run(
            [
                "gpg",
                "--batch",
                "--no-autostart",
                "--homedir",
                str(root),
                "--import",
                str(ROOT / "docs/security/release-signing-key.asc"),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        if imported.returncode != 0:
            raise RegistryError("cannot import the reviewed Ravenroot release key")
        for index, (payload, signature) in enumerate(pairs):
            payload_path = root / f"payload-{index}"
            signature_path = root / f"payload-{index}.asc"
            payload_path.write_bytes(payload)
            signature_path.write_bytes(signature)
            completed = subprocess.run(
                [
                    "gpg",
                    "--batch",
                    "--no-autostart",
                    "--homedir",
                    str(root),
                    "--status-fd=1",
                    "--verify",
                    str(signature_path),
                    str(payload_path),
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            if completed.returncode != 0:
                raise RegistryError("Central bundle contains an invalid release signature")
            valid = [
                line.split()
                for line in completed.stdout.splitlines()
                if line.startswith("[GNUPG:] VALIDSIG ")
            ]
            if len(valid) != 1 or "31841485DE6D55A504CAC4B2DB18FAA6B85083EA" not in valid[0]:
                raise RegistryError("Central bundle was not signed by the reviewed Ravenroot key")


def verify_signature(payload: bytes, signature: bytes) -> None:
    verify_signatures([(payload, signature)])


def validate_bundle(path: Path, version: str, signature_verifier=None) -> None:
    if not path.is_file():
        raise RegistryError(f"Central bundle is missing: {path}")
    expected: set[str] = set()
    signatures: list[tuple[bytes, bytes]] = []
    with zipfile.ZipFile(path) as bundle:
        entries = {name for name in bundle.namelist() if not name.endswith("/")}
        for filename, local in local_payloads(version):
            if not local.is_file():
                raise RegistryError(f"tagged build payload is missing: {local.relative_to(ROOT)}")
            artifact = artifact_for(filename, version)
            primary = bundle_path(artifact, version, filename)
            expected.add(primary)
            expected.add(f"{primary}.asc")
            expected.update(f"{primary}.{algorithm}" for algorithm in CHECKSUMS)
            if primary not in entries:
                raise RegistryError(f"Central bundle payload is missing: {filename}")
            payload = bundle.read(primary)
            if payload != local.read_bytes():
                raise RegistryError(f"Central bundle differs from tagged build: {filename}")
            signature_name = f"{primary}.asc"
            if signature_name not in entries:
                raise RegistryError(f"Central bundle signature is missing: {filename}.asc")
            pair = (payload, bundle.read(signature_name))
            if signature_verifier is None:
                signatures.append(pair)
            else:
                signature_verifier(*pair)
            for algorithm in CHECKSUMS:
                checksum_name = f"{primary}.{algorithm}"
                if checksum_name not in entries:
                    raise RegistryError(f"Central bundle checksum is missing: {filename}.{algorithm}")
                actual = bundle.read(checksum_name).decode("ascii").strip().split()[0]
                expected_digest = hashlib.new(algorithm, payload).hexdigest()
                if actual != expected_digest:
                    raise RegistryError(f"Central bundle checksum differs: {filename}.{algorithm}")
        unexpected = entries.difference(expected)
        missing = expected.difference(entries)
        if unexpected or missing:
            raise RegistryError(
                f"Central bundle file set differs; unexpected={sorted(unexpected)}, missing={sorted(missing)}"
            )
    if signature_verifier is None:
        verify_signatures(signatures)


def compare_local(version: str) -> None:
    signatures: list[tuple[bytes, bytes]] = []
    for filename, local in local_payloads(version):
        if not local.is_file():
            raise RegistryError(f"tagged build payload is missing: {local.relative_to(ROOT)}")
        artifact = artifact_for(filename, version)
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
        signature_url = f"{remote_url}.asc"
        try:
            with urllib.request.urlopen(signature_url, timeout=60) as response:
                signature = response.read()
        except urllib.error.HTTPError as exc:
            raise RegistryError(f"published signature is missing: {filename}.asc") from exc
        signatures.append((remote, signature))
        for algorithm in CHECKSUMS:
            checksum_url = f"{remote_url}.{algorithm}"
            try:
                with urllib.request.urlopen(checksum_url, timeout=60) as response:
                    actual = response.read().decode("ascii").strip().split()[0]
            except urllib.error.HTTPError as exc:
                raise RegistryError(f"published checksum is missing: {filename}.{algorithm}") from exc
            expected = hashlib.new(algorithm, remote).hexdigest()
            if actual != expected:
                raise RegistryError(f"published checksum differs: {filename}.{algorithm}")
    verify_signatures(signatures)


def portal_request(
    endpoint: str,
    username: str,
    token: str,
    *,
    body: bytes | None = None,
    content_type: str | None = None,
) -> bytes:
    authorization = base64.b64encode(f"{username}:{token}".encode("utf-8")).decode("ascii")
    headers = {"Authorization": f"Bearer {authorization}"}
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(f"{PORTAL}/{endpoint}", data=body, headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def upload_bundle(path: Path, version: str, username: str, token: str) -> str:
    boundary = f"ravenroot-{secrets.token_hex(16)}"
    filename = path.name.replace('"', "")
    prefix = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="bundle"; filename="{filename}"\r\n'
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode("ascii")
    suffix = f"\r\n--{boundary}--\r\n".encode("ascii")
    query = urllib.parse.urlencode(
        {"name": f"Ravenroot {version}", "publishingType": "AUTOMATIC"}
    )
    response = portal_request(
        f"upload?{query}",
        username,
        token,
        body=prefix + path.read_bytes() + suffix,
        content_type=f"multipart/form-data; boundary={boundary}",
    )
    deployment_id = response.decode("utf-8").strip()
    try:
        uuid.UUID(deployment_id)
    except ValueError as exc:
        raise RegistryError("Central Portal returned an invalid deployment ID") from exc
    return deployment_id


def wait_for_deployment(deployment_id: str, username: str, token: str) -> None:
    for _ in range(180):
        query = urllib.parse.urlencode({"id": deployment_id})
        document = json.loads(portal_request(f"status?{query}", username, token))
        state = document.get("deploymentState")
        if state == "PUBLISHED":
            return
        if state == "FAILED":
            raise RegistryError(f"Central deployment stopped in state {state}")
        if state not in {"PENDING", "VALIDATING", "VALIDATED", "PUBLISHING"}:
            raise RegistryError(f"Central deployment returned unknown state {state!r}")
        time.sleep(20)
    raise RegistryError("Central deployment did not publish within one hour")


def publish_bundle(path: Path, version: str) -> None:
    validate_bundle(path, version)
    state = central_state(version)["state"]
    if state == "partial":
        raise RegistryError("Maven Central contains only part of this immutable version")
    if state == "complete":
        compare_local(version)
        return
    username = os.environ.get("CENTRAL_USERNAME")
    token = os.environ.get("CENTRAL_TOKEN")
    if not username or not token:
        raise RegistryError("Central publication credentials are unavailable")
    deployment_id = upload_bundle(path, version, username, token)
    wait_for_deployment(deployment_id, username, token)
    for _ in range(60):
        if central_state(version)["state"] == "complete":
            compare_local(version)
            return
        time.sleep(20)
    raise RegistryError("published Central payloads did not become visible")


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
    validate = commands.add_parser("validate-bundle")
    validate.add_argument("--version", required=True)
    validate.add_argument("--bundle", type=Path, required=True)
    publish = commands.add_parser("publish-bundle")
    publish.add_argument("--version", required=True)
    publish.add_argument("--bundle", type=Path, required=True)
    build = commands.add_parser("build-bundle")
    build.add_argument("--version", required=True)
    build.add_argument("--bundle", type=Path, required=True)
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
        elif arguments.command == "compare-local":
            compare_local(arguments.version)
            print("The published Maven payloads match the tagged build.")
        elif arguments.command == "validate-bundle":
            validate_bundle(arguments.bundle, arguments.version)
            print("The staged Central bundle is complete, signed, and reproducible.")
        elif arguments.command == "publish-bundle":
            publish_bundle(arguments.bundle, arguments.version)
            print("The Central release is published and matches the staged bundle.")
        else:
            build_bundle(arguments.bundle, arguments.version)
            print("Built the signed Central release bundle without publishing it.")
    except (
        RegistryError,
        OSError,
        UnicodeDecodeError,
        json.JSONDecodeError,
        urllib.error.URLError,
        zipfile.BadZipFile,
    ) as exc:
        print(f"Central registry verification failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
