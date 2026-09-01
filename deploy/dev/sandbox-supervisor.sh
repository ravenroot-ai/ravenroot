#!/bin/sh
# DEVELOPMENT-ONLY sandbox supervisor — IT ISOLATES NOTHING.
#
# It satisfies the contract expected by SandboxSupervisorProcessLauncher and nothing else: it applies
# NO operating-system isolation. No CPU, memory, process, file, or network limits are imposed; the
# arguments describing them are read and ignored. It runs the trusted worker as an ordinary child
# process of the same container.
#
# It exists for one reason only: to allow the create/validate/test/approve/activate cycle to complete
# on a local installation, because without a supervisor the Validate step fails with
# SANDBOX_LAUNCHER_MISSING and no programmable artifact can be built.
#
# This is not the isolation required by SEC-11 and does not replace it. It is mounted only by
# compose.yaml, which declares itself “a genuine local-only development mode”; it does not enter the
# image and is not part of any release artifact.
set -eu

for argument in "$@"; do
    case "$argument" in
        --ravenroot-sandbox-supervisor-capabilities=v1)
            printf 'ravenroot-sandbox-supervisor/1'
            exit 0
            ;;
        --trusted-worker=*) WORKER=${argument#--trusted-worker=} ;;
        --trusted-jre=*)    JRE=${argument#--trusted-jre=} ;;
    esac
done

[ -n "${WORKER:-}" ] && [ -n "${JRE:-}" ] || exit 78

WORK=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-dev-sandbox.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/request"

# On its first execution, GraalPy materializes its standard library — json, math, re, datetime, and
# everything else — in an on-disk resource cache. Without explicit direction, it uses $HOME/.cache;
# here HOME is /opt/ravenroot, which compose.yaml mounts read-only: extraction fails, sys.path lacks
# the standard library, and EVERY import dies with ModuleNotFoundError — including `import json`, the
# first line of almost every example.
#
# The error names the module, so it reads as “that module does not exist.” The library exists and is
# complete: the cache cannot be created. The language sandbox is unrelated — measurements show that
# all imports pass even with IOAccess.NONE, the policy used by the worker to build the context.
#
# The property must be written HERE, on the command, rather than as an environment variable: the
# launcher starts this supervisor with a scrubbed environment, so JAVA_TOOL_OPTIONS configured in
# compose.yaml reaches the server but NOT the worker. Measurements show that under the supervisor
# the “Picked up JAVA_TOOL_OPTIONS” line does not appear and the worker continued resolving the
# cache at $HOME/.cache.
#
# The path is the persistent volume, not the /tmp tmpfs: extraction uses several tens of MB, /tmp is
# 64m here, and recreating it at every restart would cost each session's first Validate.
"$JRE" -Dpolyglot.engine.userResourceCache=/opt/ravenroot/data/cache \
    -cp "$WORKER" ai.ravenroot.programming.graalvm.GraalVmWorkerMain \
    < "$WORK/request" > "$WORK/response" 2>/dev/null || true

# SandboxSupervisorProtocol: MAGIC(0x52525331) | version(1) | outcome(0 = COMPLETED) | length | body.
# All integer fields are big-endian, and length must exactly match the body: the reader rejects a
# byte too many or too few with SANDBOX_PROTOCOL_FAILURE.
LENGTH=$(wc -c < "$WORK/response" | tr -d ' ')
printf '\122\122\123\061\000\000\000\001\000'
printf "$(printf '\\%03o\\%03o\\%03o\\%03o' \
    $(( (LENGTH >> 24) & 255 )) $(( (LENGTH >> 16) & 255 )) \
    $(( (LENGTH >> 8) & 255 )) $(( LENGTH & 255 )))"
cat "$WORK/response"
