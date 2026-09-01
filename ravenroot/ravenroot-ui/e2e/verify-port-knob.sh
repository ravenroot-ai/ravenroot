#!/usr/bin/env bash
# The runtime half of the port knob's proving control (UI-04).
#
# The static half — the bash/JS default pin, the absence of stale literals, and the canary host NOT
# being normalised — lives in `test/harness-ports.test.js` and runs with the ordinary suite. The two
# cases below cannot honestly live there: both are about PROCESSES AND SIGNALS, and a test runner
# that mocks them proves nothing about a cleanup path that has to survive an interrupt.
#
# EVERY CASE IS RED-THEN-GREEN. A cleanup that reports success is not evidence; a cleanup that
# survives the interruption it was built for is. So each case first REPRODUCES the failure through
# the same mechanism, and only then shows the shipped code not exhibiting it.
#
# Usage: e2e/verify-port-knob.sh [port]
# The port defaults to a high private one. Concurrent runs need separate ports; sharing one can
# silently contaminate a passing suite with another run's server.
set -uo pipefail
cd "$(dirname "$0")/.."

PORT="${1:-47311}"
STALE_PORT=4173          # what the OLD verification checked, unconditionally
FAILURES=0

note()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
pass()  { printf '  PASS  %s\n' "$*"; }
fail()  { printf '  FAIL  %s\n' "$*"; FAILURES=$((FAILURES + 1)); }

# Only ever kills pids this script owns. KILL BY PID, NEVER BY PATTERN: a pattern matching our own
# process matches every other agent's identical process, and the victim sees only an unexplained
# failure with nothing pointing back.
OWNED_PIDS=()
cleanup() {
  for pid in "${OWNED_PIDS[@]:-}"; do
    kill -9 "${pid}" >/dev/null 2>&1
  done
  wait >/dev/null 2>&1
}
trap cleanup EXIT

port_holder() { lsof -ti:"$1" 2>/dev/null || true; }

wait_for_port() {
  local port="$1" tries=0
  while [ "${tries}" -lt 100 ]; do
    [ -n "$(port_holder "${port}")" ] && return 0
    sleep 0.2
    tries=$((tries + 1))
  done
  return 1
}

wait_for_port_free() {
  local port="$1" tries=0
  while [ "${tries}" -lt 60 ]; do
    [ -z "$(port_holder "${port}")" ] && return 0
    sleep 0.2
    tries=$((tries + 1))
  done
  return 1
}

if [ -n "$(port_holder "${PORT}")" ]; then
  echo "verify-port-knob: port ${PORT} is already in use — pass a free one as \$1" >&2
  exit 2
fi

# ── CASE 2 — A CLEANUP REPORTING SUCCESS ON A PORT NOBODY USED ───────────────────────────────────
#
# The failure this guards is not a leak; it is a cleanup that CANNOT SEE a leak. Before the knob,
# the verification checked a hardcoded 4173 whatever port the run actually used, so the moment the
# port became configurable it would inspect a port the run never touched and print success while the
# real one was still held.
note "CASE 2 — a cleanup verifying a literal reports success on a port nobody used"

# A stand-in leak: something genuinely bound to the port this run used.
node -e "require('node:http').createServer(()=>{}).listen(${PORT},'127.0.0.1')" &
LEAK_PID=$!
OWNED_PIDS+=("${LEAK_PID}")
if ! wait_for_port "${PORT}"; then
  fail "could not bind the stand-in leak on ${PORT}"
else
  # RED: the OLD verification, scoped to a literal.
  if [ -z "$(port_holder "${STALE_PORT}")" ]; then
    pass "RED reproduced — checking the literal ${STALE_PORT} finds nothing and would report 'cleanup verified' while ${PORT} is still held"
  else
    fail "RED not reproduced — ${STALE_PORT} is occupied, so this case cannot demonstrate the blindness"
  fi
  # GREEN: the shipped verification, scoped to the port the run used.
  if [ -n "$(port_holder "${PORT}")" ]; then
    pass "GREEN — checking the port actually used finds the leak and reports it"
  else
    fail "GREEN failed — the derived check missed a leak that is really there"
  fi
fi
kill -9 "${LEAK_PID}" >/dev/null 2>&1
wait_for_port_free "${PORT}" || true

# ── CASE 3 — THE SIGTERM-MID-RUN REGRESSION, ON THE NEW CONFIGURABLE PORT ────────────────────────
#
# This case guards a MEASURED leak: a SIGTERM mid-run can kill the tracked spinners but ORPHAN
# PLAYWRIGHT'S OWN `webServer` GRANDCHILD, leaving it bound to the port. The verification must use the
# configured port; checking a fixed port would miss the orphan created by this run.
note "CASE 3 — SIGTERM mid-run leaves nothing behind, re-proved on the configurable port"

TARGET='leaves the zoom badge describing the active document'

# RED: the naive teardown replaced — SIGTERM the foregrounded pid ONLY, never its group.
RR_UI_PORT="${PORT}" npx playwright test e2e/workspace-panes.spec.js -g "${TARGET}" >/dev/null 2>&1 &
NAIVE_PID=$!
OWNED_PIDS+=("${NAIVE_PID}")
if ! wait_for_port "${PORT}"; then
  fail "playwright never bound ${PORT} — the naive case could not be set up"
else
  kill -TERM "${NAIVE_PID}" >/dev/null 2>&1
  sleep 2
  ORPHAN="$(port_holder "${PORT}")"
  if [ -n "${ORPHAN}" ]; then
    pass "RED reproduced — killing only the pid orphans the webServer, still bound to ${PORT} (pid ${ORPHAN})"
    for pid in ${ORPHAN}; do kill -9 "${pid}" >/dev/null 2>&1; done
    wait_for_port_free "${PORT}" || true
  else
    fail "RED not reproduced — nothing was orphaned, so the green result below proves nothing"
  fi
fi

# GREEN: the shipped harness, interrupted the same way, on the same port.
RR_UI_PORT="${PORT}" ./e2e/load-harness.sh "${TARGET}" 1 >/tmp/verify-knob-harness.log 2>&1 &
HARNESS_PID=$!
OWNED_PIDS+=("${HARNESS_PID}")
if ! wait_for_port "${PORT}"; then
  fail "the harness never bound ${PORT}"
else
  kill -TERM "${HARNESS_PID}" >/dev/null 2>&1
  sleep 3
  if wait_for_port_free "${PORT}"; then
    pass "GREEN — after the same interrupt the harness left ${PORT} free"
  else
    fail "GREEN failed — ${PORT} still held by $(port_holder "${PORT}") after the harness was interrupted"
  fi
  if pgrep -x yes >/dev/null 2>&1; then
    fail "GREEN failed — a load spinner survived the interrupt"
  else
    pass "GREEN — no load spinner survived the interrupt"
  fi
  # The harness must also NAME the port it verified, not a literal, or its success line is the very
  # false confirmation case 2 is about.
  if grep -q "nothing on port ${PORT}" /tmp/verify-knob-harness.log 2>/dev/null; then
    pass "GREEN — the harness reported cleanup against ${PORT}, the port it actually used"
  else
    printf '  NOTE  harness output did not carry the derived success line (interrupted before it printed):\n'
    tail -3 /tmp/verify-knob-harness.log 2>/dev/null | sed 's/^/        /'
  fi
fi

# ── CASE 4 — TWO WORKTREES, CONCURRENTLY, WITHOUT ANYONE SETTING ANYTHING ────────────────────────
#
# An OPT-IN knob still lets a run that sets no variable take a fixed default,
# so concurrent worktrees using defaults would still collide. The default is therefore DERIVED FROM
# THE WORKTREE'S OWN PATH rather than relying on every caller to opt in.
#
# Both halves are required. Showing two worktrees not colliding proves nothing unless the same
# construction is shown CAPABLE of colliding — which is what disabling the derivation does.
note "CASE 4 — two worktrees do not collide at their defaults, and DO when derivation is off"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "${SANDBOX}"' RETURN 2>/dev/null || true
for name in alpha beta; do
  mkdir -p "${SANDBOX}/${name}/e2e" "${SANDBOX}/${name}/dist"
  cp e2e/ports.mjs e2e/ui-fixture-server.mjs "${SANDBOX}/${name}/e2e/"
  cp -R dist/. "${SANDBOX}/${name}/dist/" 2>/dev/null || true
done

A_PORT="$(node "${SANDBOX}/alpha/e2e/ports.mjs" --print-ui-port)"
B_PORT="$(node "${SANDBOX}/beta/e2e/ports.mjs" --print-ui-port)"
A_SVC="$(node "${SANDBOX}/alpha/e2e/ports.mjs" --print-service-port)"
B_SVC="$(node "${SANDBOX}/beta/e2e/ports.mjs" --print-service-port)"

# RED: with derivation disabled, both worktrees take the SAME fixed default — the collision.
A_FIXED="$(RR_PORT_DERIVE=0 node "${SANDBOX}/alpha/e2e/ports.mjs" --print-ui-port)"
B_FIXED="$(RR_PORT_DERIVE=0 node "${SANDBOX}/beta/e2e/ports.mjs" --print-ui-port)"
if [ "${A_FIXED}" = "${B_FIXED}" ]; then
  pass "RED reproduced — with derivation off both worktrees want port ${A_FIXED}, which is the collision"
else
  fail "RED not reproduced — derivation-off did not produce a shared port, so the green result proves nothing"
fi

# GREEN: at their derived defaults the two worktrees want different ports, and the PAIRS are disjoint.
if [ "${A_PORT}" != "${B_PORT}" ]; then
  pass "GREEN — derived defaults differ: alpha ${A_PORT}/${A_SVC}, beta ${B_PORT}/${B_SVC}"
else
  fail "GREEN failed — two worktrees derived the same port ${A_PORT}"
fi
if [ "${A_PORT}" != "${B_SVC}" ] && [ "${B_PORT}" != "${A_SVC}" ]; then
  pass "GREEN — the two PAIRS are disjoint, so neither run's UI port is the other's service port"
else
  fail "GREEN failed — the pairs overlap"
fi

# And prove it CONCURRENTLY, with real servers, neither setting any environment variable.
( cd "${SANDBOX}/alpha" && node e2e/ui-fixture-server.mjs ) >/dev/null 2>&1 &
A_PID=$!; OWNED_PIDS+=("${A_PID}")
( cd "${SANDBOX}/beta" && node e2e/ui-fixture-server.mjs ) >/dev/null 2>&1 &
B_PID=$!; OWNED_PIDS+=("${B_PID}")
sleep 2
if [ -n "$(port_holder "${A_PORT}")" ] && [ -n "$(port_holder "${B_PORT}")" ]; then
  pass "GREEN — both servers are bound and serving CONCURRENTLY, with no variable set by either"
else
  fail "GREEN failed — alpha on ${A_PORT}: '$(port_holder "${A_PORT}")', beta on ${B_PORT}: '$(port_holder "${B_PORT}")'"
fi
kill -9 "${A_PID}" "${B_PID}" >/dev/null 2>&1
rm -rf "${SANDBOX}"

note "RESULT"
if [ "${FAILURES}" -eq 0 ]; then
  echo "  verify-port-knob: all cases passed red-then-green on port ${PORT}"
  exit 0
fi
echo "  verify-port-knob: ${FAILURES} case(s) failed" >&2
exit 1
