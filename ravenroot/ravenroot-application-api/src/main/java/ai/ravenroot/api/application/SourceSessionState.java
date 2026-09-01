package ai.ravenroot.api.application;

/**
 * Editor-facing lifecycle for one process-local inbound-source session.
 *
 * <p>This vocabulary deliberately does not imply persistence, failover or ownership outside the
 * serving process. {@link #LISTENING} means that this replica's deployment is ready to accept
 * external events; it is not a cluster-wide availability statement.</p>
 */
public enum SourceSessionState {
    /** Source construction or listener binding is still in progress. */
    STARTING,
    /** Every source in this process is ready and listening. */
    LISTENING,
    /** The session is serving, but one or more sources reported reduced health. */
    DEGRADED,
    /** Startup failed without leaving a partially registered session serving. */
    FAILED,
    /** Admission is closing and deployment-owned resources are being released. */
    STOPPING,
    /** This process has released the session's deployment-owned resources. */
    STOPPED
}
