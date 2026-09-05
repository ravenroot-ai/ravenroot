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

DEFAULTS="$TEMP_DIR/defaults"
cat >"$DEFAULTS" <<'EOF'
RAVENROOT_HUMAN_TASK_DEFAULT_RESPONSE_BYTES=65536
RAVENROOT_HUMAN_TASK_MAX_RESPONSE_BYTES=262144
RAVENROOT_HUMAN_TASK_DEFAULT_ESCALATION_SECONDS=0
RAVENROOT_HUMAN_TASK_MAX_ESCALATION_SECONDS=2591999
RAVENROOT_HUMAN_TASK_DEFAULT_EXPIRY_SECONDS=604800
RAVENROOT_HUMAN_TASK_MAX_EXPIRY_SECONDS=2592000
RAVENROOT_HUMAN_TASK_MAX_TITLE_BYTES=256
RAVENROOT_HUMAN_TASK_MAX_DESCRIPTION_BYTES=4096
RAVENROOT_HUMAN_TASK_MAX_RESPONSE_SCHEMA_BYTES=16384
RAVENROOT_HUMAN_TASK_MAX_AUTHORIZATION_TOKENS=16
RAVENROOT_HUMAN_TASK_MAX_AUTHORIZATION_TOKEN_BYTES=256
RAVENROOT_HUMAN_TASK_MAX_DECISION_BODY_BYTES=262144
RAVENROOT_HUMAN_TASK_DEFAULT_PAGE_SIZE=50
RAVENROOT_HUMAN_TASK_MAX_PAGE_SIZE=100
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_DEPTH=32
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_COLLECTION_SIZE=1024
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_VALUE_COUNT=4096
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_TEXT_LENGTH=16384
RAVENROOT_HUMAN_TASK_RESPONSE_MAX_KEY_LENGTH=256
RAVENROOT_HUMAN_TASK_WRITE_ATTEMPTS=3
EOF

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
RAVENROOT_HUMAN_TASK_MAX_RESPONSE_SCHEMA_BYTES=8192
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
sed 's/=.*$/=/' "$DEFAULTS" >"$TEMP_DIR/compose-blank-defaults"
assert_compose_from_file "$TEMP_DIR/compose-default.json" "$TEMP_DIR/compose-blank-defaults"

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

helm template ravenroot "$CHART" --set-string auth.issuer=https://idp.example.test/ \
  --set-string auth.audience=ravenroot-human-task-test \
  --set-string auth.jwksUri=https://idp.example.test/jwks >"$TEMP_DIR/helm-default.yaml"
assert_helm_from_file "$TEMP_DIR/helm-default.yaml" "$DEFAULTS"

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

if helm template ravenroot "$CHART" --set-string auth.issuer=https://idp.example.test/ \
  --set-string auth.audience=ravenroot-human-task-test \
  --set-string auth.jwksUri=https://idp.example.test/jwks \
  --set humanTask.maxResponseBytes=67108865 >"$TEMP_DIR/helm-invalid-range.out" 2>&1; then
  echo 'Helm accepted an out-of-range Human Task policy value.' >&2
  exit 1
fi

if helm template ravenroot "$CHART" --set-string auth.issuer=https://idp.example.test/ \
  --set-string auth.audience=ravenroot-human-task-test \
  --set-string auth.jwksUri=https://idp.example.test/jwks \
  --set-string humanTask.maxResponseBytes=not-a-number >"$TEMP_DIR/helm-invalid-shape.out" 2>&1; then
  echo 'Helm accepted a malformed Human Task policy value.' >&2
  exit 1
fi

echo 'Human Task Compose and Helm configuration contracts passed.'
