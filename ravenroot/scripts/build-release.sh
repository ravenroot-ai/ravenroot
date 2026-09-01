#!/usr/bin/env sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

for command in java mvn node npm; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Required command not found: $command" >&2
    exit 1
  fi
done

PRODUCT_VERSION=$(
  cd "$PROJECT_DIR"
  mvn --quiet --no-transfer-progress help:evaluate \
    -Dexpression=project.version -DforceStdout
)

(
  cd "$PROJECT_DIR/ravenroot-ui"
  npm ci
  npm test
  npm run build
)

(
  cd "$PROJECT_DIR"
  mvn -B clean verify
)

echo "Ravenroot release $PRODUCT_VERSION built."
echo "Runnable artifact: $PROJECT_DIR/ravenroot-distribution/target/ravenroot.jar"
