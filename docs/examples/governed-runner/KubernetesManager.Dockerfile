# Both images are operator-pinned immutable references. No Docker/CRI client or socket is used.
ARG KUBECTL_IMAGE
ARG RAVENROOT_IMAGE
FROM ${KUBECTL_IMAGE} AS kubectl
FROM ${RAVENROOT_IMAGE}
COPY --from=kubectl /bin/kubectl /usr/local/bin/kubectl
ENTRYPOINT ["java", "-cp", "/opt/ravenroot/ravenroot.jar", "ai.ravenroot.server.RunnerWorkerMain"]
