#!/usr/bin/env sh
# Validates every chart value leaf has a closed schema contract and that the rendered platform
# settings keep the image, service, storage, and filesystem integration invariants coherent.
set -eu

PROJECT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
CHART="$PROJECT_DIR/deploy/helm/ravenroot"
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ravenroot-helm-values-contract.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

helm_base() {
  helm template ravenroot "$CHART" \
    --set-string auth.issuer=https://idp.example.test/ \
    --set-string auth.audience=ravenroot-helm-values-contract \
    --set-string auth.jwksUri=https://idp.example.test/jwks "$@"
}

helm_base >"$TEMP_DIR/default.yaml"
helm_base \
  --set-string image.repository=registry.example.test/ravenroot \
  --set-string image.tag=release-test \
  --set-string image.digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  --set image.pullPolicy=Always \
  --set service.type=NodePort \
  --set service.port=9090 \
  --set resources.requests.cpu=200m \
  --set resources.requests.memory=384Mi \
  --set-string resources.limits.cpu=2 \
  --set resources.limits.memory=2Gi \
  --set podSecurityContext.fsGroup=10002 \
  --set podSecurityContext.fsGroupChangePolicy=Always \
  --set securityContext.runAsUser=10002 \
  --set securityContext.runAsGroup=10002 \
  --set probes.readiness.initialDelaySeconds=4 \
  --set probes.readiness.periodSeconds=6 \
  --set probes.liveness.initialDelaySeconds=16 \
  --set probes.liveness.periodSeconds=11 \
  --set tmpfs.sizeLimit=128Mi \
  --set persistence.size=2Gi \
  --set-string persistence.storageClass=fast-rwo \
  >"$TEMP_DIR/nondefault.yaml"

helm_base \
  --set-string image.repository=registry.example.test/ravenroot \
  --set-string image.tag=release-test \
  --set-string image.digest= \
  >"$TEMP_DIR/tag-only.yaml"

if helm template ravenroot "$CHART" >"$TEMP_DIR/missing-auth.out" 2>&1; then
  echo "Helm accepted a release without required OIDC values" >&2
  exit 1
fi

python3 - "$PROJECT_DIR" "$TEMP_DIR/default.yaml" "$TEMP_DIR/nondefault.yaml" "$TEMP_DIR/tag-only.yaml" <<'PY'
import json
import sys
from pathlib import Path

import yaml

root = Path(sys.argv[1])
schema = json.loads((root / "deploy/helm/ravenroot/values.schema.json").read_text())
values = yaml.safe_load((root / "deploy/helm/ravenroot/values.yaml").read_text())

expected_top = set(values)
if schema.get("type") != "object" or schema.get("additionalProperties") is not False:
    raise SystemExit("the Helm root values contract must be closed")
if set(schema.get("required", [])) != expected_top or set(schema.get("properties", [])) != expected_top:
    raise SystemExit("every current top-level Helm value must be required and schema-owned")

def verify_leaf_schema(value, node, path):
    if isinstance(value, dict):
        if "$ref" in node:
            node = schema["definitions"][node["$ref"].rsplit("/", 1)[1]]
        if node.get("type") != "object" or node.get("additionalProperties") is not False:
            raise SystemExit(f"{path} must be a closed schema object")
        if set(node.get("required", [])) != set(value) or set(node.get("properties", [])) != set(value):
            raise SystemExit(f"{path} schema leaves do not match values.yaml")
        for key, child in value.items():
            verify_leaf_schema(child, node["properties"][key], f"{path}.{key}")
    elif isinstance(value, list):
        if node.get("type") != "array" or "items" not in node:
            raise SystemExit(f"{path} must specify array item validation")
        for index, child in enumerate(value):
            verify_leaf_schema(child, node["items"], f"{path}.{index}")
    elif not isinstance(value, (str, int, bool, float)) and value is not None:
        raise SystemExit(f"unexpected non-scalar Helm value at {path}")

verify_leaf_schema(values, schema, "values")

service = schema["properties"]["service"]
if service["properties"]["type"].get("enum") != ["ClusterIP", "NodePort", "LoadBalancer"]:
    raise SystemExit("service.type must enumerate only service kinds implemented by this chart")
if service["properties"]["port"] != {"type": "integer", "minimum": 1, "maximum": 65535}:
    raise SystemExit("service.port must use the complete TCP/UDP port range")
if schema["properties"]["engine"].get("enum") != ["pekko"]:
    raise SystemExit("the packaged Helm image must expose only its Pekko engine")

for document_path, expected in [(Path(sys.argv[2]), values), (Path(sys.argv[3]), None)]:
    docs = [item for item in yaml.safe_load_all(document_path.read_text()) if item]
    by_kind = {item["kind"]: item for item in docs}
    deployment, service_doc, pvc = (by_kind["Deployment"], by_kind["Service"], by_kind["PersistentVolumeClaim"])
    pod = deployment["spec"]["template"]["spec"]
    container = pod["containers"][0]
    env = {item["name"]: item["value"] for item in container["env"]}
    if container["ports"] != [{"name": "http", "containerPort": 8080, "protocol": "TCP"}]:
        raise SystemExit("the pod HTTP port must remain the fixed image contract port 8080")
    if env.get("RAVENROOT_PORT") != "8080" or env.get("RAVENROOT_BIND_ADDRESS") != "0.0.0.0":
        raise SystemExit("the chart must retain the exact image port and bind-address mirrors")
    if service_doc["spec"]["ports"] != [{"name": "http", "port": service_doc["spec"]["ports"][0]["port"], "targetPort": "http", "protocol": "TCP"}]:
        raise SystemExit("the Service must target the named pod HTTP port")
    mounts = {item["name"]: item["mountPath"] for item in container["volumeMounts"]}
    if mounts != {"temporary-files": "/tmp", "ravenroot-data": "/opt/ravenroot/data"}:
        raise SystemExit("temporary and durable mount roots are fixed deployment-layout contracts")
    volumes = {item["name"]: item for item in pod["volumes"]}
    if volumes["ravenroot-data"].get("persistentVolumeClaim", {}).get("claimName") != pvc["metadata"]["name"]:
        raise SystemExit("the data mount must use the chart PVC")
    if expected is not None:
        if container["image"] != "ravenroot:local" or container["imagePullPolicy"] != "IfNotPresent":
            raise SystemExit("default image values did not render")
        if {name: env[name] for name in ("RAVENROOT_AUTH_ISSUER", "RAVENROOT_AUTH_AUDIENCE", "RAVENROOT_AUTH_JWKS_URI")} != {
                "RAVENROOT_AUTH_ISSUER": "https://idp.example.test/",
                "RAVENROOT_AUTH_AUDIENCE": "ravenroot-helm-values-contract",
                "RAVENROOT_AUTH_JWKS_URI": "https://idp.example.test/jwks"}:
            raise SystemExit("required OIDC values did not render")
        if service_doc["spec"]["type"] != expected["service"]["type"] or service_doc["spec"]["ports"][0]["port"] != expected["service"]["port"]:
            raise SystemExit("default Service values did not render")
        if pod["securityContext"] != expected["podSecurityContext"] or container["securityContext"] != expected["securityContext"]:
            raise SystemExit("default pod security values did not render")
        if container["resources"] != expected["resources"]:
            raise SystemExit("default resource requirements did not render")
        if (container["readinessProbe"]["initialDelaySeconds"], container["readinessProbe"]["periodSeconds"],
                container["livenessProbe"]["initialDelaySeconds"], container["livenessProbe"]["periodSeconds"]) \
                != (expected["probes"]["readiness"]["initialDelaySeconds"],
                    expected["probes"]["readiness"]["periodSeconds"],
                    expected["probes"]["liveness"]["initialDelaySeconds"],
                    expected["probes"]["liveness"]["periodSeconds"]):
            raise SystemExit("default probe timing values did not render")
        if volumes["temporary-files"]["emptyDir"] != {"medium": "Memory", "sizeLimit": expected["tmpfs"]["sizeLimit"]}:
            raise SystemExit("default tmpfs size limit did not render")
        if pvc["spec"]["accessModes"] != expected["persistence"]["accessModes"] or pvc["spec"]["resources"]["requests"]["storage"] != expected["persistence"]["size"]:
            raise SystemExit("default persistent-volume contract did not render")
        if "storageClassName" in pvc["spec"]:
            raise SystemExit("empty default storage class must delegate to the cluster")
    else:
        if container["image"] != "registry.example.test/ravenroot@sha256:" + "a" * 64 \
                or container["imagePullPolicy"] != "Always":
            raise SystemExit("nondefault image values did not render")
        if service_doc["spec"]["type"] != "NodePort" or service_doc["spec"]["ports"][0]["port"] != 9090:
            raise SystemExit("nondefault Service values did not render")
        if container["resources"] != {"requests": {"cpu": "200m", "memory": "384Mi"}, "limits": {"cpu": "2", "memory": "2Gi"}}:
            raise SystemExit("nondefault resource requirements did not render")
        if pod["securityContext"]["runAsNonRoot"] is not True \
                or pod["securityContext"]["fsGroup"] != 10002 \
                or pod["securityContext"]["fsGroupChangePolicy"] != "Always":
            raise SystemExit("nondefault pod security values did not render")
        if container["securityContext"]["allowPrivilegeEscalation"] is not False \
                or container["securityContext"]["readOnlyRootFilesystem"] is not True \
                or container["securityContext"]["runAsUser"] != 10002 \
                or container["securityContext"]["runAsGroup"] != 10002:
            raise SystemExit("nondefault container identity values did not render")
        if (container["readinessProbe"]["initialDelaySeconds"], container["readinessProbe"]["periodSeconds"],
                container["livenessProbe"]["initialDelaySeconds"], container["livenessProbe"]["periodSeconds"]) \
                != (4, 6, 16, 11):
            raise SystemExit("nondefault probe timing values did not render")
        if volumes["temporary-files"]["emptyDir"]["sizeLimit"] != "128Mi":
            raise SystemExit("nondefault tmpfs size limit did not render")
        if pvc["spec"]["resources"]["requests"]["storage"] != "2Gi" or pvc["spec"].get("storageClassName") != "fast-rwo":
            raise SystemExit("nondefault persistent-volume values did not render")

tag_documents = [item for item in yaml.safe_load_all(Path(sys.argv[4]).read_text()) if item]
tag_deployment = next(item for item in tag_documents if item["kind"] == "Deployment")
tag_image = tag_deployment["spec"]["template"]["spec"]["containers"][0]["image"]
if tag_image != "registry.example.test/ravenroot:release-test":
    raise SystemExit("tag-only image values did not render")

dockerfile = (root / "Dockerfile").read_text()
raw = (root / "deploy/kubernetes/ravenroot.yaml").read_text()
documentation = (root / "docs/reference/configuration.md").read_text()
for required in ("Helm values compatibility", "post-renderer", "maintained chart/template customization",
                 "--set-string resources.limits.cpu=2"):
    if required not in documentation:
        raise SystemExit("closed Helm values migration guidance is incomplete")
for source, required in [
        (dockerfile, "ENV RAVENROOT_PORT=8080"),
        (dockerfile, "RAVENROOT_BIND_ADDRESS=0.0.0.0"),
        (dockerfile, "EXPOSE 8080"),
        (raw, "- name: RAVENROOT_PORT\n              value: \"8080\""),
        (raw, "- name: RAVENROOT_BIND_ADDRESS\n              value: \"0.0.0.0\"")]:
    if required not in source:
        raise SystemExit(f"missing fixed port/bind image integration evidence: {required!r}")
PY

for invalid in \
  image.repository= \
  image.digest=sha256:bad \
  image.pullPolicy=Sometimes \
  service.type=ExternalName \
  service.port=0 \
  service.port=65536 \
  resources.requests.cpu=not-a-quantity \
  resources.limits.memory=-1Gi \
  tmpfs.sizeLimit=not-a-quantity \
  tmpfs.sizeLimit=-64Mi \
  tmpfs.sizeLimit=0 \
  persistence.size=-1Gi \
  persistence.size=0 \
  persistence.accessModes[0]=ReadOnlyMany \
  podSecurityContext.runAsNonRoot=false \
  securityContext.allowPrivilegeEscalation=true \
  securityContext.readOnlyRootFilesystem=false \
  podSecurityContext.runAsNonRoot=not-a-boolean \
  podSecurityContext.fsGroup=0 \
  podSecurityContext.fsGroupChangePolicy=Never \
  podSecurityContext.seccompProfile.type=Unconfined \
  securityContext.allowPrivilegeEscalation=not-a-boolean \
  securityContext.readOnlyRootFilesystem=not-a-boolean \
  securityContext.capabilities.drop[0]=NET_ADMIN \
  securityContext.runAsUser=0 \
  securityContext.runAsGroup=0 \
  probes.readiness.initialDelaySeconds=-1 \
  probes.readiness.periodSeconds=0 \
  probes.liveness.initialDelaySeconds=-1 \
  probes.liveness.periodSeconds=0 \
  unrecognizedValue=true; do
  if helm_base --set "$invalid" >"$TEMP_DIR/invalid.out" 2>&1; then
    echo "Helm accepted an invalid values contract input: $invalid" >&2
    exit 1
  fi
done

for field in issuer audience jwksUri; do
  for blank in "   " "$(printf '\034')" "$(printf '\342\200\203')" "$(printf '\343\200\200')"; do
    if helm_base --set-string "auth.$field=$blank" >"$TEMP_DIR/invalid.out" 2>&1; then
      echo "Helm accepted a Java-whitespace-only OIDC value: auth.$field" >&2
      exit 1
    fi
  done
done
helm_base --set-string auth.audience='ravenroot-üñîçødë' >"$TEMP_DIR/unicode-audience.yaml"

helm_base --set-string resources.requests.cpu=0.5 --set resources.limits.memory=500m \
  --set persistence.size=0.5Gi --set tmpfs.sizeLimit=0.5Mi >"$TEMP_DIR/fractional.yaml"
helm_base --set-string resources.requests.cpu=+0.5 --set-string persistence.size=+0.5Gi \
  --set-string tmpfs.sizeLimit=+0.5Mi >"$TEMP_DIR/positive-sign.yaml"

echo "Helm values contract tests passed."
