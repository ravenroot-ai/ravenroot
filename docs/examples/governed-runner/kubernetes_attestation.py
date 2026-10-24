"""Positive enforcement probe for the immutable Kubernetes Agent image.

This runs before Agent admission. It never accepts a shell program, path, cluster credential or
graph-selected option. Every temporary file and child is owned by this invocation and cleaned up.
The manager retains the bounded evidence and refuses unproven storage and process limits.
"""
from contextlib import contextmanager
import ctypes
import errno
import json
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import time


# A failure marker is unverified diagnostic context, never positive enforcement proof. Only
# these closed literals cross the process boundary; exception text and dynamic values do not.
class DiagnosticFailure(Exception):
    def __init__(self, phase, reason):
        self.phase, self.reason = phase, reason


@contextmanager
def diagnostic_phase(phase):
    try:
        yield
    except Exception as failure:
        reasons = {
            "positive CPU throttling evidence is missing": "CPU_THROTTLING_MISSING",
            "Workspace initialization identity changed": "SEED_IDENTITY_CHANGED",
            "Workspace seed initialization is uncertain; refusing a fresh clone": "SEED_UNCERTAIN",
        }
        reason = (reasons.get(failure.args[0]) if type(failure) is RuntimeError
                  and len(failure.args) == 1 and isinstance(failure.args[0], str) else None)
        if reason is None:
            reason = ("PROCESS_TIMEOUT" if isinstance(failure, subprocess.TimeoutExpired) else
                      "PROCESS_EXIT" if isinstance(failure, subprocess.CalledProcessError) else
                      "OS_ERROR" if isinstance(failure, OSError) else
                      "INVALID_VALUE" if isinstance(failure, (ValueError, KeyError)) else
                      "REFUSED" if isinstance(failure, RuntimeError) else "UNKNOWN")
        raise DiagnosticFailure(phase, reason) from None


def entrypoint(arguments):
    try:
        if arguments == ["--memory-boundary"]:
            with diagnostic_phase("MEMORY_BOUNDARY"):
                memory_boundary()
        elif arguments == ["--network-control"]:
            with diagnostic_phase("CONTROL"):
                network_isolated(False)
                print('{"networkReachable":true}', flush=True)
        elif not arguments:
            run()
        else:
            with diagnostic_phase("ARGUMENTS"):
                raise ValueError("unknown attestation operation")
        return 0
    except Exception as failure:
        phase, reason = ((failure.phase, failure.reason) if isinstance(failure, DiagnosticFailure)
                         else ("UNKNOWN", "UNKNOWN"))
        if not isinstance(phase, str) or not isinstance(reason, str) or phase not in {"UNKNOWN", "NETWORK", "IDENTITY", "PRIVILEGES", "CREDENTIAL", "ROOT", "LANDLOCK",
                         "CEILINGS", "STORAGE", "PROCESS", "CGROUP", "WORKSPACE", "CONTROL", "MEMORY_BOUNDARY", "ARGUMENTS"} \
                or reason not in {"UNKNOWN", "REFUSED", "OS_ERROR", "INVALID_VALUE", "PROCESS_EXIT", "PROCESS_TIMEOUT",
                                  "CPU_THROTTLING_MISSING", "SEED_UNCERTAIN", "SEED_IDENTITY_CHANGED"}:
            phase, reason = "UNKNOWN", "UNKNOWN"
        print("RR_ATTESTATION_FAILURE_V1:" + phase + ":" + reason, file=sys.stderr, flush=True)
        return 1


def quota(root, limit):
    geometry = os.statvfs(root)
    effective = geometry.f_blocks * geometry.f_frsize
    if not 0 < effective <= limit:
        raise RuntimeError("filesystem capacity exceeds the approved hard limit")
    descriptor, name = tempfile.mkstemp(prefix=".ravenroot-quota-", dir=root)
    written = 0
    refused = False
    chunk = b"\0" * min(1_048_576, limit)
    try:
        os.write(descriptor, chunk)
        os.fsync(descriptor)  # Positive within-quota write, not just accepted API configuration.
        written += len(chunk)
        try:
            while written <= limit:
                written += os.write(descriptor, chunk)
                os.fsync(descriptor)
        except OSError as failure:
            if failure.errno not in {errno.ENOSPC, errno.EDQUOT}:
                raise
            refused = True
        if not refused:
            raise RuntimeError("write beyond the approved storage ceiling succeeded")
        return {"requestedBytes": limit, "enforcedBytes": effective, "withinQuotaWrite": True,
                "beyondQuotaRejected": True, "backend": "fixed-filesystem-v1"}
    finally:
        os.close(descriptor)
        os.unlink(name)
        directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)


def processes(limit):
    completed = subprocess.run(["/opt/kubernetes_pid_probe", str(limit)], check=True,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30)
    if len(completed.stdout) > 256:
        raise ValueError("PID attestation exceeded its bound")
    return json.loads(completed.stdout)


def initialize_workspace():
    root = pathlib.Path("/workspace")
    marker = root / ".ravenroot-initialized"
    pending = root / ".ravenroot-initializing"
    if marker.exists():
        if marker.read_bytes() != b"immutable-image-seed-v1\n":
            raise RuntimeError("Workspace initialization identity changed")
        return
    if pending.exists() or any(entry.name != "lost+found" for entry in root.iterdir()):
        raise RuntimeError("Workspace seed initialization is uncertain; refusing a fresh clone")
    with pending.open("xb") as stream:
        stream.write(b"immutable-image-seed-v1\n")
        stream.flush()
        os.fsync(stream.fileno())
    for entry in pathlib.Path("/opt/workspace-seed").iterdir():
        if entry.is_dir():
            shutil.copytree(entry, root / entry.name, symlinks=True)
        else:
            shutil.copy2(entry, root / entry.name, follow_symlinks=False)
    os.sync()
    pending.replace(marker)
    descriptor = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def network_isolated(expected=True):
    # The manager resolves the operator-owned secretless control Service, never graph input.
    host = os.environ["RAVENROOT_NETWORK_CONTROL_HOST"]
    port = 9443
    deadline = time.monotonic() + 10
    while True:
        try:
            with socket.create_connection((host, port), timeout=2):
                isolated = False
        except (TimeoutError, OSError):
            isolated = True
        if isolated == expected:
            return
        if time.monotonic() >= deadline:
            raise RuntimeError("network enforcement control did not match the required phase")
        time.sleep(0.1)


def cgroups():
    root = pathlib.Path("/sys/fs/cgroup")
    memory = int((root / "memory.max").read_text().strip())
    quota_value, period = (root / "cpu.max").read_text().split()
    quota_value, period = int(quota_value), int(period)
    cpu = (quota_value * 1000 + period - 1) // period
    if not 0 < memory <= int(os.environ["RAVENROOT_MEMORY_LIMIT_BYTES"]):
        raise RuntimeError("the memory cgroup does not enforce the approved ceiling")
    if not 0 < cpu <= int(os.environ["RAVENROOT_CPU_MILLICORES"]):
        raise RuntimeError("the CPU cgroup does not enforce the approved ceiling")
    def throttled():
        return dict(line.split() for line in (root / "cpu.stat").read_text().splitlines())["nr_throttled"]
    before = int(throttled())
    # A single bounded CPU probe is meaningful for fractional-CPU profiles. Larger approved
    # ceilings retain their kernel cgroup proof; the native boundary test uses 500 millicores.
    if cpu < 1000:
        end = time.monotonic() + 10
        # Host contention can delay the cgroup receiving a complete CPU quota period. Wait for
        # actual kernel throttling, within a fixed deadline, rather than assuming 1.2 wall seconds
        # always means the probe received enough CPU. Absence of positive proof still refuses.
        while int(throttled()) <= before and time.monotonic() < end:
            interval = min(end, time.monotonic() + 0.1)
            while time.monotonic() < interval:
                pass
        if int(throttled()) <= before:
            raise RuntimeError("positive CPU throttling evidence is missing")
    within_memory = bytearray(1_048_576)
    within_memory[0] = 1
    return {"memoryLimitBytes": memory, "cpuMillicores": cpu, "withinMemoryAllocation": True,
            "cpuThrottlingObserved": int(throttled()) > before}


def run():
    with diagnostic_phase("NETWORK"):
        network_isolated()
    with diagnostic_phase("IDENTITY"):
        if os.getuid() != 65532 or os.getgid() != 65532:
            raise RuntimeError("approved non-root identity required")
    with diagnostic_phase("PRIVILEGES"):
        status = pathlib.Path("/proc/self/status").read_text()
        fields = dict(line.split(":", 1) for line in status.splitlines() if ":" in line)
        if int(fields["CapEff"].strip(), 16) != 0 or fields["NoNewPrivs"].strip() != "1":
            raise RuntimeError("capability or privilege confinement is missing")
    with diagnostic_phase("CREDENTIAL"):
        if pathlib.Path("/var/run/secrets/kubernetes.io/serviceaccount/token").exists():
            raise RuntimeError("Agent received a Kubernetes credential")
    with diagnostic_phase("ROOT"):
        if not os.statvfs("/").f_flag & os.ST_RDONLY:
            raise RuntimeError("Agent root filesystem must be read-only")
    with diagnostic_phase("LANDLOCK"):
        libc = ctypes.CDLL(None, use_errno=True)
        abi = libc.syscall(444, 0, 0, 1)
        if abi < 3:
            raise RuntimeError("Landlock ABI 3 is required")
    with diagnostic_phase("CEILINGS"):
        limit = int(os.environ["RAVENROOT_WORKSPACE_LIMIT_BYTES"])
        process_limit = int(os.environ["RAVENROOT_PROCESS_LIMIT"])
        if not 1_048_576 <= limit <= 1_099_511_627_776 or not 4 <= process_limit <= 4096:
            raise ValueError("invalid operator enforcement ceiling")
    with diagnostic_phase("STORAGE"):
        proof = quota("/workspace", limit)
    with diagnostic_phase("PROCESS"):
        proof.update(processes(process_limit))
    with diagnostic_phase("CGROUP"):
        proof.update(cgroups())
    with diagnostic_phase("WORKSPACE"):
        initialize_workspace()
    proof.update(protocolVersion=1, nonRoot=True, capabilitiesDropped=True, noNewPrivileges=True,
                 readOnlyRoot=True, landlockAbi=abi, serviceAccountAbsent=True, networkBlocked=True)
    print(json.dumps(proof, separators=(",", ":")), flush=True)


def memory_boundary():
    # Destructive, fixed CI/operator probe on a sacrificial owned Pod, never normal preflight.
    # cgroup v2 memory.oom.group may kill every process; the manager must prove UID absence.
    limit = int(pathlib.Path('/sys/fs/cgroup/memory.max').read_text())
    if limit > int(os.environ['RAVENROOT_MEMORY_LIMIT_BYTES']):
        raise RuntimeError('memory ceiling is unproven')
    allocation = bytearray(limit * 2)
    raise RuntimeError('memory boundary was not enforced')


if __name__ == "__main__":
    sys.exit(entrypoint(sys.argv[1:]))
