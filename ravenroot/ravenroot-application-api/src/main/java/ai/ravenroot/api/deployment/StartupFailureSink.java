package ai.ravenroot.api.deployment;

/** Privileged once-per-transition sink. The throwable must never be copied into the safe event. */
@FunctionalInterface
public interface StartupFailureSink {
    void record(DeploymentId deploymentId, StartupFailure failure,
                java.util.Optional<String> sourceNode, Throwable originalFailure);

    static StartupFailureSink logging() {
        System.Logger logger = System.getLogger("ai.ravenroot.startup");
        return (deploymentId, failure, sourceNode, originalFailure) -> logger.log(System.Logger.Level.ERROR,
                safeLogMessage(deploymentId, failure, sourceNode), originalFailure);
    }

    /**
     * Builds the ordinary structured-log projection without throwable or graph-authored raw text.
     * The privileged logger receives the original throwable as a separate parameter.
     */
    static String safeLogMessage(DeploymentId deploymentId, StartupFailure failure,
                                 java.util.Optional<String> sourceNode) {
        java.util.Objects.requireNonNull(deploymentId, "deploymentId");
        java.util.Objects.requireNonNull(failure, "failure");
        java.util.Objects.requireNonNull(sourceNode, "sourceNode");
        String safeDeployment = ai.ravenroot.api.application.DiagnosticIdentifier
                .node(deploymentId.value()).display();
        String safeNode = sourceNode
                .map(value -> ai.ravenroot.api.application.DiagnosticIdentifier.node(value).display())
                .orElse("-");
        return "deployment startup failed deployment=" + safeDeployment
                + " phase=" + failure.phase() + " reason=" + failure.reason()
                + " node=" + safeNode + " incident=" + failure.incidentId();
    }
}
