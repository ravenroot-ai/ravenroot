#!/usr/bin/env sh
# Proves, rather than asserts, that an empty ravenroot-plugins/ directory produces a
# filesystem identical to the distribution built before PLAT-12 existed. Builds two images -- one
# from a baseline commit (default: where this branch diverged from dev, before PLAT-12) and
# one from the current working tree with whatever ravenroot-plugins/ currently contains -- and
# compares their exported filesystems with verify-empty-plugins-parity.py, which ignores mtime but
# checks everything else: path, type, mode, ownership and content.
#
# Usage: ./scripts/verify-empty-plugins-parity.sh [baseline-ref]
#   baseline-ref  defaults to `git merge-base HEAD origin/dev`, so it stays correct even after dev
#                 has moved on from the commit this branch actually forked from.
#
# Requires Docker. Requires the current ravenroot-plugins/ to be empty of bundle candidates for the
# proof to mean what its name says -- this script does not enforce that, because the same tool is
# also useful later to prove a *populated* build differs from baseline in exactly the expected way.
#
# Both builds below always run `mvn clean package` (the Dockerfile's own invocation), from a fresh
# container filesystem each time -- never `package` alone against a working tree that might already
# hold a previous run's shaded jar. Running `package` twice without `clean` can produce
# reference.conf/version.conf differences by
# CRC32 (1538 lines becoming 3077) -- the shade plugin's own AppendingTransformer appending onto an
# already-shaded jar, not a real reproducibility defect, and it nearly shipped as one. This script's
# use of `docker build` for both sides is what avoids that trap; anyone re-verifying by another means
# (a local `mvn package` rerun, say) needs to `clean` both sides deliberately, because nothing else
# will do it for them.
#
# A non-zero exit from this script (via verify-empty-plugins-parity.py) means "a difference exists,
# go read it" -- not "something is broken". It is an adjudication tool, not a CI pass/fail gate; see
# that script's own docstring before wiring this into an automated pipeline.
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BASELINE_REF=${1:-$(git -C "$PROJECT_DIR" merge-base HEAD origin/dev)}
BASELINE_WORKTREE=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-parity-baseline.XXXXXX")
BASELINE_IMAGE="ravenroot-parity-baseline:$$"
CANDIDATE_IMAGE="ravenroot-parity-candidate:$$"

cleanup() {
  docker rmi "$BASELINE_IMAGE" "$CANDIDATE_IMAGE" >/dev/null 2>&1 || true
  git -C "$PROJECT_DIR" worktree remove "$BASELINE_WORKTREE" --force >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Baseline ref: $BASELINE_REF"
git -C "$PROJECT_DIR" worktree add --detach "$BASELINE_WORKTREE" "$BASELINE_REF" >/dev/null

# No --build-arg overrides on either build: both must use the Dockerfile's own defaults for
# VERSION/REVISION/SOURCE_URL/CREATED so the OCI labels cannot differ for a reason unrelated to
# plugins. This is also why the two builds cannot literally run `docker build .` with identical
# arguments and expect identical raw image digests: the baseline Dockerfile predates PLAT-12's new
# COPY/RUN instructions entirely, so the two images necessarily have different layer histories even
# when their merged filesystem content is identical. That is exactly why this script compares merged
# filesystem content (via verify-empty-plugins-parity.py) rather than image manifest digests.
echo "Building baseline image from $BASELINE_WORKTREE ..."
docker build -t "$BASELINE_IMAGE" "$BASELINE_WORKTREE" >&2

echo "Building candidate image from $PROJECT_DIR ..."
docker build -t "$CANDIDATE_IMAGE" "$PROJECT_DIR" >&2

echo "Comparing merged filesystems ..."
python3 "$PROJECT_DIR/scripts/verify-empty-plugins-parity.py" "$BASELINE_IMAGE" "$CANDIDATE_IMAGE"
