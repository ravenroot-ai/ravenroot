#!/usr/bin/env python3
"""Verify digest-addressed Buildx OCR SBOM and provenance attestations offline.

This deliberately follows and re-hashes the complete local OCI path: wrapper index, image index,
platform manifest, attestation manifest and in-toto statement. It never trusts a filename merely
because it resembles a digest.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import sys


OCI_INDEX = "application/vnd.oci.image.index.v1+json"
OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json"
OCI_CONFIG = "application/vnd.oci.image.config.v1+json"
OCI_EMPTY_CONFIG = "application/vnd.oci.empty.v1+json"
IN_TOTO_LAYER = "application/vnd.in-toto+json"
IN_TOTO_STATEMENT = "https://in-toto.io/Statement/v0.1"
ATTESTATION_TYPE = "attestation-manifest"
REFERENCE_TYPE = "vnd.docker.reference.type"
REFERENCE_DIGEST = "vnd.docker.reference.digest"
SBOM_PREDICATES = {"https://spdx.dev/Document"}
PROVENANCE_PREDICATES = {"https://slsa.dev/provenance/v0.2", "https://slsa.dev/provenance/v1"}
MAXIMUM_JSON_BLOB_BYTES = 8 * 1024 * 1024
STREAM_CHUNK_BYTES = 64 * 1024
SUPPORTED_SPDX_VERSIONS = frozenset({"SPDX-2.3"})
BUILDKIT_SLSA_V1_BUILD_TYPE = \
    "https://github.com/moby/buildkit/blob/master/docs/attestations/slsa-definitions.md"


def fail(message: str) -> None:
    raise ValueError(message)


def valid_digest(value: object) -> bool:
    return isinstance(value, str) and value.startswith("sha256:") and len(value) == 71 and all(
        character in "0123456789abcdef" for character in value[7:])


def read_json_bytes(data: bytes | bytearray, label: str) -> object:
    try:
        return json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"cannot read JSON {label}: {error}")


def bounded_materialization(path: Path, label: str, maximum: int, *, declared_size: int | None = None,
                            expected_digest: str | None = None) -> bytearray:
    """Open once and retain no more than maximum bytes, with one-byte overflow detection."""
    if declared_size is not None and declared_size > maximum:
        fail(f"{label} exceeds the {maximum}-byte JSON materialization limit")
    expected_size = maximum if declared_size is None else declared_size
    retained = bytearray()
    hasher = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            while len(retained) < expected_size:
                chunk = stream.read(min(STREAM_CHUNK_BYTES, expected_size - len(retained)))
                if not chunk:
                    break
                retained.extend(chunk)
                hasher.update(chunk)
            extra = stream.read(1)
    except OSError as error:
        fail(f"cannot read {label}: {error}")
    if extra:
        if declared_size is None:
            fail(f"{label} exceeds the {maximum}-byte JSON materialization limit")
        fail(f"{label} blob does not match its declared size")
    if declared_size is not None and len(retained) != declared_size:
        fail(f"{label} blob does not match its declared size")
    if expected_digest is not None and "sha256:" + hasher.hexdigest() != expected_digest:
        fail(f"{label} blob does not match descriptor {expected_digest}")
    return retained


def read_json_path(path: Path, label: str) -> object:
    return read_json_bytes(bounded_materialization(path, label, MAXIMUM_JSON_BLOB_BYTES), label)


def descriptor(value: object, label: str, expected_media_types: set[str]) -> dict:
    if not isinstance(value, dict):
        fail(f"{label} descriptor is malformed")
    digest, size, media_type = value.get("digest"), value.get("size"), value.get("mediaType")
    if not valid_digest(digest) or not isinstance(size, int) or isinstance(size, bool) or size < 0:
        fail(f"{label} descriptor has invalid digest or size")
    if media_type not in expected_media_types:
        fail(f"{label} descriptor has unexpected media type {media_type!r}")
    return value


def blob(layout: Path, item: object, label: str, expected_media_types: set[str], *, materialize: bool = False) \
        -> tuple[dict, bytes | bytearray | None]:
    item = descriptor(item, label, expected_media_types)
    digest = item["digest"]
    path = layout / "blobs" / "sha256" / digest[7:]
    if materialize and item["size"] > MAXIMUM_JSON_BLOB_BYTES:
        fail(f"{label} blob exceeds the {MAXIMUM_JSON_BLOB_BYTES}-byte JSON materialization limit")
    actual_size = 0
    hasher = hashlib.sha256()
    try:
        if materialize:
            data = bounded_materialization(
                path, label, MAXIMUM_JSON_BLOB_BYTES,
                declared_size=item["size"], expected_digest=digest)
            return item, data
        with path.open("rb") as stream:
            data = None
            while chunk := stream.read(STREAM_CHUNK_BYTES):
                actual_size += len(chunk)
                hasher.update(chunk)
    except OSError as error:
        fail(f"cannot read {label} blob {digest}: {error}")
    actual = "sha256:" + hasher.hexdigest()
    if actual != digest or actual_size != item["size"]:
        fail(f"{label} blob does not match descriptor {digest}")
    return item, data


def json_blob(layout: Path, item: object, label: str, expected_media_types: set[str]) -> tuple[dict, dict]:
    item, data = blob(layout, item, label, expected_media_types, materialize=True)
    assert data is not None
    value = read_json_bytes(data, label)
    if not isinstance(value, dict):
        fail(f"{label} is not a JSON object")
    return item, value


def manifests(value: dict, label: str) -> list[dict]:
    if value.get("schemaVersion") != 2 or not isinstance(value.get("manifests"), list):
        fail(f"{label} is not an OCI manifest list")
    return value["manifests"]


def non_empty_string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value:
        fail(f"{label} must be a non-empty string")
    return value


def object_field(value: object, label: str) -> dict:
    if not isinstance(value, dict):
        fail(f"{label} must be an object")
    return value


def spdx_predicate(predicate: dict) -> None:
    if predicate.get("spdxVersion") not in SUPPORTED_SPDX_VERSIONS:
        fail("SPDX spdxVersion is not a supported SPDX version")
    if predicate.get("dataLicense") != "CC0-1.0":
        fail("SPDX dataLicense must be CC0-1.0")
    if predicate.get("SPDXID") != "SPDXRef-DOCUMENT":
        fail("SPDX SPDXID must be SPDXRef-DOCUMENT")
    non_empty_string(predicate.get("name"), "SPDX name")
    non_empty_string(predicate.get("documentNamespace"), "SPDX documentNamespace")
    creation = object_field(predicate.get("creationInfo"), "SPDX creationInfo")
    creators = creation.get("creators")
    if not isinstance(creators, list) or not creators or not all(isinstance(item, str) and item for item in creators):
        fail("SPDX creationInfo.creators must be a non-empty string list")
    non_empty_string(creation.get("created"), "SPDX creationInfo.created")
    if not isinstance(predicate.get("packages"), list):
        fail("SPDX packages must be an array")


def slsa_v02_predicate(predicate: dict) -> None:
    builder = object_field(predicate.get("builder"), "SLSA v0.2 builder")
    non_empty_string(builder.get("id"), "SLSA v0.2 builder.id")
    non_empty_string(predicate.get("buildType"), "SLSA v0.2 buildType")
    object_field(predicate.get("invocation"), "SLSA v0.2 invocation")
    metadata = object_field(predicate.get("metadata"), "SLSA v0.2 metadata")
    non_empty_string(metadata.get("buildInvocationID"), "SLSA v0.2 metadata.buildInvocationID")
    if not isinstance(predicate.get("materials"), list):
        fail("SLSA v0.2 materials must be an array")


def slsa_v1_predicate(predicate: dict) -> None:
    definition = object_field(predicate.get("buildDefinition"), "SLSA v1 buildDefinition")
    build_type = non_empty_string(definition.get("buildType"), "SLSA v1 buildDefinition.buildType")
    external = object_field(definition.get("externalParameters"), "SLSA v1 buildDefinition.externalParameters")
    dependencies = definition.get("resolvedDependencies")
    if not isinstance(dependencies, list):
        fail("SLSA v1 buildDefinition.resolvedDependencies must be an array")
    details = object_field(predicate.get("runDetails"), "SLSA v1 runDetails")
    builder = object_field(details.get("builder"), "SLSA v1 runDetails.builder")
    # BuildKit v1 emits an empty builder id for local exporters, but the field itself remains part
    # of the shape. Invocation metadata and the complete build definition are the non-empty bound.
    builder_id = builder.get("id")
    if not isinstance(builder_id, str):
        fail("SLSA v1 runDetails.builder.id must be a string")
    metadata = object_field(details.get("metadata"), "SLSA v1 runDetails.metadata")
    non_empty_string(metadata.get("invocationId"), "SLSA v1 runDetails.metadata.invocationId")
    if not builder_id:
        if build_type != BUILDKIT_SLSA_V1_BUILD_TYPE:
            fail("empty SLSA v1 builder.id is supported only for BuildKit v1")
        if not external:
            fail("BuildKit v1 empty builder.id requires external parameters")
        if not dependencies:
            fail("BuildKit v1 empty builder.id requires resolved dependencies")
        for position, dependency in enumerate(dependencies):
            dependency = object_field(dependency, f"BuildKit v1 resolved dependency {position}")
            non_empty_string(dependency.get("uri"), f"BuildKit v1 resolved dependency {position}.uri")
            digests = object_field(dependency.get("digest"), f"BuildKit v1 resolved dependency {position}.digest")
            if not digests or not all(isinstance(name, str) and name and isinstance(value, str) and value
                                      for name, value in digests.items()):
                fail(f"BuildKit v1 resolved dependency {position}.digest must be non-empty strings")
        non_empty_string(metadata.get("startedOn"), "BuildKit v1 runDetails.metadata.startedOn")
        non_empty_string(metadata.get("finishedOn"), "BuildKit v1 runDetails.metadata.finishedOn")


def image_layers(layout: Path, manifest: dict, label: str) -> None:
    if manifest.get("schemaVersion") != 2:
        fail(f"{label} is not schema version 2")
    json_blob(layout, manifest.get("config"), f"{label} config", {OCI_CONFIG})
    layers = manifest.get("layers")
    if not isinstance(layers, list):
        fail(f"{label} has no layers")
    permitted = {"application/vnd.oci.image.layer.v1.tar", "application/vnd.oci.image.layer.v1.tar+gzip",
                 "application/vnd.oci.image.layer.v1.tar+zstd"}
    for number, layer in enumerate(layers):
        blob(layout, layer, f"{label} layer {number}", permitted)


def predicate_kind(statement: dict, platform_digest: str) -> str:
    if statement.get("_type") != IN_TOTO_STATEMENT:
        fail("attestation statement has an unsupported in-toto _type")
    # Unnamed local OCI export produced by Buildx uses an empty in-toto subject and binds the
    # platform through the immutable descriptor annotation checked by inspect(). Named statements
    # must instead carry exactly that platform digest and cannot make a conflicting claim.
    subjects = statement.get("subject")
    if subjects != []:
        expected = {"sha256": platform_digest[7:]}
        if not isinstance(subjects, list) or len(subjects) != 1 or not isinstance(subjects[0], dict) \
                or subjects[0].get("digest") != expected:
            fail("attestation statement subject does not equal its platform manifest digest")
    predicate = statement.get("predicate")
    if not isinstance(predicate, dict) or not predicate:
        fail("attestation statement has no non-empty structured predicate")
    predicate_type = statement.get("predicateType")
    if predicate_type in SBOM_PREDICATES:
        spdx_predicate(predicate)
        return "sbom"
    if predicate_type == "https://slsa.dev/provenance/v0.2":
        slsa_v02_predicate(predicate)
        return "provenance"
    if predicate_type == "https://slsa.dev/provenance/v1":
        slsa_v1_predicate(predicate)
        return "provenance"
    fail(f"unsupported attestation predicate type {predicate_type!r}")


def attestation_kinds(layout: Path, item: dict, platform_digest: str) -> set[str]:
    _, manifest = json_blob(layout, item, "attestation manifest", {OCI_MANIFEST})
    if manifest.get("schemaVersion") != 2:
        fail("attestation manifest is not schema version 2")
    # Buildx currently emits an OCI image config for attestations; older exporters emit the OCI
    # empty config. Both are immutable descriptor-checked here, unlike the statement layer.
    if manifest.get("config", {}).get("mediaType") == OCI_CONFIG:
        json_blob(layout, manifest.get("config"), "attestation config", {OCI_CONFIG})
    else:
        json_blob(layout, manifest.get("config"), "attestation config", {OCI_EMPTY_CONFIG})
    layers = manifest.get("layers")
    if not isinstance(layers, list) or not layers:
        fail("attestation manifest has no statement layers")
    kinds = set()
    for position, layer in enumerate(layers):
        _, data = blob(layout, layer, f"in-toto statement {position}", {IN_TOTO_LAYER}, materialize=True)
        assert data is not None
        statement = read_json_bytes(data, f"in-toto statement {position}")
        if not isinstance(statement, dict):
            fail("in-toto statement is not an object")
        kinds.add(predicate_kind(statement, platform_digest))
    return kinds


def inspect(layout: Path, metadata: Path | None) -> dict:
    if metadata is None:
        fail("Buildx metadata is required to bind this OCI layout to its output digest")
    try:
        outer = read_json_path(layout / "index.json", "OCI wrapper index")
        metadata_value = read_json_path(metadata, "Buildx metadata")
    except OSError as error:
        fail(f"cannot read OCI wrapper or metadata: {error}")
    if not isinstance(outer, dict) or not isinstance(metadata_value, dict):
        fail("OCI wrapper or Buildx metadata is not an object")
    image_index_digest = metadata_value.get("containerimage.digest")
    if not valid_digest(image_index_digest):
        fail(f"Buildx image digest is malformed: {image_index_digest!r}")
    wrapper = manifests(outer, "OCI wrapper index")
    if len(wrapper) != 1:
        fail("OCI wrapper must contain exactly one image-index descriptor")
    wrapper_item = descriptor(wrapper[0], "OCI wrapper", {OCI_INDEX})
    if wrapper_item["digest"] != image_index_digest:
        fail("OCI wrapper digest does not equal Buildx metadata image digest")
    _, image_index = json_blob(layout, wrapper_item, "image index", {OCI_INDEX})

    subjects: dict[str, set[str]] = {}
    attestations: list[tuple[dict, str]] = []
    for position, item in enumerate(manifests(image_index, "image index")):
        if not isinstance(item, dict):
            fail("image index descriptor is malformed")
        annotations = item.get("annotations") or {}
        if not isinstance(annotations, dict):
            fail("image index descriptor annotations are malformed")
        if annotations.get(REFERENCE_TYPE) == ATTESTATION_TYPE:
            descriptor(item, f"attestation descriptor {position}", {OCI_MANIFEST})
            subject = annotations.get(REFERENCE_DIGEST)
            if not valid_digest(subject):
                fail("attestation descriptor has no valid platform subject digest")
            attestations.append((item, subject))
        elif isinstance(item.get("platform"), dict) and all(
                isinstance(item["platform"].get(key), str) and item["platform"][key]
                for key in ("os", "architecture")):
            subject, platform_manifest = json_blob(layout, item, f"platform manifest {position}", {OCI_MANIFEST})
            image_layers(layout, platform_manifest, f"platform manifest {position}")
            subjects[subject["digest"]] = set()
        else:
            fail("image index contains a descriptor that is neither a platform nor an attestation")
    if not subjects:
        fail("OCI layout has no platform image manifests")
    for item, subject in attestations:
        if subject not in subjects:
            fail(f"attestation refers to non-image subject {subject!r}")
        subjects[subject].update(attestation_kinds(layout, item, subject))

    checked_subjects = []
    for subject, kinds in sorted(subjects.items()):
        missing = {"sbom", "provenance"} - kinds
        if missing:
            fail(f"image subject {subject} is missing {', '.join(sorted(missing))} attestation")
        checked_subjects.append({"subjectDigest": subject, "attestations": sorted(kinds)})
    return {"imageIndexDigest": image_index_digest, "subjects": checked_subjects}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--layout", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--metadata", type=Path)
    arguments = parser.parse_args()
    try:
        report = inspect(arguments.layout, arguments.metadata)
        arguments.report.parent.mkdir(parents=True, exist_ok=True)
        temporary = arguments.report.with_suffix(arguments.report.suffix + ".tmp")
        temporary.write_text(json.dumps(report, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, arguments.report)
    except (OSError, ValueError) as error:
        print(f"OCR attestation verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
