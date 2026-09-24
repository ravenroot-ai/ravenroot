package ai.ravenroot.api.deployment;

import java.util.Objects;

/** Safe exceptional completion for a rolled-back deployment start. */
public final class DeploymentStartupException extends RuntimeException {
    private final StartupFailure failure;

    public DeploymentStartupException(StartupFailure failure) {
        super("Deployment startup failed; quote incident "
                + Objects.requireNonNull(failure, "failure").incidentId());
        this.failure = failure;
    }

    public StartupFailure failure() { return failure; }
}
