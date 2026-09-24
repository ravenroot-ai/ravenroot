package ai.ravenroot.api.deployment;

/** Privileged once-per-transition sink. The throwable must never be copied into the safe event. */
@FunctionalInterface
public interface StartupFailureSink {
    void record(DeploymentId deploymentId, StartupFailure failure,
                java.util.Optional<String> sourceNode, Throwable originalFailure);

    static StartupFailureSink logging() {
        System.Logger logger = System.getLogger("ai.ravenroot.startup");
        return (deploymentId, failure, sourceNode, originalFailure) -> logger.log(System.Logger.Level.ERROR,
                "deployment startup failed deployment="
                        + ai.ravenroot.api.application.DiagnosticIdentifier.node(deploymentId.value()).display()
                        + " phase=" + failure.phase() + " reason=" + failure.reason()
                        + " node=" + sourceNode.orElse("-") + " incident=" + failure.incidentId(),
                originalFailure);
    }
}
