#!/usr/bin/env python3
"""Validate and reconcile Ravenroot's immutable OCI release image."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path


REPOSITORY = "ghcr.io/ravenroot-ai/ravenroot"
PREDICATES = {
    "https://spdx.dev/Document",
    "https://slsa.dev/provenance/v1",
}


class OciRegistryError(ValueError):
    """Raised when local or remote OCI state violates the release contract."""


def read_json(path: Path) -> dict[str, object]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise OciRegistryError(f"cannot read OCI document {path}: {exc}") from exc
    if not isinstance(document, dict):
        raise OciRegistryError(f"OCI document is not an object: {path}")
    return document


def sha256(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return f"sha256:{result.hexdigest()}"


def blob(layout: Path, digest: str) -> Path:
    algorithm, separator, value = digest.partition(":")
    if separator != ":" or algorithm != "sha256" or len(value) != 64:
        raise OciRegistryError(f"unsupported OCI digest: {digest}")
    path = layout / "blobs" / algorithm / value
    if not path.is_file():
        raise OciRegistryError(f"OCI blob is missing: {digest}")
    if sha256(path) != digest:
        raise OciRegistryError(f"OCI blob digest mismatch: {digest}")
    return path


def descriptor_blob(layout: Path, descriptor: object, description: str) -> tuple[str, Path]:
    if not isinstance(descriptor, dict):
        raise OciRegistryError(f"{description} is not an OCI descriptor")
    digest = descriptor.get("digest")
    size = descriptor.get("size")
    if not isinstance(digest, str):
        raise OciRegistryError(f"{description} has no content digest")
    if not isinstance(size, int) or isinstance(size, bool) or size < 0:
        raise OciRegistryError(f"{description} has no valid content size")
    path = blob(layout, digest)
    if path.stat().st_size != size:
        raise OciRegistryError(f"OCI blob size mismatch: {digest}")
    return digest, path


def exactly_one(values: list[str], description: str) -> str:
    if len(values) != 1:
        raise OciRegistryError(f"expected one {description}, found {len(values)}")
    return values[0]


def validate_predicate(
    path: Path, predicate_type: str, image_digest: str, version: str
) -> None:
    statement = read_json(path)
    if statement.get("_type") != "https://in-toto.io/Statement/v0.1":
        raise OciRegistryError(f"{predicate_type} is not an in-toto statement")
    if statement.get("predicateType") != predicate_type:
        raise OciRegistryError(f"{predicate_type} annotation differs from its statement")
    subjects = statement.get("subject")
    if not isinstance(subjects, list) or len(subjects) != 1 or not isinstance(subjects[0], dict):
        raise OciRegistryError(f"{predicate_type} must identify exactly one image subject")
    subject = subjects[0]
    digest = subject.get("digest")
    expected_name = (
        f"pkg:docker/{REPOSITORY}@{version}?platform=linux%2Famd64"
    )
    if subject.get("name") != expected_name or not isinstance(digest, dict):
        raise OciRegistryError(f"{predicate_type} identifies the wrong image subject")
    if digest != {"sha256": image_digest.removeprefix("sha256:")}:
        raise OciRegistryError(f"{predicate_type} subject digest differs from the image")


def validate_local(layout: Path, version: str, commit: str) -> dict[str, str]:
    index = read_json(layout / "index.json")
    descriptors = index.get("manifests")
    if not isinstance(descriptors, list) or len(descriptors) != 1:
        raise OciRegistryError("OCI layout must contain exactly one tagged index")
    index_digest, index_path = descriptor_blob(layout, descriptors[0], "tagged OCI index")
    nested_index = read_json(index_path)
    manifests = nested_index.get("manifests")
    if not isinstance(manifests, list):
        raise OciRegistryError("tagged OCI document is not an image index")

    image_descriptors = [
        item
        for item in manifests
        if isinstance(item, dict)
        and isinstance(item.get("platform"), dict)
        and item["platform"].get("architecture") == "amd64"
        and item["platform"].get("os") == "linux"
    ]
    if len(image_descriptors) != 1:
        raise OciRegistryError(f"expected one linux/amd64 image, found {len(image_descriptors)}")
    image_digest, image_path = descriptor_blob(
        layout, image_descriptors[0], "linux/amd64 image"
    )
    image_manifest = read_json(image_path)
    config = image_manifest.get("config")
    _config_digest, config_path = descriptor_blob(layout, config, "image config")
    image_config = read_json(config_path)
    layers = image_manifest.get("layers")
    if not isinstance(layers, list):
        raise OciRegistryError("image manifest has no layers")
    for layer in layers:
        descriptor_blob(layout, layer, "image layer")
    config_body = image_config.get("config")
    labels = config_body.get("Labels") if isinstance(config_body, dict) else None
    expected_labels = {
        "org.opencontainers.image.source": "https://github.com/ravenroot-ai/ravenroot",
        "org.opencontainers.image.revision": commit,
        "org.opencontainers.image.version": version,
        "org.opencontainers.image.licenses": "Apache-2.0",
        "org.opencontainers.image.documentation": "https://docs.ravenroot.ai",
    }
    if not isinstance(labels, dict):
        raise OciRegistryError("image config has no OCI labels")
    for name, expected in expected_labels.items():
        if labels.get(name) != expected:
            raise OciRegistryError(
                f"OCI label {name} must be {expected!r}, found {labels.get(name)!r}"
            )

    attestations: list[dict[str, object]] = []
    for item in manifests:
        if not isinstance(item, dict):
            continue
        annotations = item.get("annotations")
        if not isinstance(annotations, dict):
            continue
        if (
            annotations.get("vnd.docker.reference.type") == "attestation-manifest"
            and annotations.get("vnd.docker.reference.digest") == image_digest
        ):
            attestations.append(item)
    if len(attestations) != 1:
        raise OciRegistryError(
            f"expected one image attestation manifest, found {len(attestations)}"
        )
    attestation_digest, attestation_path = descriptor_blob(
        layout, attestations[0], "image attestation manifest"
    )
    attestation = read_json(attestation_path)
    descriptor_blob(layout, attestation.get("config"), "attestation config")
    layers = attestation.get("layers")
    if not isinstance(layers, list):
        raise OciRegistryError("attestation manifest has no predicate layers")
    predicates: dict[str, Path] = {}
    for layer in layers:
        _digest, path = descriptor_blob(layout, layer, "attestation predicate")
        annotations = layer.get("annotations") if isinstance(layer, dict) else None
        predicate_type = (
            annotations.get("in-toto.io/predicate-type")
            if isinstance(annotations, dict)
            else None
        )
        if not isinstance(predicate_type, str) or predicate_type in predicates:
            raise OciRegistryError("attestation predicate annotations are missing or duplicated")
        predicates[predicate_type] = path
    if set(predicates) != PREDICATES:
        raise OciRegistryError(
            f"OCI attestation predicate set differs: {sorted(set(predicates))}"
        )
    for predicate_type, path in predicates.items():
        validate_predicate(path, predicate_type, image_digest, version)

    return {
        "index_digest": index_digest,
        "image_digest": image_digest,
        "attestation_digest": attestation_digest,
    }


def inspect(reference: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["skopeo", "inspect", "--raw", f"docker://{reference}"],
        check=False,
        capture_output=True,
        text=True,
    )


def remote_absent(result: subprocess.CompletedProcess[str]) -> bool:
    message = result.stderr.lower()
    return result.returncode != 0 and any(
        marker in message
        for marker in ("manifest unknown", "name unknown", "not found", "status code 404")
    )


def verify_manifest_digest(raw: str, expected_digest: str) -> None:
    manifest = raw.rstrip("\n").encode("utf-8")
    actual = f"sha256:{hashlib.sha256(manifest).hexdigest()}"
    if actual != expected_digest:
        raise OciRegistryError(
            f"immutable GHCR content differs: expected {expected_digest}, found {actual}"
        )


def remote_identity(raw: str, expected_image_digest: str) -> tuple[str, str]:
    try:
        document = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise OciRegistryError("GHCR returned an invalid OCI index") from exc
    manifests = document.get("manifests") if isinstance(document, dict) else None
    if not isinstance(manifests, list):
        raise OciRegistryError("GHCR tag does not resolve to an OCI image index")
    images = [
        str(item.get("digest"))
        for item in manifests
        if isinstance(item, dict)
        and isinstance(item.get("platform"), dict)
        and item["platform"].get("architecture") == "amd64"
        and item["platform"].get("os") == "linux"
    ]
    image_digest = exactly_one(images, "published linux/amd64 image")
    if image_digest != expected_image_digest:
        raise OciRegistryError(
            f"immutable GHCR image differs: expected {expected_image_digest}, found {image_digest}"
        )
    attestations = [
        str(item.get("digest"))
        for item in manifests
        if isinstance(item, dict)
        and isinstance(item.get("annotations"), dict)
        and item["annotations"].get("vnd.docker.reference.type") == "attestation-manifest"
        and item["annotations"].get("vnd.docker.reference.digest") == image_digest
    ]
    return image_digest, exactly_one(attestations, "published image attestation manifest")


def verify_remote_attestation(digest: str) -> None:
    result = inspect(f"{REPOSITORY}@{digest}")
    if result.returncode != 0:
        raise OciRegistryError(f"cannot read published OCI attestations: {result.stderr.strip()}")
    verify_manifest_digest(result.stdout, digest)
    try:
        document = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise OciRegistryError("GHCR returned an invalid attestation manifest") from exc
    layers = document.get("layers") if isinstance(document, dict) else None
    if not isinstance(layers, list):
        raise OciRegistryError("published attestation manifest has no predicate layers")
    predicates = [
        str(annotations.get("in-toto.io/predicate-type"))
        for layer in layers
        if isinstance(layer, dict)
        for annotations in [layer.get("annotations")]
        if isinstance(annotations, dict)
    ]
    if len(predicates) != len(PREDICATES) or set(predicates) != PREDICATES:
        raise OciRegistryError(
            f"published OCI attestation predicate set differs: {sorted(predicates)}"
        )


def reconcile(archive: Path, version: str, expected_image_digest: str) -> dict[str, str]:
    if not archive.is_file():
        raise OciRegistryError(f"OCI archive is missing: {archive}")
    algorithm, separator, value = expected_image_digest.partition(":")
    if separator != ":" or algorithm != "sha256" or len(value) != 64:
        raise OciRegistryError("expected OCI image digest is not sha256")
    tag_reference = f"{REPOSITORY}:{version}"
    current = inspect(tag_reference)
    if current.returncode == 0:
        _image, attestation = remote_identity(current.stdout, expected_image_digest)
        verify_remote_attestation(attestation)
    elif remote_absent(current):
        copied = subprocess.run(
            [
                "skopeo",
                "copy",
                "--all",
                f"oci-archive:{archive}",
                f"docker://{tag_reference}",
            ],
            check=False,
        )
        if copied.returncode != 0:
            raise OciRegistryError("GHCR publication failed")
    else:
        raise OciRegistryError(f"cannot determine GHCR state: {current.stderr.strip()}")

    published = inspect(tag_reference)
    if published.returncode != 0:
        raise OciRegistryError(f"cannot verify published GHCR tag: {published.stderr.strip()}")
    _image, attestation = remote_identity(published.stdout, expected_image_digest)
    verify_remote_attestation(attestation)
    published_index = published.stdout.rstrip("\n").encode("utf-8")
    index_digest = f"sha256:{hashlib.sha256(published_index).hexdigest()}"
    digest_reference = f"{REPOSITORY}@{index_digest}"
    immutable = inspect(digest_reference)
    if immutable.returncode != 0:
        raise OciRegistryError(
            f"published digest is not addressable: {immutable.stderr.strip()}"
        )
    verify_manifest_digest(immutable.stdout, index_digest)
    return {"digest": index_digest, "reference": digest_reference}


def write_outputs(values: dict[str, str]) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as output:
            for key, value in values.items():
                output.write(f"{key}={value}\n")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    commands = result.add_subparsers(dest="command", required=True)
    local = commands.add_parser("validate-local")
    local.add_argument("--layout", type=Path, required=True)
    local.add_argument("--version", required=True)
    local.add_argument("--commit", required=True)
    publish = commands.add_parser("reconcile")
    publish.add_argument("--archive", type=Path, required=True)
    publish.add_argument("--version", required=True)
    publish.add_argument("--digest", required=True)
    return result


def main() -> int:
    arguments = parser().parse_args()
    try:
        if arguments.command == "validate-local":
            values = validate_local(arguments.layout, arguments.version, arguments.commit)
        else:
            values = reconcile(arguments.archive, arguments.version, arguments.digest)
    except (OciRegistryError, OSError) as exc:
        print(f"OCI registry verification failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(values, sort_keys=True))
    write_outputs(values)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
