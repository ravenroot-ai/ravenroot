#!/usr/bin/env sh
# Validates that Compose and Helm carry the one server-owned Human Task policy without
# reimplementing its defaults. The server's HumanTaskConfiguration tests own parsing and startup
# refusal; this test proves the deployment descriptors preserve absent, blank, custom, and invalid
# inputs for that authority and that Helm rejects invalid scalar values before deployment.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
CHART="$PROJECT_DIR/deploy/helm/ravenroot"
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-human-task-platform.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

POLICY_ENV="$TEMP_DIR/policy-env"
cat >"$POLICY_ENV" <<'EOF'
RAVENROOT_HUMAN_TASK_DEFAULT_RESPONSE_BYTES
RAVENROOT_HUMAN_TASK_MAX_RESPONSE_BYTES
RAVENROOT_HUMAN_TASK_DEFAULT_ESCALATION_SECONDS
RAVENROOT_HUMAN_TASK_MAX_ESCALATION_SECONDS
RAVENROOT_HUMAN_TASK_DEFAULT_EXPIRY_SECONDS
RAVENROOT_HUMAN_TASK_MAX_EXPIRY_SECONDS
RAVENROOT_HUMAN_TASK_MAX_TITLE_BYTES
RAVENROOT_HUMAN_TASK_MAX_DESCRIPTION_BYTES
RAVENROOT_HUMAN_TASK_MAX_RESPONSE_SCHEMA_BYTES
RAVENROOT_HUMAN_TASK_MAX_AUTHORIZATION_TOKENS
RAVENROOT_HUMAN_TASK_MAX_AUTHORIZATION_TOKEN_BYTES
RAVENROOT_HUMAN_TASK_MAX_DECISION_BODY_BYTES
RAVENROOT_HUMAN_TASK_DEFAULT_PAGE_SIZE
RAVENROOT_HUMAN_TASK_MAX_PAGE_SIZE
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_DEPTH
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_COLLECTION_SIZE
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_VALUE_COUNT
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_TEXT_LENGTH
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_KEY_LENGTH
RAVENROOT_HUMAN_TASK_WRITE_ATTEMPTS
RAVENROOT_HUMAN_TASK_MAX_CONFIRMATION_PROMPT_BYTES
RAVENROOT_HUMAN_TASK_MAX_CONFIRMATION_ACTION_LABEL_BYTES
RAVENROOT_HUMAN_TASK_MAX_DECISION_COMMENT_BYTES
RAVENROOT_HUMAN_TASK_ATTENTION_POLL_MILLIS
RAVENROOT_HUMAN_TASK_ATTENTION_POLL_BACKOFF_MAX_MILLIS
RAVENROOT_HUMAN_TASK_DEFAULT_ATTENTION_PAGE_SIZE
RAVENROOT_HUMAN_TASK_MAX_ATTENTION_PAGE_SIZE
EOF

# The source configuration is the authority for this carrier test. Refuse a platform-only field or
# a newly added server field that the descriptors have not been taught to forward.
python3 - "$PROJECT_DIR/ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/HumanTaskConfiguration.java" "$POLICY_ENV" <<'PY'
import re
import sys

source = open(sys.argv[1], encoding="utf-8").read()
configured = set(re.findall(r'"(RAVENROOT_HUMAN_TASK_[A-Z_]+)"', source))
expected = {line.strip() for line in open(sys.argv[2], encoding="utf-8") if line.strip()}
if configured != expected:
    missing = sorted(configured - expected)
    stale = sorted(expected - configured)
    raise SystemExit(f"Human Task platform mapping differs from source (missing={missing}, stale={stale})")
PY

# Every Human Task leaf shares Java's Character.isWhitespace-or-isSpaceChar blank contract.
python3 - "$PROJECT_DIR/deploy/helm/ravenroot/values.schema.json" <<'PY'
import json
import sys

schema = json.load(open(sys.argv[1], encoding="utf-8"))
definitions = schema["definitions"]
expected_human_task_blank = ("^[\u0009-\u000D\u001C-\u0020\u00A0\u1680\u2000-\u200A"
                             "\u2028-\u2029\u202F\u205F\u3000]*$")
if definitions.get("humanTaskBlank") != {
        "type": "string", "pattern": expected_human_task_blank}:
    raise SystemExit("Helm Human Task blank definition differs from the Java character contract")

properties = schema["properties"]["humanTask"]["properties"]
if len(properties) != 27:
    raise SystemExit("Helm Human Task policy must expose exactly 27 fields")
required = schema["properties"]["humanTask"].get("required", [])
if len(required) != len(set(required)) or set(required) != set(properties):
    raise SystemExit("Every Helm Human Task policy field must be required")
used_definitions = set()
for name, node in properties.items():
    reference = node.get("$ref", "")
    prefix = "#/definitions/"
    if not reference.startswith(prefix):
        raise SystemExit(f"Helm Human Task field {name} does not use a shared bounded definition")
    definition_name = reference[len(prefix):]
    used_definitions.add(definition_name)
    choices = definitions[definition_name].get("oneOf", [])
    blank_references = [choice.get("$ref") for choice in choices if "$ref" in choice]
    if blank_references != ["#/definitions/humanTaskBlank"]:
        raise SystemExit(f"Helm Human Task field {name} does not use the Human Task blank contract")

shared_ranges = set(definitions) - {"graphBlank", "humanTaskBlank"}
if used_definitions != shared_ranges:
    raise SystemExit("Helm Human Task shared range definitions are stale or unused")
PY

EMPTY_ASSIGNMENTS="$TEMP_DIR/empty-assignments"
sed 's/$/=/' "$POLICY_ENV" >"$EMPTY_ASSIGNMENTS"

# Values and raw Kubernetes must carry all source-owned settings as blank strings. A numerical
# value here would recreate a second default authority beside HumanTaskPolicy.DEFAULTS.
python3 - "$PROJECT_DIR/deploy/helm/ravenroot/values.yaml" \
  "$PROJECT_DIR/deploy/kubernetes/ravenroot.yaml" "$POLICY_ENV" <<'PY'
import re
import sys

values_text = open(sys.argv[1], encoding="utf-8").read()
kubernetes = open(sys.argv[2], encoding="utf-8").read()
environment = {line.strip() for line in open(sys.argv[3], encoding="utf-8") if line.strip()}

human_task = {}
inside = False
for line in values_text.splitlines():
    if line == "humanTask:":
        inside = True
        continue
    if inside and line and not line.startswith(" "):
        break
    if inside:
        match = re.fullmatch(r"  ([A-Za-z][A-Za-z0-9]*): (.*)", line)
        if match:
            if match.group(1) in human_task:
                raise SystemExit(f"Helm Human Task value {match.group(1)} is duplicated")
            human_task[match.group(1)] = match.group(2)

def helm_name(name):
    words = name.removeprefix("RAVENROOT_HUMAN_TASK_").lower().split("_")
    return words[0] + "".join(word.title() for word in words[1:])

expected_values = {helm_name(name) for name in environment}
if set(human_task) != expected_values or any(value != '\"\"' for value in human_task.values()):
    raise SystemExit("Helm Human Task values must be the complete source-owned blank carrier set")

raw_names = re.findall(r"^\s*- name: (RAVENROOT_HUMAN_TASK_[A-Z_]+)\s*$", kubernetes, re.MULTILINE)
if len(raw_names) != len(set(raw_names)) or set(raw_names) != environment:
    raise SystemExit("Raw Kubernetes Human Task names must exactly match the source-owned set")
for name in environment:
    fragment = f'- name: {name}\n              value: ""'
    if kubernetes.count(fragment) != 1:
        raise SystemExit(f"Raw Kubernetes does not carry exactly one blank {name}")
PY

CUSTOMS="$TEMP_DIR/customs"
cat >"$CUSTOMS" <<'EOF'
RAVENROOT_HUMAN_TASK_DEFAULT_RESPONSE_BYTES=32768
RAVENROOT_HUMAN_TASK_MAX_RESPONSE_BYTES=131072
RAVENROOT_HUMAN_TASK_DEFAULT_ESCALATION_SECONDS=60
RAVENROOT_HUMAN_TASK_MAX_ESCALATION_SECONDS=7200
RAVENROOT_HUMAN_TASK_DEFAULT_EXPIRY_SECONDS=3600
RAVENROOT_HUMAN_TASK_MAX_EXPIRY_SECONDS=86400
RAVENROOT_HUMAN_TASK_MAX_TITLE_BYTES=128
RAVENROOT_HUMAN_TASK_MAX_DESCRIPTION_BYTES=2048
RAVENROOT_HUMAN_TASK_MAX_RESPONSE_SCHEMA_BYTES=64
RAVENROOT_HUMAN_TASK_MAX_AUTHORIZATION_TOKENS=8
RAVENROOT_HUMAN_TASK_MAX_AUTHORIZATION_TOKEN_BYTES=128
RAVENROOT_HUMAN_TASK_MAX_DECISION_BODY_BYTES=131072
RAVENROOT_HUMAN_TASK_DEFAULT_PAGE_SIZE=25
RAVENROOT_HUMAN_TASK_MAX_PAGE_SIZE=75
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_DEPTH=24
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_COLLECTION_SIZE=512
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_VALUE_COUNT=2048
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_TEXT_LENGTH=8192
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_KEY_LENGTH=128
RAVENROOT_HUMAN_TASK_WRITE_ATTEMPTS=5
RAVENROOT_HUMAN_TASK_MAX_CONFIRMATION_PROMPT_BYTES=65536
RAVENROOT_HUMAN_TASK_MAX_CONFIRMATION_ACTION_LABEL_BYTES=256
RAVENROOT_HUMAN_TASK_MAX_DECISION_COMMENT_BYTES=16384
RAVENROOT_HUMAN_TASK_ATTENTION_POLL_MILLIS=300000
RAVENROOT_HUMAN_TASK_ATTENTION_POLL_BACKOFF_MAX_MILLIS=300000
RAVENROOT_HUMAN_TASK_DEFAULT_ATTENTION_PAGE_SIZE=100
RAVENROOT_HUMAN_TASK_MAX_ATTENTION_PAGE_SIZE=100
EOF

compose_config() {
  output=$1
  shift
  # Run with a scrubbed environment so this test never reads or prints an operator's local Compose
  # inputs. HOME is retained only for Docker's client configuration; Compose config never starts a
  # container or contacts a registry.
  env -i PATH="$PATH" HOME="$HOME" "$@" docker compose --file "$PROJECT_DIR/compose.yaml" \
    config --format json >"$output"
}

assert_compose_from_file() {
  config=$1
  expected=$2
  python3 - "$config" "$expected" <<'PY'
import json
import sys

config = json.load(open(sys.argv[1], encoding="utf-8"))
environment = config["services"]["ravenroot"]["environment"]
for line in open(sys.argv[2], encoding="utf-8"):
    name, value = line.rstrip("\n").split("=", 1)
    if environment.get(name) != value:
        raise SystemExit(f"Compose did not preserve {name}'s expected policy value")
PY
}

assert_compose_blank() {
  config=$1
  python3 - "$config" "$POLICY_ENV" <<'PY'
import json
import sys

environment = json.load(open(sys.argv[1], encoding="utf-8"))["services"]["ravenroot"]["environment"]
for name in open(sys.argv[2], encoding="utf-8"):
    name = name.strip()
    if environment.get(name) != "   ":
        raise SystemExit(f"Compose did not preserve blank {name} for the server default")
PY
}

compose_config "$TEMP_DIR/compose-default.json"
assert_compose_from_file "$TEMP_DIR/compose-default.json" "$EMPTY_ASSIGNMENTS"

set --
while IFS= read -r name; do
  set -- "$@" "$name=   "
done <"$POLICY_ENV"
compose_config "$TEMP_DIR/compose-blank.json" "$@"
assert_compose_blank "$TEMP_DIR/compose-blank.json"

set --
while IFS= read -r assignment; do
  set -- "$@" "$assignment"
done <"$CUSTOMS"
compose_config "$TEMP_DIR/compose-custom.json" "$@"
assert_compose_from_file "$TEMP_DIR/compose-custom.json" "$CUSTOMS"

compose_config "$TEMP_DIR/compose-invalid.json" RAVENROOT_HUMAN_TASK_MAX_RESPONSE_BYTES=not-a-number
printf '%s\n' 'RAVENROOT_HUMAN_TASK_MAX_RESPONSE_BYTES=not-a-number' >"$TEMP_DIR/compose-invalid"
assert_compose_from_file "$TEMP_DIR/compose-invalid.json" "$TEMP_DIR/compose-invalid"

assert_helm_from_file() {
  rendered=$1
  expected=$2
  python3 - "$rendered" "$expected" <<'PY'
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
for line in open(sys.argv[2], encoding="utf-8"):
    name, value = line.rstrip("\n").split("=", 1)
    pattern = r"- name: " + re.escape(name) + r"\n\s+value: \"" + re.escape(value) + r"\""
    if not re.search(pattern, text):
        raise SystemExit(f"Helm did not render {name}'s expected policy value")
PY
}

assert_helm_blank() {
  rendered=$1
  python3 - "$rendered" "$POLICY_ENV" <<'PY'
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
for name in open(sys.argv[2], encoding="utf-8"):
    name = name.strip()
    if not re.search(r"- name: " + re.escape(name) + r"\n\s+value: \"   \"", text):
        raise SystemExit(f"Helm did not render blank {name} for the server default")
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
    raise SystemExit(f"Helm did not preserve the exact Human Task scalar for {sys.argv[2]}")
PY
}

helm template ravenroot "$CHART" --set-string auth.issuer=https://idp.example.test/ \
  --set-string auth.audience=ravenroot-human-task-test \
  --set-string auth.jwksUri=https://idp.example.test/jwks >"$TEMP_DIR/helm-default.yaml"
assert_helm_from_file "$TEMP_DIR/helm-default.yaml" "$EMPTY_ASSIGNMENTS"

set -- helm template ravenroot "$CHART" --set-string auth.issuer=https://idp.example.test/ \
  --set-string auth.audience=ravenroot-human-task-test \
  --set-string auth.jwksUri=https://idp.example.test/jwks
while IFS= read -r assignment; do
  name=${assignment%%=*}
  value=${assignment#*=}
  helm_name=$(printf '%s' "$name" | awk -F_ '{
    suffix = ""
    for (i = 4; i <= NF; i++) suffix = suffix (i == 4 ? "" : "_") $i
    count = split(suffix, words, "_")
    result = tolower(words[1])
    for (i = 2; i <= count; i++) result = result toupper(substr(tolower(words[i]), 1, 1)) substr(tolower(words[i]), 2)
    print result
  }')
  set -- "$@" --set "humanTask.$helm_name=$value"
done <"$CUSTOMS"
"$@" >"$TEMP_DIR/helm-custom.yaml"
assert_helm_from_file "$TEMP_DIR/helm-custom.yaml" "$CUSTOMS"

set -- helm template ravenroot "$CHART" --set-string auth.issuer=https://idp.example.test/ \
  --set-string auth.audience=ravenroot-human-task-test \
  --set-string auth.jwksUri=https://idp.example.test/jwks
while IFS= read -r name; do
  helm_name=$(printf '%s' "$name" | awk -F_ '{
    suffix = ""
    for (i = 4; i <= NF; i++) suffix = suffix (i == 4 ? "" : "_") $i
    count = split(suffix, words, "_")
    result = tolower(words[1])
    for (i = 2; i <= count; i++) result = result toupper(substr(tolower(words[i]), 1, 1)) substr(tolower(words[i]), 2)
    print result
  }')
  set -- "$@" --set-string "humanTask.$helm_name=   "
done <"$POLICY_ENV"
"$@" >"$TEMP_DIR/helm-blank.yaml"
assert_helm_blank "$TEMP_DIR/helm-blank.yaml"

tab=$(printf '\011')
em_space=$(printf '\342\200\203')
nbsp=$(printf '\302\240')
non_whitespace=$(printf '\342\230\203')
for label_and_value in "empty|" "space| " "tab|$tab" "em-space|$em_space" "nbsp|$nbsp"; do
  label=${label_and_value%%|*}
  value=${label_and_value#*|}
  helm template ravenroot "$CHART" --set-string auth.issuer=https://idp.example.test/ \
    --set-string auth.audience=ravenroot-human-task-test \
    --set-string auth.jwksUri=https://idp.example.test/jwks \
    --set-string "humanTask.maxResponseBytes=$value" >"$TEMP_DIR/helm-human-task-$label.yaml"
  assert_helm_scalar "$TEMP_DIR/helm-human-task-$label.yaml" RAVENROOT_HUMAN_TASK_MAX_RESPONSE_BYTES "$value"
done

for label_and_value in "unicode-text|$non_whitespace" "non-number|not-a-number"; do
  label=${label_and_value%%|*}
  value=${label_and_value#*|}
  if helm template ravenroot "$CHART" --set-string auth.issuer=https://idp.example.test/ \
      --set-string auth.audience=ravenroot-human-task-test \
      --set-string auth.jwksUri=https://idp.example.test/jwks \
      --set-string "humanTask.maxResponseBytes=$value" \
      >"$TEMP_DIR/helm-human-task-invalid-$label.out" 2>&1; then
    echo "Helm accepted an invalid Human Task blank carrier: $label" >&2
    exit 1
  fi
done

if helm template ravenroot "$CHART" --set-string auth.issuer=https://idp.example.test/ \
  --set-string auth.audience=ravenroot-human-task-test \
  --set-string auth.jwksUri=https://idp.example.test/jwks \
  --set humanTask.maxResponseBytes=67108865 >"$TEMP_DIR/helm-invalid-range.out" 2>&1; then
  echo 'Helm accepted an out-of-range Human Task policy value.' >&2
  exit 1
fi

for invalid in humanTask.maxResponseSchemaBytes=129 humanTask.maxAuthorizationTokens=257 humanTask.maxPageSize=1001 humanTask.writeAttempts=33 \
  humanTask.maxConfirmationPromptBytes=65537 humanTask.maxConfirmationActionLabelBytes=257 \
  humanTask.maxDecisionCommentBytes=16385 humanTask.attentionPollMillis=300001 \
  humanTask.attentionPollBackoffMaxMillis=300001 humanTask.defaultAttentionPageSize=101 \
  humanTask.maxAttentionPageSize=101; do
  if helm template ravenroot "$CHART" --set-string auth.issuer=https://idp.example.test/ \
    --set-string auth.audience=ravenroot-human-task-test \
    --set-string auth.jwksUri=https://idp.example.test/jwks \
    --set "$invalid" >"$TEMP_DIR/helm-invalid-resource-cap.out" 2>&1; then
    echo "Helm accepted an out-of-range Human Task resource cap: $invalid" >&2
    exit 1
  fi
done

for invalid in humanTask.maxResponseBytes=not-a-number humanTask.maxConfirmationPromptBytes=not-a-number \
  humanTask.maxConfirmationActionLabelBytes=not-a-number humanTask.maxDecisionCommentBytes=not-a-number \
  humanTask.attentionPollMillis=not-a-number humanTask.attentionPollBackoffMaxMillis=not-a-number \
  humanTask.defaultAttentionPageSize=not-a-number humanTask.maxAttentionPageSize=not-a-number; do
  if helm template ravenroot "$CHART" --set-string auth.issuer=https://idp.example.test/ \
    --set-string auth.audience=ravenroot-human-task-test \
    --set-string auth.jwksUri=https://idp.example.test/jwks \
    --set-string "$invalid" >"$TEMP_DIR/helm-invalid-shape.out" 2>&1; then
    echo "Helm accepted a malformed Human Task policy value: $invalid" >&2
    exit 1
  fi
done

echo 'Human Task Compose and Helm configuration contracts passed.'
