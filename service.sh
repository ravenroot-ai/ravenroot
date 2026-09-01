#!/usr/bin/env sh
# Optional container/Kubernetes helper. The direct local launcher remains
# ravenroot/scripts/server.sh and does not require Docker, Helm, or Kubernetes.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
COMPOSE_FILE=${RAVENROOT_COMPOSE_FILE:-"$PROJECT_DIR/compose.yaml"}
DEFAULT_COMPOSE_OVERRIDE_FILE="$PROJECT_DIR/.ravenroot-local/compose.override.yaml"
if [ "${RAVENROOT_COMPOSE_OVERRIDE_FILE+x}" = x ]; then
  COMPOSE_OVERRIDE_FILE=$RAVENROOT_COMPOSE_OVERRIDE_FILE
  COMPOSE_OVERRIDE_EXPLICIT=true
else
  COMPOSE_OVERRIDE_FILE=$DEFAULT_COMPOSE_OVERRIDE_FILE
  COMPOSE_OVERRIDE_EXPLICIT=false
fi
HELM_CHART_DIR=${RAVENROOT_HELM_CHART_DIR:-"$PROJECT_DIR/deploy/helm/ravenroot"}
COMPOSE_WAIT_TIMEOUT=${RAVENROOT_COMPOSE_WAIT_TIMEOUT:-60}
HELM_RELEASE=${RAVENROOT_HELM_RELEASE:-ravenroot}
HELM_NAMESPACE=${RAVENROOT_HELM_NAMESPACE:-default}
HELM_TIMEOUT=${RAVENROOT_HELM_TIMEOUT:-5m}
LOCAL_HOST_BIND_ADDRESS=${RAVENROOT_HOST_BIND_ADDRESS:-127.0.0.1}
LOCAL_HOST_PORT=${RAVENROOT_HOST_PORT:-8080}

usage() {
  cat <<'EOF'
Usage: ./service.sh [command] [options]

Compose commands (Docker is required only for these commands):
  start, up, restart       Start genuine disabled-auth local mode on 127.0.0.1 and wait for health
  stop, down               Stop and remove the Compose service
  status, ps               Show Compose service status
  logs                     Follow Ravenroot Compose logs

Kubernetes commands (Helm and cluster access are required only for these commands):
  deploy, upgrade          helm upgrade --install, wait atomically for readiness
  undeploy                 Uninstall the configured Helm release and wait for deletion
  k8s-status               Show the configured Helm release status

Options for start/up/restart (the default command is restart):
  -si, --skipimage         Skip image, source/UI builds, and tests; recreate using the existing image
  -sb, --skipbuild         Skip source/UI builds and tests; build only the OCI image
  -st, --skiptest          Build source/UI and the OCI image, but do not run tests
  -h, --help               Show this help

Build selection is deterministic: --skipimage overrides the other options; --skipbuild implies
--skiptest. The image build still compiles its isolated build stages when --skipbuild is used.

Plugin bundles (PLAT-12): the build stages the same way for source, UI and plugin material, so
the three options above apply to ravenroot-plugins/ exactly as they apply to everything else the
image build compiles:
  -si  Reuses the existing image outright, so it does not see plugin bundles added or changed in
       ravenroot-plugins/ since that image was built -- rebuild without -si to pick them up.
  -sb  Does not recompile plugin sources; rebuilds the image using whatever bundles are already
       produced (in ravenroot-plugins/ as it stands, or via Docker's own layer cache).
  -st  Compiles plugin sources into bundles without running their tests, the same as it does for
       Ravenroot's own source and UI.
Docker's layer cache invalidation is one-directional, not mutual independence: a change to
ravenroot-plugins/ alone invalidates only the plugin-staging layer, not the expensive source/UI
compile before it -- but a change to Ravenroot's own source invalidates the plugin-staging layer too,
because it sits downstream of the source copy, even though no plugin file changed. See the
Dockerfile's PLAT-12 comments for why that ordering (plugin staging after the compile, not beside it)
was chosen deliberately rather than incidentally.

Configuration:
  RAVENROOT_PLUGINS_DIR            Plugin bundle convention directory for the Compose build
                                    (default: ravenroot-plugins); a non-relative or ".."-bearing
                                    value is rejected by the Dockerfile's own explicit check, not by
                                    Docker's build context handling, which clamps rather than refuses
  RAVENROOT_COMPOSE_FILE          Compose file (default: ./compose.yaml)
  RAVENROOT_COMPOSE_OVERRIDE_FILE Optional second Compose file. When unset, service.sh automatically
                                    uses ./.ravenroot-local/compose.override.yaml if it exists. Set
                                    this variable to an empty value to disable the automatic override.
  RAVENROOT_COMPOSE_WAIT_TIMEOUT  Compose health wait timeout in seconds (default: 60)
  RAVENROOT_HOST_PORT              Local Compose port (default: 8080)
  RAVENROOT_HELM_RELEASE          Helm release name (default: ravenroot)
  RAVENROOT_HELM_NAMESPACE        Kubernetes namespace (default: default)
  RAVENROOT_HELM_TIMEOUT          Helm operation timeout (default: 5m)
  RAVENROOT_IMAGE_REPOSITORY      Helm image repository (default: ravenroot)
  RAVENROOT_IMAGE_TAG             Helm image tag, for local Minikube use local (default: local)
  RAVENROOT_IMAGE_DIGEST          Immutable image digest; takes precedence over the tag

Helm deploy/upgrade requires these public OIDC settings in the environment:
  RAVENROOT_AUTH_ISSUER, RAVENROOT_AUTH_AUDIENCE, RAVENROOT_AUTH_JWKS_URI

Compose start/restart always uses supported disabled authentication, but only after verifying the
rendered service has exactly one TCP publication, exclusively as 127.0.0.1. A non-loopback host
binding, additional port, or unexpected port contract is rejected. `jq` is required for this
structured preflight.

Examples:
  ./service.sh
  ./service.sh restart
  ./service.sh restart -st
  ./service.sh restart --skiptest
  ./service.sh --skipimage
  ./service.sh deploy
  RAVENROOT_IMAGE_REPOSITORY=ghcr.io/acme/ravenroot \
  RAVENROOT_IMAGE_DIGEST=sha256:... ./service.sh upgrade
EOF
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

# Node runtime selection lives in scripts/lib/node-runtime.sh because dev.sh builds the same UI and
# must use the same version: one definition, two callers.
if [ ! -r "$PROJECT_DIR/scripts/lib/node-runtime.sh" ]; then
  echo "scripts/lib/node-runtime.sh, the library shared with dev.sh, is missing or unreadable." >&2
  echo "The checkout is incomplete: restore it before retrying." >&2
  exit 1
fi
# shellcheck source=scripts/lib/node-runtime.sh
. "$PROJECT_DIR/scripts/lib/node-runtime.sh"

compose() {
  require_command docker
  if [ -n "$COMPOSE_OVERRIDE_FILE" ]; then
    if [ -r "$COMPOSE_OVERRIDE_FILE" ]; then
      set -- --file "$COMPOSE_FILE" --file "$COMPOSE_OVERRIDE_FILE" "$@"
    elif [ "$COMPOSE_OVERRIDE_EXPLICIT" = true ]; then
      echo "RAVENROOT_COMPOSE_OVERRIDE_FILE is missing or unreadable: $COMPOSE_OVERRIDE_FILE" >&2
      exit 2
    else
      set -- --file "$COMPOSE_FILE" "$@"
    fi
  else
    set -- --file "$COMPOSE_FILE" "$@"
  fi
  RAVENROOT_AUTH_MODE=disabled \
  RAVENROOT_BIND_ADDRESS=0.0.0.0 \
  RAVENROOT_CONTAINER_LOOPBACK_ONLY=true \
  RAVENROOT_LOCAL_HOST_BIND_ADDRESS="$LOCAL_HOST_BIND_ADDRESS" \
  RAVENROOT_HOST_BIND_ADDRESS="$LOCAL_HOST_BIND_ADDRESS" \
    docker compose "$@"
}

require_loopback_compose_contract() {
  if [ "$LOCAL_HOST_BIND_ADDRESS" != 127.0.0.1 ]; then
    echo "Disabled local authentication may only be published on 127.0.0.1; refusing $LOCAL_HOST_BIND_ADDRESS." >&2
    exit 2
  fi
  case "$LOCAL_HOST_PORT" in
    ''|*[!0-9]*)
      echo "RAVENROOT_HOST_PORT must be a numeric TCP port; refusing $LOCAL_HOST_PORT." >&2
      exit 2
      ;;
  esac
  if [ "$LOCAL_HOST_PORT" -lt 1 ] || [ "$LOCAL_HOST_PORT" -gt 65535 ]; then
    echo "RAVENROOT_HOST_PORT must be between 1 and 65535; refusing $LOCAL_HOST_PORT." >&2
    exit 2
  fi
  require_command jq
  if ! compose config --format json | jq -e --argjson expectedPort "$LOCAL_HOST_PORT" '
    .services | type == "object"
    and (.ravenroot | type == "object")
    and (.ravenroot.environment | type == "object")
    and (.ravenroot.environment.RAVENROOT_AUTH_MODE == "disabled")
    and (.ravenroot.environment.RAVENROOT_BIND_ADDRESS == "0.0.0.0")
    and (.ravenroot.environment.RAVENROOT_CONTAINER_LOOPBACK_ONLY == "true")
    and (.ravenroot.environment.RAVENROOT_LOCAL_HOST_BIND_ADDRESS == "127.0.0.1")
    and (.ravenroot.ports | type == "array" and length == 1)
    and (.ravenroot.ports[0] | type == "object")
    and (.ravenroot.ports[0] as $port
      | $port.host_ip == "127.0.0.1"
      and $port.target == 8080
      and ($port.protocol // "tcp" | type == "string" and ascii_downcase == "tcp")
      and ($port.mode == "ingress")
      and ($port.published | tostring) as $published
      | ($published | test("^[1-9][0-9]{0,4}$"))
      and (($published | tonumber) == $expectedPort))
  ' >/dev/null; then
    echo "Compose local-security contract requires exactly one 127.0.0.1:$LOCAL_HOST_PORT -> 8080/tcp publication; refusing startup." >&2
    exit 2
  fi
}

run_source_build() {
  select_node_runtime
  require_command npm
  require_command mvn
  (
    cd "$PROJECT_DIR/ravenroot/ravenroot-ui"
    npm ci
    if [ "$SKIP_TEST" = false ]; then
      npm test
    fi
    npm run build
  )
  (
    cd "$PROJECT_DIR/ravenroot"
    if [ "$SKIP_TEST" = true ]; then
      mvn --batch-mode --no-transfer-progress -DskipTests clean package
    else
      mvn --batch-mode --no-transfer-progress clean verify
    fi
  )
}

start_compose() {
  require_loopback_compose_contract
  if [ "$SKIP_IMAGE" = false ]; then
    if [ "$SKIP_BUILD" = false ]; then
      run_source_build
    fi
    compose build
  fi

  if ! compose up --detach --no-build --force-recreate --remove-orphans --wait \
    --wait-timeout "$COMPOSE_WAIT_TIMEOUT"; then
    echo "Ravenroot Compose startup failed; inspect with './service.sh logs' or 'docker compose ps'." >&2
    return 1
  fi
  echo "Ravenroot Compose service is running and healthy."
}

require_oidc_configuration() {
  missing=false
  for variable in RAVENROOT_AUTH_ISSUER RAVENROOT_AUTH_AUDIENCE RAVENROOT_AUTH_JWKS_URI; do
    case "$variable" in
      RAVENROOT_AUTH_ISSUER) value=${RAVENROOT_AUTH_ISSUER:-} ;;
      RAVENROOT_AUTH_AUDIENCE) value=${RAVENROOT_AUTH_AUDIENCE:-} ;;
      RAVENROOT_AUTH_JWKS_URI) value=${RAVENROOT_AUTH_JWKS_URI:-} ;;
    esac
    if [ -z "$value" ]; then
      echo "$variable is required for Helm deployment." >&2
      missing=true
    fi
  done
  if [ "$missing" = true ]; then
    echo "Set public OIDC configuration in the environment; do not put credentials in Helm values." >&2
    exit 2
  fi
}

deploy_helm() {
  require_command helm
  require_oidc_configuration
  image_repository=${RAVENROOT_IMAGE_REPOSITORY:-ravenroot}
  image_tag=${RAVENROOT_IMAGE_TAG:-local}
  image_digest=${RAVENROOT_IMAGE_DIGEST:-}

  set -- upgrade --install "$HELM_RELEASE" "$HELM_CHART_DIR" \
    --namespace "$HELM_NAMESPACE" --create-namespace --wait --atomic --timeout "$HELM_TIMEOUT" \
    --set-string "image.repository=$image_repository" \
    --set-string "image.tag=$image_tag" \
    --set-string "auth.issuer=$RAVENROOT_AUTH_ISSUER" \
    --set-string "auth.audience=$RAVENROOT_AUTH_AUDIENCE" \
    --set-string "auth.jwksUri=$RAVENROOT_AUTH_JWKS_URI"
  if [ -n "$image_digest" ]; then
    set -- "$@" --set-string "image.digest=$image_digest"
  fi
  helm "$@"
}

undeploy_helm() {
  require_command helm
  helm uninstall "$HELM_RELEASE" --namespace "$HELM_NAMESPACE" --wait --timeout "$HELM_TIMEOUT"
}

command=restart
command_set=false
SKIP_IMAGE=false
SKIP_BUILD=false
SKIP_TEST=false

while [ "$#" -gt 0 ]; do
  case "$1" in
    -si|-skipimage|--skipimage|skipimage) SKIP_IMAGE=true; SKIP_BUILD=true; SKIP_TEST=true ;;
    -sb|-skipbuild|--skipbuild|skipbuild) SKIP_BUILD=true; SKIP_TEST=true ;;
    -st|-skiptest|--skiptest|skiptest) SKIP_TEST=true ;;
    -h|--help|help|h) command=help ;;
    start|up|restart|stop|down|status|ps|logs|deploy|upgrade|undeploy|k8s-status)
      if [ "$command_set" = true ]; then
        echo "Only one command may be supplied." >&2
        exit 2
      fi
      command=$1
      command_set=true
      ;;
    *) echo "Unknown command or option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

case "$command" in
  start|up|restart) start_compose ;;
  stop|down) compose down --remove-orphans ;;
  status|ps) compose ps ;;
  logs) compose logs --follow ravenroot ;;
  deploy|upgrade) deploy_helm ;;
  undeploy) undeploy_helm ;;
  k8s-status) require_command helm; helm status "$HELM_RELEASE" --namespace "$HELM_NAMESPACE" ;;
  help) usage ;;
esac
