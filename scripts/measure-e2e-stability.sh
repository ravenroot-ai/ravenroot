#!/usr/bin/env sh
# Measure the UI end-to-end suite instead of recalling it.
#
# Why this exists. Four measures of the same suite taken within hours of each other reported four
# different numbers, and every one of them was read as "the baseline" by whoever took it. A gate that
# does not produce the same answer twice is an anecdote, so the failure rate must be MEASURED, with
# the number of runs and the load condition declared. This script is that method,
# executable, so the next reader re-runs it instead of re-deriving it.
#
# Two mistakes this script exists to make impossible, both committed for real on 2026-08-28:
#
#   1. COMPARING COUNTS. "19 failed" twice is not the same result twice: 18 known plus 1 new looks
#      identical, in a count, to 17 known plus 2 new. This script never reports a verdict from a
#      count. It emits the sorted LIST OF TEST NAMES per run and diffs the lists.
#   2. TAKING THE EXIT CODE FROM A PIPE. `playwright test | tee log | tail` reports tail(1)'s status,
#      which is 0 whatever the suite did. Every invocation here captures `$?` from the Playwright
#      process itself, on the line immediately after it, before anything else runs.
#
# What it does:
#   1. Builds the UI once, from scratch (`rm -rf dist && npm run build`). `e2e/ui-fixture-server.mjs`
#      serves `dist/` as static files and never rebuilds it, so a stale `dist/` silently measures a
#      different product under the current commit hash — the failure mode recorded in
#      docs/qa/end-to-end-and-accessibility.md §3. One build per invocation is enough and is the
#      point: every repetition below runs the SAME bytes, so a difference between repetitions is
#      instability and cannot be anything else.
#   2. Runs the suite (or the subset named in RAVENROOT_E2E_TARGET) N times, N = $1, default 2.
#   3. Writes, per run, a sorted `status<TAB>file:line<TAB>title` list, and a machine-readable
#      summary, into the output directory.
#   4. Diffs the failing-name lists across runs and prints the per-test failure ratio: how many runs
#      out of N each test failed in, named, never counted in aggregate.
#
# Exit status: 0 when every run produced the IDENTICAL failing-name list (the suite is a gate,
# whatever colour it is), 1 when the lists differ (the suite is an anecdote), 2 on a harness error.
# `runs=1` also exits 0, but says REPEATABILITY NOT ASSESSED rather than claiming a comparison it
# never made — one run has nothing to compare against, and a verdict that cannot fail is worse than
# no verdict.
# A red-but-identical suite therefore exits 0 on purpose: this script measures REPEATABILITY, not
# health. The failing list it prints is what says whether the suite is green.
#
# Requires: node >= 24 (`package.json` engines; the macOS system node is v23 and fails the check —
# use /opt/homebrew/opt/node@24/bin), npm, and an installed Playwright Chromium
# (`npx playwright install chromium`, same requirement as `npm run test:e2e`).
#
# Usage:
#   ./scripts/measure-e2e-stability.sh [runs]
# Environment:
#   RAVENROOT_E2E_TARGET  Playwright argument selecting a subset, e.g. "e2e/workspace-panes.spec.js".
#                         Unset, the whole suite runs. Declare it when you report the number: a
#                         subset measured under no load is a different experiment from a full suite.
#   RAVENROOT_E2E_OUTDIR  Where the per-run artifacts go. Default: a fresh mktemp -d, path printed.
set -eu

RUNS=${1:-2}
PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
UI_DIR="$PROJECT_DIR/ravenroot/ravenroot-ui"

case "$RUNS" in
  ''|*[!0-9]*) echo "measure-e2e-stability: runs must be a positive integer, got '$RUNS'" >&2; exit 2 ;;
esac
[ "$RUNS" -ge 1 ] || { echo "measure-e2e-stability: runs must be >= 1" >&2; exit 2; }

OUTDIR=${RAVENROOT_E2E_OUTDIR:-$(mktemp -d "${TMPDIR:-/tmp}/e2e-stability.XXXXXX")}
mkdir -p "$OUTDIR"

cd "$UI_DIR"

echo "measure-e2e-stability: repository $PROJECT_DIR"
echo "measure-e2e-stability: commit     $(git -C "$PROJECT_DIR" rev-parse --short HEAD)"
echo "measure-e2e-stability: node       $(node --version)"
echo "measure-e2e-stability: runs       $RUNS"
echo "measure-e2e-stability: target     ${RAVENROOT_E2E_TARGET:-<whole suite>}"
echo "measure-e2e-stability: output     $OUTDIR"

echo "measure-e2e-stability: building dist/ from scratch"
rm -rf dist
npm run build >"$OUTDIR/build.log" 2>&1 || {
  echo "measure-e2e-stability: build failed, see $OUTDIR/build.log" >&2
  exit 2
}

run=1
while [ "$run" -le "$RUNS" ]; do
  echo "measure-e2e-stability: run $run/$RUNS started $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  # The exit code is read on the very next line, from the Playwright process itself. `set +e` is
  # what makes that possible: a trailing `|| true` would keep `set -e` from killing the loop on a
  # red suite, but `$?` would then be the status of `true`, i.e. 0 forever — the same class of
  # mistake as reading it off the tail of a pipe, and just as silent.
  set +e
  PLAYWRIGHT_JSON_OUTPUT_NAME="$OUTDIR/run-$run.json" \
    npx playwright test ${RAVENROOT_E2E_TARGET:+$RAVENROOT_E2E_TARGET} \
    --reporter=json >"$OUTDIR/run-$run.stdout" 2>"$OUTDIR/run-$run.stderr"
  playwright_status=$?
  set -e
  echo "$playwright_status" >"$OUTDIR/run-$run.status"
  [ -f "$OUTDIR/run-$run.json" ] || {
    echo "measure-e2e-stability: run $run produced no JSON report; see $OUTDIR/run-$run.stderr" >&2
    exit 2
  }
  node "$PROJECT_DIR/scripts/e2e_report_names.mjs" "$OUTDIR/run-$run.json" >"$OUTDIR/run-$run.names"
  awk -F'\t' '$1 != "passed" && $1 != "skipped"' "$OUTDIR/run-$run.names" >"$OUTDIR/run-$run.failing"
  enumerated=$(awk 'END { print NR }' "$OUTDIR/run-$run.names")
  not_passing=$(awk 'END { print NR }' "$OUTDIR/run-$run.failing")
  echo "measure-e2e-stability: run $run/$RUNS playwright exit=$playwright_status enumerated=$enumerated not-passing=$not_passing"
  run=$((run + 1))
done

echo
echo "=== failing test NAMES per run (never counts) ==="
run=1
while [ "$run" -le "$RUNS" ]; do
  echo "--- run $run (playwright exit $(cat "$OUTDIR/run-$run.status")) ---"
  cat "$OUTDIR/run-$run.failing"
  run=$((run + 1))
done

echo
echo "=== per-test failure ratio over $RUNS runs ==="
cat "$OUTDIR"/run-*.failing | cut -f2,3 | sort | uniq -c | sort -rn \
  | awk -v n="$RUNS" '{ c=$1; $1=""; sub(/^ /, ""); printf "%d/%d\t%s\n", c, n, $0 }'

echo
echo "=== repeatability verdict ==="
# One run compares nothing. Saying "REPEATABLE" here would be a check that cannot fail, which is
# the exact shape this script exists to remove from the e2e measurement.
if [ "$RUNS" -eq 1 ]; then
  echo "measure-e2e-stability: REPEATABILITY NOT ASSESSED — one run has nothing to compare against."
  echo "measure-e2e-stability: artifacts in $OUTDIR"
  exit 0
fi
stable=0
run=2
while [ "$run" -le "$RUNS" ]; do
  if ! diff -u "$OUTDIR/run-1.failing" "$OUTDIR/run-$run.failing" >"$OUTDIR/diff-1-$run.txt"; then
    echo "run 1 vs run $run: DIFFERENT failing sets"
    cat "$OUTDIR/diff-1-$run.txt"
    stable=1
  else
    echo "run 1 vs run $run: identical failing sets"
  fi
  run=$((run + 1))
done

if [ "$stable" -eq 0 ]; then
  echo "measure-e2e-stability: REPEATABLE — every run named the same failing tests"
else
  echo "measure-e2e-stability: NOT REPEATABLE — the failing set moved between runs"
fi
echo "measure-e2e-stability: artifacts in $OUTDIR"
exit "$stable"
