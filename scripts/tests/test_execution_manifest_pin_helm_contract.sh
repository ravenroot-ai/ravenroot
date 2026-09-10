#!/usr/bin/env sh
# Render the Helm carrier for the PostgreSQL-only execution-manifest pin repair bound.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
CHART="$PROJECT_DIR/deploy/helm/ravenroot"
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-execution-manifest-pin.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

helm_base() {
  helm template ravenroot "$CHART" \
    --set-string auth.issuer=https://idp.example.test/ \
    --set-string auth.audience=ravenroot-execution-manifest-pin-test \
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
pattern = (r"- name: RAVENROOT_EXECUTION_MANIFEST_PIN_ATTEMPTS"
           r'\n\s+value: ("(?:\\.|[^"\\])*")')
matches = re.findall(pattern, text)
if len(matches) != 1 or json.loads(matches[0]) != sys.argv[2]:
    raise SystemExit("Helm did not render the exact manifest pin attempt value")
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
if schema["required"].count("executionStore") != 1:
    raise SystemExit("Helm executionStore must be required once")
store = schema["properties"].get("executionStore")
expected = {
    "type": "object", "additionalProperties": False, "required": ["manifestPinAttempts"],
    "properties": {"manifestPinAttempts": {
        "x-ravenroot-environment": "RAVENROOT_EXECUTION_MANIFEST_PIN_ATTEMPTS",
        "oneOf": [{"type": "integer", "minimum": 1, "maximum": 2147483647},
                  {"$ref": "#/definitions/graphBlank"}],
    }},
}
if store != expected or schema["definitions"].get("graphBlank", {}).get("pattern") != expected_blank:
    raise SystemExit("Helm manifest pin schema must match Java positive-int and blank semantics")

values = (root / "deploy/helm/ravenroot/values.yaml").read_text()
section = re.search(r"^executionStore:\n(.*?)(?=^[A-Za-z][A-Za-z0-9]*:|\\Z)",
                    values, re.MULTILINE | re.DOTALL)
if section is None or re.findall(r'^  (manifestPinAttempts): ""$', section.group(1), re.MULTILINE) != [
        "manifestPinAttempts"]:
    raise SystemExit("Helm values must have one blank manifestPinAttempts value")
if re.search(r"manifestPinAttempts:\s*[" + "'\"]?3", section.group(1)):
    raise SystemExit("Helm must not duplicate the Java manifest pin default")

template = (root / "deploy/helm/ravenroot/templates/deployment.yaml").read_text()
expected_mapping = ('- name: RAVENROOT_EXECUTION_MANIFEST_PIN_ATTEMPTS\n'
                    '              value: {{ include "ravenroot.graphLimitValue" '
                    '.Values.executionStore.manifestPinAttempts }}')
if template.count(expected_mapping) != 1:
    raise SystemExit("Helm template must map manifestPinAttempts exactly once")
if "extraEnv" in template or "extraEnv" in values:
    raise SystemExit("This chart has no generic environment override; carrier collision must stay impossible")
PY

helm_base >"$TEMP_DIR/default.yaml"
assert_rendered_value "$TEMP_DIR/default.yaml" ""

for value in 1 7 2147483647; do
  helm_base --set "executionStore.manifestPinAttempts=$value" >"$TEMP_DIR/value-$value.yaml"
  assert_rendered_value "$TEMP_DIR/value-$value.yaml" "$value"
done

for invalid in 0 -1 2147483648; do
  if helm_base --set "executionStore.manifestPinAttempts=$invalid" >"$TEMP_DIR/invalid.out" 2>&1; then
    echo "Helm accepted an out-of-range manifest pin attempt value: $invalid" >&2
    exit 1
  fi
done

for invalid in many 1.5; do
  if helm_base --set-string "executionStore.manifestPinAttempts=$invalid" >"$TEMP_DIR/invalid.out" 2>&1; then
    echo "Helm accepted malformed manifest pin attempts: $invalid" >&2
    exit 1
  fi
done

tab=$(printf '\011')
for label_and_value in "empty|" "space| " "tab|$tab"; do
  label=${label_and_value%%|*}
  value=${label_and_value#*|}
  helm_base --set-string "executionStore.manifestPinAttempts=$value" >"$TEMP_DIR/blank-$label.yaml"
  assert_rendered_value "$TEMP_DIR/blank-$label.yaml" "$value"
done

nbsp=$(printf '\302\240')
if helm_base --set-string "executionStore.manifestPinAttempts=$nbsp" >"$TEMP_DIR/invalid.out" 2>&1; then
  echo "Helm accepted a non-Java-whitespace manifest pin value" >&2
  exit 1
fi

echo 'execution manifest pin Helm contract passed.'
