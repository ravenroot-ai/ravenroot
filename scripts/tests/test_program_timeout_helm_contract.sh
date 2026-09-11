#!/usr/bin/env sh
# Render the Helm carrier for GraalVM's typed program deadline without starting a worker.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
CHART="$PROJECT_DIR/deploy/helm/ravenroot"
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-program-timeout.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

helm_base() {
  helm template ravenroot "$CHART" \
    --set-string auth.issuer=https://idp.example.test/ \
    --set-string auth.audience=ravenroot-program-timeout-test \
    --set-string auth.jwksUri=https://idp.example.test/jwks "$@"
}

assert_rendered_value() {
  rendered=$1
  expected=$2
  python3 - "$rendered" "$expected" <<'PY'
import json
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
pattern = r"- name: RAVENROOT_PROGRAM_TIMEOUT_MS\n\s+value: (\"(?:\\.|[^\"\\])*\")"
matches = re.findall(pattern, text)
if len(matches) != 1 or json.loads(matches[0]) != sys.argv[2]:
    raise SystemExit("Helm did not render the exact program timeout value")
PY
}

python3 - "$PROJECT_DIR" <<'PY'
import json
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
schema = json.loads((root / "deploy/helm/ravenroot/values.schema.json").read_text())
expected_blank = ("^[\u0009-\u000D\u001C-\u0020\u1680\u2000-\u2006"
                  "\u2008-\u200A\u2028-\u2029\u205F\u3000]*$")
expected = {
    "x-ravenroot-environment": "RAVENROOT_PROGRAM_TIMEOUT_MS",
    "oneOf": [{"type": "integer", "minimum": 100, "maximum": 300000},
              {"$ref": "#/definitions/graphBlank"}],
}
if schema["required"].count("programTimeoutMs") != 1 \
        or schema["properties"].get("programTimeoutMs") != expected \
        or schema["definitions"].get("graphBlank", {}).get("pattern") != expected_blank:
    raise SystemExit("Helm timeout schema must match the Java-owned carrier range and blank semantics")

values = (root / "deploy/helm/ravenroot/values.yaml").read_text()
if len(re.findall(r'^programTimeoutMs: 15000$', values, re.MULTILINE)) != 1:
    raise SystemExit("Helm must retain its intentional 15000ms F30 deployment profile")

template = (root / "deploy/helm/ravenroot/templates/deployment.yaml").read_text()
expected_mapping = ('- name: RAVENROOT_PROGRAM_TIMEOUT_MS\n'
                    '              value: {{ include "ravenroot.graphLimitValue" .Values.programTimeoutMs }}')
if template.count(expected_mapping) != 1:
    raise SystemExit("Helm template must map programTimeoutMs through the blank-preserving helper")

runtime = (root / "ravenroot/ravenroot-programming-graalvm/src/main/java/ai/ravenroot/"
           "programming/graalvm/GraalVmProgramRuntime.java").read_text()
compact = " ".join(runtime.split())
if ('integerEnvironment(environment, "RAVENROOT_PROGRAM_TIMEOUT_MS", 5_000, 100, 300_000)' not in compact):
    raise SystemExit("Helm carrier range no longer matches GraalVmProgramRuntime")
if '${RAVENROOT_PROGRAM_TIMEOUT_MS:-30000}' not in (root / "compose.yaml").read_text():
    raise SystemExit("Compose must retain its explicit local-development timeout override")
documentation = (root / "docs/reference/configuration.md").read_text()
for required in ("`5000` ms", "`programTimeoutMs: 15000`", "`30000` ms local-development", "fingerprint"):
    if required not in documentation:
        raise SystemExit("program timeout documentation must state the Java, Helm, and Compose precedence")
PY

helm_base >"$TEMP_DIR/default.yaml"
assert_rendered_value "$TEMP_DIR/default.yaml" "15000"

for value in 100 15000 300000; do
  helm_base --set "programTimeoutMs=$value" >"$TEMP_DIR/value-$value.yaml"
  assert_rendered_value "$TEMP_DIR/value-$value.yaml" "$value"
done

for invalid in 99 300001; do
  if helm_base --set "programTimeoutMs=$invalid" >"$TEMP_DIR/invalid.out" 2>&1; then
    echo "Helm accepted an out-of-range program timeout: $invalid" >&2
    exit 1
  fi
done

for invalid in many 1.5; do
  if helm_base --set-string "programTimeoutMs=$invalid" >"$TEMP_DIR/invalid.out" 2>&1; then
    echo "Helm accepted a malformed program timeout: $invalid" >&2
    exit 1
  fi
done

tab=$(printf '\011')
for label_and_value in "empty|" "space| " "tab|$tab"; do
  label=${label_and_value%%|*}
  value=${label_and_value#*|}
  helm_base --set-string "programTimeoutMs=$value" >"$TEMP_DIR/blank-$label.yaml"
  assert_rendered_value "$TEMP_DIR/blank-$label.yaml" "$value"
done

nbsp=$(printf '\302\240')
if helm_base --set-string "programTimeoutMs=$nbsp" >"$TEMP_DIR/invalid.out" 2>&1; then
  echo "Helm accepted a non-Java-whitespace program timeout value" >&2
  exit 1
fi

echo 'program timeout Helm contract passed.'
