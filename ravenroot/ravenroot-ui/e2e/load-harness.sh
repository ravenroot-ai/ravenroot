#!/usr/bin/env bash
# FIX-28 synthetic-load reproduction harness for the zoom-badge test's
# load-sensitivity and its reusable AC3/AC4 check.
#
# This test is deterministic in isolation but races a genuinely asynchronous, wall-clock-timed
# condition (an animated `elk` layout's fit) once
# the machine is under contention. Ambient desktop load reproduced the failure without any spinner
# at all during diagnosis (load averages ~18-22 on 10 cores) — this script exists so the failure
# does not depend on the machine happening to be busy at the right moment and can be rerun on a
# quiet machine.
#
# What it does NOT do: retry the test, raise its assertion timeout, or otherwise characterise the
# race. It only manufactures CPU contention and runs the target test once under it, with retries:0
# from playwright.config.js still in force — a single failure under load is reported as a failure.
#
# Usage:
# e2e/load-harness.sh [grep-pattern] [repeat-each]
# Defaults reproduce the zoom-badge test 15 times under load, matching the diagnosis run.
#
# Exit code is the underlying `playwright test` exit code (unpiped), so callers can check it
# directly without falling into the "pipe reports the pipe's status" trap.

# `-m` (job control / monitor mode) gives every backgrounded command its own process group, which
# is what makes group-kill below reach Playwright's own `webServer` child instead of only the
# `npx playwright` process itself. Sending SIGTERM only to the foregrounded `npx playwright test`
# PID leaves `e2e/ui-fixture-server.mjs` (Playwright's `webServer`, spawned as a grandchild) bound to
# the UI port after the interrupt; `lsof -ti:<port>` confirms it remains alive after the harness exits.
set -um
cd "$(dirname "$0")/.."

PATTERN="${1:-leaves the zoom badge describing the active document}"
REPEAT="${2:-15}"

# The port comes from `e2e/ports.mjs`, which now DERIVES ITS DEFAULT PER WORKTREE so two concurrent
# runs cannot collide without anyone setting anything. Bash cannot import the module, so it is
# invoked — ONCE, HERE, AT STARTUP. Never inside the EXIT trap below: that path runs after an
# interrupt and must not gain a new failure mode. The trap reads the variable this line sets.
#
# There is NO LITERAL FALLBACK any more. A hardcoded default here would be a second worktree-blind
# source of truth; failing loudly is correct.
UI_PORT="${RR_UI_PORT:-$(node e2e/ports.mjs --print-ui-port 2>/dev/null)}"
if [ -z "${UI_PORT}" ]; then
  echo "load-harness: could not determine the UI port from e2e/ports.mjs" >&2
  exit 1
fi

SPIN_PIDS=()
PW_PID=""
# Kept after PW_PID is cleared, so the verification below can scope to THIS RUN'S OWN GROUP.
PW_PGID=""

cleanup() {
  for pid in "${SPIN_PIDS[@]:-}"; do
    kill "$pid" >/dev/null 2>&1
  done
  # Negative PID = the whole process group Playwright and its `webServer` child share, per `-m`
  # above. Needed on an interrupted run, where `npx playwright test` may not have torn its own
  # child down; a no-op on a clean run, where the group has already exited.
  if [ -n "${PW_PID}" ]; then
    kill -TERM "-${PW_PID}" >/dev/null 2>&1
  fi
  # NO PATTERN KILL. `pkill -f 'ui-fixture-server\.mjs'` matched every OTHER agent's identical
  # fixture server as well as ours, and the victim saw only an unexplained connection failure with
  # nothing pointing back at the cause. The mechanism to do this precisely was already here: `set -m`
  # gives this run its own process group, so our own children are reachable BY GROUP, and whatever
  # holds OUR port is reachable BY PID from `lsof`.
  #
  # RESIDUAL GAP, RECORDED RATHER THAN CLOSED: a fixture server that leaked but has not yet bound the
  # port escapes both checks. It is not ours to kill, and killing it by name is precisely the hazard
  # being removed here.
  local port_pid
  port_pid="$(lsof -ti:"${UI_PORT}" 2>/dev/null || true)"
  if [ -n "${port_pid}" ]; then
    kill -9 ${port_pid} >/dev/null 2>&1
  fi
  wait >/dev/null 2>&1
  # Verify nothing survived under any parentage: a
  # generator or server this script did not clean up would corrupt every run after it, not just
  # this one.
  local leaked=0
  if pgrep -x yes >/dev/null 2>&1; then
    echo "load-harness: WARNING — a 'yes' process is still running after cleanup" >&2
    pgrep -x yes >&2
    leaked=1
  fi
  # Scoped to THE PORT THIS RUN ACTUALLY USED and to ITS OWN PROCESS GROUP. The previous check used
  # `pgrep -f 'ui-fixture-server\.mjs'`, which is the FALSE-POSITIVE twin of the kill hazard above:
  # another agent's perfectly healthy server made this harness report a leak it had not caused, and
  # sent someone hunting something that was never there.
  if lsof -ti:"${UI_PORT}" >/dev/null 2>&1; then
    echo "load-harness: WARNING — port ${UI_PORT} is still occupied after cleanup" >&2
    leaked=1
  fi
  if [ -n "${PW_PGID}" ] && pgrep -g "${PW_PGID}" >/dev/null 2>&1; then
    echo "load-harness: WARNING — a process from this run's own group survived cleanup" >&2
    leaked=1
  fi
  [ "${leaked}" -eq 0 ] && echo "load-harness: cleanup verified — no spinner, nothing on port ${UI_PORT}, no surviving child"
}
trap cleanup EXIT

CORES="$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)"
echo "load-harness: starting ${CORES} CPU spinners"
for _ in $(seq 1 "${CORES}"); do
  yes >/dev/null &
  SPIN_PIDS+=("$!")
done

# Let the spinners actually saturate the runqueue before measuring — an immediate `uptime` reads
# the one-minute average from BEFORE they started. This is deliberately empirical, not a chosen
# "long enough" budget for the test itself: it only decides when to log the load figure, never when
# the target test is allowed to finish.
sleep 3
uptime

npx playwright test e2e/workspace-panes.spec.js -g "${PATTERN}" --repeat-each="${REPEAT}" &
PW_PID=$!
# With `set -m` the backgrounded job leads its own process group, so its pid IS its group id.
PW_PGID=$!
wait "${PW_PID}"
PW_EXIT=$?
PW_PID=""   # already exited on its own; cleanup's group-kill would be a harmless no-op regardless
echo "load-harness: playwright exit code = ${PW_EXIT}"
uptime

exit "${PW_EXIT}"
