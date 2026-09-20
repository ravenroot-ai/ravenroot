#!/usr/bin/env python3
"""Owner-only manual live-provider smoke. Never a CI, PR, merge or release prerequisite."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--owner-opt-in", action="store_true", required=True)
    parser.add_argument("--worker-config", type=Path, required=True)
    parser.add_argument("--image", required=True, help="immutable built Agent image sha256 on a quota-enforcing Linux daemon")
    arguments = parser.parse_args()
    if os.environ.get("CI") or os.environ.get("GITHUB_ACTIONS"):
        parser.error("live-provider smoke is forbidden in CI; use runner_quota_acceptance.py")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", arguments.image):
        parser.error("an immutable local Agent image ID is required")
    config = arguments.worker_config.resolve(strict=True)
    docker = shutil.which("docker")
    if docker is None:
        parser.error("docker is required")
    root = Path(__file__).resolve().parents[2]
    subprocess.run(["mvn", "-B", "--no-transfer-progress", "-f", str(root / "ravenroot/pom.xml"),
                    "-pl", "ravenroot-core", "-am", "-Dtest=WorkspaceAgentRuntimeTest#writableContainerDevelopmentCycleUsesRealWorkspaceAcrossEveryRestart",
                    "-Dsurefire.failIfNoSpecifiedTests=false", "-Dravenroot.runner.testWritableImage=" + arguments.image,
                    "-Dravenroot.runner.testAgentConfiguration=" + str(config), "-Dravenroot.runner.testDocker=" + docker,
                    "test"], check=True, timeout=900)
    print("Owner local smoke completed; attest the approved provider/model and observed report separately, without credentials.")


if __name__ == "__main__":
    main()
