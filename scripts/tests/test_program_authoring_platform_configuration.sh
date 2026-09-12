#!/usr/bin/env sh
# Validates program-authoring bindings across Java, Compose, Helm, and raw Kubernetes. Renders only.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
CHART="$PROJECT_DIR/deploy/helm/ravenroot"
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-program-authoring.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

cat >"$TEMP_DIR/mappings" <<'EOF'
RAVENROOT_PROGRAM_AUTHORING_MAX_SOURCE_BYTES MAX_SOURCE_BYTES_ENV maxSourceBytes 1048576
RAVENROOT_PROGRAM_AUTHORING_MAX_BUILD_REQUEST_BYTES MAX_BUILD_REQUEST_BYTES_ENV maxBuildRequestBytes 10485760
RAVENROOT_PROGRAM_AUTHORING_MAX_PROGRAMS_PER_BUILD MAX_PROGRAMS_PER_BUILD_ENV maxProgramsPerBuild 256
EOF

python3 - "$PROJECT_DIR" "$TEMP_DIR/mappings" <<'PY'
import json
import re
import sys
from pathlib import Path

root, mappings_path = map(Path, sys.argv[1:])
mappings = [line.split() for line in mappings_path.read_text().splitlines()]
if len(mappings) != 3 or any(len(row) != 4 for row in mappings):
    raise SystemExit("program authoring mappings must be three exact four-part tuples")
if any(len(set(column)) != 3 for column in zip(*[row[:3] for row in mappings])):
    raise SystemExit("program authoring environment, symbol, and Helm leaf columns must be unique")

java = (root / "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/programming/"
        "ProgramAuthoringLimits.java").read_text()
for environment, symbol, _, _ in mappings:
    declaration = rf'public static final String\s+{symbol}\s*=\s*(?:\n\s*)?"{environment}"\s*;'
    if len(re.findall(declaration, java)) != 1:
        raise SystemExit(f"Java must declare {symbol} as exact environment {environment}")

values = (root / "deploy/helm/ravenroot/values.yaml").read_text()
if len(re.findall(r'^programAuthoring:\n(?:  [^\n]+\n){3}', values, re.MULTILINE)) != 1:
    raise SystemExit("Helm values must contain one three-leaf programAuthoring object")
for _, _, leaf, _ in mappings:
    if len(re.findall(rf'^  {leaf}: ""$', values, re.MULTILINE)) != 1:
        raise SystemExit(f"Helm value {leaf} must delegate to the Java default with one blank")

schema = json.loads((root / "deploy/helm/ravenroot/values.schema.json").read_text())
if schema.get("required", []).count("programAuthoring") != 1:
    raise SystemExit("Helm schema must require programAuthoring exactly once")
policy = schema["properties"].get("programAuthoring", {})
leaves = {row[2] for row in mappings}
if policy.get("type") != "object" or policy.get("additionalProperties") is not False \
        or set(policy.get("required", [])) != leaves or set(policy.get("properties", {})) != leaves:
    raise SystemExit("Helm programAuthoring schema must be closed over the exact three leaves")
for environment, _, leaf, maximum in mappings:
    expected = {
        "x-ravenroot-environment": environment,
        "oneOf": [{"type": "integer", "minimum": 1, "maximum": int(maximum)},
                  {"$ref": "#/definitions/graphBlank"}],
    }
    if policy["properties"].get(leaf) != expected:
        raise SystemExit(f"Helm schema drifted from Java authority for {leaf}")

template = (root / "deploy/helm/ravenroot/templates/deployment.yaml").read_text()
compose = (root / "compose.yaml").read_text()
kubernetes = (root / "deploy/kubernetes/ravenroot.yaml").read_text()
for environment, _, leaf, _ in mappings:
    rendered = (f'- name: {environment}\n'
                f'              value: {{{{ include "ravenroot.graphLimitValue" '
                f'.Values.programAuthoring.{leaf} }}}}')
    if template.count(rendered) != 1:
        raise SystemExit(f"Helm template mapping missing or duplicated for {environment}")
    if compose.count(f'{environment}: ${{{environment}:-}}') != 1:
        raise SystemExit(f"Compose mapping missing or duplicated for {environment}")
    raw = rf'- name: {environment}\n\s+value: ""'
    if len(re.findall(raw, kubernetes)) != 1:
        raise SystemExit(f"raw Kubernetes mapping missing or duplicated for {environment}")
PY

helm_base() {
  helm template ravenroot "$CHART" \
    --set-string auth.issuer=https://idp.example.test/ \
    --set-string auth.audience=ravenroot-program-authoring-test \
    --set-string auth.jwksUri=https://idp.example.test/jwks "$@"
}

assert_rendered() {
  python3 - "$1" "$TEMP_DIR/mappings" "$2" <<'PY'
import json
import re
import sys

document, mappings_path, expected_path = sys.argv[1:]
text = open(document, encoding="utf-8").read()
expected = json.loads(open(expected_path, encoding="utf-8").read())
for environment, _, leaf, _ in (line.split() for line in open(mappings_path, encoding="utf-8")):
    pattern = rf'- name: {environment}\n\s+value: ("(?:\\.|[^"\\])*")'
    matches = re.findall(pattern, text)
    if len(matches) != 1 or json.loads(matches[0]) != expected[leaf]:
        raise SystemExit(f"Helm did not render the exact selected value for {environment}")
PY
}

printf '%s\n' '{"maxSourceBytes":"","maxBuildRequestBytes":"","maxProgramsPerBuild":""}' \
  >"$TEMP_DIR/defaults.json"
helm_base >"$TEMP_DIR/default.yaml"
assert_rendered "$TEMP_DIR/default.yaml" "$TEMP_DIR/defaults.json"

printf '%s\n' '{"maxSourceBytes":"4096","maxBuildRequestBytes":"8192","maxProgramsPerBuild":"7"}' \
  >"$TEMP_DIR/overrides.json"
helm_base --set programAuthoring.maxSourceBytes=4096 \
  --set programAuthoring.maxBuildRequestBytes=8192 \
  --set programAuthoring.maxProgramsPerBuild=7 >"$TEMP_DIR/overrides.yaml"
assert_rendered "$TEMP_DIR/overrides.yaml" "$TEMP_DIR/overrides.json"

while read -r _ _ leaf maximum; do
  for invalid in 0 $((maximum + 1)); do
    if helm_base --set "programAuthoring.$leaf=$invalid" >"$TEMP_DIR/invalid.out" 2>&1; then
      echo "Helm accepted out-of-range programAuthoring.$leaf=$invalid" >&2
      exit 1
    fi
  done
done <"$TEMP_DIR/mappings"

echo 'program authoring platform configuration contract passed.'
