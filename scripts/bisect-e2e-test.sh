#!/bin/sh
# A `git bisect run` script for one Playwright test, so "when did this go red" is a
# measurement instead of an argument from commit titles.
#
# The e2e suite has failures nobody could attribute, which is how eighteen of them accumulated
# unowned on `dev` (docs/qa/end-to-end-and-accessibility.md §11). Reading history and picking the
# plausible-looking commit is exactly the move that produced a confident wrong answer twice already;
# this bisects instead.
#
# Exit status, the contract `git bisect run` reads:
#   0   the test is green here             -> good
#   1   the test is red here                -> bad
#   125 cannot decide here                  -> skip
#
# 125 covers two different cases, and BOTH matter:
#   * the build fails at this commit — unrelated breakage, not this test's verdict;
#   * `-g` matches NO test at this commit, i.e. the spec did not exist yet. Playwright exits
#     non-zero for "no tests found", so without this guard every commit predating the spec scores
#     `bad` and the bisect converges on the commit that ADDED the test. That is a wrong answer that
#     looks exactly like a right one, which is why the guard is here and not left to the caller.
#
# The exit code is Playwright's own, read on the line immediately after it runs — never from the end
# of a pipe (`| tee | tail` reports tail(1)'s status, always 0) and never after a trailing `|| true`
# (which makes `$?` the status of `true`, also always 0).
#
# THE TEST MUST BE DETERMINISTIC AT THE COMMITS BEING BISECTED, or one run per step is not enough to
# decide a step. Measure the rate first — `RAVENROOT_E2E_TARGET=<spec> scripts/measure-e2e-stability.sh <n>` — and if the
# test is intermittent, this script's single run will scatter good/bad verdicts and the bisect will
# converge on noise. The two motivating specs failed 9 runs in 10, which is exactly the case where
# this is NOT the right instrument.
#
# ONE PREMISE IT DOES NOT ENFORCE: it does not re-run `npm ci` per step, so it builds every commit
# against the `node_modules` already installed in the worktree. That is correct only while the
# lockfile is unchanged across the bisected range — check it first
# (`git diff --stat <good> <bad> -- ravenroot/ravenroot-ui/package-lock.json`) and install per step
# yourself if it is not, rather than trusting a build made from the wrong dependency tree.
#
# Requires: node >= 24, an installed Playwright Chromium, and a git worktree it may check out freely.
# Use a DEDICATED worktree: bisect moves HEAD, and doing that under a branch someone is working on
# is how a concurrent session loses its tree.
#
# Usage, from the worktree being bisected:
#   git bisect start <bad> <good>
#   RAVENROOT_E2E_WORKTREE=/path/to/worktree \
#   RAVENROOT_E2E_SPEC=e2e/workspace-panes.spec.js \
#   RAVENROOT_E2E_TITLE="refits a halved pane" \
#     git bisect run ./scripts/bisect-e2e-test.sh
#   git bisect reset
set -u

WT=${RAVENROOT_E2E_WORKTREE:-$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)}
SPEC=${RAVENROOT_E2E_SPEC:?set RAVENROOT_E2E_SPEC to the spec file, e.g. e2e/workspace-panes.spec.js}
TITLE=${RAVENROOT_E2E_TITLE:?set RAVENROOT_E2E_TITLE to a -g pattern matching exactly one test}

# Per-worktree log paths, not fixed /tmp names: several sessions bisect this repository at once and
# fixed names let one run read another's output while believing it read its own.
LOG_PREFIX=${TMPDIR:-/tmp}/ravenroot-bisect-$(basename "$WT")
BUILD_LOG="$LOG_PREFIX-build.log"
RUN_LOG="$LOG_PREFIX-run.log"

cd "$WT/ravenroot/ravenroot-ui" || exit 125

# Fresh build every step: `e2e/ui-fixture-server.mjs` serves `dist/` as static files and never
# rebuilds it, so a stale `dist/` would measure the previous step's product under this step's commit.
rm -rf dist
npm run build >"$BUILD_LOG" 2>&1 || exit 125

npx playwright test "$SPEC" -g "$TITLE" --reporter=line >"$RUN_LOG" 2>&1
status=$?
short=$(git -C "$WT" rev-parse --short HEAD)

if grep -qi "No tests found" "$RUN_LOG"; then
  echo "$short: no test matches '$TITLE' at this commit -> skip"
  exit 125
fi

echo "$short playwright exit=$status | $(grep -E 'passed|failed' "$RUN_LOG" | tail -1)"
[ "$status" -eq 0 ] && exit 0
exit 1
