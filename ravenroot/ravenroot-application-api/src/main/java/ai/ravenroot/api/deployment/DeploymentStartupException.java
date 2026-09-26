package ai.ravenroot.api.deployment;

import java.util.Objects;

/** Safe exceptional completion for a rolled-back deployment start. */
public final class DeploymentStartupException extends RuntimeException {
    /** Safe structured failure shared with status projections. */
    private final StartupFailure failure;

    /**
     * Creates a safe exceptional completion for a rolled-back start.
     * @param failure public structured failure
     */
    public DeploymentStartupException(StartupFailure failure) {
        super("Deployment startup failed; quote incident "
                + Objects.requireNonNull(failure, "failure").incidentId());
        this.failure = failure;
    }

    /**
     * Returns the public structured startup failure.
     * @return safe structured failure
     */
    public StartupFailure failure() { return failure; }
}
