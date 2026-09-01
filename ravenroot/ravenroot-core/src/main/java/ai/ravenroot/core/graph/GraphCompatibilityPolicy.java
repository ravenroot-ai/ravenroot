package ai.ravenroot.core.graph;

/** Explicit compatibility promise required when publishing or migrating a definition. */
public enum GraphCompatibilityPolicy {
    NONE,
    BACKWARD,
    FORWARD,
    FULL;

    public boolean accepts(GraphCompatibilityReport report) {
        return switch (this) {
            case NONE -> true;
            case BACKWARD -> report.backwardCompatible();
            case FORWARD -> report.forwardCompatible();
            case FULL -> report.backwardCompatible() && report.forwardCompatible();
        };
    }
}
