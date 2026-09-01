#!/usr/bin/env python3
"""Offline regression tests for the OCR OCI attestation verifier."""

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("verify_attestations.py")
SPEC = importlib.util.spec_from_file_location("verify_attestations", SCRIPT)
VERIFY = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(VERIFY)
DEFAULT = object()


class ReadProbe:
    def __init__(self, stream, calls: list[int], returned: list[int] | None = None):
        self.stream = stream
        self.calls = calls
        self.returned = returned

    def read(self, size: int = -1) -> bytes:
        self.calls.append(size)
        if size < 0:
            raise AssertionError("verifier attempted an unbounded read")
        data = self.stream.read(size)
        if self.returned is not None:
            self.returned.append(len(data))
        return data

    def __enter__(self):
        self.stream.__enter__()
        return self

    def __exit__(self, *arguments):
        return self.stream.__exit__(*arguments)

    def __getattr__(self, name):
        return getattr(self.stream, name)


class AttestationVerificationTest(unittest.TestCase):
    def write_blob(self, layout: Path, data: bytes, media_type: str) -> dict:
        digest = "sha256:" + hashlib.sha256(data).hexdigest()
        path = layout / "blobs" / "sha256" / digest[7:]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return {"mediaType": media_type, "digest": digest, "size": len(data)}

    def write_json(self, layout: Path, value: object, media_type: str) -> dict:
        return self.write_blob(layout, json.dumps(value, sort_keys=True, separators=(",", ":")).encode(), media_type)

    def platform(self, layout: Path, architecture: str) -> dict:
        config = self.write_json(layout, {"architecture": architecture, "os": "linux"}, VERIFY.OCI_CONFIG)
        layer = self.write_blob(layout, b"synthetic-layer", "application/vnd.oci.image.layer.v1.tar+gzip")
        return self.write_json(layout, {"schemaVersion": 2, "config": config, "layers": [layer]}, VERIFY.OCI_MANIFEST)

    def predicate(self, predicate_type: str) -> dict:
        if predicate_type == "https://spdx.dev/Document":
            return {"spdxVersion": "SPDX-2.3", "dataLicense": "CC0-1.0", "SPDXID": "SPDXRef-DOCUMENT",
                    "name": "sbom", "documentNamespace": "https://example.invalid/spdx",
                    "creationInfo": {"creators": ["Tool: Buildx"], "created": "2026-01-01T00:00:00Z"},
                    "packages": []}
        if predicate_type == "https://slsa.dev/provenance/v0.2":
            return {"builder": {"id": "https://example.invalid/buildkit"}, "buildType": "https://example.invalid/build",
                    "invocation": {}, "metadata": {"buildInvocationID": "build-1"}, "materials": []}
        return {"buildDefinition": {"buildType": VERIFY.BUILDKIT_SLSA_V1_BUILD_TYPE,
                                    "externalParameters": {"configSource": {"path": "Dockerfile"}},
                                    "resolvedDependencies": [{"uri": "pkg:docker/example/base@sha256:1",
                                                              "digest": {"sha256": "1" * 64}}]},
                "runDetails": {"builder": {"id": ""},
                               "metadata": {"invocationId": "build-1",
                                            "startedOn": "2026-01-01T00:00:00Z",
                                            "finishedOn": "2026-01-01T00:00:01Z"}}}

    def read_json_blob(self, layout: Path, item: dict) -> dict:
        path = layout / "blobs" / "sha256" / item["digest"][7:]
        return json.loads(path.read_text())

    def rewrite_json_blob(self, layout: Path, item: dict, value: dict) -> None:
        data = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
        digest = "sha256:" + hashlib.sha256(data).hexdigest()
        path = layout / "blobs" / "sha256" / digest[7:]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        item["digest"] = digest
        item["size"] = len(data)

    def corrupt_descriptor(self, layout: Path, metadata: Path, level: str, field: str) -> None:
        outer_path = layout / "index.json"
        outer = json.loads(outer_path.read_text())
        index_item = outer["manifests"][0]
        image_index = self.read_json_blob(layout, index_item)
        platform_item = next(item for item in image_index["manifests"] if "platform" in item)
        attestation_item = next(item for item in image_index["manifests"]
                                if item.get("annotations", {}).get(VERIFY.REFERENCE_TYPE) == VERIFY.ATTESTATION_TYPE)
        platform_manifest = self.read_json_blob(layout, platform_item)
        attestation_manifest = self.read_json_blob(layout, attestation_item)
        targets = {
            "image index": index_item,
            "platform manifest": platform_item,
            "platform config": platform_manifest["config"],
            "platform layer": platform_manifest["layers"][0],
            "attestation manifest": attestation_item,
            "attestation config": attestation_manifest["config"],
            "statement layer": attestation_manifest["layers"][0],
        }
        target = targets[level]
        if field == "digest":
            target[field] = "sha256:" + "0" * 64
        elif field == "size":
            target[field] += 1
        else:
            target[field] = "application/x-tampered"

        if level in {"platform config", "platform layer"}:
            self.rewrite_json_blob(layout, platform_item, platform_manifest)
        elif level in {"attestation config", "statement layer"}:
            self.rewrite_json_blob(layout, attestation_item, attestation_manifest)
        if level not in {"image index"}:
            self.rewrite_json_blob(layout, index_item, image_index)
        outer_path.write_text(json.dumps(outer, sort_keys=True, separators=(",", ":")))
        metadata_value = json.loads(metadata.read_text())
        metadata_value["containerimage.digest"] = index_item["digest"]
        metadata.write_text(json.dumps(metadata_value, sort_keys=True, separators=(",", ":")))

    def attestation(self, layout: Path, predicate_types: list[str], *, statement_subject: object = None,
                    predicate: object = None, statement_type: object = VERIFY.IN_TOTO_STATEMENT,
                    layer_media_type: str = VERIFY.IN_TOTO_LAYER) -> dict:
        if statement_subject is None:
            statement_subject = []
        statements = [self.write_json(layout, {"_type": statement_type, "subject": statement_subject,
                                                "predicateType": predicate_type,
                                                "predicate": self.predicate(predicate_type) if predicate is None else predicate}, layer_media_type)
                      for predicate_type in predicate_types]
        # Buildx emits an image config (rather than an empty-config descriptor) for its
        # multi-layer SBOM/provenance attestation manifest.
        config = self.write_json(layout, {"architecture": "unknown", "os": "unknown"}, VERIFY.OCI_CONFIG)
        return self.write_json(layout, {"schemaVersion": 2, "config": config, "layers": statements}, VERIFY.OCI_MANIFEST)

    def layout(self, *, include_sbom: bool = True, platforms: int = 1, attest_platforms: set[int] | None = None,
               statement_subject: object = None, predicate: object = None, predicate_type: object = None,
               annotation_subject: object = DEFAULT,
               statement_type: object = VERIFY.IN_TOTO_STATEMENT, layer_media_type: str = VERIFY.IN_TOTO_LAYER,
               provenance_type: str = "https://slsa.dev/provenance/v1") -> tuple[Path, Path, list[str]]:
        temporary = Path(tempfile.mkdtemp())
        layout = temporary / "layout"
        layout.mkdir()
        platform_descriptors = [self.platform(layout, "amd64" if index == 0 else "arm64")
                                for index in range(platforms)]
        subjects = [item["digest"] for item in platform_descriptors]
        descriptors = [dict(item, platform={"os": "linux", "architecture": "amd64" if index == 0 else "arm64"})
                       for index, item in enumerate(platform_descriptors)]
        for index in (set(range(platforms)) if attest_platforms is None else attest_platforms):
            current_subject = subjects[index]
            kinds = [provenance_type]
            if include_sbom:
                kinds.append("https://spdx.dev/Document")
            attestation = self.attestation(layout, [predicate_type or kind for kind in kinds],
                                           statement_subject=statement_subject, predicate=predicate,
                                           statement_type=statement_type, layer_media_type=layer_media_type)
            annotations = {VERIFY.REFERENCE_TYPE: VERIFY.ATTESTATION_TYPE}
            if annotation_subject is DEFAULT:
                annotations[VERIFY.REFERENCE_DIGEST] = current_subject
            elif annotation_subject is not None:
                annotations[VERIFY.REFERENCE_DIGEST] = annotation_subject
            descriptors.append(dict(attestation, annotations=annotations))
        image_index = self.write_json(layout, {"schemaVersion": 2, "manifests": descriptors}, VERIFY.OCI_INDEX)
        (layout / "index.json").write_text(json.dumps({"schemaVersion": 2, "manifests": [image_index]},
                                                        sort_keys=True, separators=(",", ":")))
        metadata = temporary / "metadata.json"
        metadata.write_text(json.dumps({"containerimage.digest": image_index["digest"]}))
        return layout, metadata, subjects

    def test_accepts_buildx_representative_sbom_and_provenance_for_exact_platform_subject(self) -> None:
        layout, metadata, _ = self.layout()
        report = VERIFY.inspect(layout, metadata)
        self.assertEqual(1, len(report["subjects"]))
        self.assertEqual(["provenance", "sbom"], report["subjects"][0]["attestations"])
        layout, metadata, _ = self.layout(provenance_type="https://slsa.dev/provenance/v0.2")
        self.assertEqual(1, len(VERIFY.inspect(layout, metadata)["subjects"]))

    def test_rejects_missing_attestation_and_unattested_platform(self) -> None:
        layout, metadata, _ = self.layout(include_sbom=False)
        with self.assertRaisesRegex(ValueError, "missing sbom"):
            VERIFY.inspect(layout, metadata)
        layout, metadata, _ = self.layout(platforms=2, attest_platforms={0})
        with self.assertRaisesRegex(ValueError, "missing provenance, sbom"):
            VERIFY.inspect(layout, metadata)

    def test_rejects_tampered_referenced_blob_and_wrapper_mismatch(self) -> None:
        layout, metadata, subjects = self.layout()
        (layout / "blobs" / "sha256" / subjects[0][7:]).write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "declared size|descriptor"):
            VERIFY.inspect(layout, metadata)
        layout, metadata, _ = self.layout()
        wrapper = json.loads((layout / "index.json").read_text())
        wrapper["manifests"][0]["digest"] = "sha256:" + "0" * 64
        (layout / "index.json").write_text(json.dumps(wrapper))
        with self.assertRaisesRegex(ValueError, "wrapper digest"):
            VERIFY.inspect(layout, metadata)

    def test_rejects_wrong_or_missing_platform_subject_and_empty_or_misleading_predicate(self) -> None:
        layout, metadata, _ = self.layout(annotation_subject="sha256:" + "0" * 64)
        with self.assertRaisesRegex(ValueError, "non-image subject"):
            VERIFY.inspect(layout, metadata)
        layout, metadata, _ = self.layout(annotation_subject=None)
        with self.assertRaisesRegex(ValueError, "no valid platform subject"):
            VERIFY.inspect(layout, metadata)
        layout, metadata, _ = self.layout(statement_subject=[{"digest": {"sha256": "0" * 64}}])
        with self.assertRaisesRegex(ValueError, "subject does not equal"):
            VERIFY.inspect(layout, metadata)
        layout, metadata, _ = self.layout(predicate={})
        with self.assertRaisesRegex(ValueError, "non-empty structured predicate"):
            VERIFY.inspect(layout, metadata)
        layout, metadata, _ = self.layout(predicate_type="https://attacker.invalid/spdx-provenance")
        with self.assertRaisesRegex(ValueError, "unsupported attestation predicate"):
            VERIFY.inspect(layout, metadata)

    def test_rejects_wrong_in_toto_type_statement_layer_and_malformed_wrapper(self) -> None:
        layout, metadata, _ = self.layout(statement_type="https://in-toto.io/Statement/v1")
        with self.assertRaisesRegex(ValueError, "in-toto _type"):
            VERIFY.inspect(layout, metadata)
        layout, metadata, _ = self.layout(layer_media_type="application/json")
        with self.assertRaisesRegex(ValueError, "unexpected media type"):
            VERIFY.inspect(layout, metadata)
        layout, metadata, _ = self.layout()
        (layout / "index.json").write_text(json.dumps({"schemaVersion": 2, "manifests": []}))
        with self.assertRaisesRegex(ValueError, "exactly one"):
            VERIFY.inspect(layout, metadata)

    def test_rejects_arbitrary_or_incomplete_spdx_and_slsa_predicates(self) -> None:
        subject = "sha256:" + "1" * 64
        statement = {"_type": VERIFY.IN_TOTO_STATEMENT, "subject": [],
                     "predicateType": "https://spdx.dev/Document", "predicate": {"arbitrary": True}}
        with self.assertRaisesRegex(ValueError, "SPDX spdxVersion"):
            VERIFY.predicate_kind(statement, subject)
        spdx = self.predicate("https://spdx.dev/Document")
        del spdx["creationInfo"]
        statement["predicate"] = spdx
        with self.assertRaisesRegex(ValueError, "SPDX creationInfo"):
            VERIFY.predicate_kind(statement, subject)

        for predicate_type, missing_field, expected in [
                ("https://slsa.dev/provenance/v0.2", "metadata", "SLSA v0.2 metadata"),
                ("https://slsa.dev/provenance/v1", "runDetails", "SLSA v1 runDetails")]:
            predicate = self.predicate(predicate_type)
            del predicate[missing_field]
            statement = {"_type": VERIFY.IN_TOTO_STATEMENT, "subject": [],
                         "predicateType": predicate_type, "predicate": predicate}
            with self.assertRaisesRegex(ValueError, expected):
                VERIFY.predicate_kind(statement, subject)
            statement["predicate"] = {"arbitrary": True}
            with self.assertRaisesRegex(ValueError, "must be an object"):
                VERIFY.predicate_kind(statement, subject)

    def test_spdx_version_and_document_constants_are_exact(self) -> None:
        subject = "sha256:" + "1" * 64
        for version in ("SPDX-2.3",):
            statement = {"_type": VERIFY.IN_TOTO_STATEMENT, "subject": [],
                         "predicateType": "https://spdx.dev/Document",
                         "predicate": self.predicate("https://spdx.dev/Document")}
            statement["predicate"]["spdxVersion"] = version
            self.assertEqual("sbom", VERIFY.predicate_kind(statement, subject))
        for version in ("SPDX-not-a-version", "SPDX-2.2", "SPDX-3.0", "spdx-2.3", ""):
            statement["predicate"]["spdxVersion"] = version
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, "supported SPDX version"):
                VERIFY.predicate_kind(statement, subject)
        statement["predicate"] = self.predicate("https://spdx.dev/Document")
        for field, value, expected in [("SPDXID", "SPDXRef-other", "SPDXRef-DOCUMENT"),
                                       ("dataLicense", "MIT", "CC0-1.0")]:
            statement["predicate"] = self.predicate("https://spdx.dev/Document")
            statement["predicate"][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, expected):
                VERIFY.predicate_kind(statement, subject)

    def test_empty_slsa_v1_builder_id_is_only_the_complete_buildkit_exception(self) -> None:
        subject = "sha256:" + "1" * 64
        base = {"_type": VERIFY.IN_TOTO_STATEMENT, "subject": [],
                "predicateType": "https://slsa.dev/provenance/v1",
                "predicate": self.predicate("https://slsa.dev/provenance/v1")}
        self.assertEqual("provenance", VERIFY.predicate_kind(base, subject))
        mutations = [
            ("wrong build type", lambda p: p["buildDefinition"].__setitem__("buildType", "https://example.invalid/build")),
            ("empty external parameters", lambda p: p["buildDefinition"].__setitem__("externalParameters", {})),
            ("missing dependencies", lambda p: p["buildDefinition"].__setitem__("resolvedDependencies", [])),
            ("bad dependency", lambda p: p["buildDefinition"].__setitem__("resolvedDependencies", [{}])),
            ("missing invocation", lambda p: p["runDetails"]["metadata"].pop("invocationId")),
            ("missing start", lambda p: p["runDetails"]["metadata"].pop("startedOn")),
            ("missing finish", lambda p: p["runDetails"]["metadata"].pop("finishedOn")),
            ("non-string id", lambda p: p["runDetails"]["builder"].__setitem__("id", None)),
        ]
        for label, mutate in mutations:
            statement = json.loads(json.dumps(base))
            mutate(statement["predicate"])
            with self.subTest(label=label), self.assertRaises(ValueError):
                VERIFY.predicate_kind(statement, subject)

    def test_rejects_every_descriptor_digest_size_and_media_type_tamper(self) -> None:
        levels = ("image index", "platform manifest", "platform config", "platform layer",
                  "attestation manifest", "attestation config", "statement layer")
        for level in levels:
            for field in ("digest", "size", "mediaType"):
                layout, metadata, _ = self.layout()
                self.corrupt_descriptor(layout, metadata, level, field)
                with self.subTest(level=level, field=field), self.assertRaises(ValueError):
                    VERIFY.inspect(layout, metadata)

    def test_materialized_blobs_read_only_declared_plus_one_without_unbounded_apis(self) -> None:
        temporary = Path(tempfile.mkdtemp())
        layout = temporary / "layout"
        layout.mkdir()
        backing = b"{" + b" " * (VERIFY.MAXIMUM_JSON_BLOB_BYTES - 1) + b"}"
        for declared_size in (1, VERIFY.MAXIMUM_JSON_BLOB_BYTES):
            media_type = VERIFY.OCI_CONFIG
            item = self.write_blob(layout, backing, media_type)
            item["size"] = declared_size
            calls: list[int] = []
            returned: list[int] = []
            opens = 0
            original_open = Path.open

            def monitored_open(path, *arguments, **keywords):
                nonlocal opens
                opens += 1
                return ReadProbe(original_open(path, *arguments, **keywords), calls, returned)

            with patch.object(Path, "read_bytes", side_effect=AssertionError("read_bytes is forbidden")), \
                    patch.object(Path, "open", new=monitored_open):
                with self.assertRaisesRegex(ValueError, "declared size"):
                    VERIFY.blob(layout, item, "lying config", {media_type}, materialize=True)
            self.assertTrue(calls)
            self.assertEqual(1, opens)
            self.assertNotIn(-1, calls)
            self.assertEqual(declared_size + 1, sum(returned))

        oversized = b"x" * (VERIFY.MAXIMUM_JSON_BLOB_BYTES + 1)
        for media_type, label in [(VERIFY.OCI_INDEX, "image index"), (VERIFY.OCI_CONFIG, "platform config"),
                                  (VERIFY.IN_TOTO_LAYER, "in-toto statement")]:
            item = self.write_blob(layout, oversized, media_type)
            with self.assertRaisesRegex(ValueError, "materialization limit"):
                VERIFY.blob(layout, item, label, {media_type}, materialize=True)

    def test_root_index_and_metadata_exact_limit_and_plus_one_are_one_open_bounded(self) -> None:
        layout, metadata, _ = self.layout()
        root = layout / "index.json"

        def pad_to(path: Path, size: int) -> None:
            data = path.read_bytes()
            self.assertLessEqual(len(data), size)
            path.write_bytes(data + b" " * (size - len(data)))

        pad_to(root, VERIFY.MAXIMUM_JSON_BLOB_BYTES)
        pad_to(metadata, VERIFY.MAXIMUM_JSON_BLOB_BYTES)
        calls: list[int] = []
        open_counts: dict[Path, int] = {}
        original_open = Path.open

        def monitored_open(path, *arguments, **keywords):
            open_counts[path] = open_counts.get(path, 0) + 1
            return ReadProbe(original_open(path, *arguments, **keywords), calls)

        with patch.object(Path, "read_bytes", side_effect=AssertionError("read_bytes is forbidden")), \
                patch.object(Path, "stat", side_effect=AssertionError("stat is forbidden")), \
                patch.object(Path, "open", new=monitored_open):
            self.assertEqual(1, len(VERIFY.inspect(layout, metadata)["subjects"]))
        self.assertTrue(calls)
        self.assertNotIn(-1, calls)
        self.assertEqual(1, open_counts[root])
        self.assertEqual(1, open_counts[metadata])

        for _, label in ((root, "OCI wrapper index"), (metadata, "Buildx metadata")):
            layout, metadata, _ = self.layout()
            path = layout / "index.json" if label.startswith("OCI") else metadata
            pad_to(path, VERIFY.MAXIMUM_JSON_BLOB_BYTES + 1)
            calls = []
            ignored_calls: list[int] = []
            original_open = Path.open
            def monitored_target_open(opened_path, *arguments, **keywords):
                recorded = calls if opened_path == path else ignored_calls
                return ReadProbe(original_open(opened_path, *arguments, **keywords), recorded)
            with patch.object(Path, "read_bytes", side_effect=AssertionError("read_bytes is forbidden")), \
                    patch.object(Path, "stat", side_effect=AssertionError("stat is forbidden")), \
                    patch.object(Path, "open", new=monitored_target_open):
                with self.subTest(label=label), self.assertRaisesRegex(ValueError, "materialization limit"):
                    VERIFY.inspect(layout, metadata)
            self.assertNotIn(-1, calls)
            self.assertLessEqual(sum(calls), VERIFY.MAXIMUM_JSON_BLOB_BYTES + 1)

    def test_streams_large_opaque_image_layers_without_read_bytes(self) -> None:
        temporary = Path(tempfile.mkdtemp())
        layout = temporary / "layout"
        layout.mkdir()
        layer_type = "application/vnd.oci.image.layer.v1.tar+gzip"
        item = self.write_blob(layout, b"x" * (VERIFY.MAXIMUM_JSON_BLOB_BYTES + 1), layer_type)
        with patch.object(Path, "read_bytes", side_effect=AssertionError("opaque layer was materialized")):
            _, data = VERIFY.blob(layout, item, "opaque image layer", {layer_type})
        self.assertIsNone(data)


if __name__ == "__main__":
    unittest.main()
