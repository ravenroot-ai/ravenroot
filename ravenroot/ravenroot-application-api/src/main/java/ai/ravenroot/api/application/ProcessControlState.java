package ai.ravenroot.api.application;

/**
 * Current process command authority, retained with the aggregate rather than its compactable audit.
 * RECOVERY_REQUIRED denotes legacy data whose command history was already lost before migration;
 * only an explicit revision-protected operator command can restore admission.
 */
public enum ProcessControlState {
    RUNNING, PAUSED, CANCELLED, DRAINING, STOPPED, RECOVERY_REQUIRED
}
