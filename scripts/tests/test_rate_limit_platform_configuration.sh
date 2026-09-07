#!/usr/bin/env sh
# Validates the 23 HTTP limit bindings across Java, Compose, Helm, and raw Kubernetes. Renders only.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
CHART="$PROJECT_DIR/deploy/helm/ravenroot"
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-rate-limit-platform.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

MAPPINGS="$TEMP_DIR/mappings"
cat >"$MAPPINGS" <<'EOF_MAPPINGS'
RAVENROOT_RATELIMIT_ADDRESS_RPS addressRequestsPerSecond rateLimit.addressRequestsPerSecond 1000000
RAVENROOT_RATELIMIT_ADDRESS_BURST addressBurst rateLimit.addressBurst 1000000
RAVENROOT_RATELIMIT_TENANT_RPS tenantRequestsPerSecond rateLimit.tenantRequestsPerSecond 1000000
RAVENROOT_RATELIMIT_TENANT_BURST tenantBurst rateLimit.tenantBurst 1000000
RAVENROOT_RATELIMIT_PRINCIPAL_RPS principalRequestsPerSecond rateLimit.principalRequestsPerSecond 1000000
RAVENROOT_RATELIMIT_PRINCIPAL_BURST principalBurst rateLimit.principalBurst 1000000
RAVENROOT_RATELIMIT_SUBMISSION_RPS submissionsPerSecond rateLimit.submissionsPerSecond 1000000
RAVENROOT_RATELIMIT_SUBMISSION_BURST submissionBurst rateLimit.submissionBurst 1000000
RAVENROOT_RATELIMIT_TENANT_CONCURRENT_SUBMISSIONS tenantConcurrentSubmissions rateLimit.tenantConcurrentSubmissions 2147483647
RAVENROOT_RATELIMIT_GLOBAL_ACTIVE_EXECUTIONS globalActiveExecutions rateLimit.globalActiveExecutions 2147483647
RAVENROOT_RATELIMIT_TENANT_STREAMS tenantConcurrentStreams rateLimit.tenantConcurrentStreams 2147483647
RAVENROOT_RATELIMIT_PRINCIPAL_STREAMS principalConcurrentStreams rateLimit.principalConcurrentStreams 2147483647
RAVENROOT_SSE_QUEUE_CAPACITY streamQueueCapacity rateLimit.streamQueueCapacity 2147483647
RAVENROOT_RATELIMIT_MAX_QUERY_BYTES maxQueryBytes rateLimit.maxQueryBytes 2147483647
RAVENROOT_RATELIMIT_MAX_QUERY_PARAMETERS maxQueryParameters rateLimit.maxQueryParameters 2147483647
RAVENROOT_RATELIMIT_MAX_HEADER_COUNT maxHeaderCount rateLimit.maxHeaderCount 2147483647
RAVENROOT_RATELIMIT_MAX_HEADER_BYTES maxHeaderBytes rateLimit.maxHeaderBytes 2147483647
RAVENROOT_RATELIMIT_MAX_HEADER_VALUE_BYTES maxHeaderValueBytes rateLimit.maxHeaderValueBytes 2147483647
RAVENROOT_RATELIMIT_MAX_TRACKED_CLIENTS maxTrackedClients rateLimit.maxTrackedClients 2147483647
RAVENROOT_RATELIMIT_MAX_TRACKED_TENANTS maxTrackedTenants rateLimit.maxTrackedTenants 2147483647
RAVENROOT_RATELIMIT_MAX_TRACKED_PRINCIPALS maxTrackedPrincipals rateLimit.maxTrackedPrincipals 2147483647
RAVENROOT_RATELIMIT_IDLE_TTL_SECONDS idleEntryTtl rateLimit.idleEntryTtlSeconds 3600
RAVENROOT_RATELIMIT_EXECUTION_MAX_AGE_SECONDS executionMaxAge rateLimit.executionMaxAgeSeconds 86400
EOF_MAPPINGS

python3 - "$PROJECT_DIR" "$MAPPINGS" "$TEMP_DIR" <<'PY'
import json
import re
import shlex
import sys
from pathlib import Path

root, mappings_path, temporary = map(Path, sys.argv[1:])
mappings = [line.split() for line in mappings_path.read_text().splitlines() if line.strip()]
if len(mappings) != 23 or any(len(row) != 4 for row in mappings):
    raise SystemExit("rate-limit mappings must contain 23 exact four-part tuples")
columns = list(zip(*mappings))
if any(len(set(column)) != 23 for column in columns[:3]):
    raise SystemExit("rate-limit environment, component, and Helm path columns must be unique")

java_path = root / "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/ratelimit/RateLimitConfiguration.java"
source = java_path.read_text()
expected_environments = {row[0] for row in mappings}
source_environments = set(re.findall(r'"(RAVENROOT_(?:RATELIMIT_[A-Z_]+|SSE_QUEUE_CAPACITY))"', source))
if source_environments != expected_environments:
    raise SystemExit("rate-limit Java environment literals differ from the carrier mapping")

record = re.search(r"record RateLimitConfiguration\((.*?)\) \{", source, re.DOTALL)
if not record:
    raise SystemExit("cannot locate RateLimitConfiguration components")
components = re.findall(r"\b(?:int|Duration)\s+([A-Za-z][A-Za-z0-9]*)", record.group(1))
if components != [row[1] for row in mappings]:
    raise SystemExit(f"rate-limit record component order changed: {components}")

def call_body(java_source):
    method = java_source.index("public static RateLimitConfiguration fromEnvironment")
    marker = "return new RateLimitConfiguration("
    start = java_source.index(marker, method) + len(marker)
    depth = 1
    quoted = False
    escaped = False
    for index in range(start, len(java_source)):
        character = java_source[index]
        if quoted:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                quoted = False
        elif character == '"':
            quoted = True
        elif character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
            if depth == 0:
                return java_source[start:index]
    raise ValueError("unterminated RateLimitConfiguration factory call")

def split_arguments(body):
    arguments = []
    start = 0
    depth = 0
    quoted = False
    escaped = False
    for index, character in enumerate(body):
        if quoted:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                quoted = False
        elif character == '"':
            quoted = True
        elif character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
        elif character == "," and depth == 0:
            arguments.append(body[start:index])
            start = index + 1
    arguments.append(body[start:])
    return [re.sub(r"\s+", "", argument) for argument in arguments]

def validate_factory(java_source):
    actual = split_arguments(call_body(java_source))
    expected = []
    for index, (environment, component, _, _) in enumerate(mappings):
        if index < 21:
            expected.append(f'integer(environment,"{environment}",DEFAULTS.{component})')
        else:
            expected.append(
                f'Duration.ofSeconds(integer(environment,"{environment}",'
                f'(int)DEFAULTS.{component}.toSeconds()))'
            )
    if actual != expected:
        raise ValueError("RateLimitConfiguration factory arguments do not match mapping order")

validate_factory(source)
first, second = mappings[0][0], mappings[1][0]
mutant = source.replace(f'"{first}"', '"RATE_LIMIT_SWAP_SENTINEL"')
mutant = mutant.replace(f'"{second}"', f'"{first}"')
mutant = mutant.replace('"RATE_LIMIT_SWAP_SENTINEL"', f'"{second}"')
try:
    validate_factory(mutant)
except ValueError:
    pass
else:
    raise SystemExit("rate-limit Java factory validator accepted swapped environment literals")

def reject_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result

schema_path = root / "deploy/helm/ravenroot/values.schema.json"
schema = json.loads(schema_path.read_text(), object_pairs_hook=reject_duplicates)
expected_blank = ("^[\u0009-\u000D\u001C-\u0020\u1680\u2000-\u2006"
                  "\u2008-\u200A\u2028-\u2029\u205F\u3000]*$")
if schema["definitions"].get("graphBlank") != {"type": "string", "pattern": expected_blank}:
    raise SystemExit("Helm graphBlank differs from Java 21 Character.isWhitespace")
if "rateLimit" not in schema.get("required", []):
    raise SystemExit("Helm schema must require rateLimit")
rate = schema["properties"]["rateLimit"]
expected_leaves = {row[2].split(".")[1] for row in mappings}
if rate.get("additionalProperties") is not False:
    raise SystemExit("Helm rateLimit must be closed")
if set(rate.get("required", [])) != expected_leaves or set(rate.get("properties", {})) != expected_leaves:
    raise SystemExit("Helm rateLimit required properties differ from mappings")
for environment, _, path, maximum in mappings:
    node = rate["properties"][path.split(".")[1]]
    integers = [choice for choice in node.get("oneOf", []) if choice.get("type") == "integer"]
    blanks = [choice for choice in node.get("oneOf", [])
              if choice.get("$ref") == "#/definitions/graphBlank"]
    if node.get("x-ravenroot-environment") != environment:
        raise SystemExit(f"Helm {path} has the wrong environment annotation")
    if integers != [{"type": "integer", "minimum": 1, "maximum": int(maximum)}] or len(blanks) != 1:
        raise SystemExit(f"Helm {path} has the wrong scalar range or blank contract")

values_text = (root / "deploy/helm/ravenroot/values.yaml").read_text()
section = re.search(r"^rateLimit:\n(.*?)(?=^[A-Za-z][A-Za-z0-9]*:|\Z)",
                    values_text, re.MULTILINE | re.DOTALL)
if not section:
    raise SystemExit("Helm values lack rateLimit")
leaves = re.findall(r'^  ([A-Za-z][A-Za-z0-9]*): ""$', section.group(1), re.MULTILINE)
if len(leaves) != len(set(leaves)) or set(leaves) != expected_leaves:
    raise SystemExit("Helm rateLimit values must be exactly 23 unique blank leaves")

compose = (root / "compose.yaml").read_text()
template = (root / "deploy/helm/ravenroot/templates/deployment.yaml").read_text()
kubernetes = (root / "deploy/kubernetes/ravenroot.yaml").read_text()
patterns = (r"RAVENROOT_RATELIMIT_[A-Z_]+|RAVENROOT_SSE_QUEUE_CAPACITY")
for relative, text, occurrences_per_name in (
        ("compose.yaml", compose, 2), ("Helm template", template, 1),
        ("raw Kubernetes", kubernetes, 1)):
    names = re.findall(patterns, text)
    if (len(names) != 23 * occurrences_per_name or set(names) != expected_environments
            or any(names.count(name) != occurrences_per_name for name in expected_environments)):
        raise SystemExit(f"{relative} has duplicate, missing, or stale rate-limit names")
for environment, _, path, _ in mappings:
    if compose.count(f"{environment}: ${{{environment}:-}}") != 1:
        raise SystemExit(f"Compose must forward one {environment}")
    expected = (f'- name: {environment}\n'
                f'              value: {{{{ include "ravenroot.graphLimitValue" .Values.{path} }}}}')
    if template.count(expected) != 1:
        raise SystemExit(f"Helm template must map {environment} to {path}")
    if kubernetes.count(f'- name: {environment}\n              value: ""') != 1:
        raise SystemExit(f"raw Kubernetes must carry one blank {environment}")

def write_assignments(name, value):
    lines = []
    for environment, _, _, maximum in mappings:
        selected = value(int(maximum))
        lines.append(f"{environment}={selected}\n")
    (temporary / name).write_text("".join(lines))

write_assignments("defaults", lambda maximum: "")
write_assignments("all-one", lambda maximum: "1")
write_assignments("all-maximum", lambda maximum: str(maximum))
for name, selector in (("helm-one-flags", lambda maximum: 1),
                       ("helm-maximum-flags", lambda maximum: maximum)):
    flags = []
    for _, _, path, maximum in mappings:
        flags.extend(["--set", f"{path}={selector(int(maximum))}"])
    (temporary / name).write_text("\n".join(shlex.quote(flag) for flag in flags) + "\n")
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
        raise SystemExit(f"Compose changed {name}'s rate-limit value")
PY
}

compose_config "$TEMP_DIR/compose-default.json"
assert_compose "$TEMP_DIR/compose-default.json" "$TEMP_DIR/defaults"

for tuple in all-one all-maximum; do
  set --
  while IFS= read -r assignment; do set -- "$@" "$assignment"; done <"$TEMP_DIR/$tuple"
  compose_config "$TEMP_DIR/compose-$tuple.json" "$@"
  assert_compose "$TEMP_DIR/compose-$tuple.json" "$TEMP_DIR/$tuple"
done

compose_config "$TEMP_DIR/compose-padded.json" "RAVENROOT_RATELIMIT_ADDRESS_RPS= 01 "
printf '%s\n' 'RAVENROOT_RATELIMIT_ADDRESS_RPS= 01 ' >"$TEMP_DIR/padded"
assert_compose "$TEMP_DIR/compose-padded.json" "$TEMP_DIR/padded"
compose_config "$TEMP_DIR/compose-malformed.json" RAVENROOT_RATELIMIT_ADDRESS_RPS=not-a-number
printf '%s\n' 'RAVENROOT_RATELIMIT_ADDRESS_RPS=not-a-number' >"$TEMP_DIR/malformed"
assert_compose "$TEMP_DIR/compose-malformed.json" "$TEMP_DIR/malformed"

helm_base() {
  helm template ravenroot "$CHART" \
    --set-string auth.issuer=https://idp.example.test/ \
    --set-string auth.audience=ravenroot-rate-limit-platform-test \
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
    if len(matches) != 1 or str(json.loads(matches[0])) != value:
        raise SystemExit(f"Helm changed {name}'s rate-limit value")
PY
}

helm_base >"$TEMP_DIR/helm-default.yaml"
assert_helm "$TEMP_DIR/helm-default.yaml" "$TEMP_DIR/defaults"

for tuple in one maximum; do
  set --
  while IFS= read -r flag; do set -- "$@" "$flag"; done <"$TEMP_DIR/helm-$tuple-flags"
  helm_base "$@" >"$TEMP_DIR/helm-$tuple.yaml"
  assert_helm "$TEMP_DIR/helm-$tuple.yaml" "$TEMP_DIR/all-$tuple"
done

tab=$(printf '\011')
em_space=$(printf '\342\200\203')
nbsp=$(printf '\302\240')
for label_and_value in "empty|" "space| " "tab|$tab" "em-space|$em_space"; do
  label=${label_and_value%%|*}
  value=${label_and_value#*|}
  helm_base --set-string "rateLimit.addressRequestsPerSecond=$value" \
    >"$TEMP_DIR/helm-blank-$label.yaml"
done
for label_and_value in "nbsp|$nbsp" "text|not-a-number"; do
  label=${label_and_value%%|*}
  value=${label_and_value#*|}
  if helm_base --set-string "rateLimit.addressRequestsPerSecond=$value" \
      >"$TEMP_DIR/helm-invalid-$label.out" 2>&1; then
    echo "Helm accepted invalid rate-limit blank/text carrier: $label" >&2
    exit 1
  fi
done

while read -r environment component path maximum; do
  above=$((maximum + 1))
  for invalid in 0 -1 "$above"; do
    if helm_base --set "$path=$invalid" >"$TEMP_DIR/helm-invalid.out" 2>&1; then
      echo "Helm accepted invalid rate-limit scalar: $path=$invalid" >&2
      exit 1
    fi
  done
  if helm_base --set-string "$path=not-a-number" >"$TEMP_DIR/helm-invalid.out" 2>&1; then
    echo "Helm accepted invalid rate-limit text: $path" >&2
    exit 1
  fi
done <"$MAPPINGS"

echo 'Rate-limit Java mapping, Compose, Helm, and raw Kubernetes render contracts passed.'
