#!/usr/bin/env sh
# QA-11 requires coverage of both build paths. Container end-to-end tests exist for PLAT-12
# (`scripts/verify-plugin-activation-on-image.sh`, against Dockerfile.ci, the PUBLISHED image path),
# but they do not prove the other path: local `Dockerfile` via `docker compose`
# (`./service.sh`), the path a developer or an integrator actually uses, and the one
# docs/getting-started/plugin-bundles.md's "Scoping" section says increments 1-2's parity/confinement
# proofs already cover for build-time behavior only — never runtime activation.
#
# What it asserts, deliberately more than "the container starts": a smoke test that only checked
# health would pass identically with zero plugins loaded, leaving activation untested.
# This script instead:
#   1. Builds a REAL `ravenroot-mail` bundle and stages it where `compose.yaml`'s own
#      `RAVENROOT_PLUGINS_DIR` build arg expects it (a path relative to the build context root,
#      exactly like a developer's own `./ravenroot-plugins/` would be).
#   2. Builds the Compose image once (`./service.sh restart --skipbuild` — skips service.sh's own
#      host-side `npm`/`mvn` pre-build so this script never touches host tooling beyond `plugin.sh
#      build`, and lets `docker compose build` do a fully self-contained build exactly as
#      `compose.yaml`/`Dockerfile` document; unlike the UI palette test this build is NOT run
#      against an isolated Maven repo on the host, because the Maven inside the Dockerfile build runs
#      INSIDE the container against its own BuildKit cache mount, never against a shared host ~/.m2).
#   3. Starts the stack via Compose (not a raw `docker run`) with the bundle enabled, waits for the
#      service's own healthcheck, and asserts `/v1/node-types` on the published host port contains
#      all of the bundle's behaviors (GREEN).
#   4. Restarts the SAME already-built image via Compose with `RAVENROOT_ENABLED_PLUGINS` unset —
#      same installed bundle, not rebuilt — and asserts those same behaviors are ABSENT (RED).
#      Built-in node types remaining present rules out "the catalog endpoint broke" as a false pass.
#
# Isolation from any OTHER Ravenroot Compose deployment on this machine: `compose.yaml` names its
# project "ravenroot" and its image "ravenroot:local" as fixed defaults, and both are shared across
# ANY invocation unless overridden — a real developer's own `./service.sh` instance would otherwise
# collide with this script's containers, network and named volume, or have its own image tag
# overwritten by this script's build. `COMPOSE_PROJECT_NAME` and `RAVENROOT_IMAGE` are set to values
# unique to this run's PID for exactly that reason, the same way
# scripts/verify-plugin-activation-on-image.sh tags its own throwaway image.
#
# Requires: Docker, Docker Compose v2, Maven, Java (for `plugin.sh build`), curl, jq (service.sh's
# own preflight requires it).
#
# Usage: ./scripts/verify-plugin-activation-on-compose.sh
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
REACTOR_DIR="$PROJECT_DIR/ravenroot"
MVN_CONFIG_DIR="$REACTOR_DIR/.mvn"
MVN_CONFIG_FILE="$MVN_CONFIG_DIR/maven.config"

WORKDIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-plugin-compose.XXXXXX")
M2_REPO=${RAVENROOT_VERIFY_MAVEN_REPO_LOCAL:-"$WORKDIR/m2-repo"}
CREATED_MVN_CONFIG=0

# Relative to $PROJECT_DIR (the build context root) and dot-prefixed so it never collides with a
# developer's own ./ravenroot-plugins/. No ".." anywhere in the name -- the Dockerfile's own
# confinement check (see its PLAT-12 comment) refuses a value containing that substring, and this
# script's own value must pass the same check a real caller's would.
PLUGINS_REL_DIR=".verify-plugin-compose-plugins-$$"
PLUGINS_ABS_DIR="$PROJECT_DIR/$PLUGINS_REL_DIR"

IMAGE_TAG="ravenroot-verify-compose:$$"
COMPOSE_PROJECT="ravenroot-verify-compose-$$"
export COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT"
export RAVENROOT_IMAGE="$IMAGE_TAG"

# A host port unlikely to collide with a developer's own running instance (service.sh's own default
# is 8080) or with another concurrent run of this script -- derived from this process's PID rather
# than fixed, cheaply avoiding the exact hazard e2e/ports.mjs documents for the UI suite's own pair.
HOST_PORT=$((20000 + ($$ % 20000)))
export RAVENROOT_HOST_PORT="$HOST_PORT"

STARTED=0

cleanup() {
  if [ "$STARTED" = 1 ]; then
    ( cd "$PROJECT_DIR" && ./service.sh stop ) >/dev/null 2>&1 || true
  fi
  # Belt and suspenders beyond service.sh's own `compose down --remove-orphans`: also drop the
  # per-run named volume so a run never leaves a `ravenroot-verify-compose-<pid>_ravenroot-audit-
  # data` volume behind on every invocation.
  ( cd "$PROJECT_DIR" && docker compose --file compose.yaml down --volumes --remove-orphans ) >/dev/null 2>&1 || true
  docker rmi "$IMAGE_TAG" >/dev/null 2>&1 || true
  if [ "$CREATED_MVN_CONFIG" = 1 ]; then
    rm -f "$MVN_CONFIG_FILE"
    rmdir "$MVN_CONFIG_DIR" 2>/dev/null || true
  fi
  rm -rf "$PLUGINS_ABS_DIR"
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}
require_command docker
require_command mvn
require_command java
require_command curl
require_command jq

if [ -e "$MVN_CONFIG_FILE" ]; then
  echo "Refusing to run: $MVN_CONFIG_FILE already exists (a previous run did not clean up, or" >&2
  echo "another process is using it). Remove it and rerun." >&2
  exit 1
fi
if [ -e "$PLUGINS_ABS_DIR" ]; then
  echo "Refusing to run: $PLUGINS_ABS_DIR already exists." >&2
  exit 1
fi

mkdir -p "$MVN_CONFIG_DIR"
printf -- '-Dmaven.repo.local=%s\n' "$M2_REPO" > "$MVN_CONFIG_FILE"
CREATED_MVN_CONFIG=1
echo "Isolated Maven repository (host-side plugin.sh build only): $M2_REPO" >&2

# See docs/getting-started/plugin-bundles.md#isolated-repositories-and-the-one-command-that-breaks-under-them:
# `plugin.sh build`'s dependency:copy-dependencies call must still RESOLVE ai.ravenroot.* coordinates
# even though it excludes them from copying, so the reactor has to be installed into the isolated
# repository once before `plugin.sh build` will succeed against it.
echo "Installing the reactor into the isolated repository..." >&2
( cd "$REACTOR_DIR" && mvn -B -DskipTests clean install )

echo "Building the real mail bundle..." >&2
"$PROJECT_DIR/plugin.sh" build mail --skip-tests
MAIL_BUNDLE_DIR=$("$PROJECT_DIR/plugin.sh" bundle-dir mail)
"$PROJECT_DIR/plugin.sh" install "$MAIL_BUNDLE_DIR" --dir "$PLUGINS_ABS_DIR"
echo "Staged at $PLUGINS_ABS_DIR:" >&2
find "$PLUGINS_ABS_DIR" -maxdepth 1 -mindepth 1 >&2

fail=0

echo "==== GREEN: building the Compose image with the bundle staged, enabling it ====" >&2
( cd "$PROJECT_DIR" \
  && RAVENROOT_PLUGINS_DIR="$PLUGINS_REL_DIR" RAVENROOT_ENABLED_PLUGINS=ai.ravenroot.extensions.mail \
     ./service.sh restart --skipbuild )
STARTED=1

echo "Querying /v1/node-types on 127.0.0.1:$HOST_PORT ..." >&2
CATALOG=$(curl --fail --silent "http://127.0.0.1:$HOST_PORT/v1/node-types")
case "$CATALOG" in
  *'"behavior":"mail.send"'*) echo "OK: mail.send is registered (enabled)" >&2 ;;
  *) echo "FAIL: mail.send is NOT registered with the bundle enabled" >&2; fail=1 ;;
esac
case "$CATALOG" in
  *'"behavior":"mail.imap.query"'*) echo "OK: mail.imap.query is registered (enabled)" >&2 ;;
  *) echo "FAIL: mail.imap.query is NOT registered with the bundle enabled" >&2; fail=1 ;;
esac
case "$CATALOG" in
  *'"behavior":"mail.imap.consume"'*) echo "OK: mail.imap.consume is registered (enabled)" >&2 ;;
  *) echo "FAIL: mail.imap.consume is NOT registered with the bundle enabled" >&2; fail=1 ;;
esac
for behavior in mail.imap.move mail.imap.delete; do
  case "$CATALOG" in
    *"\"behavior\":\"$behavior\""*) echo "OK: $behavior is registered (enabled)" >&2 ;;
    *) echo "FAIL: $behavior is NOT registered with the bundle enabled" >&2; fail=1 ;;
  esac
done

echo "==== RED: same image, same installed bundle, RAVENROOT_ENABLED_PLUGINS unset ====" >&2
( cd "$PROJECT_DIR" && ./service.sh stop )
STARTED=0
( cd "$PROJECT_DIR" \
  && RAVENROOT_PLUGINS_DIR="$PLUGINS_REL_DIR" \
     ./service.sh restart --skipimage )
STARTED=1

CATALOG=$(curl --fail --silent "http://127.0.0.1:$HOST_PORT/v1/node-types")
case "$CATALOG" in
  *'"behavior":"mail.send"'*)
    echo "FAIL: mail.send IS registered even though RAVENROOT_ENABLED_PLUGINS was not set -- presence on disk activated on its own" >&2
    fail=1
    ;;
  *) echo "OK: mail.send is absent -- installed but not enabled never activates" >&2 ;;
esac
for behavior in mail.imap.move mail.imap.delete; do
  case "$CATALOG" in
    *"\"behavior\":\"$behavior\""*)
      echo "FAIL: $behavior IS registered even though RAVENROOT_ENABLED_PLUGINS was not set" >&2
      fail=1
      ;;
    *) echo "OK: $behavior is absent" >&2 ;;
  esac
done
case "$CATALOG" in
  *'"behavior":"mail.imap.query"'*)
    echo "FAIL: mail.imap.query IS registered even though RAVENROOT_ENABLED_PLUGINS was not set" >&2
    fail=1
    ;;
  *) echo "OK: mail.imap.query is absent" >&2 ;;
esac
case "$CATALOG" in
  *'"behavior":"mail.imap.consume"'*)
    echo "FAIL: mail.imap.consume IS registered even though RAVENROOT_ENABLED_PLUGINS was not set" >&2
    fail=1
    ;;
  *) echo "OK: mail.imap.consume is absent" >&2 ;;
esac
# A smoke test that only checked "the container starts" would pass with zero plugins loaded (the
# documented failure mode) -- this assertion is what tells that apart from a genuinely
# healthy catalog: the built-in node types must still be there.
case "$CATALOG" in
  *'"behavior":"template"'*) echo "OK: built-in node types are still present -- the catalog endpoint itself is healthy" >&2 ;;
  *) echo "FAIL: the catalog looks broken, not merely missing the plugin -- 'template' is absent too" >&2; fail=1 ;;
esac

if [ "$fail" -ne 0 ]; then
  echo "FAILED: see above." >&2
  exit 1
fi
echo "PASSED: the Compose build path activates an enabled plugin bundle and leaves an installed-but-not-enabled one inert, on the actual local docker-compose.yaml/Dockerfile a developer or integrator uses."
