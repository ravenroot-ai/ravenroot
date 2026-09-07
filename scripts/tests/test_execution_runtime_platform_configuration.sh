#!/usr/bin/env sh
# Validates execution-runtime carriers and renders Compose/Helm configuration without starting services.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
CHART="$PROJECT_DIR/deploy/helm/ravenroot"
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-execution-runtime-platform.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

MAPPINGS="$TEMP_DIR/mappings"
cat >"$MAPPINGS" <<'EOF_MAPPINGS'
RAVENROOT_ENGINE_MAX_STASHED_COMMANDS_PER_NODE executionRuntime.maxStashedCommandsPerNode 10000
RAVENROOT_ENGINE_LIFECYCLE_STEP_SECONDS executionRuntime.lifecycleStepSeconds 10
RAVENROOT_ENGINE_TERMINAL_HISTORY_CAPACITY executionRuntime.terminalHistoryCapacity 1024
RAVENROOT_GRAPH_RUNNER_SHUTDOWN_STEP_SECONDS executionRuntime.runnerShutdownStepSeconds 10
EOF_MAPPINGS

# This check owns deployment shape and transport. The Java test independently proves these schema
# ceilings and names match ExecutionRuntimeConfiguration; this script deliberately does not parse Java.
python3 - "$PROJECT_DIR" "$MAPPINGS" <<'PY'
import json
import re
import sys
from pathlib import Path

root, mappings_path = map(Path, sys.argv[1:])
mappings = [line.split() for line in mappings_path.read_text().splitlines() if line.strip()]
if len(mappings) != 4 or any(len(row) != 3 for row in mappings):
    raise SystemExit("execution-runtime mappings must contain four exact triples")
if len({row[0] for row in mappings}) != 4 or len({row[1] for row in mappings}) != 4:
    raise SystemExit("execution-runtime names and paths must each be unique")

expected_names = {row[0] for row in mappings}
expected_leaves = {row[1].split(".")[1] for row in mappings}
schema = json.loads((root / "deploy/helm/ravenroot/values.schema.json").read_text())
expected_graph_blank = ("^[\u0009-\u000D\u001C-\u0020\u1680\u2000-\u2006"
                        "\u2008-\u200A\u2028-\u2029\u205F\u3000]*$")
if schema.get("definitions", {}).get("graphBlank") != {
        "type": "string", "pattern": expected_graph_blank}:
    raise SystemExit("Helm graphBlank differs from Java 21 Character.isWhitespace")
if schema.get("required", []).count("executionRuntime") != 1:
    raise SystemExit("Helm executionRuntime must be required")
runtime = schema["properties"]["executionRuntime"]
if set(runtime) != {"type", "additionalProperties", "required", "properties"}:
    raise SystemExit("Helm executionRuntime has missing or extra object constraints")
if runtime.get("type") != "object" or runtime.get("additionalProperties") is not False:
    raise SystemExit("Helm executionRuntime must be a closed object")
if len(runtime.get("required", [])) != 4 or set(runtime.get("required", [])) != expected_leaves:
    raise SystemExit("Helm executionRuntime required leaves differ from mappings")
if set(runtime.get("properties", {})) != expected_leaves:
    raise SystemExit("Helm executionRuntime properties differ from mappings")
for environment, path, maximum in mappings:
    leaf = path.split(".")[1]
    node = runtime["properties"][leaf]
    if set(node) != {"x-ravenroot-environment", "oneOf"}:
        raise SystemExit(f"Helm {path} has missing or extra leaf constraints")
    if node.get("x-ravenroot-environment") != environment:
        raise SystemExit(f"Helm {path} is not bound to {environment}")
    if node.get("oneOf") != [
            {"type": "integer", "minimum": 1, "maximum": int(maximum)},
            {"$ref": "#/definitions/graphBlank"}]:
        raise SystemExit(f"Helm {path} differs from its positive bounded carrier contract")

values = (root / "deploy/helm/ravenroot/values.yaml").read_text()
section = re.search(r"^executionRuntime:\n(.*?)(?=^[A-Za-z][A-Za-z0-9]*:|\Z)",
                    values, re.MULTILINE | re.DOTALL)
if not section:
    raise SystemExit("Helm values lack executionRuntime")
leaves = re.findall(r'^  ([A-Za-z][A-Za-z0-9]*): ""$', section.group(1), re.MULTILINE)
if len(leaves) != 4 or set(leaves) != expected_leaves:
    raise SystemExit(f"Helm executionRuntime values must be four unique blanks: {leaves}")

compose = (root / "compose.yaml").read_text()
compose_names = re.findall(r"^\s+(RAVENROOT_(?:ENGINE_|GRAPH_RUNNER_)[A-Z0-9_]+):",
                           compose, re.MULTILINE)
if len(compose_names) != 4 or set(compose_names) != expected_names:
    raise SystemExit("Compose execution-runtime carriers are missing, duplicate, or stale")
for name in expected_names:
    expected = rf"^\s+{re.escape(name)}: \$\{{{re.escape(name)}:-\}}$"
    if len(re.findall(expected, compose, re.MULTILINE)) != 1:
        raise SystemExit(f"Compose must carry one blank-default {name}")

raw = (root / "deploy/kubernetes/ravenroot.yaml").read_text()
raw_names = re.findall(r"^\s+- name: (RAVENROOT_(?:ENGINE_|GRAPH_RUNNER_)[A-Z0-9_]+)$",
                       raw, re.MULTILINE)
if len(raw_names) != 4 or set(raw_names) != expected_names:
    raise SystemExit("raw Kubernetes execution-runtime carriers are missing, duplicate, or stale")
for name in expected_names:
    pattern = rf"- name: {re.escape(name)}\n\s+value: \"\""
    if len(re.findall(pattern, raw)) != 1:
        raise SystemExit(f"raw Kubernetes must expose one blank {name}")

template = (root / "deploy/helm/ravenroot/templates/deployment.yaml").read_text()
template_names = re.findall(r"- name: (RAVENROOT_(?:ENGINE_|GRAPH_RUNNER_)[A-Z0-9_]+)", template)
if len(template_names) != 4 or set(template_names) != expected_names:
    raise SystemExit("Helm execution-runtime template bindings are missing, duplicate, or stale")
for environment, path, _ in mappings:
    expected = (f'- name: {environment}\n'
                f'              value: {{{{ include "ravenroot.graphLimitValue" .Values.{path} }}}}')
    if template.count(expected) != 1:
        raise SystemExit(f"Helm template does not map {environment} to {path}")
PY

compose_render() {
  output=$1
  shift
  env -i PATH="$PATH" HOME="$HOME" "$@" docker compose --file "$PROJECT_DIR/compose.yaml" \
    config --format json >"$output"
}

assert_compose() {
  rendered=$1
  expected=$2
  python3 - "$rendered" "$expected" <<'PY'
import json
import sys

environment = json.load(open(sys.argv[1], encoding="utf-8"))["services"]["ravenroot"]["environment"]
for line in open(sys.argv[2], encoding="utf-8"):
    name, value = line.rstrip("\n").split("=", 1)
    if environment.get(name) != value:
        raise SystemExit(f"Compose did not preserve {name}={value!r}")
PY
}

awk '{print $1 "="}' "$MAPPINGS" >"$TEMP_DIR/defaults"
compose_render "$TEMP_DIR/compose-default.json"
assert_compose "$TEMP_DIR/compose-default.json" "$TEMP_DIR/defaults"

set --
: >"$TEMP_DIR/maxima"
while read -r name path maximum; do
  set -- "$@" "$name=$maximum"
  printf '%s=%s\n' "$name" "$maximum" >>"$TEMP_DIR/maxima"
done <"$MAPPINGS"
compose_render "$TEMP_DIR/compose-maxima.json" "$@"
assert_compose "$TEMP_DIR/compose-maxima.json" "$TEMP_DIR/maxima"

set --
while read -r name path maximum; do
  set -- "$@" "$name=1"
done <"$MAPPINGS"
compose_render "$TEMP_DIR/compose-minima.json" "$@"
awk '{print $1 "=1"}' "$MAPPINGS" >"$TEMP_DIR/minima"
assert_compose "$TEMP_DIR/compose-minima.json" "$TEMP_DIR/minima"

cat >"$TEMP_DIR/mixed" <<'EOF_MIXED'
RAVENROOT_ENGINE_MAX_STASHED_COMMANDS_PER_NODE=7
RAVENROOT_ENGINE_LIFECYCLE_STEP_SECONDS=2
RAVENROOT_ENGINE_TERMINAL_HISTORY_CAPACITY=9
RAVENROOT_GRAPH_RUNNER_SHUTDOWN_STEP_SECONDS=1
EOF_MIXED
compose_render "$TEMP_DIR/compose-mixed.json" \
  RAVENROOT_ENGINE_MAX_STASHED_COMMANDS_PER_NODE=7 \
  RAVENROOT_ENGINE_LIFECYCLE_STEP_SECONDS=2 \
  RAVENROOT_ENGINE_TERMINAL_HISTORY_CAPACITY=9 \
  RAVENROOT_GRAPH_RUNNER_SHUTDOWN_STEP_SECONDS=1
assert_compose "$TEMP_DIR/compose-mixed.json" "$TEMP_DIR/mixed"

helm_base() {
  helm template ravenroot "$CHART" \
    --set-string auth.issuer=https://idp.example.test/ \
    --set-string auth.audience=ravenroot-execution-runtime-test \
    --set-string auth.jwksUri=https://idp.example.test/jwks "$@"
}

assert_helm() {
  rendered=$1
  expected=$2
  python3 - "$rendered" "$expected" <<'PY'
import json
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
for line in open(sys.argv[2], encoding="utf-8"):
    name, value = line.rstrip("\n").split("=", 1)
    pattern = r"- name: " + re.escape(name) + r'\n\s+value: ("(?:\\.|[^"\\])*")'
    matches = re.findall(pattern, text)
    if len(matches) != 1 or json.loads(matches[0]) != value:
        raise SystemExit(f"Helm did not preserve {name}={value!r}")
PY
}

helm_base >"$TEMP_DIR/helm-default.yaml"
assert_helm "$TEMP_DIR/helm-default.yaml" "$TEMP_DIR/defaults"

set --
while read -r name path maximum; do
  set -- "$@" --set "$path=1"
done <"$MAPPINGS"
helm_base "$@" >"$TEMP_DIR/helm-minima.yaml"
assert_helm "$TEMP_DIR/helm-minima.yaml" "$TEMP_DIR/minima"

set --
while read -r name path maximum; do
  set -- "$@" --set "$path=$maximum"
done <"$MAPPINGS"
helm_base "$@" >"$TEMP_DIR/helm-maxima.yaml"
assert_helm "$TEMP_DIR/helm-maxima.yaml" "$TEMP_DIR/maxima"

helm_base \
  --set executionRuntime.maxStashedCommandsPerNode=7 \
  --set executionRuntime.lifecycleStepSeconds=2 \
  --set executionRuntime.terminalHistoryCapacity=9 \
  --set executionRuntime.runnerShutdownStepSeconds=1 \
  >"$TEMP_DIR/helm-mixed.yaml"
assert_helm "$TEMP_DIR/helm-mixed.yaml" "$TEMP_DIR/mixed"

while read -r name path maximum; do
  invalid=$((maximum + 1))
  if helm_base --set "$path=$invalid" >"$TEMP_DIR/helm-invalid.out" 2>&1; then
    echo "Helm accepted an above-ceiling execution runtime value: $path" >&2
    exit 1
  fi
done <"$MAPPINGS"

for invalid in \
  executionRuntime.maxStashedCommandsPerNode=0 \
  executionRuntime.lifecycleStepSeconds=-1; do
  if helm_base --set "$invalid" >"$TEMP_DIR/helm-invalid.out" 2>&1; then
    echo "Helm accepted an invalid execution runtime integer: $invalid" >&2
    exit 1
  fi
done

for invalid in \
  executionRuntime.terminalHistoryCapacity=not-a-number \
  executionRuntime.runnerShutdownStepSeconds=1.5; do
  if helm_base --set-string "$invalid" >"$TEMP_DIR/helm-invalid.out" 2>&1; then
    echo "Helm accepted malformed execution runtime text: $invalid" >&2
    exit 1
  fi
done

tab=$(printf '\011')
em_space=$(printf '\342\200\203')
nbsp=$(printf '\302\240')
figure_space=$(printf '\342\200\207')
narrow_nbsp=$(printf '\342\200\257')
for label_and_value in "empty|" "space| " "tab|$tab" "em-space|$em_space"; do
  label=${label_and_value%%|*}
  value=${label_and_value#*|}
  helm_base --set-string "executionRuntime.lifecycleStepSeconds=$value" \
    >"$TEMP_DIR/helm-blank-$label.yaml"
  printf 'RAVENROOT_ENGINE_LIFECYCLE_STEP_SECONDS=%s\n' "$value" >"$TEMP_DIR/one-blank"
  assert_helm "$TEMP_DIR/helm-blank-$label.yaml" "$TEMP_DIR/one-blank"
done

for label_and_value in "nbsp|$nbsp" "figure-space|$figure_space" "narrow-nbsp|$narrow_nbsp"; do
  label=${label_and_value%%|*}
  value=${label_and_value#*|}
  if helm_base --set-string "executionRuntime.lifecycleStepSeconds=$value" \
      >"$TEMP_DIR/helm-invalid-$label.out" 2>&1; then
    echo "Helm accepted an invalid execution-runtime blank: $label" >&2
    exit 1
  fi
done

echo "Execution runtime platform configuration contract passed (4 settings; render only)."
