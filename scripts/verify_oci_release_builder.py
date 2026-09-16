#!/usr/bin/env python3
"""Build a minimal release image and validate BuildKit's real attestation envelope."""

from __future__ import annotations

import argparse
import subprocess
import tempfile
from pathlib import Path

from oci_registry import validate_local


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.0.0-oci-contract"
COMMIT = "0" * 40
SBOM_GENERATOR = (
    "docker.io/docker/buildkit-syft-scanner:stable-1@"
    "sha256:ae4f3b554449e7e25548e7d8ccc029d17357348e30c6e3df01b92bc93654d6a9"
)


DOCKERFILE = """\
FROM scratch
ARG VERSION
ARG REVISION
LABEL org.opencontainers.image.source="https://github.com/ravenroot-ai/ravenroot" \\
      org.opencontainers.image.revision="$REVISION" \\
      org.opencontainers.image.version="$VERSION" \\
      org.opencontainers.image.licenses="Apache-2.0" \\
      org.opencontainers.image.documentation="https://docs.ravenroot.ai"
COPY payload /payload
"""


def verify(builder: str | None = None) -> dict[str, str]:
    with tempfile.TemporaryDirectory(prefix="ravenroot-oci-contract-") as directory:
        root = Path(directory)
        context = root / "context"
        context.mkdir()
        (context / "Dockerfile").write_text(DOCKERFILE, encoding="utf-8")
        (context / "payload").write_text("ravenroot release OCI contract\n", encoding="utf-8")
        archive = root / "image.tar"
        command = ["docker", "buildx", "build"]
        if builder:
            command.extend(("--builder", builder))
        command.extend(
            (
                "--file",
                str(context / "Dockerfile"),
                "--platform",
                "linux/amd64",
                "--tag",
                f"ghcr.io/ravenroot-ai/ravenroot:{VERSION}",
                "--build-arg",
                f"VERSION={VERSION}",
                "--build-arg",
                f"REVISION={COMMIT}",
                "--provenance=mode=max,reproducible=true",
                f"--sbom=generator={SBOM_GENERATOR}",
                f"--output=type=oci,dest={archive}",
                str(context),
            )
        )
        subprocess.run(command, cwd=REPOSITORY_ROOT, check=True)
        layout = root / "layout"
        layout.mkdir()
        subprocess.run(("tar", "-xf", str(archive), "-C", str(layout)), check=True)
        return validate_local(layout, VERSION, COMMIT)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--builder")
    arguments = parser.parse_args()
    result = verify(arguments.builder)
    print(
        "Validated the pinned BuildKit OCI contract: "
        f"index={result['index_digest']} image={result['image_digest']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
