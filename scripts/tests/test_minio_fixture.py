from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from scripts.fixtures.minio import verify as minio_fixture


ROOT = Path(__file__).resolve().parents[2]
SERVER_TEST_DIGEST = "sha256:" + "1" * 64
CLIENT_TEST_DIGEST = "sha256:" + "2" * 64


def finalized_fixtures() -> tuple[minio_fixture.Fixture, ...]:
    fixtures = minio_fixture.load_manifest(allow_pending=True)
    return tuple(
        replace(fixture, project_digest={
            "server": SERVER_TEST_DIGEST,
            "client": CLIENT_TEST_DIGEST,
        }[fixture.role])
        for fixture in fixtures
    )


class MinioFixtureContractTest(unittest.TestCase):
    def test_canonical_manifest_is_complete_and_project_owned(self) -> None:
        fixtures = minio_fixture.load_manifest(allow_pending=True)
        self.assertEqual([fixture.role for fixture in fixtures], ["server", "client"])
        for fixture in fixtures:
            self.assertEqual(
                fixture.project_repository,
                "ghcr.io/ravenroot-ai/ravenroot-minio-acceptance",
            )
            if fixture.project_digest != minio_fixture.PENDING_DIGEST:
                self.assertRegex(fixture.project_digest, r"^sha256:[0-9a-f]{64}$")
            self.assertRegex(fixture.upstream_digest, r"^sha256:[0-9a-f]{64}$")
            self.assertEqual(set(fixture.platforms), {"linux/amd64", "linux/arm64"})
            self.assertEqual(fixture.license, "AGPL-3.0-only")
        if any(fixture.project_digest == minio_fixture.PENDING_DIGEST for fixture in fixtures):
            with self.assertRaisesRegex(minio_fixture.FixtureError, "has not been promoted"):
                minio_fixture.load_manifest()
        else:
            self.assertEqual(minio_fixture.load_manifest(), fixtures)

    def test_manifest_rejects_an_unowned_or_mutable_target(self) -> None:
        original = minio_fixture.MANIFEST.read_text(encoding="utf-8")
        server_digest = next(
            fixture.project_digest
            for fixture in minio_fixture.load_manifest(allow_pending=True)
            if fixture.role == "server"
        )
        cases = (
            ("ghcr.io/ravenroot-ai/ravenroot-minio-acceptance", "quay.io/minio/minio"),
            (f"server.project.digest={server_digest}", "server.project.digest=latest"),
            ("server.upstream.revision=ecde75f9112f8410cb6cacb4b76193f1475b587e",
             "server.upstream.revision=main"),
        )
        for before, after in cases:
            with self.subTest(after=after), tempfile.TemporaryDirectory() as directory:
                manifest = Path(directory) / "manifest.properties"
                manifest.write_text(original.replace(before, after), encoding="utf-8")
                with self.assertRaises(minio_fixture.FixtureError):
                    minio_fixture.load_manifest(manifest, allow_pending=True)

    def test_preflight_reports_role_reference_and_platform_without_environment(self) -> None:
        def unavailable(arguments):
            return subprocess.CompletedProcess(arguments, 1, "", "manifest unknown\nsecond line")

        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(
            minio_fixture.FixtureError,
            r"server fixture is unavailable at ghcr\.io/ravenroot-ai/.+ manifest unknown second line",
        ):
            minio_fixture.preflight(finalized_fixtures(), "linux/amd64", True, unavailable)

    def test_preflight_requires_the_requested_index_architecture(self) -> None:
        def arm_only(arguments):
            payload = {"manifests": [{"platform": {"os": "linux", "architecture": "arm64"}}]}
            return subprocess.CompletedProcess(arguments, 0, json.dumps(payload), "")

        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(
            minio_fixture.FixtureError, "does not contain linux/amd64"
        ):
            minio_fixture.preflight(finalized_fixtures(), "linux/amd64", False, arm_only)

    def test_registry_login_reads_an_explicit_token_only_from_stdin(self) -> None:
        token = "fixture-token-that-must-not-be-an-argument"
        completed = subprocess.CompletedProcess((), 0, "Login Succeeded", "")
        environment = {
            minio_fixture.REGISTRY_USER_ENV: "fixture-reader",
            minio_fixture.REGISTRY_TOKEN_ENV: token,
        }
        with patch.dict(os.environ, environment, clear=True), patch(
            "scripts.fixtures.minio.verify.subprocess.run", return_value=completed
        ) as run:
            minio_fixture.registry_login_if_configured()
        arguments = run.call_args.args[0]
        self.assertEqual(
            arguments,
            ("docker", "login", "ghcr.io", "--username", "fixture-reader", "--password-stdin"),
        )
        self.assertEqual(run.call_args.kwargs["input"], token)
        self.assertNotIn(token, arguments)

    def test_build_command_is_pinned_reproducible_and_repository_linked(self) -> None:
        fixture = finalized_fixtures()[0]
        command = minio_fixture.build_command(fixture, Path("metadata.json"), push=True)
        joined = " ".join(command)
        dockerfile = minio_fixture.DOCKERFILE.read_text(encoding="utf-8")
        self.assertIn(f"BASE_IMAGE={fixture.upstream_reference}", command)
        self.assertIn("linux/amd64,linux/arm64", command)
        self.assertIn("SOURCE_DATE_EPOCH=0", command)
        self.assertIn("BUILDKIT_MULTI_PLATFORM=1", command)
        self.assertIn("--provenance=false", command)
        self.assertIn("--sbom=false", command)
        self.assertIn("rewrite-timestamp=true", joined)
        self.assertIn(
            'org.opencontainers.image.source="https://github.com/ravenroot-ai/ravenroot"',
            dockerfile,
        )

    def test_java_fixture_has_no_independent_registry_authority(self) -> None:
        source = (ROOT / "ravenroot" / "ravenroot-extensions" / "ravenroot-object-storage" / "src" /
                  "test" / "java" / "ai" / "ravenroot" / "extensions" / "storage" /
                  "StorageMinioIntegrationTest.java").read_text(encoding="utf-8")
        self.assertNotIn("quay.io/", source)
        self.assertNotIn("bitnamilegacy/", source)
        self.assertNotIn("ghcr.io/", source)
        self.assertIn("FixtureImages.load()", source)
        self.assertIn("pullAndVerify()", source)

    def test_ci_preflights_before_the_backend_reactor_with_scoped_read(self) -> None:
        workflow = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        self.assertIn("minio-fixture-preflight:", workflow)
        self.assertIn("needs: [release-classification, minio-fixture-preflight]", workflow)
        self.assertIn("python3 scripts/fixtures/minio/verify.py preflight --platform linux/amd64 --pull", workflow)
        self.assertGreaterEqual(workflow.count("packages: read"), 2)
        self.assertGreaterEqual(workflow.count("RAVENROOT_FIXTURE_REGISTRY_TOKEN: ${{ github.token }}"), 2)
        self.assertNotIn("secrets.", workflow)

    def test_publication_is_manual_pinned_and_has_only_scoped_package_write(self) -> None:
        workflow = (ROOT / ".github" / "workflows" / "publish-minio-fixtures.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("\n  push:", workflow)
        self.assertIn("permissions: {}", workflow)
        self.assertIn("contents: read\n      packages: write", workflow)
        self.assertIn("moby/buildkit@sha256:", workflow)
        self.assertIn("RAVENROOT_FIXTURE_REGISTRY_TOKEN: ${{ github.token }}", workflow)
        self.assertIn("build --role server --push", workflow)
        self.assertIn("build --role client --push", workflow)
        self.assertNotIn("secrets.", workflow)


if __name__ == "__main__":
    unittest.main()
