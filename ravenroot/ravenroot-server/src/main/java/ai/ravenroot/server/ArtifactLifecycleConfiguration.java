package ai.ravenroot.server;

import java.util.Map;
import java.util.Objects;

/** Strict standalone artifact-governance configuration. */
public record ArtifactLifecycleConfiguration(boolean dualControl) {
    public static final String DUAL_CONTROL_ENV = "RAVENROOT_ARTIFACT_DUAL_CONTROL";

    public static ArtifactLifecycleConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String configured = environment.get(DUAL_CONTROL_ENV);
        if (configured == null || configured.isBlank()) {
            // Automatic graph readiness is the default delivery path. Deployments that require a
            // second human approver opt in explicitly; absence must not make every imported graph
            // wait forever for an operator action that the UI did not ask for. Blank has the same
            // meaning as absence across startup configuration.
            return new ArtifactLifecycleConfiguration(false);
        }
        if ("true".equals(configured)) {
            return new ArtifactLifecycleConfiguration(true);
        }
        if ("false".equals(configured)) {
            return new ArtifactLifecycleConfiguration(false);
        }
        throw new IllegalArgumentException(DUAL_CONTROL_ENV + " must be exactly 'true' or 'false'");
    }
}
