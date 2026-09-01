package ai.ravenroot.api.programming;

/**
 * Defines the artifact lifecycle audit sink contract exposed to Ravenroot integrators.
 */
@FunctionalInterface
public interface ArtifactLifecycleAuditSink {
/**
 * Records one immutable artifact lifecycle change in the audit system.
 * @param event lifecycle event containing the state transition and evidence reference.
 */
    void record(ArtifactLifecycleAuditEvent event);
}
