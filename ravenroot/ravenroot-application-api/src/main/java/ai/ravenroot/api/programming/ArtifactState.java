package ai.ravenroot.api.programming;

/** Explicit control-plane lifecycle. Only ACTIVE artifacts may be executed. */
public enum ArtifactState {
/**
 * Source was registered but has not passed validation.
 */
    GENERATED,
/**
 * Source passed runtime validation without executing it.
 */
    VALIDATED,
/**
 * Artifact completed its required test lifecycle step.
 */
    TESTED,
/**
 * Authorized reviewer approved the tested artifact.
 */
    APPROVED,
/**
 * Approved artifact is eligible for sandbox execution.
 */
    ACTIVE,
/**
 * Artifact is permanently ineligible for new execution.
 */
    RETIRED
}
