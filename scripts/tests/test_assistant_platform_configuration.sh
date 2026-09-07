#!/usr/bin/env sh
# Validates assistant limit bindings across Java, Compose, Helm, and raw Kubernetes. Renders only.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
CHART="$PROJECT_DIR/deploy/helm/ravenroot"
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-assistant-platform.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

MAPPINGS="$TEMP_DIR/mappings"
cat >"$MAPPINGS" <<'EOF_MAPPINGS'
RAVENROOT_ASSISTANT_MAX_OUTPUT_TOKENS maxOutputTokens DEFAULT_MAX_OUTPUT_TOKENS assistant.maxOutputTokens
RAVENROOT_ASSISTANT_MAX_TOOL_ITERATIONS maxToolIterations DEFAULT_MAX_TOOL_ITERATIONS assistant.maxToolIterations
EOF_MAPPINGS

python3 - "$PROJECT_DIR" "$MAPPINGS" "$TEMP_DIR/maxima" <<'PY'
import json
import re
import sys
from pathlib import Path

root, mappings_path, maxima_path = map(Path, sys.argv[1:])
mappings = [line.split() for line in mappings_path.read_text().splitlines() if line.strip()]
if len(mappings) != 2 or any(len(row) != 4 for row in mappings):
    raise SystemExit("assistant mappings must contain two exact four-part tuples")
columns = list(zip(*mappings))
if any(len(set(column)) != 2 for column in columns):
    raise SystemExit("assistant mapping tuple columns must each be unique")

java_path = root / "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/assistant/AssistantConfiguration.java"
source = java_path.read_text()
expected_variables = {row[0] for row in mappings}
source_variables = set(re.findall(r'"(RAVENROOT_ASSISTANT_MAX_[A-Z_]+)"', source))
if source_variables != expected_variables:
    raise SystemExit(f"assistant Java setting names differ from mappings: {source_variables}")

def validate_environment_declarations(java_source):
    for environment, _, _, _ in mappings:
        symbol = environment.removeprefix("RAVENROOT_ASSISTANT_") + "_VARIABLE"
        declarations = re.findall(
            rf'public static final String\s+{re.escape(symbol)}\s*=\s*"([^"]*)"\s*;',
            java_source,
        )
        if declarations != [environment]:
            raise ValueError(
                f"Java {symbol} must be declared exactly once with literal {environment}"
            )

validate_environment_declarations(source)

# Prove that declaration symbols and values are paired, rather than merely comparing two sets.
# The mutant changes only the two String literal values and must fail this same validator.
first_environment, second_environment = (row[0] for row in mappings)
first_symbol = first_environment.removeprefix("RAVENROOT_ASSISTANT_") + "_VARIABLE"
second_symbol = second_environment.removeprefix("RAVENROOT_ASSISTANT_") + "_VARIABLE"

def replace_declaration_literal(java_source, symbol, replacement):
    pattern = rf'(public static final String\s+{re.escape(symbol)}\s*=\s*)"[^"]*"(\s*;)'
    changed, count = re.subn(pattern, rf'\1"{replacement}"\2', java_source)
    if count != 1:
        raise SystemExit(f"cannot create one-literal mutation for Java {symbol}")
    return changed

swapped_source = replace_declaration_literal(source, first_symbol, second_environment)
swapped_source = replace_declaration_literal(swapped_source, second_symbol, first_environment)
try:
    validate_environment_declarations(swapped_source)
except ValueError:
    pass
else:
    raise SystemExit("assistant Java declaration validator accepted swapped environment literals")

record_header = re.search(r"record AssistantConfiguration\((.*?)\) \{", source, re.DOTALL)
if not record_header:
    raise SystemExit("cannot locate AssistantConfiguration record components")
components = re.findall(r"\bint\s+(max[A-Za-z]+)", record_header.group(1))
if components != ["maxOutputTokens", "maxToolIterations"]:
    raise SystemExit(f"assistant configuration limit components changed: {components}")

constructor = re.search(
    r"return new AssistantConfiguration\(enabled,.*?seconds\(trimmed\(env.get\(TIMEOUT_VARIABLE\)\)\),(.*?)source, allowLocalHttp\);",
    source, re.DOTALL)
if not constructor:
    raise SystemExit("cannot locate assistant environment-to-record construction")
normalized = re.sub(r"\s+", "", constructor.group(1))
expected_calls = "".join(
    f"boundedPositiveInteger(env.get({environment.removeprefix('RAVENROOT_ASSISTANT_')}_VARIABLE),"
    f"{environment.removeprefix('RAVENROOT_ASSISTANT_')}_VARIABLE,{constant}),"
    for environment, _, constant, _ in mappings
)
if normalized != expected_calls:
    raise SystemExit("assistant environment settings are swapped or no longer map to record component order")

constants = {}
for _, _, constant, _ in mappings:
    match = re.search(rf"public static final int {re.escape(constant)}\s*=\s*([0-9_]+)\s*;", source)
    if not match:
        raise SystemExit(f"cannot locate Java constant {constant}")
    constants[constant] = int(match.group(1).replace("_", ""))

schema_path = root / "deploy/helm/ravenroot/values.schema.json"
schema = json.loads(schema_path.read_text())
if "assistant" not in schema.get("required", []):
    raise SystemExit("Helm schema must require the assistant policy object")
assistant = schema["properties"]["assistant"]
expected_leaves = {row[3].split(".")[1] for row in mappings}
if assistant.get("additionalProperties") is not False:
    raise SystemExit("Helm assistant policy must be closed")
if set(assistant.get("required", [])) != expected_leaves:
    raise SystemExit("Helm assistant required leaves differ from mappings")
if set(assistant.get("properties", {})) != expected_leaves:
    raise SystemExit("Helm assistant properties differ from mappings")

maxima = []
for environment, component, constant, helm_path in mappings:
    _, leaf = helm_path.split(".")
    node = assistant["properties"][leaf]
    if node.get("x-ravenroot-environment") != environment:
        raise SystemExit(f"Helm {helm_path} is not bound to {environment}")
    integers = [choice for choice in node.get("oneOf", []) if choice.get("type") == "integer"]
    blanks = [choice for choice in node.get("oneOf", [])
              if choice.get("$ref") == "#/definitions/graphBlank"]
    if len(integers) != 1 or integers[0].get("minimum") != 1:
        raise SystemExit(f"Helm {helm_path} lacks one positive integer choice")
    if integers[0].get("maximum") != constants[constant] or len(blanks) != 1:
        raise SystemExit(f"Helm {helm_path} differs from Java maximum/blank semantics")
    maxima.append(f"{environment} {helm_path} {constants[constant]}\n")
Path(maxima_path).write_text("".join(maxima))

values = (root / "deploy/helm/ravenroot/values.yaml").read_text()
section = re.search(r"^assistant:\n(.*?)(?=^[A-Za-z][A-Za-z0-9]*:|\Z)", values, re.MULTILINE | re.DOTALL)
if not section:
    raise SystemExit("Helm values lack the assistant section")
leaves = re.findall(r'^  ([A-Za-z][A-Za-z0-9]*): ""$', section.group(1), re.MULTILINE)
if leaves != [row[3].split(".")[1] for row in mappings]:
    raise SystemExit(f"Helm assistant values must be exactly two unique blanks: {leaves}")

checks = {
    "compose.yaml": (r"^\s+{name}: \$\{{{name}:-\}}$", 2),
    "docs/examples/assistant/compose.override.yaml":
        (r"^\s+{name}: \$\{{{name}:-\}}$", 2),
    "deploy/kubernetes/ravenroot.yaml": (r'^\s+- name: {name}\n\s+value: ""$', 1),
}
for relative, (pattern, occurrences_per_name) in checks.items():
    text = (root / relative).read_text()
    actual_names = re.findall(r"RAVENROOT_ASSISTANT_MAX_[A-Z_]+", text)
    for name in expected_variables:
        if len(re.findall(pattern.format(name=re.escape(name)), text, re.MULTILINE)) != 1:
            raise SystemExit(f"{relative} must carry exactly one correctly shaped {name}")
    if set(actual_names) != expected_variables or any(
            actual_names.count(name) != occurrences_per_name for name in expected_variables):
        raise SystemExit(f"{relative} has duplicate or stale assistant limit names")

template = (root / "deploy/helm/ravenroot/templates/deployment.yaml").read_text()
actual_names = re.findall(r"RAVENROOT_ASSISTANT_MAX_[A-Z_]+", template)
if set(actual_names) != expected_variables or len(actual_names) != 2:
    raise SystemExit("Helm template has duplicate or stale assistant limit names")
for environment, _, _, helm_path in mappings:
    expected = (f'- name: {environment}\n'
                f'              value: {{{{ include "ravenroot.graphLimitValue" .Values.{helm_path} }}}}')
    if template.count(expected) != 1:
        raise SystemExit(f"Helm template does not map {environment} to {helm_path}")
PY

compose_render() {
  output=$1
  shift
  env -i PATH="$PATH" HOME="$HOME" "$@" docker compose --file "$PROJECT_DIR/compose.yaml" \
    config --format json >"$output"
}

combined_compose_render() {
  output=$1
  shift
  env -i PATH="$PATH" HOME="$HOME" \
    RAVENROOT_ASSISTANT_PROVIDER=openai-compatible \
    RAVENROOT_ASSISTANT_ENDPOINT=https://models.example.test/v1/chat/completions \
    RAVENROOT_ASSISTANT_MODEL=fixture-model \
    RAVENROOT_ASSISTANT_ALLOWED_HOSTS=models.example.test \
    RAVENROOT_ASSISTANT_ALLOWED_PORTS=443 \
    "$@" docker compose --file "$PROJECT_DIR/compose.yaml" \
    --file "$PROJECT_DIR/docs/examples/assistant/compose.override.yaml" \
    config --format json >"$output"
}

assert_compose_limits() {
  rendered=$1
  output=$2
  iterations=$3
  python3 - "$rendered" "$output" "$iterations" <<'PY'
import json
import sys
environment = json.load(open(sys.argv[1], encoding="utf-8"))["services"]["ravenroot"]["environment"]
expected = {
    "RAVENROOT_ASSISTANT_MAX_OUTPUT_TOKENS": sys.argv[2],
    "RAVENROOT_ASSISTANT_MAX_TOOL_ITERATIONS": sys.argv[3],
}
for name, value in expected.items():
    if environment.get(name) != value:
        raise SystemExit(f"Compose changed {name}: expected {value!r}, got {environment.get(name)!r}")
PY
}

compose_render "$TEMP_DIR/compose-default.json"
assert_compose_limits "$TEMP_DIR/compose-default.json" "" ""
combined_compose_render "$TEMP_DIR/compose-override-default.json"
assert_compose_limits "$TEMP_DIR/compose-override-default.json" "" ""
combined_compose_render "$TEMP_DIR/compose-custom.json" \
  RAVENROOT_ASSISTANT_MAX_OUTPUT_TOKENS=17 RAVENROOT_ASSISTANT_MAX_TOOL_ITERATIONS=2
assert_compose_limits "$TEMP_DIR/compose-custom.json" 17 2
combined_compose_render "$TEMP_DIR/compose-whitespace.json" \
  "RAVENROOT_ASSISTANT_MAX_OUTPUT_TOKENS=   " "RAVENROOT_ASSISTANT_MAX_TOOL_ITERATIONS=not-a-number"
assert_compose_limits "$TEMP_DIR/compose-whitespace.json" "   " not-a-number

helm_base() {
  helm template ravenroot "$CHART" \
    --set-string auth.issuer=https://idp.example.test/ \
    --set-string auth.audience=ravenroot-assistant-platform-test \
    --set-string auth.jwksUri=https://idp.example.test/jwks "$@"
}

assert_helm_limit() {
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
    raise SystemExit(f"Helm did not preserve exactly one {sys.argv[2]}={sys.argv[3]!r}")
PY
}

helm_base >"$TEMP_DIR/helm-default.yaml"
assert_helm_limit "$TEMP_DIR/helm-default.yaml" RAVENROOT_ASSISTANT_MAX_OUTPUT_TOKENS ""
assert_helm_limit "$TEMP_DIR/helm-default.yaml" RAVENROOT_ASSISTANT_MAX_TOOL_ITERATIONS ""

while read -r name path maximum; do
  helm_base --set "$path=1" >"$TEMP_DIR/helm-one.yaml"
  assert_helm_limit "$TEMP_DIR/helm-one.yaml" "$name" 1
  helm_base --set "$path=$maximum" >"$TEMP_DIR/helm-maximum.yaml"
  assert_helm_limit "$TEMP_DIR/helm-maximum.yaml" "$name" "$maximum"
  above=$((maximum + 1))
  for invalid in 0 -1 "$above"; do
    if helm_base --set "$path=$invalid" >"$TEMP_DIR/helm-invalid.out" 2>&1; then
      echo "Helm accepted invalid assistant limit $path" >&2
      exit 1
    fi
  done
  for invalid in not-a-number; do
    if helm_base --set-string "$path=$invalid" >"$TEMP_DIR/helm-invalid.out" 2>&1; then
      echo "Helm accepted invalid assistant limit text $path" >&2
      exit 1
    fi
  done
done <"$TEMP_DIR/maxima"

tab=$(printf '\011')
em_space=$(printf '\342\200\203')
nbsp=$(printf '\302\240')
for label_and_value in "empty|" "space| " "tab|$tab" "em-space|$em_space"; do
  label=${label_and_value%%|*}
  value=${label_and_value#*|}
  helm_base --set-string "assistant.maxOutputTokens=$value" >"$TEMP_DIR/helm-blank-$label.yaml"
  assert_helm_limit "$TEMP_DIR/helm-blank-$label.yaml" RAVENROOT_ASSISTANT_MAX_OUTPUT_TOKENS "$value"
done
for label_and_value in "nbsp|$nbsp" "text|not-a-number"; do
  label=${label_and_value%%|*}
  value=${label_and_value#*|}
  if helm_base --set-string "assistant.maxOutputTokens=$value" \
      >"$TEMP_DIR/helm-invalid-$label.out" 2>&1; then
    echo "Helm accepted invalid assistant blank carrier: $label" >&2
    exit 1
  fi
done

echo 'Assistant limit Compose, Helm, raw Kubernetes, and swapped Java environment-literal rejection contracts passed.'
