#!/usr/bin/env sh
# The current chart supports its SQLite deployment only; PostgreSQL pin repair stays a direct Java env contract.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
CHART="$PROJECT_DIR/deploy/helm/ravenroot"
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-execution-manifest-pin.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

helm_base() {
  helm template ravenroot "$CHART" \
    --set-string auth.issuer=https://idp.example.test/ \
    --set-string auth.audience=ravenroot-execution-manifest-pin-test \
    --set-string auth.jwksUri=https://idp.example.test/jwks "$@"
}

python3 - "$PROJECT_DIR" <<'PY'
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
schema = json.loads((root / "deploy/helm/ravenroot/values.schema.json").read_text())
if "executionStore" in schema.get("required", []) or "executionStore" in schema.get("properties", {}):
    raise SystemExit("the SQLite-only Helm chart must not expose an executionStore values carrier")
values = (root / "deploy/helm/ravenroot/values.yaml").read_text()
template = (root / "deploy/helm/ravenroot/templates/deployment.yaml").read_text()
if "manifestPinAttempts" in values or "RAVENROOT_EXECUTION_MANIFEST_PIN_ATTEMPTS" in template:
    raise SystemExit("the SQLite-only Helm chart must not render the PostgreSQL manifest-pin carrier")
documentation = (root / "docs/reference/postgresql-persistence.md").read_text()
if "current Helm chart deploys the SQLite store only" not in documentation:
    raise SystemExit("PostgreSQL documentation must state the current Helm support boundary")
PY

helm_base >"$TEMP_DIR/default.yaml"
if grep -F 'RAVENROOT_EXECUTION_MANIFEST_PIN_ATTEMPTS' "$TEMP_DIR/default.yaml" >/dev/null; then
  echo "default Helm render unexpectedly forwards the PostgreSQL manifest-pin setting" >&2
  exit 1
fi
if helm_base --set executionStore.manifestPinAttempts=7 >"$TEMP_DIR/unsupported.out" 2>&1; then
  echo "Helm accepted an unsupported PostgreSQL manifest-pin values path" >&2
  exit 1
fi

echo 'execution manifest pin Helm boundary contract passed.'
