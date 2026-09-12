import hashlib
import json
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from scripts.central_registry import RegistryError, build_bundle, publish_bundle, validate_bundle
from scripts.github_release import (
    GitHubReleaseError,
    reconcile_assets,
    release,
    verify_release,
)
from scripts.oci_registry import (
    OciRegistryError,
    remote_absent,
    reconcile,
    validate_downloaded_attestation,
    validate_local,
    verify_public,
    verify_remote_attestation,
)


VERSION = "0.1.0-alpha.1"
COMMIT = "a" * 40


def json_bytes(document):
    return json.dumps(document, sort_keys=True, separators=(",", ":")).encode("utf-8")


def add_blob(layout: Path, document) -> str:
    contents = json_bytes(document)
    digest = hashlib.sha256(contents).hexdigest()
    path = layout / "blobs" / "sha256" / digest
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(contents)
    return f"sha256:{digest}"


def add_descriptor(layout: Path, document, **values):
    digest = add_blob(layout, document)
    size = (layout / "blobs" / digest.replace(":", "/")).stat().st_size
    return {"digest": digest, "size": size, **values}


def make_layout(
    root: Path,
    *,
    version: str = VERSION,
    predicates=None,
    subject_version: str | None = None,
    statement_predicate_type: str | None = None,
) -> str:
    predicates = predicates or {
        "https://spdx.dev/Document",
        "https://slsa.dev/provenance/v1",
    }
    labels = {
        "org.opencontainers.image.source": "https://github.com/ravenroot-ai/ravenroot",
        "org.opencontainers.image.revision": COMMIT,
        "org.opencontainers.image.version": version,
        "org.opencontainers.image.licenses": "Apache-2.0",
        "org.opencontainers.image.documentation": "https://docs.ravenroot.ai",
    }
    config = add_descriptor(root, {"config": {"Labels": labels}})
    image = add_descriptor(root, {"config": config, "layers": []})
    image_digest = image["digest"]
    subject = {
        "name": (
            "pkg:docker/ghcr.io/ravenroot-ai/ravenroot@"
            f"{subject_version or version}?platform=linux%2Famd64"
        ),
        "digest": {"sha256": image_digest.removeprefix("sha256:")},
    }
    predicate_layers = [
        add_descriptor(
            root,
            {
                "_type": "https://in-toto.io/Statement/v0.1",
                "predicateType": statement_predicate_type or predicate,
                "subject": [subject],
                "predicate": {},
            },
            annotations={"in-toto.io/predicate-type": predicate},
        )
        for predicate in sorted(predicates)
    ]
    attestation_config = add_descriptor(root, {"architecture": "unknown", "os": "unknown"})
    attestation = add_descriptor(
        root,
        {"config": attestation_config, "layers": predicate_layers},
        annotations={
            "vnd.docker.reference.digest": image_digest,
            "vnd.docker.reference.type": "attestation-manifest",
        },
    )
    index = add_descriptor(
        root,
        {
            "manifests": [
                {
                    **image,
                    "platform": {"architecture": "amd64", "os": "linux"},
                },
                {
                    **attestation,
                },
            ]
        },
    )
    (root / "index.json").write_bytes(json_bytes({"manifests": [index]}))
    return index["digest"]


def make_attestation_layout(root: Path, **values):
    make_layout(root, **values)
    outer = json.loads((root / "index.json").read_text(encoding="utf-8"))
    index_digest = outer["manifests"][0]["digest"]
    index = json.loads(
        (root / "blobs" / index_digest.replace(":", "/")).read_text(encoding="utf-8")
    )
    image = index["manifests"][0]
    attestation = index["manifests"][1]
    (root / "index.json").write_bytes(json_bytes({"manifests": [attestation]}))
    return attestation, image["digest"]


class OciRegistryTest(unittest.TestCase):
    def test_validates_exact_local_identity_and_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            layout = Path(directory)
            digest = make_layout(layout)
            result = validate_local(layout, VERSION, COMMIT)
            self.assertEqual(result["index_digest"], digest)

    def test_rejects_version_and_predicate_mismatches(self):
        with tempfile.TemporaryDirectory() as directory:
            layout = Path(directory)
            make_layout(layout, version="9.9.9")
            with self.assertRaisesRegex(OciRegistryError, "image.version"):
                validate_local(layout, VERSION, COMMIT)
        with tempfile.TemporaryDirectory() as directory:
            layout = Path(directory)
            make_layout(layout, predicates={"https://spdx.dev/Document"})
            with self.assertRaisesRegex(OciRegistryError, "predicate set differs"):
                validate_local(layout, VERSION, COMMIT)

    def test_rejects_corrupt_or_mismatched_predicate_content(self):
        with tempfile.TemporaryDirectory() as directory:
            layout = Path(directory)
            make_layout(layout)
            index = json.loads((layout / "index.json").read_text())
            index_blob = layout / "blobs" / index["manifests"][0]["digest"].replace(":", "/")
            document = json.loads(index_blob.read_text())
            image = document["manifests"][0]
            layer = layout / "blobs" / image["digest"].replace(":", "/")
            layer.write_bytes(b"corrupt")
            with self.assertRaisesRegex(OciRegistryError, "digest mismatch"):
                validate_local(layout, VERSION, COMMIT)

        with tempfile.TemporaryDirectory() as directory:
            layout = Path(directory)
            make_layout(layout, subject_version="9.9.9")
            with self.assertRaisesRegex(OciRegistryError, "wrong image subject"):
                validate_local(layout, VERSION, COMMIT)

    def test_only_authoritative_manifest_absence_allows_publication(self):
        authoritative = (
            "reading manifest 0.2.0-alpha.1 in ghcr.io/ravenroot-ai/ravenroot: "
            "manifest unknown"
        )
        self.assertTrue(remote_absent(subprocess.CompletedProcess([], 1, "", authoritative)))
        underscored = (
            "Error parsing image name: reading manifest release in "
            "ghcr.io/ravenroot-ai/ravenroot: MANIFEST_UNKNOWN: unknown tag"
        )
        self.assertTrue(remote_absent(subprocess.CompletedProcess([], 1, "", underscored)))
        skopeo_log = (
            'time="2026-01-01T00:00:00Z" level=fatal msg="Error parsing image name '
            '\\"docker://ghcr.io/ravenroot-ai/ravenroot:0.2.0-alpha.1\\": reading '
            "manifest 0.2.0-alpha.1 in ghcr.io/ravenroot-ai/ravenroot: manifest unknown\""
        )
        self.assertTrue(remote_absent(subprocess.CompletedProcess([], 1, "", skopeo_log)))
        self.assertFalse(remote_absent(subprocess.CompletedProcess([], 0, "{}", authoritative)))

    def test_local_and_transport_failures_are_never_registry_absence(self):
        failures = (
            "credential helper executable not found",
            "error loading registries configuration: file not found",
            "dial tcp: lookup ghcr.io: no such host",
            "connection refused",
            "i/o timeout",
            "TLS handshake timeout",
            "x509: certificate signed by unknown authority",
            "unauthorized: authentication required",
            "denied: permission denied",
            "status code 404: not found",
            "name unknown",
            "credential helper failed: reading manifest release in "
            "ghcr.io/other/repository: manifest unknown",
            "credential helper failed: reading manifest release in "
            "ghcr.io/ravenroot-ai/ravenroot: manifest unknown",
            "reading manifest release in ghcr.io/ravenroot-ai/ravenroot: "
            "manifest unknown\nunauthorized: authentication required",
        )
        for message in failures:
            with self.subTest(message=message):
                self.assertFalse(remote_absent(subprocess.CompletedProcess([], 1, "", message)))

    def test_downloaded_attestation_verifies_predicate_blobs_and_subjects(self):
        with tempfile.TemporaryDirectory() as directory:
            layout = Path(directory)
            descriptor, image_digest = make_attestation_layout(layout)
            validate_downloaded_attestation(layout, descriptor, VERSION, image_digest)

    def test_downloaded_attestation_rejects_wrong_subject_and_missing_or_corrupt_blob(self):
        with tempfile.TemporaryDirectory() as directory:
            layout = Path(directory)
            descriptor, image_digest = make_attestation_layout(
                layout, subject_version="9.9.9"
            )
            with self.assertRaisesRegex(OciRegistryError, "wrong image subject"):
                validate_downloaded_attestation(layout, descriptor, VERSION, image_digest)

        for state in ("missing", "corrupt"):
            with self.subTest(state=state), tempfile.TemporaryDirectory() as directory:
                layout = Path(directory)
                descriptor, image_digest = make_attestation_layout(layout)
                manifest = json.loads(
                    (layout / "blobs" / descriptor["digest"].replace(":", "/")).read_text()
                )
                predicate = layout / "blobs" / manifest["layers"][0]["digest"].replace(":", "/")
                if state == "missing":
                    predicate.unlink()
                else:
                    predicate.write_bytes(b"corrupt")
                with self.assertRaisesRegex(OciRegistryError, "blob is missing|digest mismatch"):
                    validate_downloaded_attestation(layout, descriptor, VERSION, image_digest)

    def test_downloaded_attestation_rejects_descriptor_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            layout = Path(directory)
            descriptor, image_digest = make_attestation_layout(layout)
            remote = {**descriptor, "size": descriptor["size"] + 1}
            with self.assertRaisesRegex(OciRegistryError, "descriptor differs"):
                validate_downloaded_attestation(layout, remote, VERSION, image_digest)

    def test_downloaded_attestation_rejects_predicate_type_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            layout = Path(directory)
            descriptor, image_digest = make_attestation_layout(
                layout, statement_predicate_type="https://example.invalid/predicate"
            )
            with self.assertRaisesRegex(OciRegistryError, "annotation differs"):
                validate_downloaded_attestation(layout, descriptor, VERSION, image_digest)

    @mock.patch("scripts.oci_registry.subprocess.run")
    def test_remote_attestation_is_downloaded_by_digest_before_validation(self, run):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            source.mkdir()
            descriptor, image_digest = make_attestation_layout(source)

            def copy_attestation(arguments, **_kwargs):
                destination = arguments[-1].removeprefix("oci:").rsplit(":", 1)[0]
                shutil.copytree(source, destination)
                return subprocess.CompletedProcess(arguments, 0, "", "")

            run.side_effect = copy_attestation
            verify_remote_attestation(descriptor, VERSION, image_digest)
            arguments = run.call_args.args[0]
            self.assertIn("--preserve-digests", arguments)
            self.assertIn(
                f"docker://ghcr.io/ravenroot-ai/ravenroot@{descriptor['digest']}",
                arguments,
            )

    @mock.patch("scripts.oci_registry.verify_remote_attestation")
    @mock.patch("scripts.oci_registry.inspect")
    def test_existing_exact_digest_is_verified_without_copy(self, inspect, verify_attestation):
        image_digest = "sha256:" + "1" * 64
        attestation_raw = json.dumps(
            {
                "layers": [
                    {"annotations": {"in-toto.io/predicate-type": predicate}}
                    for predicate in ("https://spdx.dev/Document", "https://slsa.dev/provenance/v1")
                ]
            },
            separators=(",", ":"),
        )
        attestation_digest = f"sha256:{hashlib.sha256(attestation_raw.encode()).hexdigest()}"
        raw = json.dumps(
            {
                "manifests": [
                    {
                        "digest": image_digest,
                        "size": 1,
                        "platform": {"architecture": "amd64", "os": "linux"},
                    },
                    {
                        "digest": attestation_digest,
                        "size": len(attestation_raw.encode()),
                        "annotations": {
                            "vnd.docker.reference.digest": image_digest,
                            "vnd.docker.reference.type": "attestation-manifest",
                        },
                    },
                ]
            },
            separators=(",", ":"),
        )
        inspect.side_effect = [
            subprocess.CompletedProcess([], 0, raw, ""),
            subprocess.CompletedProcess([], 0, raw, ""),
            subprocess.CompletedProcess([], 0, raw, ""),
        ]
        with tempfile.NamedTemporaryFile() as archive:
            result = reconcile(Path(archive.name), VERSION, image_digest)
            self.assertEqual(result["digest"], f"sha256:{hashlib.sha256(raw.encode()).hexdigest()}")
        self.assertEqual(verify_attestation.call_count, 2)
        self.assertTrue(
            all(":0.1.0-alpha.1@" not in call.args[0] for call in inspect.call_args_list)
        )

    @mock.patch("scripts.oci_registry.inspect")
    def test_existing_different_digest_fails_closed(self, inspect):
        raw = json.dumps(
            {
                "manifests": [
                    {
                        "digest": "sha256:" + "1" * 64,
                        "size": 1,
                        "platform": {"architecture": "amd64", "os": "linux"},
                    }
                ]
            }
        )
        inspect.return_value = subprocess.CompletedProcess([], 0, raw, "")
        with tempfile.NamedTemporaryFile() as archive:
            with self.assertRaisesRegex(OciRegistryError, "differs"):
                reconcile(Path(archive.name), VERSION, "sha256:" + "0" * 64)

    @mock.patch("scripts.oci_registry.validate_local")
    @mock.patch("scripts.oci_registry.subprocess.run")
    def test_public_gate_pulls_tag_and_digest_anonymously(self, run, validate):
        digest = "sha256:" + "4" * 64
        run.return_value = subprocess.CompletedProcess([], 0, "", "")
        validate.return_value = {"index_digest": digest}
        result = verify_public(VERSION, digest, COMMIT)
        self.assertEqual(result["digest"], digest)
        self.assertEqual(run.call_count, 2)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertTrue(all("--src-no-creds" in command for command in commands))
        self.assertTrue(all("--preserve-digests" in command for command in commands))
        self.assertIn(f"docker://ghcr.io/ravenroot-ai/ravenroot:{VERSION}", commands[0])
        self.assertIn(f"docker://ghcr.io/ravenroot-ai/ravenroot@{digest}", commands[1])

    @mock.patch("scripts.oci_registry.validate_local")
    @mock.patch("scripts.oci_registry.subprocess.run")
    def test_public_gate_rejects_either_reference_resolving_elsewhere(self, run, validate):
        digest = "sha256:" + "4" * 64
        run.return_value = subprocess.CompletedProcess([], 0, "", "")
        validate.side_effect = [
            {"index_digest": digest},
            {"index_digest": "sha256:" + "5" * 64},
        ]
        with self.assertRaisesRegex(OciRegistryError, "different index digest"):
            verify_public(VERSION, digest, COMMIT)


class CentralBundleTest(unittest.TestCase):
    def build_bundle(self, root: Path, *, corrupt_checksum=False):
        payload = root / "ravenroot-core.jar"
        payload.write_bytes(b"verified")
        primary = f"ai/ravenroot/ravenroot-core/{VERSION}/ravenroot-core-{VERSION}.jar"
        bundle = root / "central-bundle.zip"
        with zipfile.ZipFile(bundle, "w") as archive:
            archive.writestr(primary, payload.read_bytes())
            archive.writestr(f"{primary}.asc", b"signature")
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                checksum = hashlib.new(algorithm, payload.read_bytes()).hexdigest()
                if corrupt_checksum and algorithm == "sha256":
                    checksum = "0" * len(checksum)
                archive.writestr(f"{primary}.{algorithm}", checksum)
        return payload, bundle

    @mock.patch("scripts.central_registry.local_payloads")
    def test_validates_exact_payload_signature_and_checksums(self, payloads):
        with tempfile.TemporaryDirectory() as directory:
            payload, bundle = self.build_bundle(Path(directory))
            payloads.return_value = [(f"ravenroot-core-{VERSION}.jar", payload)]
            verifier = mock.Mock()
            validate_bundle(bundle, VERSION, verifier)
            verifier.assert_called_once_with(b"verified", b"signature")

    @mock.patch("scripts.central_registry.local_payloads")
    def test_rejects_bundle_checksum_mismatch(self, payloads):
        with tempfile.TemporaryDirectory() as directory:
            payload, bundle = self.build_bundle(Path(directory), corrupt_checksum=True)
            payloads.return_value = [(f"ravenroot-core-{VERSION}.jar", payload)]
            with self.assertRaisesRegex(RegistryError, "checksum differs"):
                validate_bundle(bundle, VERSION, lambda *_: None)

    @mock.patch("scripts.central_registry.local_payloads")
    def test_builds_deterministic_bundle_from_verified_outputs(self, payloads):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = root / f"ravenroot-core-{VERSION}.jar"
            payload.write_bytes(b"verified")
            Path(f"{payload}.asc").write_bytes(b"signature")
            payloads.return_value = [(payload.name, payload)]
            first = root / "first.zip"
            second = root / "second.zip"
            build_bundle(first, VERSION)
            build_bundle(second, VERSION)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            validate_bundle(first, VERSION, lambda *_: None)

    @mock.patch("scripts.central_registry.compare_local")
    @mock.patch("scripts.central_registry.central_state", return_value={"state": "complete"})
    @mock.patch("scripts.central_registry.validate_bundle")
    @mock.patch("scripts.central_registry.upload_bundle")
    def test_complete_immutable_release_is_verified_without_upload(
        self, upload, _validate, _state, compare
    ):
        publish_bundle(Path("bundle.zip"), VERSION)
        upload.assert_not_called()
        compare.assert_called_once_with(VERSION)

    @mock.patch("scripts.central_registry.validate_bundle")
    @mock.patch("scripts.central_registry.central_state", return_value={"state": "partial"})
    def test_partial_immutable_release_fails_closed(self, _state, _validate):
        with self.assertRaisesRegex(RegistryError, "only part"):
            publish_bundle(Path("bundle.zip"), VERSION)

    @mock.patch.dict(
        "os.environ", {"CENTRAL_USERNAME": "username", "CENTRAL_TOKEN": "token"}, clear=False
    )
    @mock.patch("scripts.central_registry.compare_local")
    @mock.patch(
        "scripts.central_registry.central_state",
        side_effect=[{"state": "absent"}, {"state": "complete"}],
    )
    @mock.patch("scripts.central_registry.validate_bundle")
    @mock.patch("scripts.central_registry.wait_for_deployment")
    @mock.patch("scripts.central_registry.upload_bundle", return_value="deployment-id")
    def test_absent_release_uploads_once_then_verifies(
        self, upload, wait, _validate, _state, compare
    ):
        bundle = Path("bundle.zip")
        publish_bundle(bundle, VERSION)
        upload.assert_called_once_with(bundle, VERSION, "username", "token")
        wait.assert_called_once_with("deployment-id", "username", "token")
        compare.assert_called_once_with(VERSION)


class GitHubReleaseTest(unittest.TestCase):
    def document(self, notes: str, *, draft=True, assets=None):
        return {
            "tag_name": f"v{VERSION}",
            "name": f"Ravenroot {VERSION}",
            "body": notes,
            "draft": draft,
            "prerelease": True,
            "assets": assets or [],
        }

    def test_release_metadata_must_match_reviewed_notes(self):
        with tempfile.TemporaryDirectory() as directory:
            notes = Path(directory) / "notes.md"
            notes.write_text("reviewed\n", encoding="utf-8")
            verify_release(self.document("reviewed"), f"v{VERSION}", notes, True, allow_draft=True)
            with self.assertRaisesRegex(GitHubReleaseError, "different reviewed notes"):
                verify_release(self.document("changed"), f"v{VERSION}", notes, True, allow_draft=True)

    def test_published_release_cannot_gain_a_missing_asset(self):
        with tempfile.TemporaryDirectory() as directory:
            assets = Path(directory)
            (assets / "artifact.jar").write_bytes(b"payload")
            with self.assertRaisesRegex(GitHubReleaseError, "missing immutable asset"):
                reconcile_assets(self.document("reviewed", draft=False), assets, allow_upload=False)

    def test_unexpected_remote_asset_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(GitHubReleaseError, "unexpected assets"):
                reconcile_assets(
                    self.document("reviewed", assets=[{"name": "unexpected", "url": "url"}]),
                    Path(directory),
                    allow_upload=True,
                )

    @mock.patch.dict("os.environ", {"GITHUB_REPOSITORY": "ravenroot-ai/ravenroot"})
    @mock.patch("scripts.github_release.gh")
    def test_draft_release_is_discoverable_for_resume(self, gh):
        draft = self.document("reviewed")
        gh.return_value = subprocess.CompletedProcess([], 0, json.dumps([[draft]]), "")
        self.assertEqual(release(f"v{VERSION}"), draft)


if __name__ == "__main__":
    unittest.main()
