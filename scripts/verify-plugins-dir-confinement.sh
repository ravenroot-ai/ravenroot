#!/usr/bin/env sh
# Proves the RAVENROOT_PLUGINS_DIR build argument cannot be used to reach content
# outside the Docker build context, and that the Dockerfile's own explicit validation -- not Docker's
# build-context handling -- is what makes that true.
#
# This distinction is load-bearing: a Dockerfile and its
# accompanying documentation claimed Docker "refuses" an escaping path. It does not. Docker CLAMPS a
# COPY source path to the build context root rather than erroring, which this script demonstrates
# directly with a throwaway probe Dockerfile before testing anything else -- `RAVENROOT_PLUGINS_DIR=../`
# does not fail, it silently stages the entire build context as "plugin source". Content never
# reached outside the context either way, but relying on that clamping alone, and describing it as a
# refusal, would have been a comment asserting a security property the code did not actually provide.
#
# What actually protects the real build is the explicit `case` check near the top of the java-build
# stage in the Dockerfile, before COPY ravenroot/. This script proves that check works: several
# malicious/malformed values are shown failing FAST (before the expensive Maven build ever starts),
# and the default value is shown succeeding through to a completed java-build stage.
#
# Usage: ./scripts/verify-plugins-dir-confinement.sh
# Requires Docker. Exits non-zero if any expectation is violated.
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
FAILURES=0

log() {
  printf '%s\n' "$1"
}

# ---- Part 1: the mechanism claim, isolated and cheap ------------------------------------------
# A throwaway probe Dockerfile, unrelated to the real one, so this part costs seconds regardless of
# how large the real repository's build context is.
PROBE_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-confinement-probe.XXXXXX")
trap 'rm -rf "$PROBE_DIR"; docker rmi -f "$PROBE_TAG" >/dev/null 2>&1 || true' EXIT
mkdir -p "$PROBE_DIR/context/inside"
echo "inside-context" > "$PROBE_DIR/context/inside/marker.txt"
echo "context-root-marker" > "$PROBE_DIR/context/root-marker.txt"
cat > "$PROBE_DIR/context/Dockerfile" <<'EOF'
# syntax=docker/dockerfile:1.7
FROM busybox:1.36
ARG PROBE_DIR=inside
COPY ${PROBE_DIR}/ /dest/
EOF
PROBE_TAG="ravenroot-confinement-probe:$$"

log "[1/2] Confirming Docker clamps rather than refuses (throwaway probe, not the real Dockerfile) ..."
if ! docker build --build-arg PROBE_DIR=../ -t "$PROBE_TAG" "$PROBE_DIR/context" >/dev/null 2>&1; then
  log "  UNEXPECTED: Docker refused PROBE_DIR=../ outright. If Docker's own behaviour has changed to"
  log "  refuse rather than clamp, the Dockerfile's explicit check is still correct but this proof's"
  log "  premise needs updating -- do not assume the old finding still holds."
  FAILURES=$((FAILURES + 1))
else
  if docker run --rm "$PROBE_TAG" sh -c 'test -f /dest/root-marker.txt' >/dev/null 2>&1; then
    log "  Confirmed: PROBE_DIR=../ succeeded and staged the context root (root-marker.txt present)."
  else
    log "  UNEXPECTED: build succeeded but the context root was not staged as expected."
    FAILURES=$((FAILURES + 1))
  fi
fi
docker rmi -f "$PROBE_TAG" >/dev/null 2>&1 || true

# ---- Part 2: the real protection, against the real Dockerfile ---------------------------------
log "[2/2] Testing the real Dockerfile's RAVENROOT_PLUGINS_DIR validation ..."

assert_build_fails_fast() {
  value=$1
  label=$2
  started=$(date +%s)
  if docker build --build-arg "RAVENROOT_PLUGINS_DIR=$value" \
      --target java-build -t "ravenroot-confinement-test:$$" "$PROJECT_DIR" >/tmp/confinement-build.$$.log 2>&1; then
    log "  FAIL ($label): RAVENROOT_PLUGINS_DIR='$value' built successfully; it should have been refused."
    FAILURES=$((FAILURES + 1))
  else
    elapsed=$(($(date +%s) - started))
    if grep -q "RAVENROOT_PLUGINS_DIR must be" /tmp/confinement-build.$$.log; then
      log "  OK ($label): refused in ${elapsed}s with the expected message."
    else
      log "  FAIL ($label): build failed, but not with the expected validation message -- see /tmp/confinement-build.$$.log"
      FAILURES=$((FAILURES + 1))
    fi
  fi
  rm -f /tmp/confinement-build.$$.log
  docker rmi -f "ravenroot-confinement-test:$$" >/dev/null 2>&1 || true
}

assert_build_fails_fast "../" "parent directory, trailing slash"
assert_build_fails_fast ".." "parent directory, no trailing slash"
assert_build_fails_fast "/etc" "absolute path"
assert_build_fails_fast "" "empty value"
assert_build_fails_fast "ravenroot-plugins/../ravenroot-plugins" "contains .. even though it resolves to itself"

log "  Confirming the default value still succeeds (this leg runs the real Maven build; slower) ..."
if docker build --target java-build -t "ravenroot-confinement-test:$$" "$PROJECT_DIR" >/tmp/confinement-default.$$.log 2>&1; then
  log "  OK (default): RAVENROOT_PLUGINS_DIR default built successfully through java-build."
else
  log "  FAIL (default): the default value should build successfully -- see /tmp/confinement-default.$$.log"
  FAILURES=$((FAILURES + 1))
fi
rm -f /tmp/confinement-default.$$.log
docker rmi -f "ravenroot-confinement-test:$$" >/dev/null 2>&1 || true

if [ "$FAILURES" -gt 0 ]; then
  log ""
  log "$FAILURES expectation(s) failed."
  exit 1
fi
log ""
log "All expectations met."
