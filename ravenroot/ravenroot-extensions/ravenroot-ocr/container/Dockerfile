# syntax=docker/dockerfile:1.7

# Deliberately has no mutable default. The caller must supply the published Ravenroot OCI index
# digest, so amd64 and arm64 resolve through one reviewed multi-architecture artifact.
ARG RAVENROOT_IMAGE
FROM ${RAVENROOT_IMAGE}
ARG RAVENROOT_IMAGE

USER 0:0
RUN case "$RAVENROOT_IMAGE" in \
      *@sha256:[0-9a-f][0-9a-f]*) : ;; \
      *) echo "RAVENROOT_IMAGE must be an immutable OCI digest" >&2; exit 1 ;; \
    esac \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends \
         tesseract-ocr=5.3.4-1build5 \
         tesseract-ocr-eng=1:4.1.0-2 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/ravenroot/ocr-tmp \
    && chown 10001:10001 /opt/ravenroot/ocr-tmp \
    && chmod 0700 /opt/ravenroot/ocr-tmp

# `./plugin.sh build ocr` creates this validated bundle before the image build. It remains immutable
# with the rest of the read-only root filesystem; it is never a writable plugin mount.
COPY --from=ocr-bundle --chown=10001:10001 / \
    /opt/ravenroot/plugins/ai.ravenroot.extensions.ocr/

LABEL ai.ravenroot.image.variant="ocr" \
      ai.ravenroot.ocr.executable="/usr/bin/tesseract" \
      ai.ravenroot.ocr.language-data="/usr/share/tesseract-ocr/5/tessdata" \
      ai.ravenroot.ocr.writable-mount="/opt/ravenroot/ocr-tmp" \
      ai.ravenroot.ocr.optional-readonly-mount="/opt/ravenroot/ocr-tessdata"

USER 10001:10001
ENV RAVENROOT_ENABLED_PLUGINS=ai.ravenroot.extensions.ocr
STOPSIGNAL SIGTERM
