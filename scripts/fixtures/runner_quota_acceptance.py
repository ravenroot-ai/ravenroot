#!/usr/bin/env python3
"""Fail-closed writable runner acceptance on an ephemeral GitHub-hosted Linux machine.

No packages are installed and no existing Docker daemon, filesystem or context is changed.
The only formatted object is this fixture's newly created bounded regular file. XFS project
quotas and a separate classic-overlay2 daemon are prerequisites, never inferred from flags.
"""
from __future__ import annotations

import grp
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
# Official Python 3.13.15 / Alpine 3.24, linux/amd64; immutable manifest inspected during review.
BASE_IMAGE = "python@sha256:46ee549c88617e9bc8acb843a326f1a5c0fa5608d7f9703509efe6d53b55f318"
TEST = "writableContainerDevelopmentCycleUsesRealWorkspaceAcrossEveryRestart"
TEST_CLASS = "ai.ravenroot.core.runtime.WorkspaceAgentRuntimeTest"
TOOLS = ("sudo", "dockerd", "docker", "mkfs.xfs", "mount", "umount", "findmnt", "mvn")


def prerequisites(environment: dict[str, str]) -> Path:
    if (platform.system() != "Linux" or platform.machine() != "x86_64"
            or environment.get("GITHUB_ACTIONS") != "true"
            or environment.get("RUNNER_ENVIRONMENT") != "github-hosted"):
        raise RuntimeError("quota acceptance requires the ephemeral GitHub-hosted Linux x86_64 job")
    for tool in TOOLS:
        if shutil.which(tool) is None:
            raise RuntimeError(f"quota acceptance prerequisite missing: {tool}; no packages are installed implicitly")
    parent = Path(environment["RUNNER_TEMP"]).resolve(strict=True)
    if not parent.is_dir() or parent == Path("/"):
        raise RuntimeError("an existing dedicated runner temporary directory is required")
    return parent


def daemon_arguments(directory: Path) -> list[str]:
    return ["sudo", "-n", "dockerd", "--config-file=" + str(directory / "daemon.json"),
            "--data-root=" + str(directory / "xfs" / "docker"), "--exec-root=" + str(directory / "exec"),
            "--pidfile=" + str(directory / "daemon.pid"), "--host=unix://" + str(directory / "docker.sock"),
            "--group=" + grp.getgrgid(os.getgid()).gr_name, "--storage-driver=overlay2",
            "--feature=containerd-snapshotter=false", "--bridge=none", "--iptables=false",
            "--ip6tables=false", "--ip-masq=false"]


def verify_report(path: Path) -> None:
    suite = ET.parse(path).getroot()
    cases = list(suite.iter("testcase"))
    # Surefire includes the JUnit @TempDir parameter type in the XML name, although
    # Maven's -Dtest selector takes the bare method name. Pin both identities;
    # never accept a prefix match or an unrelated green test from a stale report.
    if (suite.tag != "testsuite" or suite.get("name") != TEST_CLASS
            or any(suite.get(key) != value for key, value in
                   (("tests", "1"), ("errors", "0"), ("failures", "0"), ("skipped", "0")))
            or suite.get("flakes", "0") != "0"
            or len(cases) != 1 or cases[0] not in list(suite)
            or cases[0].get("classname") != TEST_CLASS
            or cases[0].get("name") != TEST + "(Path)"
            or any(child.tag not in ("system-out", "system-err") for child in cases[0])):
        raise RuntimeError("the writable nine-job acceptance did not execute successfully exactly once")


def run() -> None:
    parent = prerequisites(dict(os.environ))
    subprocess.run(["sudo", "-n", "true"], check=True, timeout=10)
    directory = Path(tempfile.mkdtemp(prefix="ravenroot-runner-quota-", dir=parent)).resolve()
    image_file = directory / "quota.img"
    mountpoint = directory / "xfs"
    daemon = None
    mounted = False
    environment = dict(os.environ)
    # Do not permit an ambient Docker context/TLS configuration to redirect the fixture or test.
    for key in ("DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"):
        environment.pop(key, None)
    environment.update(DOCKER_HOST="unix://" + str(directory / "docker.sock"), DOCKER_BUILDKIT="0")

    def command(arguments: list[str], timeout: int = 60) -> str:
        return subprocess.run(arguments, check=True, timeout=timeout, env=environment,
                              text=True, stdout=subprocess.PIPE).stdout.strip()

    try:
        with image_file.open("xb") as image:
            image.truncate(4 * 1024 * 1024 * 1024)
        mountpoint.mkdir()
        (directory / "daemon.json").write_text("{}\n", encoding="utf-8")
        command(["mkfs.xfs", "-f", "-n", "ftype=1", str(image_file)])
        command(["sudo", "-n", "mount", "-o", "loop,pquota", str(image_file), str(mountpoint)])
        mounted = True
        filesystem = json.loads(command(["findmnt", "--json", "--mountpoint", str(mountpoint), "-o", "FSTYPE,OPTIONS"]))["filesystems"][0]
        if filesystem["fstype"] != "xfs" or not {"prjquota", "pquota"}.intersection(filesystem["options"].split(",")):
            raise RuntimeError("the fixture filesystem does not enforce XFS project quotas")
        with (directory / "daemon.log").open("wb") as log:
            daemon = subprocess.Popen(daemon_arguments(directory), stdout=log, stderr=subprocess.STDOUT, env=environment)
        for _ in range(60):
            if daemon.poll() is not None:
                raise RuntimeError("the isolated quota daemon failed to start")
            try:
                info = json.loads(command(["docker", "info", "--format", "{{json .}}"], 5))
                break
            except subprocess.CalledProcessError:
                time.sleep(1)
        else:
            raise RuntimeError("the isolated quota daemon did not become ready within 60 seconds")
        if info["Driver"] != "overlay2" or Path(info["DockerRootDir"]).resolve() != mountpoint / "docker":
            raise RuntimeError("Docker is not using the fixture's classic overlay2 quota filesystem")
        print("RUNNER_QUOTA_SUBSTRATE=" + json.dumps({"driver": info["Driver"], "filesystem": filesystem,
              "dockerVersion": info["ServerVersion"], "baseImage": BASE_IMAGE}), flush=True)
        command(["docker", "pull", BASE_IMAGE], 180)
        tag = "ravenroot-quota-acceptance:fixture"
        command(["docker", "build", "--network=none", "--build-arg", "BASE_IMAGE=" + BASE_IMAGE,
                 "-t", tag, str(ROOT / "docs/examples/governed-runner")], 180)
        image = command(["docker", "image", "inspect", "--format={{.Id}}", tag])
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", image):
            raise RuntimeError("the fixture image does not have an immutable local identity")
        report = ROOT / "ravenroot/ravenroot-core/target/surefire-reports/TEST-ai.ravenroot.core.runtime.WorkspaceAgentRuntimeTest.xml"
        if report.exists():
            report.unlink()  # Exact fixture report only; a stale green report is not acceptance.
        subprocess.run(["mvn", "-B", "--no-transfer-progress", "-f", str(ROOT / "ravenroot/pom.xml"),
                        "-pl", "ravenroot-core", "-am", "-Dtest=WorkspaceAgentRuntimeTest#" + TEST,
                        "-Dsurefire.failIfNoSpecifiedTests=false", "-Dravenroot.runner.testWritableImage=" + image,
                        "-Dravenroot.runner.testDocker=" + str(Path(shutil.which("docker")).resolve()), "test"],
                       check=True, timeout=900, env=environment)
        verify_report(report)
        print("RUNNER_WRITABLE_NINE_JOB_ACCEPTANCE=passed", flush=True)
    finally:
        # Never address the host's default daemon or perform a global Docker prune.
        if daemon is not None and daemon.poll() is None:
            pid_file = directory / "daemon.pid"
            if not pid_file.is_file():
                daemon.terminate()
                daemon.wait(timeout=30)
            else:
                pid = int(pid_file.read_text().strip())
                arguments = Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0")
                if ("--data-root=" + str(mountpoint / "docker")).encode() not in arguments:
                    raise RuntimeError("refusing cleanup of a daemon outside this fixture")
                command(["sudo", "-n", "kill", "-TERM", str(pid)])
                daemon.wait(timeout=30)
        if mounted:
            command(["sudo", "-n", "umount", str(mountpoint)])
        if directory.parent != parent or not directory.name.startswith("ravenroot-runner-quota-"):
            raise RuntimeError("refusing cleanup outside the exact fixture directory")
        command(["sudo", "-n", "rm", "-rf", "--", str(directory)])


if __name__ == "__main__":
    run()
