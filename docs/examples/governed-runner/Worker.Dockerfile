# Operator-selected immutable server and Docker CLI images. Neither image is graph input.
ARG DOCKER_CLI_IMAGE
ARG RAVENROOT_IMAGE
FROM ${DOCKER_CLI_IMAGE} AS dockercli
FROM ${RAVENROOT_IMAGE}
COPY --from=dockercli /usr/local/bin/docker /usr/bin/docker
ENTRYPOINT ["java", "-cp", "/opt/ravenroot/ravenroot.jar", "ai.ravenroot.server.RunnerWorkerMain"]
CMD ["/etc/ravenroot/worker.json"]
