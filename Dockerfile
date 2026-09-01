# syntax=docker/dockerfile:1.7

# Multi-architecture Docker Official Images, pinned to their OCI index digest.
ARG NODE_IMAGE=node:24-bookworm-slim@sha256:3638d9a6fe4030bd716be989438248074489337ba3275657f93595428be4fc03
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21@sha256:2b4496088e7b80ae10a8c9f74e574ea21380325a006ec684532ad6bad5bc7273
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64

FROM ${NODE_IMAGE} AS ui-build
WORKDIR /workspace/ravenroot-ui
COPY ravenroot/ravenroot-ui/package.json ravenroot/ravenroot-ui/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY ravenroot/ravenroot-ui/ ./
# UI tests run independently against a full repository checkout
# checkout, because the suite reads shared fixtures from ../ravenroot-core (see
# ravenroot/ravenroot-ui/test/graphml-corpus.test.js). This stage only builds the UI; it does not
# have that sibling directory in its build context and must not re-run the suite here. A previous
# version copied that fixture subtree here so `npm test` could run inside this stage; now that the
# suite no longer runs here, the bridge is gone with it.
RUN npm run build

FROM ${MAVEN_IMAGE} AS java-build
WORKDIR /workspace/ravenroot
ARG MAVEN_PROFILES=""

# RAVENROOT_PLUGINS_DIR is validated here, before anything expensive runs, rather
# than trusted to Docker's own build-context handling. It does not refuse an escaping value: a `..`
# or absolute path is *clamped* to the context root, not rejected -- verified directly (not assumed)
# with a throwaway probe Dockerfile, kept at scripts/verify-plugins-dir-confinement.sh, which shows
# `RAVENROOT_PLUGINS_DIR=../` succeeding and staging the entire build context (this Dockerfile,
# every source file, everything `.dockerignore` does not exclude) as "plugin source". An earlier
# version of this comment claimed Docker refuses the escape outright; it does not, and the difference
# matters: content never left the context either way, but the mechanism actually protecting this
# build is the check below, not the claim the previous comment made. Restricting the value to a
# same-name-or-deeper relative path, both here and in service.sh's matching check, is what turns a
# misconfigured or malicious value into a loud build failure instead of a build that silently stages
# the whole repository and then, finding no ravenroot-plugin.json at that level, silently produces an
# empty plugins directory with nothing in the log louder than an easily-missed one-line notice.
ARG RAVENROOT_PLUGINS_DIR=ravenroot-plugins
RUN case "$RAVENROOT_PLUGINS_DIR" in \
      ""|/*|*..*) \
        echo "RAVENROOT_PLUGINS_DIR must be a non-empty path relative to the build context, without '..': got '$RAVENROOT_PLUGINS_DIR'" >&2; \
        exit 1 ;; \
      *) : ;; \
    esac

COPY ravenroot/ ./
COPY --from=ui-build /workspace/ravenroot-ui/dist ./ravenroot-ui/dist
# Java tests run independently with the same
# `mvn clean verify` used locally. This stage only packages the artifact for the runtime image, so it
# skips re-running the suite (-DskipTests still compiles test sources, catching compile breakage).
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml,required=false \
    if [ -n "$MAVEN_PROFILES" ]; then \
      mvn -B -P"$MAVEN_PROFILES" -DskipTests clean package; \
    else \
      mvn -B -DskipTests clean package; \
    fi

# Plugin bundle staging, deliberately placed after the reactor build rather than
# alongside the `COPY ravenroot/` above. A change to plugin material alone invalidates only this
# layer and the one after it -- not the expensive `mvn package` layer above, which Docker's cache
# still reuses. Note the direction this does NOT hold: a change to Ravenroot's own core source
# invalidates from `COPY ravenroot/` onward as always, and this layer sits downstream of that, so it
# invalidates too even though no plugin file changed -- which is correct (it also redoes bundle
# validation rather than trusting a result computed against a stale jar), but is one-directional, not
# mutual independence.
#
# RAVENROOT_PLUGINS_DIR was already validated above, before `COPY ravenroot/`: see that check for why
# this instruction does not additionally guard against an escaping value itself.
COPY ${RAVENROOT_PLUGINS_DIR}/ /workspace/plugins-src/
# PluginBundleBuildCopy validates every candidate before copying any of them and fails the whole
# build on the first invalid one -- an invalid bundle sitting in the convention directory is a build
# error, never something silently left out of the image. /workspace/plugins-staged always exists
# after this step, empty or not (see that class's javadoc), which is what lets the runtime stage
# below decide -- unconditionally, with no special case for "zero plugins" -- whether to create
# /opt/ravenroot/plugins in the final image at all.
RUN java -cp "ravenroot-plugin-bundle/target/classes:ravenroot-application-api/target/classes" \
    ai.ravenroot.plugin.bundle.PluginBundleBuildCopy /workspace/plugins-src /workspace/plugins-staged

FROM ${RUNTIME_IMAGE} AS runtime
ARG VERSION=dev
ARG REVISION=unknown
ARG SOURCE_URL=""
ARG CREATED=""
ARG DEFAULT_ENGINE=pekko

LABEL org.opencontainers.image.title="Ravenroot" \
      org.opencontainers.image.description="Graph-governed actor-model backend automation server and UI" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.source="${SOURCE_URL}" \
      org.opencontainers.image.created="${CREATED}"

# The java-build stage's staged plugin directory always exists, empty or not (see
# PluginBundleBuildCopy). It lands here at a dotfile scratch path, never directly at
# /opt/ravenroot/plugins, because the RUN below has to be able to leave zero trace of it when it was
# empty -- and COPY cannot itself be conditional. This is the mechanism the empty-directory parity
# proof depends on: an empty plugins directory must produce a filesystem identical to the one built
# before PLAT-12 existed, not "identical except for one harmless empty directory".
COPY --from=java-build /workspace/plugins-staged/ /opt/ravenroot/.plugins-staged/

RUN groupadd --gid 10001 ravenroot \
    && useradd --uid 10001 --gid 10001 --no-create-home \
       --home-dir /opt/ravenroot --shell /usr/sbin/nologin ravenroot \
    && mkdir -p /opt/ravenroot/data/audit /opt/ravenroot/data/cache \
    && if [ -n "$(find /opt/ravenroot/.plugins-staged -mindepth 1 -maxdepth 1 2>/dev/null)" ]; then \
         mkdir -p /opt/ravenroot/plugins \
         && cp -a /opt/ravenroot/.plugins-staged/. /opt/ravenroot/plugins/; \
       fi \
    && rm -rf /opt/ravenroot/.plugins-staged \
    && chown -R 10001:10001 /opt/ravenroot
# /opt/ravenroot/data/audit is pre-created and owned by the app user at build time under SEC-13, so
# that when a deployment mounts an empty volume over /opt/ravenroot/data
# (compose.yaml does; a Kubernetes PVC could too), Docker's/K8s' existing-content seeding copies this
# directory with correct ownership into the volume. Without this, the non-root process could still hit
# a permission-denied error against a root-owned empty volume even though the filesystem is writable.
#
# /opt/ravenroot/data/cache is the same mechanism for the same reason, for a different consumer: it
# is GraalVmWorkerMain's own default for GraalPy's on-disk resource cache, the directory
# the standard library gets materialised into on first Python use. GraalPy otherwise resolves that
# path under $HOME, which is /opt/ravenroot here -- a directory this image's root filesystem serves
# read-only in every deployment mode that sets it (compose.yaml, deploy/helm, deploy/kubernetes) --
# so without a pre-created, correctly-owned target on the one writable volume every mode already
# provisions, the very first Python import would fail with ModuleNotFoundError, naming the module
# rather than the unwritable cache directory that is the real cause.
#
# /opt/ravenroot/plugins, when it exists at all, is created and populated in this same RUN layer and
# is never declared as a volume or mounted over: it is build-time material, immutable and read-only
# for the life of the container, exactly like ravenroot.jar below -- not a second writable surface
# alongside /opt/ravenroot/data.

WORKDIR /opt/ravenroot
COPY --from=java-build --chown=10001:10001 \
    /workspace/ravenroot/ravenroot-distribution/target/ravenroot.jar ./ravenroot.jar

USER 10001:10001
ENV RAVENROOT_PORT=8080 \
    RAVENROOT_BIND_ADDRESS=0.0.0.0 \
    RAVENROOT_AUTH_MODE=oidc \
    RAVENROOT_ENGINE=${DEFAULT_ENGINE} \
    # -Dorg.sqlite.tmpdir: the SQLite JDBC driver extracts its native library to a temp directory and
    # dlopen()s it, so that directory must be executable. Every deployment mode here mounts /tmp as a
    # tmpfs or emptyDir, and Docker's tmpfs defaults to noexec -- so the driver failed to load and the
    # wired execution store aborted startup with a deliberately path-free "UNAVAILABLE", which
    # named neither /tmp nor the library. Pointed at the data mount instead: it is the one directory
    # every mode already requires to be writable for the store itself, so this adds no new
    # requirement, and /tmp keeps its noexec hardening.
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dorg.sqlite.tmpdir=/opt/ravenroot/data"

EXPOSE 8080
STOPSIGNAL SIGTERM
# Deliberately still /health (RavenrootHealthcheck's own target,
# hardcoded), not /ready, even though Kubernetes' readinessProbe below is being repointed to
# /ready in the same change. Docker has no readiness concept -- an unhealthy container is
# restarted, never drained or removed from a load-balancing rotation Docker itself does not run.
# Pointing this at /ready would make Docker restart the container the moment it starts draining
# or hits a transient STORE_DEGRADED, which is exactly the liveness-probe-on-a-readiness-signal
# mistake the readiness/liveness split avoids on Kubernetes -- restarting is the right response to "the
# process is stuck", not to "this process would like new work paused for a while."
HEALTHCHECK --interval=10s --timeout=5s --start-period=15s --retries=3 \
    CMD ["java", "-cp", "/opt/ravenroot/ravenroot.jar", "ai.ravenroot.server.RavenrootHealthcheck"]
ENTRYPOINT ["java", "-jar", "/opt/ravenroot/ravenroot.jar"]
