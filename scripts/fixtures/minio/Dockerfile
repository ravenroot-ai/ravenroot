ARG BASE_IMAGE
FROM ${BASE_IMAGE}

ARG SOURCE_DATE_EPOCH
ARG FIXTURE_ROLE
ARG UPSTREAM_VERSION
ARG UPSTREAM_SOURCE
ARG UPSTREAM_REVISION
ARG UPSTREAM_DIGEST
ARG UPSTREAM_CREATED
ARG FIXTURE_LICENSE

LABEL org.opencontainers.image.source="https://github.com/ravenroot-ai/ravenroot" \
      org.opencontainers.image.title="Ravenroot MinIO acceptance ${FIXTURE_ROLE}" \
      org.opencontainers.image.description="Repository-controlled wrapper for the immutable MinIO acceptance fixture" \
      org.opencontainers.image.version="${UPSTREAM_VERSION}" \
      org.opencontainers.image.licenses="${FIXTURE_LICENSE}" \
      org.opencontainers.image.created="${UPSTREAM_CREATED}" \
      org.opencontainers.image.documentation="https://github.com/ravenroot-ai/ravenroot/blob/dev/scripts/fixtures/minio/README.md" \
      ai.ravenroot.fixture.role="${FIXTURE_ROLE}" \
      ai.ravenroot.fixture.upstream.source="${UPSTREAM_SOURCE}" \
      ai.ravenroot.fixture.upstream.revision="${UPSTREAM_REVISION}" \
      ai.ravenroot.fixture.upstream.digest="${UPSTREAM_DIGEST}"
