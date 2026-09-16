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
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import importlib.util

_endpoint_spec = importlib.util.spec_from_file_location("model_protocol_endpoint", Path(__file__).with_name("model_protocol_endpoint.py"))
_endpoint_module = importlib.util.module_from_spec(_endpoint_spec)
_endpoint_spec.loader.exec_module(_endpoint_module)
ModelProtocolEndpoint = _endpoint_module.ModelProtocolEndpoint

ROOT = Path(__file__).resolve().parents[2]
# Official Python 3.13.15 / Alpine 3.24, linux/amd64; immutable manifest inspected during review.
BASE_IMAGE = "python@sha256:46ee549c88617e9bc8acb843a326f1a5c0fa5608d7f9703509efe6d53b55f318"
TEST = "writableContainerDevelopmentCycleUsesRealWorkspaceAcrossEveryRestart"
TEST_CLASS = "ai.ravenroot.core.runtime.WorkspaceAgentRuntimeTest"
TOOLS = ("sudo", "dockerd", "docker", "mkfs.xfs", "mount", "umount", "findmnt", "mvn")
DAEMON_READY_SECONDS = 60
DAEMON_PROBE_SECONDS = 5
DIAGNOSTIC_BYTES = 16 * 1024


def prerequisites(environment: dict[str, str]) -> Path:
    if (platform.system() != "Linux" or platform.machine() != "x86_64"
            or environment.get("GITHUB_ACTIONS") != "true"
            or environment.get("RUNNER_ENVIRONMENT") != "github-hosted"):
        raise RuntimeError("quota acceptance requires the ephemeral GitHub-hosted Linux x86_64 job")
    for tool in TOOLS:
        if shutil.which(tool) is None:
            raise RuntimeError(f"quota acceptance prerequisite missing: {tool}; no packages are installed implicitly")
    if environment.get("RAVENROOT_RUNNER_ACCEPTANCE_CONFIG"):
        raise RuntimeError("CI acceptance is secretless: external model configuration is forbidden")
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


def wait_for_daemon(daemon: subprocess.Popen, directory: Path, environment: dict[str, str]) -> dict:
    """Require a live server response, not just successful CLI template rendering."""
    deadline = time.monotonic() + DAEMON_READY_SECONDS
    last_failure = "no server response"
    observed = {}

    def fail(reason: str) -> None:
        # Emit evidence before run() tears down the private daemon and its log. Never
        # dump the environment or the full info object (which includes proxy settings).
        try:
            with (directory / "daemon.log").open("rb") as log:
                log.seek(0, os.SEEK_END)
                log.seek(max(0, log.tell() - DIAGNOSTIC_BYTES))
                tail = log.read(DIAGNOSTIC_BYTES).decode("utf-8", errors="replace")
        except OSError as error:
            tail = "daemon log unavailable: " + type(error).__name__
        print("RUNNER_QUOTA_STARTUP_FAILURE=" + json.dumps({
            "reason": reason, "daemonExitCode": daemon.poll(),
            "lastProbe": last_failure[:DIAGNOSTIC_BYTES], "server": observed,
            "daemonLogTail": tail,
        }), file=sys.stderr, flush=True)
        raise RuntimeError(reason)

    while True:
        if daemon.poll() is not None:
            fail("the isolated quota daemon exited before readiness")
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            fail("the isolated quota daemon did not become ready within 60 seconds")
        try:
            result = subprocess.run(["docker", "info", "--format", "{{json .}}"],
                                    check=False, timeout=min(DAEMON_PROBE_SECONDS, remaining),
                                    env=environment, text=True, capture_output=True)
            if daemon.poll() is not None:
                fail("the isolated quota daemon exited during readiness probe")
            if result.returncode:
                last_failure = "docker info exit " + str(result.returncode) + ": " + result.stderr[:DIAGNOSTIC_BYTES]
            else:
                info = json.loads(result.stdout)
                if not isinstance(info, dict):
                    last_failure = "docker info did not return an object"
                else:
                    fields = ("Driver", "DockerRootDir", "ServerVersion")
                    observed = {key: str(info.get(key, ""))[:1024] for key in fields}
                    # Docker 28 formats an empty server object and exits zero when
                    # its socket is not ready. ServerErrors and populated server
                    # fields, not CLI exit status alone, distinguish that response.
                    if info.get("ServerErrors"):
                        last_failure = "docker info server errors: " + str(info["ServerErrors"])[:DIAGNOSTIC_BYTES]
                    elif not all(isinstance(info.get(key), str) and info[key].strip() for key in fields):
                        last_failure = "docker info has incomplete server fields"
                    elif time.monotonic() >= deadline:
                        fail("the isolated quota daemon did not become ready within 60 seconds")
                    elif (info["Driver"] != "overlay2"
                          or Path(info["DockerRootDir"]).resolve() != directory / "xfs" / "docker"):
                        fail("Docker is not using the fixture's classic overlay2 quota filesystem")
                    else:
                        return info
        except subprocess.TimeoutExpired:
            last_failure = "docker info probe timed out"
        except json.JSONDecodeError:
            last_failure = "docker info returned invalid JSON"
        except OSError as error:
            last_failure = "docker info could not execute: " + type(error).__name__
            fail("the isolated quota daemon readiness probe could not execute")
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(1, remaining))


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
        raise RuntimeError("the hermetic model-protocol explicit Workspace acceptance did not execute successfully exactly once")


def mounted_paths() -> set[Path]:
    """Read this mount namespace, including Docker's surviving bind-mounted netns."""
    return {Path(re.sub(r"\\([0-7]{3})", lambda match: chr(int(match[1], 8)), line.split()[4]))
            for line in Path("/proc/self/mountinfo").read_text().splitlines()}


def cleanup(parent: Path, directory: Path, daemon: subprocess.Popen | None, command) -> None:
    """Stop the exact private daemon, then unmount its children before deleting owned files.

    A failed ownership/quiescence/unmount check retains the directory for diagnosis. Neither
    lazy unmount nor recursive deletion across a remaining mount is an acceptable fallback.
    """
    if (directory.parent != parent or not directory.name.startswith("ravenroot-runner-quota-")
            or directory.resolve(strict=True) != directory):
        raise RuntimeError("refusing cleanup outside the exact fixture directory")
    if daemon is not None:
        pid_file = directory / "daemon.pid"
        if pid_file.exists():
            pid = int(pid_file.read_text().strip())
            if pid <= 1:
                raise RuntimeError("refusing invalid private daemon identity")
            process = Path(f"/proc/{pid}/cmdline")
            try:
                arguments = process.read_bytes().split(b"\0")
            except FileNotFoundError:
                arguments = None  # Already exited; no signal is necessary.
            if arguments is not None:
                required = ("--data-root=" + str(directory / "xfs/docker"),
                            "--exec-root=" + str(directory / "exec"),
                            "--pidfile=" + str(pid_file), "--host=unix://" + str(directory / "docker.sock"))
                if not all(value.encode() in arguments for value in required):
                    raise RuntimeError("refusing cleanup of a daemon outside this fixture")
                command(["sudo", "-n", "kill", "-TERM", str(pid)])
                daemon.wait(timeout=30)
                if process.exists():
                    raise RuntimeError("private daemon shutdown is unconfirmed; retaining fixture")
        elif daemon.poll() is None:
            raise RuntimeError("private daemon identity is unavailable; retaining fixture")
        daemon.wait(timeout=30)
    owned = {path for path in mounted_paths() if path == directory or directory in path.parents}
    roots = (directory / "exec", directory / "xfs")
    if any(not any(path == root or root in path.parents for root in roots) for path in owned):
        raise RuntimeError("unexpected mount in fixture; refusing deletion")
    # dockerd can leave exec/netns/default mounted even after a graceful exit. Containers'
    # overlay mounts, if any, must likewise be detached before the backing XFS loop mount.
    for path in sorted(owned, key=lambda value: (len(value.parts), str(value)), reverse=True):
        command(["sudo", "-n", "umount", "--", str(path)])
    if any(path == directory or directory in path.parents for path in mounted_paths()):
        raise RuntimeError("fixture mounts remain; refusing deletion")
    command(["sudo", "-n", "rm", "-rf", "--one-file-system", "--", str(directory)])


def run() -> None:
    parent = prerequisites(dict(os.environ))
    subprocess.run(["sudo", "-n", "true"], check=True, timeout=10)
    directory = Path(tempfile.mkdtemp(prefix="ravenroot-runner-quota-", dir=parent)).resolve()
    image_file = directory / "quota.img"
    mountpoint = directory / "xfs"
    daemon = None
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
        filesystem = json.loads(command(["findmnt", "--json", "--mountpoint", str(mountpoint), "-o", "FSTYPE,OPTIONS"]))["filesystems"][0]
        if filesystem["fstype"] != "xfs" or not {"prjquota", "pquota"}.intersection(filesystem["options"].split(",")):
            raise RuntimeError("the fixture filesystem does not enforce XFS project quotas")
        with (directory / "daemon.log").open("wb") as log:
            daemon = subprocess.Popen(daemon_arguments(directory), stdout=log, stderr=subprocess.STDOUT, env=environment)
        info = wait_for_daemon(daemon, directory, environment)
        print("RUNNER_QUOTA_SUBSTRATE=" + json.dumps({"driver": info["Driver"], "filesystem": filesystem,
              "dockerVersion": info["ServerVersion"], "baseImage": BASE_IMAGE}), flush=True)
        command(["docker", "pull", BASE_IMAGE], 180)
        tag = "ravenroot-quota-acceptance:fixture"
        # Build-time package installation is trusted image construction, not Agent egress.
        # Every runtime is still network=none and carries neither workload nor model credentials.
        command(["docker", "build", "--network=host", "--build-arg", "BASE_IMAGE=" + BASE_IMAGE,
                 "-f", str(ROOT / "docs/examples/governed-runner/Agent.Dockerfile"),
                 "-t", tag, str(ROOT / "docs/examples/governed-runner")], 180)
        image = command(["docker", "image", "inspect", "--format={{.Id}}", tag])
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", image):
            raise RuntimeError("the fixture image does not have an immutable local identity")
        # Writable container root deliberately matches PER_WORKSPACE. Landlock/seccomp, not a
        # read-only Docker root or a cooperative model, must prevent the adversarial mutations.
        confinement = command(["docker", "run", "--rm", "--network=none", "--cap-drop=ALL",
                               "--security-opt=no-new-privileges", "--user=65532:65532", "--pids-limit=32",
                               "--memory=128m", "--cpus=1", "--storage-opt", "size=64m",
                               "--entrypoint=python3", image, "/opt/security_acceptance.py"])
        if not confinement.startswith("RUNNER_NATIVE_READ_ONLY_CONFINEMENT="):
            raise RuntimeError("native confinement did not produce its positive evidence")
        print(confinement, flush=True)
        report = ROOT / "ravenroot/ravenroot-core/target/surefire-reports/TEST-ai.ravenroot.core.runtime.WorkspaceAgentRuntimeTest.xml"
        if report.exists():
            report.unlink()  # Exact fixture report only; a stale green report is not acceptance.
        with ModelProtocolEndpoint() as model:
            config = directory / "hermetic-model.json"
            config.write_text(json.dumps(model.configuration()), encoding="utf-8")
            subprocess.run(["mvn", "-B", "--no-transfer-progress", "-f", str(ROOT / "ravenroot/pom.xml"),
                        "-pl", "ravenroot-core", "-am", "-Dtest=WorkspaceAgentRuntimeTest#" + TEST,
                        "-Dsurefire.failIfNoSpecifiedTests=false", "-Dravenroot.runner.testWritableImage=" + image,
                        "-Dravenroot.runner.testAgentConfiguration=" + str(config),
                        "-Dravenroot.runner.testHermeticModel=true",
                        "-Dravenroot.runner.testDocker=" + str(Path(shutil.which("docker")).resolve()), "test"],
                           check=True, timeout=900, env=environment)
            if model.requests == 0:
                raise RuntimeError("production model gateway never called the hermetic endpoint")
        verify_report(report)
        print("RUNNER_HERMETIC_MODEL_PROTOCOL_EXPLICIT_WORKSPACE_ACCEPTANCE=passed", flush=True)
    finally:
        primary = sys.exc_info()[1]
        try:
            cleanup(parent, directory, daemon, command)
        except Exception as failure:
            # Keep Maven/native enforcement failures primary. A green body with failed
            # cleanup still fails the gate; no error is silently swallowed.
            print("RUNNER_QUOTA_CLEANUP_FAILURE=" + json.dumps({"directory": str(directory),
                  "error": str(failure), "primary": type(primary).__name__ if primary else None}),
                  file=sys.stderr, flush=True)
            if primary is None:
                raise


if __name__ == "__main__":
    run()
