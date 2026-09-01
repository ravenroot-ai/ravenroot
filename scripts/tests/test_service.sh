#!/usr/bin/env sh
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM
MOCK_BIN="$TEMP_DIR/bin"
MOCK_LOG="$TEMP_DIR/calls.log"
mkdir -p "$MOCK_BIN"

for command in docker npm mvn helm node brew; do
  cat >"$MOCK_BIN/$command" <<'EOF'
#!/usr/bin/env sh
printf '%s %s\n' "$(basename "$0")" "$*" >>"$MOCK_LOG"
case "$(basename "$0") $*" in
  'node --version')
    printf '%s\n' "${MOCK_NODE_VERSION:-v24.0.0}"
    ;;
  'brew --prefix '*)
    exit 1
    ;;
  'docker '*' config --format json')
    if [ -n "${MOCK_COMPOSE_JSON+x}" ]; then
      printf '%s\n' "$MOCK_COMPOSE_JSON"
      exit 0
    fi
    cat <<'JSON'
{
  "services": {
    "ravenroot": {
      "environment": {
        "RAVENROOT_AUTH_MODE": "disabled",
        "RAVENROOT_BIND_ADDRESS": "0.0.0.0",
        "RAVENROOT_CONTAINER_LOOPBACK_ONLY": "true",
        "RAVENROOT_LOCAL_HOST_BIND_ADDRESS": "127.0.0.1"
      },
      "ports": [{
        "mode": "ingress",
        "host_ip": "127.0.0.1",
        "target": 8080,
        "published": "8080",
        "protocol": "tcp"
      }]
    }
  }
}
JSON
    ;;
esac
EOF
  chmod +x "$MOCK_BIN/$command"
done

run_service() {
  : >"$MOCK_LOG"
  PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" "$PROJECT_DIR/service.sh" "$@"
}

run_service_with_compose_json() {
  compose_json=$1
  shift
  : >"$MOCK_LOG"
  PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" MOCK_COMPOSE_JSON="$compose_json" \
    "$PROJECT_DIR/service.sh" "$@"
}

require_call() {
  if ! grep -F -- "$1" "$MOCK_LOG" >/dev/null; then
    echo "Expected call not found: $1" >&2
    cat "$MOCK_LOG" >&2
    exit 1
  fi
}

reject_call() {
  if grep -F -- "$1" "$MOCK_LOG" >/dev/null; then
    echo "Unexpected call found: $1" >&2
    cat "$MOCK_LOG" >&2
    exit 1
  fi
}

assert_invalid_compose_is_rejected_before_lifecycle() {
  compose_json=$1
  : >"$MOCK_LOG"
  if run_service_with_compose_json "$compose_json" start >"$TEMP_DIR/invalid-compose.out" 2>&1; then
    echo 'Invalid local Compose contract unexpectedly succeeded.' >&2
    exit 1
  fi
  grep -F 'Compose local-security contract requires exactly one' "$TEMP_DIR/invalid-compose.out" >/dev/null
  require_call 'config --format json'
  reject_call 'npm '
  reject_call 'mvn '
  reject_call ' build'
  reject_call ' up '
}

for alias in -si -skipimage --skipimage skipimage; do
  run_service "$alias"
  require_call 'docker compose --file '
  require_call 'up --detach --no-build --force-recreate --remove-orphans --wait --wait-timeout 60'
  reject_call 'npm '
  reject_call 'mvn '
  reject_call ' build'
done

# An explicitly selected local override is composed after the tracked base file, so it can add
# machine-local runtime environment without requiring plugin-specific entries in compose.yaml.
EXPLICIT_OVERRIDE="$TEMP_DIR/compose.override.yaml"
: >"$EXPLICIT_OVERRIDE"
: >"$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" \
  RAVENROOT_COMPOSE_OVERRIDE_FILE="$EXPLICIT_OVERRIDE" \
  "$PROJECT_DIR/service.sh" -si
require_call "docker compose --file $PROJECT_DIR/compose.yaml --file $EXPLICIT_OVERRIDE config --format json"
require_call "docker compose --file $PROJECT_DIR/compose.yaml --file $EXPLICIT_OVERRIDE up --detach"

# A typo in an explicit path must fail before Docker runs. An absent conventional override, by
# contrast, is intentionally optional and is already covered by the normal invocations above.
: >"$MOCK_LOG"
if PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" \
  RAVENROOT_COMPOSE_OVERRIDE_FILE="$TEMP_DIR/missing-compose.override.yaml" \
  "$PROJECT_DIR/service.sh" -si >"$TEMP_DIR/missing-override.out" 2>&1; then
  echo 'Missing explicit Compose override unexpectedly succeeded.' >&2
  exit 1
fi
grep -F 'RAVENROOT_COMPOSE_OVERRIDE_FILE is missing or unreadable:' \
  "$TEMP_DIR/missing-override.out" >/dev/null
reject_call 'docker compose'

# Compose may omit the default protocol from its JSON. It remains the only
# accepted publication when normalized to TCP.
run_service_with_compose_json '{
  "services": {"ravenroot": {"environment": {
    "RAVENROOT_AUTH_MODE": "disabled", "RAVENROOT_BIND_ADDRESS": "0.0.0.0",
    "RAVENROOT_CONTAINER_LOOPBACK_ONLY": "true", "RAVENROOT_LOCAL_HOST_BIND_ADDRESS": "127.0.0.1"
  }, "ports": [{"mode": "ingress", "host_ip": "127.0.0.1", "target": 8080, "published": "8080"}]}}}
' -si
require_call 'up --detach --no-build --force-recreate --remove-orphans --wait --wait-timeout 60'

: >"$MOCK_LOG"
if PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" RAVENROOT_HOST_BIND_ADDRESS=0.0.0.0 \
  "$PROJECT_DIR/service.sh" start >"$TEMP_DIR/non-loopback.out" 2>&1; then
  echo 'Non-loopback disabled-auth startup unexpectedly succeeded.' >&2
  exit 1
fi
grep -F 'Disabled local authentication may only be published on 127.0.0.1' "$TEMP_DIR/non-loopback.out" >/dev/null
reject_call 'docker compose --file '

assert_invalid_compose_is_rejected_before_lifecycle '{
  "services": {"ravenroot": {"environment": {
    "RAVENROOT_AUTH_MODE": "disabled", "RAVENROOT_BIND_ADDRESS": "0.0.0.0",
    "RAVENROOT_CONTAINER_LOOPBACK_ONLY": "true", "RAVENROOT_LOCAL_HOST_BIND_ADDRESS": "127.0.0.1"
  }, "ports": [
    {"mode": "ingress", "host_ip": "127.0.0.1", "target": 8080, "published": "8080", "protocol": "tcp"},
    {"mode": "ingress", "host_ip": "0.0.0.0", "target": 8080, "published": "9090", "protocol": "tcp"}
  ]}}}
'

assert_invalid_compose_is_rejected_before_lifecycle '{
  "services": {"ravenroot": {"environment": {
    "RAVENROOT_AUTH_MODE": "disabled", "RAVENROOT_BIND_ADDRESS": "0.0.0.0",
    "RAVENROOT_CONTAINER_LOOPBACK_ONLY": "true", "RAVENROOT_LOCAL_HOST_BIND_ADDRESS": "127.0.0.1"
  }, "ports": [{"mode": "ingress", "host_ip": "127.0.0.1", "target": 9090, "published": "8080", "protocol": "tcp"}]}}}
'

assert_invalid_compose_is_rejected_before_lifecycle '{
  "services": {"ravenroot": {"environment": {
    "RAVENROOT_AUTH_MODE": "disabled", "RAVENROOT_BIND_ADDRESS": "0.0.0.0",
    "RAVENROOT_CONTAINER_LOOPBACK_ONLY": "true", "RAVENROOT_LOCAL_HOST_BIND_ADDRESS": "127.0.0.1"
  }, "ports": [{"mode": "ingress", "target": 8080, "published": "8080", "protocol": "tcp"}]}}}
'

assert_invalid_compose_is_rejected_before_lifecycle '{
  "services": {"ravenroot": {"environment": {
    "RAVENROOT_AUTH_MODE": "disabled", "RAVENROOT_BIND_ADDRESS": "0.0.0.0",
    "RAVENROOT_CONTAINER_LOOPBACK_ONLY": "true", "RAVENROOT_LOCAL_HOST_BIND_ADDRESS": "127.0.0.1"
  }, "ports": [{"mode": "ingress", "host_ip": "127.0.0.1", "target": 8080, "published": "8080", "protocol": "udp"}]}}}
'

run_service -sb
require_call 'docker compose --file '
require_call 'build'
reject_call 'npm '
reject_call 'mvn '

run_service -st
require_call 'npm run build'
require_call 'mvn --batch-mode --no-transfer-progress -DskipTests clean package'
require_call 'docker compose --file'
require_call 'npm ci'
require_call 'npm run build'
reject_call 'npm test'
require_call 'mvn --batch-mode --no-transfer-progress -DskipTests clean package'
require_call 'docker compose --file '

run_service
require_call 'npm test'
require_call 'mvn --batch-mode --no-transfer-progress clean verify'
require_call 'docker compose --file '

: >"$MOCK_LOG"
if PATH="$MOCK_BIN:$PATH" HOME="$TEMP_DIR/no-nvm" MOCK_LOG="$MOCK_LOG" MOCK_NODE_VERSION=v23.11.0 \
  "$PROJECT_DIR/service.sh" -st >"$TEMP_DIR/node-version.out" 2>&1; then
  echo 'Unsupported host Node version unexpectedly reached the source build.' >&2
  exit 1
fi
grep -F 'requires Node 24.x from .nvmrc; found v23.11.0' "$TEMP_DIR/node-version.out" >/dev/null
reject_call 'npm '
reject_call 'mvn '

: >"$MOCK_LOG"
PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" \
  RAVENROOT_AUTH_ISSUER=https://idp.example.test/ \
  RAVENROOT_AUTH_AUDIENCE=ravenroot-helm-test \
  RAVENROOT_AUTH_JWKS_URI=https://idp.example.test/jwks \
  "$PROJECT_DIR/service.sh" deploy
require_call 'helm upgrade --install ravenroot '
require_call '--atomic'
require_call '--set-string auth.issuer=https://idp.example.test/'

PATH="$MOCK_BIN:$PATH" MOCK_LOG="$MOCK_LOG" "$PROJECT_DIR/service.sh" undeploy
require_call 'helm uninstall ravenroot --namespace default --wait --timeout 5m'

"$PROJECT_DIR/ravenroot/scripts/server.sh" help >/dev/null
echo 'service.sh smoke tests passed.'
