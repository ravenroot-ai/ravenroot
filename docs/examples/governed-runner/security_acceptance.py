"""Native kernel conformance, not a model. Run in a disposable quota-bounded Agent image."""
import errno
import json
import os
from pathlib import Path
import subprocess
import sys

from agent_runtime import confine


def main():
    sentinel = Path("/workspace/security-sentinel.txt")
    sentinel.write_text("uncommitted sentinel\n")
    git = subprocess.run(["git", "rev-parse", "HEAD"], cwd="/workspace", capture_output=True, check=True, text=True).stdout.strip()
    confined = []
    confine(False, False)
    attempts = {
        "overwrite": lambda: sentinel.write_text("changed"),
        "truncate": lambda: os.truncate(sentinel, 0),
        "unlink": lambda: sentinel.unlink(),
        "rename": lambda: sentinel.rename("/workspace/renamed.txt"),
        "mkdir": lambda: Path("/workspace/new-directory").mkdir(),
        "symlink": lambda: os.symlink("/workspace/security-sentinel.txt", "/workspace/link"),
        "proc-alias": lambda: Path("/proc/1/root/workspace/security-sentinel.txt").write_text("changed"),
        "outside-workspace": lambda: Path("/tmp/escaped").write_text("changed"),
        "subprocess": lambda: subprocess.run(["/bin/sh", "-c", "echo changed > /workspace/security-sentinel.txt"], check=True),
        "exec": lambda: os.execv("/bin/sh", ["sh", "-c", "exit 73"]),
    }
    for name, operation in attempts.items():
        try:
            operation()
        except OSError as denied:
            if denied.errno not in (errno.EPERM, errno.EACCES, errno.EROFS):
                raise
            confined.append(name)
        else:
            raise AssertionError("read-only kernel confinement failed: " + name)
    assert sentinel.read_text() == "uncommitted sentinel\n"
    assert Path("/workspace/.git/HEAD").is_file()
    print("RUNNER_NATIVE_READ_ONLY_CONFINEMENT=" + json.dumps({"denied": confined, "gitHead": git}), flush=True)


if __name__ == "__main__":
    main()
