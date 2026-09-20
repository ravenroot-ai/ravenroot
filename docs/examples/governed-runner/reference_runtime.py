"""Deterministic protocol-v1 reference runtime, not a model-provider adapter."""
import json
import itertools
import pathlib
import subprocess
import sys

ROOT = pathlib.Path("/workspace")
request = json.load(sys.stdin)
if request.get("protocolVersion") != 1:
    raise ValueError("unsupported runner protocol")
command = request["command"]
authority = set(request["authority"]["capabilities"])
payload = request.get("input") or {}
original = payload.get("request", payload)
result = {"request": original}
outcome = "blocked"

if command in {"plan", "read", "research", "review", "summarize"}:
    if "WORKSPACE_WRITE" in authority or "NETWORK_EGRESS" in authority:
        raise ValueError("read-only command received writable authority")
    files = sorted(path.name for path in itertools.islice(ROOT.iterdir(), 128))
    result.update(summary="Inspected the bounded process workspace.", files=files)
    outcome = "approved" if command == "review" else "answered"
elif command in {"implement", "remediate"}:
    if "WORKSPACE_WRITE" not in authority:
        raise ValueError("workspace write authority denied")
    files = original.get("remediationFiles" if command == "remediate" else "files", {})
    if not isinstance(files, dict) or len(files) > 16:
        raise ValueError("bounded file map required")
    for name, text in files.items():
        relative = pathlib.PurePosixPath(name)
        if relative.is_absolute() or ".." in relative.parts or len(relative.parts) != 1:
            raise ValueError("reference runtime accepts simple relative filenames only")
        target = ROOT / relative
        if target.is_symlink() or not isinstance(text, str) or len(text.encode("utf-8")) > 16384:
            raise ValueError("unsafe workspace file")
        target.write_text(text, encoding="utf-8")
    result.update(summary="Applied the requested files in the process workspace.", files=sorted(files))
    outcome = "fixed" if command == "remediate" else "completed"
elif command == "test":
    if "PROCESS_EXECUTE" not in authority:
        raise ValueError("process execution authority denied")
    with open("/tmp/test-output", "wb") as output:
        completed = subprocess.run(["python3", "-m", "unittest", "discover", "-v"],
                                   cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, timeout=45, check=False)
    with open("/tmp/test-output", "rb") as output:
        evidence = output.read(2048).decode("utf-8", errors="replace")
    print(evidence, file=sys.stderr)
    result.update(summary="Executed the workspace unit tests.", exitCode=completed.returncode)
    outcome = "passed" if completed.returncode == 0 else "failed"
elif command in {"handoff", "resume", "integrate"}:
    result.update(summary="The workspace is ready for the next approved specialist.")
    outcome = "completed"
else:
    result.update(summary="This reference runtime does not implement the requested command.")

print(json.dumps({"outcome": outcome, "payload": result}, separators=(",", ":")))
