from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.fixtures.minio import verify as minio_fixture


ROOT = Path(__file__).resolve().parents[2]


class MinioFixtureContractTest(unittest.TestCase):
    def test_canonical_manifest_is_complete_and_project_owned(self) -> None:
        fixtures = minio_fixture.load_manifest()
        self.assertEqual([fixture.role for fixture in fixtures], ["server", "client"])
        for fixture in fixtures:
            self.assertTrue(fixture.project_reference.startswith(
                "ghcr.io/ravenroot-ai/ravenroot-minio-fixtures@sha256:"))
            self.assertEqual(fixture.project_digest, fixture.upstream_digest)
            self.assertEqual(set(fixture.platforms), {"linux/amd64", "linux/arm64"})
            self.assertEqual(fixture.license, "AGPL-3.0-only")

    def test_manifest_rejects_an_unowned_or_mutable_target(self) -> None:
        original = minio_fixture.MANIFEST.read_text(encoding="utf-8")
        cases = (
            ("ghcr.io/ravenroot-ai/ravenroot-minio-fixtures", "quay.io/minio/minio"),
            ("server.project.digest=sha256:451fe6858cb770cc9d0e77ba811ce287420f781c7c1b806a386f6896471a349c",
             "server.project.digest=latest"),
            ("server.upstream.revision=ecde75f9112f8410cb6cacb4b76193f1475b587e",
             "server.upstream.revision=main"),
        )
        for before, after in cases:
            with self.subTest(after=after), tempfile.TemporaryDirectory() as directory:
                manifest = Path(directory) / "manifest.properties"
                manifest.write_text(original.replace(before, after), encoding="utf-8")
                with self.assertRaises(minio_fixture.FixtureError):
                    minio_fixture.load_manifest(manifest)

    def test_preflight_reports_role_reference_and_platform_without_environment(self) -> None:
        fixtures = minio_fixture.load_manifest()

        def unavailable(arguments):
            return subprocess.CompletedProcess(arguments, 1, "", "manifest unknown\nsecond line")

        with self.assertRaisesRegex(
            minio_fixture.FixtureError,
            r"server fixture is unavailable at ghcr\.io/ravenroot-ai/.+ manifest unknown second line",
        ):
            minio_fixture.preflight(fixtures, "linux/amd64", True, unavailable)

    def test_preflight_requires_the_requested_index_architecture(self) -> None:
        fixtures = minio_fixture.load_manifest()

        def arm_only(arguments):
            payload = {"manifests": [{"platform": {"os": "linux", "architecture": "arm64"}}]}
            return subprocess.CompletedProcess(arguments, 0, json.dumps(payload), "")

        with self.assertRaisesRegex(minio_fixture.FixtureError, "does not contain linux/amd64"):
            minio_fixture.preflight(fixtures, "linux/amd64", False, arm_only)

    def test_java_fixture_has_no_independent_registry_authority(self) -> None:
        source = (ROOT / "ravenroot" / "ravenroot-extensions" / "ravenroot-object-storage" / "src" /
                  "test" / "java" / "ai" / "ravenroot" / "extensions" / "storage" /
                  "StorageMinioIntegrationTest.java").read_text(encoding="utf-8")
        self.assertNotIn("quay.io/", source)
        self.assertNotIn("bitnamilegacy/", source)
        self.assertNotIn("ghcr.io/", source)
        self.assertIn("FixtureImages.load()", source)
        self.assertIn("pullAndVerify()", source)

    def test_ci_preflights_before_the_backend_reactor(self) -> None:
        workflow = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        self.assertIn("minio-fixture-preflight:", workflow)
        self.assertIn("needs: [release-classification, minio-fixture-preflight]", workflow)
        self.assertIn("python3 scripts/fixtures/minio/verify.py preflight --platform linux/amd64 --pull", workflow)
        self.assertNotIn("packages: read", workflow)

    def test_publication_is_manual_and_has_only_scoped_package_write(self) -> None:
        workflow = (ROOT / ".github" / "workflows" / "publish-minio-fixtures.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("\n  push:", workflow)
        self.assertIn("permissions: {}", workflow)
        self.assertIn("contents: read\n      packages: write", workflow)
        self.assertIn("password-stdin", workflow)
        self.assertNotIn("secrets.", workflow)


if __name__ == "__main__":
    unittest.main()
