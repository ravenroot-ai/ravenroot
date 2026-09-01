#!/usr/bin/env sh
# QA-11 requires a test of the path from a plugin bundle's real catalog response to a visible palette
# entry and its Inspector panel; the PLAT-12 coverage did not exercise that path.
# The documentation argument that the palette needed no UI change because it is server-driven is
# correct (verified again by this script, which changes no UI code), but an architectural argument
# is not an executable test.
#
# This is the third member of a family, alongside scripts/verify-plugin-activation-on-image.sh and
# PluginBundleLoaderTest: each proves "present but not enabled
# never activates" against a different layer. This one is the browser/UI layer, and it is the only
# one of the three that drives a real page with Playwright.
#
# What it does:
#   1. Builds the UI (`npm run build`) and the full Java reactor (`mvn ... install`, into an
#      ISOLATED local repository so a shared `~/.m2` is never touched — see
#      docs/getting-started/plugin-bundles.md#isolated-repositories-and-the-one-command-that-breaks-under-them
#      for why `install`, not `package`, is required before `plugin.sh build` works against an
#      isolated repository).
#   2. Builds the real `ravenroot-mail` bundle (`./plugin.sh build mail`) and installs it into a
#      throwaway plugins directory (`./plugin.sh install`) — installed, deliberately not yet enabled.
#   3. Runs `ravenroot/ravenroot-ui/e2e/plugin-ui/plugin-palette-inspector.spec.js` against
#      `playwright.plugin-ui.config.js` TWICE, each invocation starting and stopping a REAL
#      `ravenroot.jar` as Playwright's own `webServer`:
#        - GREEN: RAVENROOT_ENABLED_PLUGINS=ai.ravenroot.extensions.mail — the spec asserts all of
#          the bundle's behaviors are visible palette entries and that clicking one populates the
#          Inspector's property fields from that behavior's OWN NodeTypeDescriptor, fetched live from
#          the running server, never a hardcoded fixture.
#        - RED: RAVENROOT_ENABLED_PLUGINS unset, same installed bundle — the spec asserts the SAME
#          same behaviors are ABSENT from the palette. This is the half that must fail if presence on
#          disk were ever mistaken for activation; it is not merely something this script's author
#          once saw fail, it is asserted on every run.
#
# Requires: JDK 21, Maven, Node.js, npm, a Playwright Chromium install (`npx playwright install
# chromium` once, same requirement as `npm run test:e2e`). Not Docker — unlike the container test,
# this proof runs the JAR path (quickstart.md Path 1), which is the lighter of the two documented
# local routes and does not need an image build to answer a UI question.
#
# Usage: ./scripts/verify-plugin-palette-ui.sh
# Optional: RAVENROOT_VERIFY_MAVEN_REPO_LOCAL=/path/to/warm/repo to reuse an already-populated,
# throwaway Maven repository instead of downloading the world into a fresh empty one every run —
# still isolated from any shared ~/.m2, just not re-downloaded. Left unset, a fresh empty one is
# created and removed automatically, matching the documented default.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
REACTOR_DIR="$PROJECT_DIR/ravenroot"
UI_DIR="$REACTOR_DIR/ravenroot-ui"
MVN_CONFIG_DIR="$REACTOR_DIR/.mvn"
MVN_CONFIG_FILE="$MVN_CONFIG_DIR/maven.config"

WORKDIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-plugin-ui.XXXXXX")
PLUGINS_DIR="$WORKDIR/plugins"
M2_REPO=${RAVENROOT_VERIFY_MAVEN_REPO_LOCAL:-"$WORKDIR/m2-repo"}
CREATED_MVN_CONFIG=0

cleanup() {
  if [ "$CREATED_MVN_CONFIG" = 1 ]; then
    rm -f "$MVN_CONFIG_FILE"
    rmdir "$MVN_CONFIG_DIR" 2>/dev/null || true
  fi
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}
require_command java
require_command mvn
require_command node
require_command npm

if [ -e "$MVN_CONFIG_FILE" ]; then
  echo "Refusing to run: $MVN_CONFIG_FILE already exists (a previous run did not clean up, or" >&2
  echo "another process is using it). Remove it and rerun." >&2
  exit 1
fi
mkdir -p "$MVN_CONFIG_DIR"
printf -- '-Dmaven.repo.local=%s\n' "$M2_REPO" > "$MVN_CONFIG_FILE"
CREATED_MVN_CONFIG=1
echo "Isolated Maven repository: $M2_REPO" >&2

echo "Building the UI..." >&2
( cd "$UI_DIR" && npm ci && npm run build )

echo "Installing the reactor into the isolated repository (also produces ravenroot.jar)..." >&2
( cd "$REACTOR_DIR" && mvn -B -DskipTests clean install )

JAR="$REACTOR_DIR/ravenroot-distribution/target/ravenroot.jar"
if [ ! -f "$JAR" ]; then
  echo "ravenroot.jar was not produced by the reactor build." >&2
  exit 1
fi

echo "Building the real mail bundle..." >&2
"$PROJECT_DIR/plugin.sh" build mail --skip-tests
MAIL_BUNDLE_DIR=$("$PROJECT_DIR/plugin.sh" bundle-dir mail)
"$PROJECT_DIR/plugin.sh" install "$MAIL_BUNDLE_DIR" --dir "$PLUGINS_DIR"

PORT=$(node "$UI_DIR/e2e/plugin-ui-ports.mjs" --print-port)

export RR_PLUGIN_UI_JAR="$JAR"
export RAVENROOT_PORT="$PORT"
export RAVENROOT_BIND_ADDRESS=127.0.0.1
export RAVENROOT_AUTH_MODE=disabled
export RAVENROOT_PLUGINS_INSTALL_DIR="$PLUGINS_DIR"
# Playwright's webServer spawns `java -jar` with the UI package directory as its cwd, and the
# server's audit trail defaults to a RELATIVE `./data/audit`. Left unset, that writes
# `ravenroot-ui/data/` into the working tree as a side effect of running this script — measured
# directly while writing it. Pointed at the throwaway workdir instead, cleaned up with everything
# else in `cleanup()`.
export RAVENROOT_AUDIT_DIR="$WORKDIR/audit"

run_pass() {
  # $1: green | red
  if [ "$1" = green ]; then
    export RAVENROOT_ENABLED_PLUGINS=ai.ravenroot.extensions.mail
    export RR_EXPECT_MAIL_ENABLED=1
  else
    unset RAVENROOT_ENABLED_PLUGINS || true
    export RR_EXPECT_MAIL_ENABLED=0
  fi
  echo "==== $1 pass (RAVENROOT_ENABLED_PLUGINS=${RAVENROOT_ENABLED_PLUGINS:-<unset>}) ====" >&2
  ( cd "$UI_DIR" && npx playwright test -c playwright.plugin-ui.config.js )
}

fail=0
if run_pass green; then
  echo "OK: green pass — the enabled bundle's node types appear in the palette and Inspector" >&2
else
  echo "FAIL: green pass" >&2
  fail=1
fi

if run_pass red; then
  echo "OK: red pass — the installed-but-not-enabled bundle's node types are absent" >&2
else
  echo "FAIL: red pass" >&2
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "FAILED: see above." >&2
  exit 1
fi
echo "PASSED: a real plugin bundle's node types appear in the palette and populate the Inspector from their own NodeTypeDescriptor only when enabled, on the actual documented JAR path, with no UI code changed to make it happen."
