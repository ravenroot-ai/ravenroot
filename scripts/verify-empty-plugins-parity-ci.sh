#!/usr/bin/env sh
# Proves that leaving PUBLISHED_PLUGINS empty preserves the Dockerfile.ci filesystem.
#
# This is a *separate* proof from scripts/verify-empty-plugins-parity.sh, not a parameterised variant
# of it: that script covers the LOCAL/Compose Dockerfile, which compiles from source inside the build
# itself. Dockerfile.ci compiles nothing -- it only assembles pre-built ci-artifacts/. To reproduce
# the artifact-building boundary
# faithfully outside of Actions, this script replicates the same staging commands those jobs run
# (mvn clean install, npm run build, the "Stage backend build outputs" and "Build and stage published
# plugin bundles" staging logic) against two checkouts -- a baseline
# default: where this branch forked from dev) and the current working tree with PUBLISHED_PLUGINS left
# empty -- builds both with Dockerfile.ci, and compares them with the same
# scripts/verify-empty-plugins-parity.py used for the local Dockerfile: the comparator itself does not
# know or care which Dockerfile produced the image, only the staging differs between the two proofs.
#
# Usage: ./scripts/verify-empty-plugins-parity-ci.sh [baseline-ref]
#   baseline-ref  defaults to `git merge-base HEAD origin/dev`.
#
# Requires Docker, Maven and Node/npm. Each side gets its OWN throwaway Maven local repository
# (-Dmaven.repo.local), never the shared ~/.m2 -- neither side runs `install` against it, matching the
# same discipline this repository's own build commands already follow outside of CI. Runs a full
# `mvn clean install` (into the throwaway repo) and `npm run build` on EACH side: slow, but this is the
# thorough proof, not something meant to run on every commit. See verify-empty-plugins-parity.py's own
# docstring for why "clean" (never a bare rebuild over stale output) matters for this exact claim.
#
# The candidate side builds directly against $PROJECT_DIR (deliberately, not a HEAD-only worktree):
# this proof exists to check working-tree changes before they are committed, and a worktree only ever
# sees committed content. stage() below explicitly removes ravenroot-ui/dist before building for
# exactly this reason -- a first version of this script skipped that and got a false "DIFFERENT"
# verdict: $PROJECT_DIR had a leftover ravenroot-ui/dist/ from unrelated manual Dockerfile.ci testing
# earlier in the same session, and Maven's resource plugin embeds ravenroot-ui/dist into ravenroot.jar
# when it happens to exist at build time (the release-artifact boundary check logs
# exactly how many ui/examples/** resources it found for this reason). The baseline worktree never had
# that directory, so the jars differed for a reason with nothing to do with plugins. Real CI never
# hits this: backend-build and ui-build are separate jobs, each from its own independent
# actions/checkout, so ravenroot-ui/dist can never exist when backend-build's `mvn clean install` runs
# -- the explicit rm -rf below is what reproduces that same guarantee against a working tree that, unlike
# a fresh checkout, can have arbitrary leftover state.
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BASELINE_REF=${1:-$(git -C "$PROJECT_DIR" merge-base HEAD origin/dev)}
BASELINE_WORKTREE=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-parity-ci-baseline-src.XXXXXX")
BASELINE_ARTIFACTS=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-parity-ci-baseline-art.XXXXXX")
CANDIDATE_ARTIFACTS=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-parity-ci-candidate-art.XXXXXX")
BASELINE_M2=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-parity-ci-baseline-m2.XXXXXX")
CANDIDATE_M2=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-parity-ci-candidate-m2.XXXXXX")
BASELINE_IMAGE="ravenroot-parity-ci-baseline:$$"
CANDIDATE_IMAGE="ravenroot-parity-ci-candidate:$$"

cleanup() {
  docker rmi "$BASELINE_IMAGE" "$CANDIDATE_IMAGE" >/dev/null 2>&1 || true
  git -C "$PROJECT_DIR" worktree remove "$BASELINE_WORKTREE" --force >/dev/null 2>&1 || true
  rm -rf "$BASELINE_ARTIFACTS" "$CANDIDATE_ARTIFACTS" "$BASELINE_M2" "$CANDIDATE_M2"
}
trap cleanup EXIT

# stage <source-tree> <maven-repo-local> <artifacts-out-dir>
# Mirrors the backend/UI artifact build closely enough for this proof: same mvn
# invocation, same npm build, same target-directory sweep, same unconditional
# plugins/ + .keep staging. PUBLISHED_PLUGINS is not set (empty) for either side, so the loop that
# would build individual extensions runs zero times on both -- this script is the empty-list proof
# only; it is not the tool for proving a *populated* list stages correctly.
stage() {
  src=$1
  m2=$2
  out=$3

  # Removed before every build, on both sides, even though only the $PROJECT_DIR side can realistically
  # have it: a stale ravenroot-ui/dist/ changes what `mvn clean install` embeds into ravenroot.jar (see
  # this script's own header comment for the false-positive this produced once already).
  rm -rf "$src/ravenroot/ravenroot-ui/dist"
  ( cd "$src/ravenroot" && mvn -q -B -Dmaven.repo.local="$m2" -DskipTests clean install )
  ( cd "$src/ravenroot/ravenroot-ui" && npm ci --silent && npm run build --silent )

  mkdir -p "$out/backend/targets" "$out/backend/plugins" "$out/ui"
  touch "$out/backend/plugins/.keep"
  ( cd "$src" && find ravenroot -type d -name target -prune -print0 |
      while IFS= read -r -d '' target; do
        mkdir -p "$out/backend/targets/$(dirname "$target")"
        cp -a "$src/$target" "$out/backend/targets/$target"
      done )
  cp -a "$src/ravenroot/ravenroot-ui/dist/." "$out/ui/"
}

echo "Baseline ref: $BASELINE_REF"
git -C "$PROJECT_DIR" worktree add --detach "$BASELINE_WORKTREE" "$BASELINE_REF" >/dev/null

echo "Staging baseline ci-artifacts from $BASELINE_WORKTREE ..."
stage "$BASELINE_WORKTREE" "$BASELINE_M2" "$BASELINE_ARTIFACTS"

echo "Staging candidate ci-artifacts from $PROJECT_DIR (working tree, PUBLISHED_PLUGINS left empty) ..."
stage "$PROJECT_DIR" "$CANDIDATE_M2" "$CANDIDATE_ARTIFACTS"

# Neither build overrides VERSION/REVISION/SOURCE_URL/CREATED, for the same reason
# verify-empty-plugins-parity.sh does not: both must use Dockerfile.ci's own defaults so the OCI
# labels cannot differ for a reason unrelated to plugins. The baseline Dockerfile.ci predates
# plugin COPY/RUN instructions entirely (no plugins COPY at all), so -- exactly as with the
# local Dockerfile's proof -- the two images necessarily have different layer histories even when
# their merged filesystem content is identical; that is why this compares merged filesystem content,
# not image manifest digests.
echo "Building baseline image (Dockerfile.ci from $BASELINE_WORKTREE, artifacts from $BASELINE_ARTIFACTS) ..."
cp -a "$BASELINE_ARTIFACTS" "$BASELINE_WORKTREE/ci-artifacts"
docker build -t "$BASELINE_IMAGE" -f "$BASELINE_WORKTREE/Dockerfile.ci" "$BASELINE_WORKTREE" >&2

echo "Building candidate image (Dockerfile.ci from $PROJECT_DIR, artifacts from $CANDIDATE_ARTIFACTS) ..."
CANDIDATE_CONTEXT=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-parity-ci-candidate-ctx.XXXXXX")
cp -a "$CANDIDATE_ARTIFACTS" "$CANDIDATE_CONTEXT/ci-artifacts"
docker build -t "$CANDIDATE_IMAGE" -f "$PROJECT_DIR/Dockerfile.ci" "$CANDIDATE_CONTEXT" >&2
rm -rf "$CANDIDATE_CONTEXT"

echo "Comparing merged filesystems ..."
python3 "$PROJECT_DIR/scripts/verify-empty-plugins-parity.py" "$BASELINE_IMAGE" "$CANDIDATE_IMAGE"
