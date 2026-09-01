#!/usr/bin/env sh
# Local development-environment provisioning, alongside service.sh and plugin.sh.
#
# Why it exists: the parts the distribution does not ship — the sandbox
# supervisor and model-provider adapter — each required a different manual procedure, discovered by
# trial and error and preserved only in chat messages. Plugin bundles already have the right shape
# (`./plugin.sh build`, then `install`, then the next build finds them); this script does the same
# work for the family of unshipped parts.
#
# ------------------------------------------------------------------------------------------------
# THE CONSTRAINT THAT MAKES THESE PARTS DIFFERENT FROM PLUGINS, WHICH THIS SCRIPT DOES NOT BYPASS
# ------------------------------------------------------------------------------------------------
# A plugin bundle enters the image: the Dockerfile copies it and `./service.sh` finds it. A
# model-provider adapter CANNOT take the same route, and this is not a gap to fill. P3 of
# ReleaseArtifactBoundaryChecks (ravenroot-distribution) treats a call to
# ModelProviderRegistry#register reachable from the call graph of RavenrootServerMain#main or
# RavenrootCliMain#main as A VIOLATION: the released artifact must not compose a model adapter into
# itself, and the embedding seam is supplied from outside the artifact or not supplied at all.
# Ravenroot's qualification as an upstream component under ADR 0017 depends on the shipped artifact
# not performing that composition. Previously, P3 said that call “arms an llm-prompt node in every
# graph built afterwards”; the node is now absent from the artifact entirely, so the prohibition
# remains while its rationale reflects the current packaging boundary. Any loading mechanism INSIDE the artifact
# — environment variable, configuration file, HTTP route, ServiceLoader — reaches `register` and
# makes `package` fail.
#
# Therefore, this script does NOT produce an armed release artifact. It prepares an expressly local
# development environment, exactly as the `ravenroot-dev-harness/` bench already does. The
# consequence must be faced rather than softened and is stated in the final report too: the two
# launches prepared here do not do the same thing.
#
#   ./service.sh start   -> release image: the `program` node works (supervisor mounted), while the
#                           `llm-prompt` node is NOT PRESENT — it is not in the core catalog, and
#                           obtaining it requires building a bundle and an image.
#   ./dev.sh bench       -> source-only bench, never published, loopback only: the bench supplies and
#                           arms `llm-prompt` itself; the `program` node does NOT work
#                           (DisabledProgramRuntime).
#
# Neither currently has “everything enabled at once.” That is a consequence of the constraint, not a
# defect in this script; item H28 in the decision register is where the question lives.
#
# What this script deliberately does not do: install anything at the system level, write JAVA_HOME
# or PATH anywhere other than its own process, or export RAVENROOT_* variables interpolated by
# compose.yaml (a host variable that overwrites a container-internal path is exactly the defect that
# cost hours and that compose.yaml documents on the RAVENROOT_GRAAL_SANDBOX_SUPERVISOR line).
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REACTOR_POM="$PROJECT_DIR/ravenroot/pom.xml"
UI_DIR="$PROJECT_DIR/ravenroot/ravenroot-ui"
HARNESS_DIR="$PROJECT_DIR/ravenroot-dev-harness"
SUPERVISOR_FILE="$PROJECT_DIR/deploy/dev/sandbox-supervisor.sh"
# The path INSIDE the container, not on the host: it is the value wired in compose.yaml and serves
# here only to verify that the two parts of the wiring (variable and mount) still agree.
SUPERVISOR_CONTAINER_PATH=/opt/ravenroot/dev/sandbox-supervisor.sh
SUPERVISOR_CAPABILITY=ravenroot-sandbox-supervisor/1
COMPOSE_FILE="$PROJECT_DIR/compose.yaml"
# The reactor modules that must be in ~/.m2 so adapters and bench can resolve them: they are the two
# roots, and `-am` brings application-api, core, and the rest.
REACTOR_MODULES=ravenroot-server,ravenroot-pekko

WITH_TESTS=false
SKIP_UI=false

usage() {
  cat <<'EOF'
Usage: ./dev.sh [command] [options]

Commands:
  setup (default)   Builds and prepares, in one operation, everything the next launch needs to find:
                    the editor, the reactor in ~/.m2, every adapter present in the checkout, the
                    development bench, and the sandbox supervisor mounted by compose.yaml. At the
                    end, it prints what it prepared and probes to verify it.
  check             Verifies: reports what exists and what is missing without building anything. The
                    only repair is the supervisor executable bit, because it is the only fault that
                    presents as “not configured” rather than as itself — and repairing it costs one
                    syscall, not a build.
  bench             Starts the development bench (a real llm-prompt node supplied by the bench,
                    loopback only, never published). Requires an earlier `setup`. It derives from
                    declared bench endpoints the three egress settings without which the node fails
                    when reached (item H35), prints them, and never touches an environment value
                    already present.
  help              This text.

Setup options:
  --with-tests      Also runs reactor and adapter suites. The bench ALWAYS runs its own:
                    NotAReleaseArtifactTest is the check that maintains the “never published”
                    condition, and skipping it would make this script the place where that condition
                    stops being verified.
  --skip-ui         Does not rebuild the editor. Useful only if it was already built in this session.

Setup exit codes:
  0   everything is ready, including the sandbox supervisor
  1   something failed after building began. The final report distinguishes two cases: if printed,
      everything was built but the supervisor is unusable (`./service.sh start` would start and every
      validate would return 501); if not printed, a build stopped first and the Maven error is last
      on screen
  2   a prerequisite is missing — JDK 21..25, Node from .nvmrc, or mvn — and nothing was built

What it does NOT prepare, and why:
  A release artifact with an armed model provider. This is not a script limitation: P3 of
  ReleaseArtifactBoundaryChecks fails `package` if a registration becomes reachable from the shipped
  `main` (ADR 0017). See this file's header comment.

Examples:
  ./dev.sh
  ./dev.sh setup --with-tests
  ./dev.sh check
  ./dev.sh bench
EOF
}

# ---- utilities -----------------------------------------------------------------------------------

step() { printf '\n== %s\n' "$1"; }
note() { printf '   %s\n' "$1"; }
warn() { printf '   !! %s\n' "$1" >&2; }

# Exits 2, not 1: this is a missing prerequisite, as require_java_runtime and select_node_runtime
# also state, and the three exit codes remain disjoint only if this function says so too.
require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 2
  fi
}

# ---- toolchain -----------------------------------------------------------------------------------

# JDK selection lives in scripts/lib/java-runtime.sh: it is the only part with a branch that no run
# on this machine can exercise, and being includable is what makes it verifiable.
if [ ! -r "$PROJECT_DIR/scripts/lib/java-runtime.sh" ]; then
  echo "scripts/lib/java-runtime.sh is missing or unreadable." >&2
  echo "The checkout is incomplete: restore it before retrying." >&2
  exit 1
fi
# shellcheck source=scripts/lib/java-runtime.sh
. "$PROJECT_DIR/scripts/lib/java-runtime.sh"

# Node runtime selection lives in scripts/lib/node-runtime.sh: the same definition service.sh uses,
# because both build the same editor and a second copy would silently become stale. Failure is
# immediate and is the right choice — nothing silently degrades — but the shell message would not say
# that a library from this repository is missing.
if [ ! -r "$PROJECT_DIR/scripts/lib/node-runtime.sh" ]; then
  echo "scripts/lib/node-runtime.sh, the library shared with service.sh, is missing or unreadable." >&2
  echo "The checkout is incomplete: restore it before retrying." >&2
  exit 1
fi
# shellcheck source=scripts/lib/node-runtime.sh
. "$PROJECT_DIR/scripts/lib/node-runtime.sh"

# ---- discovery -----------------------------------------------------------------------------------

# Adapters are DISCOVERED from the tree, not listed here — the same choice plugin.sh made for
# extensions, for the same reason: a hand-written table names modules that existed when it was
# written, and an adapter added later would never be built by this script despite being in the checkout.
adapter_dirs() {
  find "$PROJECT_DIR" -mindepth 1 -maxdepth 1 -type d -name 'ravenroot-adapter-*' 2>/dev/null |
    while IFS= read -r dir; do
      [ -f "$dir/pom.xml" ] || continue
      echo "$dir"
    done | sort
}

adapter_jar_of_dir() {
  dir=$1
  for candidate in "$dir"/target/*.jar; do
    case "$(basename "$candidate")" in
      *-sources.jar|*-javadoc.jar|original-*) continue ;;
    esac
    [ -f "$candidate" ] || continue
    echo "$candidate"
    return 0
  done
  return 1
}

# ---- sandbox supervisor --------------------------------------------------------------------------

# The supervisor is not “built”: it is a script that compose.yaml mounts read-only. But the two things
# that make it usable can silently disappear, and this function verifies them by EXECUTING it.
#
#   1. the executable bit. Git records the file as 100755, but a mount, copy, or local configuration
#      that does not preserve permissions removes it — and from that point the launcher treats the
#      file as MISSING, the symptom is a 501 indistinguishable from an unconfigured sandbox, and the
#      five-step cycle guide opened with `chmod +x` for this reason.
#   2. compose.yaml wiring: the variable and mount must name the SAME path inside the container. They
#      are two distant lines; correcting only one is easy.
provision_supervisor() {
  if [ ! -f "$SUPERVISOR_FILE" ]; then
    warn "$SUPERVISOR_FILE is missing: the program node will not be executable (SANDBOX_LAUNCHER_MISSING)."
    return 1
  fi
  if [ ! -x "$SUPERVISOR_FILE" ]; then
    chmod +x "$SUPERVISOR_FILE"
    note "restored executable bit on deploy/dev/sandbox-supervisor.sh"
  fi

  capability=$("$SUPERVISOR_FILE" --ravenroot-sandbox-supervisor-capabilities=v1 2>/dev/null || true)
  if [ "$capability" != "$SUPERVISOR_CAPABILITY" ]; then
    warn "supervisor did not answer the capability probe: expected '$SUPERVISOR_CAPABILITY', read '$capability'"
    return 1
  fi
  note "supervisor is executable and responds $SUPERVISOR_CAPABILITY"

  wired=true
  grep -q "RAVENROOT_GRAAL_SANDBOX_SUPERVISOR: $SUPERVISOR_CONTAINER_PATH" "$COMPOSE_FILE" || wired=false
  grep -q "sandbox-supervisor.sh:$SUPERVISOR_CONTAINER_PATH:ro" "$COMPOSE_FILE" || wired=false
  if [ "$wired" = false ]; then
    warn "compose.yaml no longer names $SUPERVISOR_CONTAINER_PATH in both the variable and mount:"
    warn "startup will still begin, and every validate will return 501 without explaining why."
    return 1
  fi
  note "compose.yaml mounts and names it: no manual step, ./service.sh finds it as is"
  return 0
}

# ---- bench egress policy -------------------------------------------------------------------------

# Why this section exists, and what it is NOT.
#
# Measured by execution, not inferred: immediately after startup, the bench lists the `ollama-local`
# profile with "usable":false and "reason":"egress-refused". The message is exemplary and says it
# all — THREE operator settings are required, and any one of the three is enough to refuse: the host
# exactly as written, the port (only 80 and 443 are defaults), and for a loopback address a network
# exception, because the default `localhost:LOOPBACK` does NOT cover the `127.0.0.1` literal. This is
# item H35 in the decision register.
#
# Three variables to compose manually before the bench becomes useful is not a five-second startup.
# Therefore the bench — and ONLY this script's `bench` command — derives them from the endpoints it is
# about to declare and prints them. This is not a shortcut around a release constraint: egress policy
# is an operator decision, and here the operator is the person running their own bench on their own
# machine to speak to their local model. The bench already does exactly this, for the same reason,
# with RAVENROOT_ALLOWED_TOOLS.
#
# Three intended limits:
#   - an already-set value is NOT touched: whoever decided has decided;
#   - only hosts and ports from DECLARED endpoints are opened, not a generic permission;
#   - the reserved network exception is added only for a loopback literal, the case for which it
#     exists, never for an arbitrary host.
# None of this touches the image, compose.yaml, or a deployment descriptor: these are variables of
# the bench process, which lives only on loopback and is not a release artifact.

endpoint_host_port() {
  url=$1
  scheme=${url%%://*}
  rest=${url#*://}
  hostport=${rest%%/*}
  case "$hostport" in
    \[*\]*) host=${hostport%%]*}; host=${host#[}; port=${hostport##*]}; port=${port#:} ;;
    *:*) host=${hostport%%:*}; port=${hostport##*:} ;;
    *) host=$hostport; port="" ;;
  esac
  if [ -z "$port" ]; then
    case "$scheme" in https) port=443 ;; *) port=80 ;; esac
  fi
  echo "$host $port"
}

append_unique() {
  list=$1
  value=$2
  [ -n "$value" ] || { printf '%s' "$list"; return 0; }
  for existing in $(printf '%s' "$list" | tr ',' ' '); do
    [ "$existing" = "$value" ] && { printf '%s' "$list"; return 0; }
  done
  if [ -z "$list" ]; then printf '%s' "$value"; else printf '%s,%s' "$list" "$value"; fi
}

# Populates BENCH_HOSTS, BENCH_PORTS, and BENCH_EXCEPTIONS from endpoints the bench will declare.
# ID-to-variable-segment derivation is the same as the bench's (lowercase letters, digits, and
# hyphens; `-` becomes `_`) and is injective precisely because `_` is not allowed in an ID.
derive_bench_egress() {
  BENCH_HOSTS=""
  BENCH_PORTS=""
  BENCH_EXCEPTIONS=""
  BENCH_UNEXPRESSIBLE=""
  for id in $(printf '%s' "${RAVENROOT_DEV_MODEL_PROVIDERS:-ollama-local}" | tr ',' ' '); do
    case "$id" in
      ''|-*|*[!a-z0-9-]*)
        # The bench rejects this ID itself with a message that names it. Stay silent here and let it
        # speak, rather than constructing a variable name from an unvalidated ID.
        continue ;;
    esac
    segment=$(printf '%s' "$id" | tr 'a-z-' 'A-Z_')
    eval "endpoint=\${RAVENROOT_DEV_MODEL_${segment}_ENDPOINT:-}"
    [ -n "$endpoint" ] || endpoint=http://127.0.0.1:11434/v1/chat/completions
    parsed=$(endpoint_host_port "$endpoint")
    host=${parsed%% *}
    port=${parsed##* }
    BENCH_HOSTS=$(append_unique "$BENCH_HOSTS" "$host")
    BENCH_PORTS=$(append_unique "$BENCH_PORTS" "$port")
    case "$host" in
      *:*)
        # An IPv6 literal CANNOT be expressed as an exception and must be reported instead of tried:
        # ReservedNetworkPolicy.fromEntries splits name and network at the FIRST ':', so
        # `::1:LOOPBACK` has an empty name and the entry is SILENTLY DISCARDED. Printing it among the
        # applied settings would declare a fact that does not happen — measured, not inferred: the
        # profile remains `usable:false` / `egress-refused` with all three lines apparently correct.
        BENCH_UNEXPRESSIBLE=$(append_unique "$BENCH_UNEXPRESSIBLE" "$host") ;;
      127.*|localhost) BENCH_EXCEPTIONS=$(append_unique "$BENCH_EXCEPTIONS" "$host:LOOPBACK") ;;
    esac
  done
}

# ---- commands ------------------------------------------------------------------------------------

cmd_setup() {
  require_command mvn
  # Do NOT `require_command java`: a supported JDK can exist without being on PATH — the normal case
  # for a Homebrew keg. Requiring it on PATH caused “required command not found” before even looking
  # where the JDK actually is.

  step "1/6  Toolchain"
  require_java_runtime
  note "JDK $(java_home_major "$JAVA_HOME") from $JAVA_HOME_ORIGIN"
  note "$JAVA_HOME"

  if [ "$SKIP_UI" = false ]; then
    step "2/6  Editor"
    select_node_runtime
    require_command npm
    note "Node $(node --version)"
    ( cd "$UI_DIR" && npm ci && npm run build )
    note "built in ravenroot/ravenroot-ui/dist"
  else
    step "2/6  Editor (skipped)"
  fi

  step "3/6  Reactor in ~/.m2"
  # Adapters and bench resolve Ravenroot dependencies from the local repository, not a reactor
  # sibling: without this step they fail with an unresolvable artifact and nothing in the message
  # says the missing step is an install.
  if [ "$WITH_TESTS" = true ]; then
    ( cd "$PROJECT_DIR/ravenroot" && mvn -B --no-transfer-progress install -pl "$REACTOR_MODULES" -am )
  else
    ( cd "$PROJECT_DIR/ravenroot" && mvn -B --no-transfer-progress install -pl "$REACTOR_MODULES" -am -DskipTests )
  fi
  note "installed $REACTOR_MODULES and their dependencies"

  step "4/6  Adapters"
  adapters_built=""
  # `while read`, not `for ... in $(...)`: the checkout path is derived, not chosen here, and a
  # directory with a space would split the word into two arguments without reporting it.
  adapter_dirs | while IFS= read -r dir; do
    note "building $(basename "$dir")"
    if [ "$WITH_TESTS" = true ]; then
      mvn -B --no-transfer-progress -f "$dir/pom.xml" install
    else
      mvn -B --no-transfer-progress -f "$dir/pom.xml" install -DskipTests
    fi
  done
  adapters_built=$(adapter_dirs | grep -c . || true)
  if [ "${adapters_built:-0}" -eq 0 ]; then
    note "no ravenroot-adapter-* directory in this checkout"
  fi

  step "5/6  Development bench"
  if [ -d "$HARNESS_DIR" ]; then
    # `verify`, always with its tests: NotAReleaseArtifactTest turns “never published” into a
    # verifiable fact instead of a promise, and this is the repository point from which the bench is
    # built most often. Skipping it here would mean the check almost never runs. `install` leaves
    # nothing in ~/.m2 (maven.install.skip): this is intentional.
    mvn -B --no-transfer-progress -f "$HARNESS_DIR/pom.xml" verify
    note "built and verified (never installed, never published: this is bench condition 1)"
  else
    note "absent from this checkout: no bench to build"
  fi

  step "6/6  Sandbox supervisor"
  supervisor_ok=true
  provision_supervisor || supervisor_ok=false

  report "$supervisor_ok"
  # Non-zero exit when the supervisor is unusable. `./dev.sh setup && ./service.sh start` is the
  # chain people actually write, and reporting success for an environment in which every validate
  # returns 501 is the same defect class this script exists to eliminate.
  [ "$supervisor_ok" = true ] || exit 1
}

cmd_check() {
  step "Toolchain"
  # The same discovery `setup` would perform, not JAVA_HOME alone: two different answers about the
  # same environment are worse than one answer, even when both are true of what they inspect.
  if select_java_runtime; then
    note "JDK $(java_home_major "$JAVA_HOME_SELECTED") from $JAVA_HOME_ORIGIN"
    note "$JAVA_HOME_SELECTED"
  else
    note "no JDK between 21 and 25: setup would stop here"
  fi

  step "Editor"
  if [ -f "$UI_DIR/dist/index.html" ]; then
    note "built: ravenroot/ravenroot-ui/dist"
  else
    note "absent: run ./dev.sh setup"
  fi

  step "Adapters"
  adapter_dirs | while IFS= read -r dir; do
    jar=$(adapter_jar_of_dir "$dir" || true)
    if [ -n "$jar" ]; then
      note "$(basename "$dir"): ${jar#"$PROJECT_DIR/"}"
    else
      note "$(basename "$dir"): not built"
    fi
  done

  step "Development bench"
  if [ ! -d "$HARNESS_DIR" ]; then
    note "absent from this checkout"
  elif [ -f "$HARNESS_DIR/target/classes/ai/ravenroot/devharness/DevHarnessMain.class" ]; then
    note "built"
  else
    note "not built: run ./dev.sh setup"
  fi

  step "Sandbox supervisor"
  supervisor_ok=true
  provision_supervisor || supervisor_ok=false

  report "$supervisor_ok"
}

cmd_bench() {
  if [ ! -d "$HARNESS_DIR" ]; then
    echo "ravenroot-dev-harness/ is not in this checkout: there is no bench to start." >&2
    exit 1
  fi
  require_command mvn
  require_java_runtime

  ui_dir=${RAVENROOT_UI_DIR:-"$UI_DIR/dist"}
  if [ ! -f "$ui_dir/index.html" ]; then
    echo "The editor is not built in $ui_dir: the bench would start without an interface." >&2
    echo "Run ./dev.sh setup, or specify RAVENROOT_UI_DIR." >&2
    exit 1
  fi
  derive_bench_egress
  applied=""
  if [ -z "${RAVENROOT_HTTP_ALLOWED_HOSTS:-}" ] && [ -n "$BENCH_HOSTS" ]; then
    RAVENROOT_HTTP_ALLOWED_HOSTS=$BENCH_HOSTS
    export RAVENROOT_HTTP_ALLOWED_HOSTS
    applied="$applied
  RAVENROOT_HTTP_ALLOWED_HOSTS=$BENCH_HOSTS"
  fi
  if [ -z "${RAVENROOT_HTTP_ALLOWED_PORTS:-}" ] && [ -n "$BENCH_PORTS" ]; then
    RAVENROOT_HTTP_ALLOWED_PORTS=$BENCH_PORTS
    export RAVENROOT_HTTP_ALLOWED_PORTS
    applied="$applied
  RAVENROOT_HTTP_ALLOWED_PORTS=$BENCH_PORTS"
  fi
  if [ -z "${RAVENROOT_EGRESS_RESERVED_EXCEPTIONS:-}" ] && [ -n "$BENCH_EXCEPTIONS" ]; then
    RAVENROOT_EGRESS_RESERVED_EXCEPTIONS=$BENCH_EXCEPTIONS
    export RAVENROOT_EGRESS_RESERVED_EXCEPTIONS
    applied="$applied
  RAVENROOT_EGRESS_RESERVED_EXCEPTIONS=$BENCH_EXCEPTIONS"
  fi

  cat <<EOF

Ravenroot development bench — NOT a release artifact.
Loopback only, never published, never for real work: ravenroot-dev-harness/README.md.
Editor: $ui_dir

The model is not started from here. The bench calls the endpoint declared by
RAVENROOT_DEV_MODEL_<ID>_ENDPOINT, which without declarations is
http://127.0.0.1:11434/v1/chat/completions (Ollama). If nothing is listening
there, the bench still starts and the llm-prompt node fails at execution rather
than startup: this is where to look if the bench starts but the traversal does
not. No route lists profiles, so the first place to look
is the traversal outcome.
EOF
  if [ -n "$applied" ]; then
    cat <<EOF

Egress policy derived from declared endpoints, because without the THREE
settings below the profile reports "usable":false with "reason":"egress-refused"
(item H35). They were absent from the environment, so they were set for this
process only; an already-present value is never touched:
$applied

EOF
  else
    printf '\n'
  fi
  if [ -n "$BENCH_UNEXPRESSIBLE" ]; then
    cat >&2 <<EOF
!! Endpoint on an IPv6 literal address: $BENCH_UNEXPRESSIBLE
   The reserved network exception was NOT set and will not be: the grammar of
   RAVENROOT_EGRESS_RESERVED_EXCEPTIONS splits name and network at the first ':', so an IPv6 literal
   produces an empty-name entry that runtime silently discards. The profile remains
   "usable":false with "reason":"egress-refused" while the endpoint remains written this way.
   Alternatives: use 'localhost' (the standard 'localhost:LOOPBACK' exception covers it) or the
   IPv4 loopback address.

EOF
  fi
  RAVENROOT_UI_DIR=$ui_dir exec mvn -B --no-transfer-progress -f "$HARNESS_DIR/pom.xml" exec:java
}

# ---- final report -------------------------------------------------------------------------------

report() {
  supervisor_ok=$1
  printf '\n------------------------------------------------------------------------\n'
  printf 'Prepared\n\n'
  if [ -f "$UI_DIR/dist/index.html" ]; then
    printf '  editor          ravenroot/ravenroot-ui/dist\n'
  else
    printf '  editor          NOT built\n'
  fi
  adapter_dirs | while IFS= read -r dir; do
    jar=$(adapter_jar_of_dir "$dir" || true)
    if [ -n "$jar" ]; then
      printf '  adapter         %s\n' "${jar#"$PROJECT_DIR/"}"
    else
      printf '  adapter         %s: NOT built\n' "$(basename "$dir")"
    fi
  done
  if [ -d "$HARNESS_DIR" ]; then
    if [ -f "$HARNESS_DIR/target/classes/ai/ravenroot/devharness/DevHarnessMain.class" ]; then
      printf '  bench           ravenroot-dev-harness (source only, never published)\n'
    else
      printf '  bench           NOT built\n'
    fi
  fi
  if [ "$supervisor_ok" = true ]; then
    printf '  supervisor      deploy/dev/sandbox-supervisor.sh, mounted by compose.yaml\n'
  else
    printf '  supervisor      NEEDS FIXING -- see messages above\n'
  fi

  cat <<EOF

Two launches, and they do not do the same thing

  ./service.sh start -si        release image + mounted supervisor
                                program node: YES     llm-prompt node: NOT IN THE CATALOG
  ./dev.sh bench                source-only bench, loopback only
                                llm-prompt node: YES  program node: NO

  The llm-prompt “NO” in the image is not a missing step: the released artifact cannot register a
  model provider, and the release gate fails package if a registration becomes reachable from the
  shipped main (ADR 0017, item H28 in the register).

How to verify that it works

  sandbox      docker exec ravenroot-ravenroot-1 sh -c \\
                 '$SUPERVISOR_CONTAINER_PATH --ravenroot-sandbox-supervisor-capabilities=v1; echo " rc=\$?"'
               exact expected result: $SUPERVISOR_CAPABILITY rc=0

  adapters     curl -s http://127.0.0.1:8080/v1/model-providers
               on the bench: "adapters":["openai-compatible"] and profile with "usable":true
               in the image: "adapters":[] and "reason":"adapter-not-installed" — the correct
               response for that deployment, not a defect

Guide: docs/developer-guide/build-test.md
EOF
}

# ---- entry point ---------------------------------------------------------------------------------

command=setup
command_set=false
while [ $# -gt 0 ]; do
  case "$1" in
    setup|check|bench)
      if [ "$command_set" = true ]; then
        echo "Only one command per invocation." >&2
        exit 2
      fi
      command=$1
      command_set=true
      ;;
    --with-tests) WITH_TESTS=true ;;
    --skip-ui) SKIP_UI=true ;;
    -h|--help|help) command=help ;;
    *) echo "Unknown command or option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

case "$command" in
  setup) cmd_setup ;;
  check) cmd_check ;;
  bench) cmd_bench ;;
  help) usage ;;
esac
