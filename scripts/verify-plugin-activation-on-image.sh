#!/usr/bin/env sh
# The contract requirement in its strongest form -- "present but not
# activated, proven inert" -- verified on a REAL, Dockerfile.ci-built published image, with TWO
# bundles staged and only ONE enabled.
#
# This is the third leg of a three-part proof, not a standalone one: PluginBundleLoaderTest
# proves presence-never-executes synthetically, against a poison-directory fixture designed
# to fail loudly if ever loaded; the dogfooding ravenroot-mail bundle already proves it locally,
# against the real bundle, activating when enabled and not when the allowlist is empty. Neither of
# those runs against the actual published-image build path or asks a running
# server's own API whether a behavior is registered, rather than reading logs or classloader internals.
# This script closes that gap: it builds the real mail bundle AND a second, purpose-built, always-inert
# fixture bundle (scripts/fixtures/inert-plugin-fixture/), stages BOTH into a Dockerfile.ci build
# exactly as the published-image artifact path does, enables ONLY mail, and queries the running
# container's own GET /v1/node-types endpoint (unauthenticated, since RAVENROOT_AUTH_MODE=disabled
# wires DisabledLoopbackAuthenticator, which never inspects headers at all -- see
# RavenrootServerTest's own use of the same endpoint under the same mode) to prove mail's behaviors are
# registered and the fixture's is not, on the actual image bytes a user would pull.
#
# Usage: ./scripts/verify-plugin-activation-on-image.sh
# Requires Docker, Maven, Java (javac), curl. Does not require npm: unlike
# verify-empty-plugins-parity-ci.sh, this proof is not about the UI, and re-staging a real UI dist just
# to satisfy Dockerfile.ci's COPY is unnecessary complexity here -- a directory with a single harmless
# placeholder file satisfies that COPY identically to a real build output, since nothing this script
# checks depends on UI content.
#
# ~/.m2 disclosure: this script never runs `mvn install`, only `package`/`compile`/
# `dependency:copy-dependencies` (via plugin.sh) against whatever local repository Maven is already
# configured to use -- no different from running plugin.sh directly outside this script.
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
REACTOR_DIR="$PROJECT_DIR/ravenroot"
APPLICATION_API_CLASSES="$REACTOR_DIR/ravenroot-application-api/target/classes"
PLUGIN_BUNDLE_CLASSES="$REACTOR_DIR/ravenroot-plugin-bundle/target/classes"
WORKDIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-activation-image.XXXXXX")
IMAGE="ravenroot-activation-image-test:$$"
CONTAINER=""

cleanup() {
  [ -n "$CONTAINER" ] && docker rm --force "$CONTAINER" >/dev/null 2>&1 || true
  docker rmi "$IMAGE" >/dev/null 2>&1 || true
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
require_command javac
require_command curl

if [ ! -d "$APPLICATION_API_CLASSES" ] || [ ! -d "$PLUGIN_BUNDLE_CLASSES" ]; then
  echo "Compiling ravenroot-plugin-bundle (first run, or classes missing)..." >&2
  ( cd "$REACTOR_DIR" && mvn -q -B -pl ravenroot-plugin-bundle -am compile )
fi
if [ ! -f "$REACTOR_DIR/ravenroot-distribution/target/ravenroot.jar" ]; then
  echo "ravenroot.jar not found -- run 'mvn -DskipTests package' in ravenroot/ first." >&2
  exit 1
fi

# ---- 1. Build the real dogfooding mail bundle --------------------------------------------------
echo "Building mail..." >&2
"$PROJECT_DIR/plugin.sh" build mail --skip-tests
MAIL_BUNDLE_DIR=$("$PROJECT_DIR/plugin.sh" bundle-dir mail)

# ---- 2. Build the second, always-inert fixture bundle -------------------------------------------
# Standalone: compiled directly against ravenroot-application-api's already-built classes, exactly
# like a real extension's classes would be, but with no pom.xml, no reactor module, and no plugin.sh
# known_extensions entry -- this must never be buildable by a normal developer command, only by this
# script, so it can never accidentally end up in PUBLISHED_PLUGINS.
echo "Building the inert fixture bundle..." >&2
FIXTURE_SRC="$PROJECT_DIR/scripts/fixtures/inert-plugin-fixture"
FIXTURE_CLASSES="$WORKDIR/inert-fixture-classes"
mkdir -p "$FIXTURE_CLASSES"
javac -d "$FIXTURE_CLASSES" -cp "$APPLICATION_API_CLASSES" \
    "$FIXTURE_SRC/ai/ravenroot/test/inert/InertFixtureNodePackage.java"

FIXTURE_JAR="$WORKDIR/inert-fixture.jar"
# InertFixtureNodePackage's own behavior is a private nested class, compiled by javac to a SEPARATE
# InertFixtureNodePackage$InertProbeBehavior.class -- omitting it here once produced a real
# MISSING_DEPENDENCY refusal at activation time (a genuine, previously-tested security property
# firing correctly, just for the wrong reason: a broken test fixture, not a broken bundle). Packing
# both class files, exactly like PluginActivationOrchestratorTest's own writeFixtureBundle helper
# does for its analogous OrchestratorFixtureNodePackage$ProbeBehavior, is what avoids that.
( cd "$FIXTURE_CLASSES" && jar --create --file "$FIXTURE_JAR" \
    ai/ravenroot/test/inert/InertFixtureNodePackage.class \
    'ai/ravenroot/test/inert/InertFixtureNodePackage$InertProbeBehavior.class' )

FIXTURE_BUNDLE_DIR="$WORKDIR/inert-fixture-bundle"
# generate-manifest instantiates the named class to read its id/version/behaviors, so its own
# compiled classes -- not just the jar it is about to describe -- must be on this classpath too,
# exactly like plugin.sh's build_one does for a real extension's target/classes.
java -cp "$PLUGIN_BUNDLE_CLASSES:$APPLICATION_API_CLASSES:$FIXTURE_CLASSES" ai.ravenroot.plugin.bundle.PluginCli \
    generate-manifest "$FIXTURE_BUNDLE_DIR" "$FIXTURE_JAR" ai.ravenroot.test.inert.InertFixtureNodePackage

# ---- 3. Stage both bundles into ci-artifacts/backend/plugins/ -------------------------------
# The published-plugin staging step resolves each bundle's own
# manifest id as the destination directory name, never a short/convenience name -- the exact bug
# destination from the manifest rather than a short or convenient name.
echo "Staging both bundles..." >&2
STAGE_DIR="$WORKDIR/ci-artifacts/backend/plugins"
mkdir -p "$STAGE_DIR"
"$PROJECT_DIR/plugin.sh" install "$MAIL_BUNDLE_DIR" --dir "$STAGE_DIR"
"$PROJECT_DIR/plugin.sh" install "$FIXTURE_BUNDLE_DIR" --dir "$STAGE_DIR"
echo "Staged:" >&2
find "$STAGE_DIR" -maxdepth 1 -mindepth 1 >&2

# ---- 4. Assemble the rest of the Dockerfile.ci build context -------------------------------------
BUILD_CTX="$WORKDIR/build-ctx"
mkdir -p "$BUILD_CTX/ci-artifacts/backend/targets/ravenroot/ravenroot-distribution/target" \
    "$BUILD_CTX/ci-artifacts/ui"
cp "$REACTOR_DIR/ravenroot-distribution/target/ravenroot.jar" \
    "$BUILD_CTX/ci-artifacts/backend/targets/ravenroot/ravenroot-distribution/target/ravenroot.jar"
# A minimal, harmless UI placeholder -- this proof is not about UI content, and Dockerfile.ci's
# COPY ci-artifacts/ui/ ./ui-resources/ui/ only needs the source directory to exist.
echo "<html></html>" > "$BUILD_CTX/ci-artifacts/ui/index.html"
cp -a "$STAGE_DIR" "$BUILD_CTX/ci-artifacts/backend/plugins"
cp "$PROJECT_DIR/Dockerfile.ci" "$BUILD_CTX/Dockerfile.ci"

# ---- 5. Build the image ---------------------------------------------------------------------------
echo "Building image..." >&2
docker build -t "$IMAGE" -f "$BUILD_CTX/Dockerfile.ci" "$BUILD_CTX" >&2

# ---- 6. Boot with ONLY mail enabled; the fixture is present but never named -----------------------
echo "Starting container (RAVENROOT_ENABLED_PLUGINS=ai.ravenroot.extensions.mail only)..." >&2
CONTAINER=$(docker run --detach --publish 127.0.0.1:0:8080 \
    -e RAVENROOT_AUTH_MODE=disabled \
    -e RAVENROOT_BIND_ADDRESS=0.0.0.0 \
    -e RAVENROOT_CONTAINER_LOOPBACK_ONLY=true \
    -e RAVENROOT_LOCAL_HOST_BIND_ADDRESS=127.0.0.1 \
    -e RAVENROOT_ENABLED_PLUGINS=ai.ravenroot.extensions.mail \
    "$IMAGE")
HOST_PORT=$(docker port "$CONTAINER" 8080/tcp | head -n1 | sed 's/.*://')

up=0
i=0
while [ "$i" -lt 30 ]; do
  if curl --fail --silent "http://127.0.0.1:$HOST_PORT/health" >/dev/null 2>&1; then
    up=1
    break
  fi
  i=$((i + 1))
  sleep 1
done
if [ "$up" -ne 1 ]; then
  echo "Container did not become healthy." >&2
  docker logs "$CONTAINER" >&2
  exit 1
fi

# ---- 7. Assert against the real endpoint, not just logs --------------------------------------------
echo "Querying /v1/node-types ..." >&2
CATALOG=$(curl --fail --silent "http://127.0.0.1:$HOST_PORT/v1/node-types")

fail=0
case "$CATALOG" in
  *'"behavior":"mail.send"'*) echo "OK: mail.send is registered" >&2 ;;
  *) echo "FAIL: mail.send is NOT registered -- the enabled bundle did not activate" >&2; fail=1 ;;
esac
case "$CATALOG" in
  *'"behavior":"mail.imap.query"'*) echo "OK: mail.imap.query is registered" >&2 ;;
  *) echo "FAIL: mail.imap.query is NOT registered -- the enabled bundle did not activate" >&2; fail=1 ;;
esac
for behavior in mail.imap.move mail.imap.delete; do
  case "$CATALOG" in
    *"\"behavior\":\"$behavior\""*) echo "OK: $behavior is registered" >&2 ;;
    *) echo "FAIL: $behavior is NOT registered -- the enabled bundle did not activate" >&2; fail=1 ;;
  esac
done
case "$CATALOG" in
  *'"behavior":"mail.imap.consume"'*) echo "OK: mail.imap.consume is registered" >&2 ;;
  *) echo "FAIL: mail.imap.consume is NOT registered -- the enabled bundle did not activate" >&2; fail=1 ;;
esac
case "$CATALOG" in
  *'"behavior":"test.inert.probe"'*)
    echo "FAIL: test.inert.probe IS registered -- the present-but-not-enabled fixture activated" >&2
    fail=1
    ;;
  *) echo "OK: test.inert.probe is absent -- the present-but-not-enabled fixture is inert" >&2 ;;
esac

LOGS=$(docker logs "$CONTAINER" 2>&1)
case "$LOGS" in
  *"id=ai.ravenroot.extensions.mail"*) echo "OK: activation log names mail" >&2 ;;
  *) echo "FAIL: no activation log line for mail" >&2; fail=1 ;;
esac
case "$LOGS" in
  *"ai.ravenroot.test.inert-fixture"*)
    echo "FAIL: the inert fixture's id appears in the logs at all -- it should never be touched" >&2
    fail=1
    ;;
  *) echo "OK: the inert fixture's id never appears in the logs" >&2 ;;
esac

if [ "$fail" -ne 0 ]; then
  echo "FAILED: see above." >&2
  exit 1
fi
echo "PASSED: one bundle activated, one present-but-inactive bundle proven inert, on a real Dockerfile.ci-built image."
