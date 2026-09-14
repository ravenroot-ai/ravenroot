ARG BASE_IMAGE
FROM ${BASE_IMAGE} AS base
FROM scratch
# Copy files, not image configuration such as implicit VOLUME or HEALTHCHECK authority.
COPY --from=base / /
USER 0
RUN mkdir -p /workspace && chown 65532:65532 /workspace
COPY probe.sh /runner-probe.sh
USER 65532:65532
WORKDIR /workspace
ENTRYPOINT ["/bin/sh", "/runner-probe.sh"]
