package ai.ravenroot.core.graph;

/** Definition control-plane lifecycle; snapshot content never changes. */
public enum GraphVersionState {
    VALIDATED,
    PUBLISHED,
    ACTIVE,
    RETIRED
}
