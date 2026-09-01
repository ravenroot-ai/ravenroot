#!/usr/bin/env sh
set -eu

BASE_IMAGE=${RAVENROOT_IMAGE:?set RAVENROOT_IMAGE to the immutable Ravenroot OCI index digest}
case "$BASE_IMAGE" in *@sha256:????????????????????????????????????????????????????????????????) ;; *)
  echo "RAVENROOT_IMAGE must be an immutable OCI digest" >&2; exit 2 ;; esac

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY=$(CDPATH='' cd -- "$SCRIPT_DIR/../../../.." && pwd)
BUNDLE=${RAVENROOT_OCR_BUNDLE:-$REPOSITORY/ravenroot/ravenroot-extensions/ravenroot-ocr/target/plugin-bundle}
ARTIFACT=${RAVENROOT_OCR_ARTIFACT_DIR:?set RAVENROOT_OCR_ARTIFACT_DIR to a new output directory}
PLATFORMS=${RAVENROOT_OCR_PLATFORMS:-linux/amd64,linux/arm64}

test -f "$BUNDLE/ravenroot-plugin.json" || { echo "OCR bundle is missing: $BUNDLE" >&2; exit 2; }
test ! -e "$ARTIFACT" || { echo "OCR artifact directory already exists: $ARTIFACT" >&2; exit 2; }
mkdir -p "$ARTIFACT"

# The OCI exporter retains the BuildKit attestation manifests locally.  The verifier below binds
# every SBOM/provenance statement to a platform manifest listed by this exact OCI index digest.
docker buildx build \
  --file "$SCRIPT_DIR/Dockerfile" \
  --build-context "ocr-bundle=$BUNDLE" \
  --build-arg "RAVENROOT_IMAGE=$BASE_IMAGE" \
  --platform "$PLATFORMS" \
  --sbom=true --provenance=mode=max \
  --metadata-file "$ARTIFACT/buildx-metadata.json" \
  --output "type=oci,dest=$ARTIFACT/oci-layout,tar=false" \
  "$REPOSITORY"

python3 "$SCRIPT_DIR/verify_attestations.py" \
  --layout "$ARTIFACT/oci-layout" \
  --metadata "$ARTIFACT/buildx-metadata.json" \
  --report "$ARTIFACT/attestation-verification.json"

printf '%s\n' "OCR OCI artifact verified: $ARTIFACT/attestation-verification.json"
