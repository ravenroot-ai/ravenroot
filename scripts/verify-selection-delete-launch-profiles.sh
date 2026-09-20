#!/usr/bin/env sh
# Exercises the same packaged UI through every supported local launch lifecycle named by #455.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
UI_DIR="$PROJECT_DIR/ravenroot/ravenroot-ui"
SERVER_PORT=${RAVENROOT_SELECTION_SERVER_PORT:-18080}
COMPOSE_PORT=${RAVENROOT_SELECTION_COMPOSE_PORT:-18081}
SERVER_RUN_DIR=${RAVENROOT_SELECTION_SERVER_RUN_DIR:-"$PROJECT_DIR/ravenroot/target/selection-delete-server"}

run_server() {
  RAVENROOT_PORT="$SERVER_PORT" \
  RAVENROOT_RUN_DIR="$SERVER_RUN_DIR" \
  RAVENROOT_ARTIFACT_STORE_DIR="$SERVER_RUN_DIR/artifact-store" \
  RAVENROOT_EXECUTION_STORE_DIR="$SERVER_RUN_DIR/execution-store" \
  RAVENROOT_AUDIT_DIR="$SERVER_RUN_DIR/audit" \
  RAVENROOT_CREDENTIAL_DIR="$SERVER_RUN_DIR/credentials" \
  RAVENROOT_ASSISTANT_CONSENT_DIR="$SERVER_RUN_DIR/assistant-consent" \
    "$PROJECT_DIR/ravenroot/scripts/server.sh" "$@"
}

cleanup() {
  run_server stop >/dev/null 2>&1 || true
  RAVENROOT_HOST_PORT="$COMPOSE_PORT" "$PROJECT_DIR/service.sh" down >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

# Use the same repository-pinned Node selection as both supported launchers.
# shellcheck source=scripts/lib/node-runtime.sh
. "$PROJECT_DIR/scripts/lib/node-runtime.sh"
select_node_runtime

run_browser_check() {
  profile=$1
  origin=$2
  echo "== Selection shortcut profile: $profile"
  (
    cd "$UI_DIR"
    RAVENROOT_LAUNCH_PROFILE_ORIGIN="$origin" \
      npx playwright test --config playwright.launch-profile.config.js --reporter=line
  )
}

for command in java mvn node npm docker jq; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Required command not found: $command" >&2
    exit 2
  fi
done

# Build the packaged artifact explicitly so server start cannot accidentally exercise an older UI.
# This harness owns browser behavior, while the ordinary unit/backend suites own their respective
# regressions, so avoid rerunning both complete test suites merely to prepare the launch artifact.
# The opt-out is only for a local rerun immediately after this exact build has already completed.
if [ "${RAVENROOT_SELECTION_SKIP_BUILD:-false}" != true ]; then
  (
    cd "$UI_DIR"
    npm ci
    npm run build
  )
  (
    cd "$PROJECT_DIR/ravenroot"
    mvn --batch-mode --no-transfer-progress -DskipTests clean package
  )
fi
run_server start
run_browser_check 'server start' "http://127.0.0.1:$SERVER_PORT"
run_server stop

# --skipbuild skips the duplicate host build; the Dockerfile still compiles the checked-out source.
RAVENROOT_HOST_PORT="$COMPOSE_PORT" "$PROJECT_DIR/service.sh" start --skipbuild
run_browser_check 'service start' "http://127.0.0.1:$COMPOSE_PORT"

RAVENROOT_HOST_PORT="$COMPOSE_PORT" "$PROJECT_DIR/service.sh" restart --skipimage
run_browser_check 'service restart (fresh browser)' "http://127.0.0.1:$COMPOSE_PORT"
