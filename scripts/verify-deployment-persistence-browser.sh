#!/usr/bin/env sh
# Real Chromium + JVM + SQLite proof for issue 464's durable Deployments workflow.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
REACTOR_DIR="$PROJECT_DIR/ravenroot"
UI_DIR="$REACTOR_DIR/ravenroot-ui"

echo "Building the editor..." >&2
(cd "$UI_DIR" && npm run build >/dev/null)

echo "Running the real persisted deployment browser lifecycle..." >&2
mvn -B -f "$REACTOR_DIR/pom.xml" \
  -pl ravenroot-server -am \
  -Dravenroot.deploymentPersistence.browserTest=true \
  -Dtest=DeploymentLifecyclePersistenceBrowserTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test

echo "PASSED: legacy Stop returned 400; corrected Start/Stop/Restart/Undeploy persisted and reconciled."
