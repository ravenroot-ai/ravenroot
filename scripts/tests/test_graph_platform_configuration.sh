#!/usr/bin/env sh
# Validates that Compose, Helm, and raw Kubernetes carry the Java-owned graph limit policy without
# copying its numerical defaults. This script renders configuration only; it never starts a service.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
CHART="$PROJECT_DIR/deploy/helm/ravenroot"
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-graph-platform.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

MAPPINGS="$TEMP_DIR/mappings"
cat >"$MAPPINGS" <<'EOF'
RAVENROOT_GRAPHML_MAX_BYTES graph.graphMl.maxBytes
RAVENROOT_GRAPH_MAX_NODES graph.graphMl.maxNodes
RAVENROOT_GRAPH_MAX_EDGES graph.graphMl.maxEdges
RAVENROOT_GRAPH_MAX_PROPERTIES graph.graphMl.maxProperties
RAVENROOT_GRAPHML_MAX_DEPTH graph.graphMl.maxDepth
RAVENROOT_GRAPHML_MAX_STRING_LENGTH graph.graphMl.maxStringLength
RAVENROOT_GRAPHML_MAX_KEYS graph.graphMl.maxKeys
RAVENROOT_GRAPHML_MAX_ELEMENTS graph.graphMl.maxElements
RAVENROOT_GRAPHML_MAX_ATTRIBUTES graph.graphMl.maxAttributes
RAVENROOT_GRAPHML_MAX_NAMESPACE_DECLARATIONS graph.graphMl.maxNamespaceDeclarations
RAVENROOT_GRAPH_MAX_PAYLOAD_BYTES graph.payload.maxEncodedBytes
RAVENROOT_GRAPH_MAX_PAYLOAD_DEPTH graph.payload.maxDepth
RAVENROOT_GRAPH_MAX_PAYLOAD_COLLECTION_SIZE graph.payload.maxCollectionSize
RAVENROOT_GRAPH_MAX_PAYLOAD_VALUE_COUNT graph.payload.maxValueCount
RAVENROOT_GRAPH_MAX_PAYLOAD_TEXT_LENGTH graph.payload.maxTextLength
RAVENROOT_GRAPH_MAX_PAYLOAD_KEY_LENGTH graph.payload.maxKeyLength
RAVENROOT_GRAPH_MAX_FAN_OUT graph.execution.maxFanOut
RAVENROOT_GRAPH_MAX_RESIDENT_ACTORS graph.execution.maxResidentActors
RAVENROOT_GRAPH_MAX_LIVE_ACTORS_PER_TRAVERSAL graph.execution.maxLiveActorsPerTraversal
RAVENROOT_GRAPH_MAX_IN_FLIGHT_HOPS graph.execution.maxInFlightHopsPerTraversal
RAVENROOT_GRAPH_MAX_QUEUED_ADMISSIONS_PER_NODE graph.execution.maxQueuedAdmissionsPerNode
RAVENROOT_GRAPH_MAX_TRAVERSAL_STEPS graph.execution.maxTraversalSteps
RAVENROOT_GRAPH_MAX_AMPLIFIED_DELIVERIES graph.execution.maxAmplifiedDeliveries
RAVENROOT_GRAPH_MAX_CUMULATIVE_PAYLOAD_BYTES graph.execution.maxCumulativePayloadBytes
RAVENROOT_GRAPH_MAX_RECOVERY_DELIVERIES_PER_ATTEMPT graph.execution.maxRecoveryDeliveriesPerAttempt
EOF

# Refuse stale or platform-only bindings. Schema maxima are checked against Java constants by the
# core GraphDeploymentConfigurationContractTest rather than copied into this shell fixture.
python3 - "$PROJECT_DIR" "$MAPPINGS" <<'PY'
import json
import re
import sys

root, mappings_path = sys.argv[1:]
mappings = [line.split() for line in open(mappings_path, encoding="utf-8") if line.strip()]
if len(mappings) != 25 or len({name for name, _ in mappings}) != 25 or len({path for _, path in mappings}) != 25:
    raise SystemExit("graph deployment mappings must contain 25 unique names and paths")

source = open(root + "/ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java",
              encoding="utf-8").read()
source_names = set(re.findall(r'"(RAVENROOT_(?:GRAPHML|GRAPH)_[A-Z_]+)"', source))
mapped_names = {name for name, _ in mappings}
if source_names != mapped_names:
    raise SystemExit(f"graph deployment mappings differ from Java (missing={sorted(source_names-mapped_names)}, "
                     f"stale={sorted(mapped_names-source_names)})")

schema = json.load(open(root + "/deploy/helm/ravenroot/values.schema.json", encoding="utf-8"))
expected_graph_blank = ("^[\u0009-\u000D\u001C-\u0020\u1680\u2000-\u2006"
                        "\u2008-\u200A\u2028-\u2029\u205F\u3000]*$")
if schema.get("definitions", {}).get("graphBlank") != {
        "type": "string", "pattern": expected_graph_blank}:
    raise SystemExit("Helm graph blank definition differs from Java 21 Character.isWhitespace")
graph = schema["properties"]["graph"]
if "graph" not in schema.get("required", []) or graph.get("additionalProperties") is not False:
    raise SystemExit("Helm graph policy must be required and closed")
expected_groups = {"graphMl": set(), "payload": set(), "execution": set()}
for _, path in mappings:
    _, group, leaf = path.split(".")
    expected_groups[group].add(leaf)
if set(graph.get("required", [])) != set(expected_groups):
    raise SystemExit("Helm graph policy group requirements differ from the Java contract")
for group, expected_leaves in expected_groups.items():
    group_schema = graph["properties"][group]
    if group_schema.get("additionalProperties") is not False:
        raise SystemExit(f"Helm graph policy group {group} must be closed")
    if set(group_schema.get("required", [])) != expected_leaves:
        raise SystemExit(f"Helm graph policy group {group} requirements differ from the Java contract")
    if set(group_schema.get("properties", {})) != expected_leaves:
        raise SystemExit(f"Helm graph policy group {group} properties differ from the Java contract")
for name, path in mappings:
    node = schema
    for component in path.split("."):
        node = node["properties"][component]
    if node.get("x-ravenroot-environment") != name:
        raise SystemExit(f"Helm schema path {path} is not bound to {name}")
    choices = node.get("oneOf", [])
    integers = [choice for choice in choices if choice.get("type") == "integer"]
    blanks = [choice for choice in choices if choice.get("$ref") == "#/definitions/graphBlank"]
    if len(integers) != 1 or integers[0].get("minimum") != 1 or not isinstance(integers[0].get("maximum"), int):
        raise SystemExit(f"Helm schema path {path} lacks one positive bounded integer choice")
    if len(blanks) != 1:
        raise SystemExit(f"Helm schema path {path} lacks the blank Java-default choice")

raw = open(root + "/deploy/kubernetes/ravenroot.yaml", encoding="utf-8").read()
for name in mapped_names:
    pattern = r"- name: " + re.escape(name) + r'\n\s+value: ""'
    if len(re.findall(pattern, raw)) != 1:
        raise SystemExit(f"raw Kubernetes must expose one blank {name}")
PY

compose_config() {
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
        raise SystemExit(f"Compose did not preserve the expected value for {name}")
PY
}

compose_config "$TEMP_DIR/compose-default.json"
awk '{print $1 "="}' "$MAPPINGS" >"$TEMP_DIR/defaults"
assert_compose "$TEMP_DIR/compose-default.json" "$TEMP_DIR/defaults"

set --
: >"$TEMP_DIR/custom"
index=0
while read -r name path; do
  index=$((index + 1))
  set -- "$@" "$name=$index"
  printf '%s=%s\n' "$name" "$index" >>"$TEMP_DIR/custom"
done <"$MAPPINGS"
compose_config "$TEMP_DIR/compose-custom.json" "$@"
assert_compose "$TEMP_DIR/compose-custom.json" "$TEMP_DIR/custom"

set --
: >"$TEMP_DIR/blanks"
while read -r name path; do
  set -- "$@" "$name=   "
  printf '%s=   \n' "$name" >>"$TEMP_DIR/blanks"
done <"$MAPPINGS"
compose_config "$TEMP_DIR/compose-blank.json" "$@"
assert_compose "$TEMP_DIR/compose-blank.json" "$TEMP_DIR/blanks"

compose_config "$TEMP_DIR/compose-malformed.json" RAVENROOT_GRAPH_MAX_FAN_OUT=not-a-number
printf '%s\n' 'RAVENROOT_GRAPH_MAX_FAN_OUT=not-a-number' >"$TEMP_DIR/malformed"
assert_compose "$TEMP_DIR/compose-malformed.json" "$TEMP_DIR/malformed"

helm_base() {
  helm template ravenroot "$CHART" \
    --set-string auth.issuer=https://idp.example.test/ \
    --set-string auth.audience=ravenroot-graph-platform-test \
    --set-string auth.jwksUri=https://idp.example.test/jwks "$@"
}

assert_helm() {
  rendered=$1
  expected=$2
  python3 - "$rendered" "$expected" <<'PY'
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
for line in open(sys.argv[2], encoding="utf-8"):
    name, value = line.rstrip("\n").split("=", 1)
    pattern = r"- name: " + re.escape(name) + r'\n\s+value: "' + re.escape(value) + r'"'
    if len(re.findall(pattern, text)) != 1:
        raise SystemExit(f"Helm did not render one expected value for {name}")
PY
}

assert_helm_scalar() {
  rendered=$1
  name=$2
  expected=$3
  python3 - "$rendered" "$name" "$expected" <<'PY'
import json
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
pattern = r"- name: " + re.escape(sys.argv[2]) + r'\n\s+value: ("(?:\\.|[^"\\])*")'
matches = re.findall(pattern, text)
if len(matches) != 1 or json.loads(matches[0]) != sys.argv[3]:
    raise SystemExit(f"Helm did not preserve the exact graph scalar for {sys.argv[2]}")
PY
}

helm_base >"$TEMP_DIR/helm-default.yaml"
assert_helm "$TEMP_DIR/helm-default.yaml" "$TEMP_DIR/defaults"

set --
: >"$TEMP_DIR/helm-custom-values"
index=0
while read -r name path; do
  index=$((index + 1))
  set -- "$@" --set "$path=$index"
  printf '%s=%s\n' "$name" "$index" >>"$TEMP_DIR/helm-custom-values"
done <"$MAPPINGS"
helm_base "$@" >"$TEMP_DIR/helm-custom.yaml"
assert_helm "$TEMP_DIR/helm-custom.yaml" "$TEMP_DIR/helm-custom-values"

set --
while read -r name path; do
  set -- "$@" --set-string "$path=   "
done <"$MAPPINGS"
helm_base "$@" >"$TEMP_DIR/helm-blank.yaml"
assert_helm "$TEMP_DIR/helm-blank.yaml" "$TEMP_DIR/blanks"

tab=$(printf '\011')
em_space=$(printf '\342\200\203')
nbsp=$(printf '\302\240')
non_whitespace=$(printf '\342\230\203')
for label_and_value in "empty|" "space| " "tab|$tab" "em-space|$em_space"; do
  label=${label_and_value%%|*}
  value=${label_and_value#*|}
  helm_base --set-string "graph.graphMl.maxDepth=$value" >"$TEMP_DIR/helm-graph-$label.yaml"
  assert_helm_scalar "$TEMP_DIR/helm-graph-$label.yaml" RAVENROOT_GRAPHML_MAX_DEPTH "$value"
done

for label_and_value in "nbsp|$nbsp" "unicode-text|$non_whitespace" "non-number|not-a-number"; do
  label=${label_and_value%%|*}
  value=${label_and_value#*|}
  if helm_base --set-string "graph.graphMl.maxDepth=$value" \
      >"$TEMP_DIR/helm-graph-invalid-$label.out" 2>&1; then
    echo "Helm accepted an invalid graph blank carrier: $label" >&2
    exit 1
  fi
done

# Render every exact schema ceiling in one chart invocation, then verify that each next integer is
# rejected. The core Java test independently proves every schema ceiling equals its typed constant.
python3 - "$PROJECT_DIR/deploy/helm/ravenroot/values.schema.json" "$MAPPINGS" \
  "$TEMP_DIR/ceilings" "$TEMP_DIR/ceiling-flags" <<'PY'
import json
import shlex
import sys

schema_path, mappings_path, expected_path, flags_path = sys.argv[1:]
schema = json.load(open(schema_path, encoding="utf-8"))
expected = []
flags = []
for line in open(mappings_path, encoding="utf-8"):
    name, path = line.split()
    node = schema
    for component in path.split("."):
        node = node["properties"][component]
    maximum = next(choice["maximum"] for choice in node["oneOf"] if choice.get("type") == "integer")
    expected.append(f"{name}={maximum}\n")
    flags.extend(["--set", f"{path}={maximum}"])
open(expected_path, "w", encoding="utf-8").writelines(expected)
open(flags_path, "w", encoding="utf-8").write("\n".join(shlex.quote(flag) for flag in flags) + "\n")
PY

set --
while IFS= read -r flag; do
  set -- "$@" "$flag"
done <"$TEMP_DIR/ceiling-flags"
helm_base "$@" >"$TEMP_DIR/helm-ceilings.yaml"
assert_helm "$TEMP_DIR/helm-ceilings.yaml" "$TEMP_DIR/ceilings"

python3 - "$PROJECT_DIR/deploy/helm/ravenroot/values.schema.json" "$MAPPINGS" >"$TEMP_DIR/above-ceilings" <<'PY'
import json
import sys

schema = json.load(open(sys.argv[1], encoding="utf-8"))
for line in open(sys.argv[2], encoding="utf-8"):
    _, path = line.split()
    node = schema
    for component in path.split("."):
        node = node["properties"][component]
    maximum = next(choice["maximum"] for choice in node["oneOf"] if choice.get("type") == "integer")
    print(path, maximum + 1)
PY

while read -r path invalid; do
  if helm_base --set "$path=$invalid" >"$TEMP_DIR/helm-invalid.out" 2>&1; then
    echo "Helm accepted an above-ceiling graph limit: $path" >&2
    exit 1
  fi
done <"$TEMP_DIR/above-ceilings"

for invalid in graph.graphMl.maxBytes=0 graph.execution.maxFanOut=-1; do
  if helm_base --set "$invalid" >"$TEMP_DIR/helm-invalid.out" 2>&1; then
    echo "Helm accepted an invalid graph limit: $invalid" >&2
    exit 1
  fi
done

for invalid in graph.payload.maxDepth=not-a-number graph.execution.maxFanOut=not-a-number; do
  if helm_base --set-string "$invalid" >"$TEMP_DIR/helm-invalid.out" 2>&1; then
    echo "Helm accepted an invalid graph limit: $invalid" >&2
    exit 1
  fi
done

echo 'Graph Compose, Helm, and raw Kubernetes configuration contracts passed.'
