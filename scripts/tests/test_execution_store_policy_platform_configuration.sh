#!/usr/bin/env sh
# Verify the eight SQLite execution-store carriers; render only, never start services.
set -eu
PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
python3 - "$PROJECT_DIR" <<'PY'
import json
import os
from pathlib import Path
import random
import re
import subprocess
import sys
import tempfile

root = Path(sys.argv[1])
chart = root / "deploy/helm/ravenroot"
# Independent public transport contract; the Java parser tests own default resolution and relations.
mappings = [
    ("maxLeaseTtlSeconds", "RAVENROOT_EXECUTION_STORE_MAX_LEASE_TTL_SECONDS", 1, 2**63-1),
    ("maxPayloadBytes", "RAVENROOT_EXECUTION_STORE_MAX_PAYLOAD_BYTES", 1, 2**31-1),
    ("maxClockSkewMillis", "RAVENROOT_EXECUTION_STORE_MAX_CLOCK_SKEW_MILLIS", 0, 2**63-1),
    ("journalRetentionSeconds", "RAVENROOT_EXECUTION_STORE_JOURNAL_RETENTION_SECONDS", 1, 2**63-1),
    ("maxInventoryPageSize", "RAVENROOT_EXECUTION_STORE_MAX_INVENTORY_PAGE_SIZE", 1, 2**31-1),
    ("terminalRetentionSeconds", "RAVENROOT_EXECUTION_STORE_TERMINAL_RETENTION_SECONDS", 1, 2**63-1),
    ("resultRetentionSeconds", "RAVENROOT_EXECUTION_STORE_RESULT_RETENTION_SECONDS", 1, 2**63-1),
    ("sqliteBusyTimeoutMillis", "RAVENROOT_SQLITE_BUSY_TIMEOUT_MILLIS", 0, 2**31-1),
]
clean_env = {key: os.environ[key] for key in ("PATH", "HOME") if key in os.environ}

def run(command, succeeds=True, environment=None):
    result = subprocess.run(command, cwd=root, env=environment or clean_env,
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if (result.returncode == 0) != succeeds:
        raise AssertionError(f"Unexpected exit {result.returncode}: {command}\n{result.stdout}\n{result.stderr}")
    return result.stdout if succeeds else result.stdout + result.stderr

schema = json.loads((chart / "values.schema.json").read_text())
section = schema["properties"]["executionStore"]
leaves = {row[0] for row in mappings}
assert schema["required"].count("executionStore") == 1
assert section["type"] == "object" and section["additionalProperties"] is False
assert len(section["required"]) == 8 and set(section["required"]) == leaves
assert set(section["properties"]) == leaves
values = (chart / "values.yaml").read_text()
block = re.search(r"^executionStore:\n(.*?)(?=^[A-Za-z]|\Z)", values, re.M | re.S).group(1)
assert set(re.findall(r'^  (\w+): ""$', block, re.M)) == leaves
compose = (root / "compose.yaml").read_text()
raw = (root / "deploy/kubernetes/ravenroot.yaml").read_text()
template = (chart / "templates/deployment.yaml").read_text()
docs = (root / "docs/reference/configuration.md").read_text()
java_whitespace = "\t\n\v\f\r\x1c\x1d\x1e\x1f \u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2008\u2009\u200a\u2028\u2029\u205f\u3000"
rng = random.Random(225)
for leaf, name, minimum, maximum in mappings:
    node = section["properties"][leaf]
    assert node["x-ravenroot-environment"] == name
    assert "quoted decimal string" in node["description"]
    assert compose.count(f"      {name}: ${{{name}:-}}\n") == 1
    assert raw.count(f'- name: {name}\n              value: ""') == 1
    assert template.count(f'- name: {name}\n              value: {{{{ .Values.executionStore.{leaf} | quote }}}}') == 1
    assert f"`{name}`" in docs and f"{name}=" in docs
    patterns = [schema["definitions"][ref["$ref"].split("/")[-1]] for ref in node["oneOf"]]
    assert len(patterns) == 2 and all(p["type"] == "string" for p in patterns)
    def accepts(value):
        return any(re.fullmatch(p["pattern"], value) for p in patterns)
    # Numeric oracle versus the published regex: all decimal-width boundaries plus random values.
    candidates = {minimum-1, minimum, maximum-1, maximum, maximum+1, 0, 1}
    candidates.update(10**power + offset for power in range(20) for offset in (-1, 0, 1))
    candidates.update(rng.randrange(0, maximum*2) for _ in range(200))
    for number in candidates:
        expected = minimum <= number <= maximum
        assert accepts(str(number)) == expected, (leaf, number)
        if number >= 0:
            assert accepts("000" + str(number)) == expected, (leaf, number, "leading zeroes")
            assert accepts("\u2003" + str(number) + "\t") == expected, (leaf, number, "whitespace")
    assert accepts("") and accepts(java_whitespace)
    for malformed in ("+1", "-1", "1.5", "1e3", "１", "1 2", "\u00a0", "\u2007", "\u202f", "\u00a01"):
        assert not accepts(malformed), (leaf, malformed)

helm = ["helm", "template", "ravenroot", str(chart),
        "--set-string", "auth.issuer=https://idp.example.test/",
        "--set-string", "auth.audience=ravenroot-store-policy-test",
        "--set-string", "auth.jwksUri=https://idp.example.test/jwks"]

def check_helm(text, expected):
    for leaf, name, _, _ in mappings:
        quoted = re.findall(r"- name: " + name + r'\n\s+value: ("(?:\\.|[^"\\])*")', text)
        assert len(quoted) == 1 and json.loads(quoted[0]) == expected[leaf], (name, quoted)

def render_values(directory, label, configured, succeeds=True):
    path = directory / (label + ".yaml")
    # JSON strings are YAML-compatible and preserve every digit/whitespace byte.
    path.write_text("executionStore:\n" + "".join(f"  {key}: {json.dumps(value)}\n" for key, value in configured.items()))
    return run(helm + ["-f", str(path)], succeeds)

with tempfile.TemporaryDirectory(prefix="ravenroot-store-policy-platform-") as temporary:
    directory = Path(temporary)
    defaults = {leaf: "" for leaf, _, _, _ in mappings}
    check_helm(run(helm), defaults)
    sets = {
        "minima": {leaf: str(minimum) for leaf, _, minimum, _ in mappings},
        "maxima": {leaf: str(maximum) for leaf, _, _, maximum in mappings},
        "mixed": {leaf: str(17 + i) for i, (leaf, _, _, _) in enumerate(mappings)},
    }
    sets["mixed"]["terminalRetentionSeconds"] = "29"
    for label, configured in sets.items():
        check_helm(render_values(directory, label, configured), configured)
        environment = dict(clean_env, **{name: configured[leaf] for leaf, name, _, _ in mappings})
        output = run(["docker", "compose", "--file", str(root / "compose.yaml"), "config", "--format", "json"], environment=environment)
        rendered = json.loads(output)["services"]["ravenroot"]["environment"]
        for leaf, name, _, _ in mappings:
            assert rendered[name] == configured[leaf], (name, rendered[name])
    output = run(["docker", "compose", "--file", str(root / "compose.yaml"), "config", "--format", "json"])
    rendered = json.loads(output)["services"]["ravenroot"]["environment"]
    assert all(rendered[name] == "" for _, name, _, _ in mappings)
    for leaf, _, minimum, maximum in mappings:
        for label, invalid in (("under", str(minimum-1)), ("over", str(maximum+1)), ("numeric", 1),
                               ("fraction", "1.5"), ("null", None), ("nbsp", "\u00a0")):
            diagnostic = render_values(directory, leaf + label, {leaf: invalid}, succeeds=False)
            assert leaf in diagnostic, diagnostic
            if label == "numeric":
                assert "Expected: string" in diagnostic, diagnostic
        # Java-blank values and optional whitespace around explicit digits survive the template.
        for label, value in (("blank", "\u2003\t"), ("padded", "\u20030017\t")):
            check_helm(render_values(directory, leaf + label, {leaf: value}), dict(defaults, **{leaf: value}))
    # Values which a YAML/Go numeric conversion rounds must remain exact as strings and fail as numbers.
    for number in (9007199254740993, 9223372036854775806, 9223372036854775807):
        check_helm(render_values(directory, "exact", {"maxLeaseTtlSeconds": str(number)}),
                   dict(defaults, maxLeaseTtlSeconds=str(number)))
        render_values(directory, "numeric-long", {"maxLeaseTtlSeconds": number}, succeeds=False)
        check_helm(run(helm + ["--set-string", f"executionStore.maxLeaseTtlSeconds={number}"]),
                   dict(defaults, maxLeaseTtlSeconds=str(number)))
    render_values(directory, "unknown", {"unknownSetting": "1"}, succeeds=False)
print("Execution-store policy platform configuration passed (8 bindings; exact ranges; render only).")
PY
