package ai.ravenroot.api.application;

/**
 * Current process command authority, retained with the aggregate rather than its compactable audit.
 * RECOVERY_REQUIRED denotes legacy data whose command history was already lost before migration;
 * only an explicit revision-protected operator command can restore admission.
 */
public enum ProcessControlState {
    /** Process execution and continuation admission are enabled, subject to execution status and policy. */
    RUNNING,
    /** Process execution is paused; runner claims and continuation delivery remain held until resumed. */
    PAUSED,
    /** Cancellation is irreversible; normal process re-entry is forbidden. */
    CANCELLED,
    /** Already-admitted process work may finish, including its durable continuations. */
    DRAINING,
    /** Process invocations are stopped; runner claims and continuation delivery remain held. */
    STOPPED,
    /** Legacy authority is unknowable; an explicit revision-protected operator command must settle it. */
    RECOVERY_REQUIRED
}
