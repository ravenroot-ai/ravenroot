#!/usr/bin/env sh
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
JAR_PATH="$PROJECT_DIR/ravenroot-distribution/target/ravenroot.jar"
RUNTIME_DIR=${RAVENROOT_RUN_DIR:-"$PROJECT_DIR/target/server"}
PID_FILE="$RUNTIME_DIR/ravenroot.pid"
LOG_FILE=${RAVENROOT_LOG_FILE:-"$RUNTIME_DIR/ravenroot.log"}
JAVA_COMMAND=${RAVENROOT_JAVA:-java}
PORT=${RAVENROOT_PORT:-8080}
AUTH_MODE=${RAVENROOT_AUTH_MODE:-disabled}
BIND_ADDRESS=${RAVENROOT_BIND_ADDRESS:-127.0.0.1}
export RAVENROOT_AUTH_MODE="$AUTH_MODE"
export RAVENROOT_BIND_ADDRESS="$BIND_ADDRESS"

usage() {
  cat <<'EOF'
Usage: ./ravenroot/scripts/server.sh <command>

Commands:
  start, s, up       Build when needed and start Ravenroot in the background
  stop, x, down      Gracefully stop the managed Ravenroot process
  status, st, ps     Show process and HTTP health status
  restart, r, re     Stop and start Ravenroot
  foreground, fg, f  Build when needed and run in the current terminal
  help, h            Show this help

Environment:
  RAVENROOT_JAVA       Java 21+ executable
  RAVENROOT_PORT       HTTP port (default: 8080)
  RAVENROOT_RUN_DIR    PID/log state directory
  RAVENROOT_LOG_FILE   background server log path
  RAVENROOT_AUTH_MODE  oidc, local-token, or disabled (this local helper defaults to disabled)
  RAVENROOT_BIND_ADDRESS
                       Listener IP (disabled/local-token require loopback; default: 127.0.0.1)
  RAVENROOT_BROWSER_ALLOWED_ORIGINS
                       Exact comma-separated browser origins (default: loopback origins on the server port)
  RAVENROOT_SSE_AUTH_REVALIDATION_SECONDS
                       Long-lived SSE credential revalidation interval, 1-300 (default: 30)
  RAVENROOT_UI_CONNECT_ORIGINS
                       Exact CSP connect destinations for confirmed external UI targets (default: self only)
  RAVENROOT_TRUSTED_TLS_TERMINATOR
                       Set true only behind a trusted TLS terminator; requires HTTPS RAVENROOT_PUBLIC_ORIGIN
  RAVENROOT_ARTIFACT_DUAL_CONTROL
                       true or false exactly; maker-checker separation defaults to true

OIDC mode (the server executable default) requires:
  RAVENROOT_AUTH_ISSUER, RAVENROOT_AUTH_AUDIENCE, RAVENROOT_AUTH_JWKS_URI

For authenticated loopback development use RAVENROOT_AUTH_MODE=local-token and set
RAVENROOT_AUTH_LOCAL_TOKEN to a caller-generated value of at least 32 characters.

Program execution is independently guarded by:
  RAVENROOT_ALLOWED_TOOLS=program.execute

Artifact lifecycle APIs are always present and are protected by authentication, tenant ownership,
role/scope authorization, independent approval, and lifecycle audit.
EOF
}

check_java() {
  if ! command -v "$JAVA_COMMAND" >/dev/null 2>&1; then
    echo "Java runtime not found: $JAVA_COMMAND" >&2
    exit 1
  fi
  JAVA_MAJOR=$("$JAVA_COMMAND" -version 2>&1 | awk -F '[\".]' '/version/ { print $2; exit }')
  if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 21 ]; then
    echo "Ravenroot requires Java 21 or newer; '$JAVA_COMMAND' reports Java ${JAVA_MAJOR:-unknown}." >&2
    echo "Set RAVENROOT_JAVA to a Java 21+ executable if it is not the default on PATH." >&2
    exit 1
  fi
}

build_if_needed() {
  if [ ! -f "$JAR_PATH" ]; then
    "$PROJECT_DIR/scripts/build-release.sh"
  fi
}

managed_pid() {
  [ -f "$PID_FILE" ] || return 1
  pid=$(sed -n '1p' "$PID_FILE")
  case "$pid" in
    ''|*[!0-9]*) return 1 ;;
  esac
  kill -0 "$pid" 2>/dev/null || return 1
  command_line=$(ps -p "$pid" -o command= 2>/dev/null || true)
  case "$command_line" in
    *ravenroot.jar*) printf '%s\n' "$pid" ;;
    *) return 1 ;;
  esac
}

http_healthy() {
  command -v curl >/dev/null 2>&1 || return 2
  curl --silent --fail --max-time 2 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1
}

start_server() {
  check_java
  if pid=$(managed_pid); then
    echo "Ravenroot is already running (PID $pid, http://127.0.0.1:$PORT)."
    return 0
  fi
  mkdir -p "$RUNTIME_DIR"
  rm -f "$PID_FILE"
  build_if_needed
  nohup "$JAVA_COMMAND" -jar "$JAR_PATH" >>"$LOG_FILE" 2>&1 </dev/null &
  pid=$!
  printf '%s\n' "$pid" >"$PID_FILE"

  if ! command -v curl >/dev/null 2>&1; then
    sleep 1
    if kill -0 "$pid" 2>/dev/null; then
      echo "Ravenroot started (PID $pid). Install curl to include the HTTP readiness check."
      echo "Log: $LOG_FILE"
      return 0
    fi
  fi

  attempt=0
  while [ "$attempt" -lt 50 ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$PID_FILE"
      echo "Ravenroot stopped during startup. See $LOG_FILE" >&2
      return 1
    fi
    if http_healthy; then
      echo "Ravenroot started (PID $pid, http://127.0.0.1:$PORT)."
      echo "Log: $LOG_FILE"
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 0.2
  done
  echo "Ravenroot process $pid is running but health did not become ready within 10 seconds." >&2
  echo "Log: $LOG_FILE" >&2
  return 1
}

stop_server() {
  if ! pid=$(managed_pid); then
    rm -f "$PID_FILE"
    echo "Ravenroot is not running."
    return 0
  fi
  kill "$pid"
  attempt=0
  while kill -0 "$pid" 2>/dev/null && [ "$attempt" -lt 100 ]; do
    attempt=$((attempt + 1))
    sleep 0.1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "Ravenroot did not stop within 10 seconds; terminating PID $pid." >&2
    kill -KILL "$pid"
  fi
  rm -f "$PID_FILE"
  echo "Ravenroot stopped."
}

status_server() {
  if ! pid=$(managed_pid); then
    rm -f "$PID_FILE"
    echo "Ravenroot is stopped."
    return 3
  fi
  if http_healthy; then
    echo "Ravenroot is running and healthy (PID $pid, http://127.0.0.1:$PORT)."
    echo "Log: $LOG_FILE"
    return 0
  else
    health_result=$?
  fi
  if [ "$health_result" -eq 2 ]; then
    echo "Ravenroot is running (PID $pid). Install curl to include the HTTP health check."
    return 0
  fi
  echo "Ravenroot process $pid is running, but http://127.0.0.1:$PORT/health is unavailable." >&2
  return 1
}

foreground_server() {
  check_java
  build_if_needed
  exec "$JAVA_COMMAND" -jar "$JAR_PATH"
}

command=${1:-help}
case "$command" in
  start|s|up) start_server ;;
  stop|x|down) stop_server ;;
  status|st|ps) status_server ;;
  restart|r|re) stop_server; start_server ;;
  foreground|fg|f) foreground_server ;;
  help|h|-h|--help) usage ;;
  *) echo "Unknown command: $command" >&2; usage >&2; exit 2 ;;
esac
