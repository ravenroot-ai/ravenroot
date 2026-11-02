package ai.ravenroot.api.application;

import java.util.Objects;

/**
 * Exact process-local observation identity and lifecycle, independent of drawing availability.
 * @param source tenant-scoped logical deployment, runtime incarnation and immutable graph version
 * @param lifecycle lifecycle observed for that source
 */
public record DeploymentObservation(DeploymentViewerView.Source source, LocalDeploymentState lifecycle) {
    /** Validates the source identity and lifecycle without requesting a drawing projection. */
    public DeploymentObservation {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(lifecycle, "lifecycle");
    }
}
