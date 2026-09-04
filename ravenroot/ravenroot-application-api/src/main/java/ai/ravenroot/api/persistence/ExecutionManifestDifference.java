package ai.ravenroot.api.persistence;

import java.util.Objects;

/**
 * One dimension along which a pinned manifest and the current runtime disagree.
 *
 * <p>Both values are drawn from the manifest's own closed vocabulary — a version number, an enum
 * name, a constrained identifier or a hexadecimal digest — so a difference report is safe to return
 * to an operator, write to a log and project over the API without a redaction pass. That is the same
 * property the manifest itself has and for the same reason: there is no field either side could have
 * put a secret into.</p>
 *
 * @param dimension which semantic dependency differs.
 * @param pinned the value recorded when the execution was accepted.
 * @param observed the value the current runtime resolves.
 */
public record ExecutionManifestDifference(Dimension dimension, String pinned, String observed) {

    /**
     * The closed set of dimensions a manifest comparison can report.
     *
     * <p>Closed rather than a free-text label so a caller can branch on a difference, and so the
     * report cannot become a channel for arbitrary text.</p>
     */
    public enum Dimension {
        /** The stored manifest was written under a different field layout than this build writes. */
        MANIFEST_FORMAT_VERSION,
        /** The canonical graph snapshot format version differs. */
        GRAPH_SCHEMA_VERSION,
        /** The stored canonical definition format version differs. */
        DEFINITION_FORMAT_VERSION,
        /** The submission policy the execution was admitted under differs. */
        EXECUTION_POLICY,
        /** The admission stance for a behavior no trusted catalog entry claims differs. */
        UNKNOWN_BEHAVIOR_MODE,
        /** The execution engine's identifier or capability set differs. */
        ENGINE,
        /** The execution store's capability set differs. */
        EXECUTION_STORE,
        /** The graph execution limits in force differ. */
        EXECUTION_LIMITS,
        /** The program runtime's identifier or compatibility contract differs. */
        PROGRAM_RUNTIME,
        /** A node package the execution was admitted with is no longer resolved. */
        NODE_PACKAGE_MISSING,
        /** A node package the execution was admitted with resolves at a different identity. */
        NODE_PACKAGE_CHANGED
    }

    /** Rejects a difference that does not name both a dimension and the two values it compared. */
    public ExecutionManifestDifference {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(observed, "observed");
    }

    /**
     * Renders this difference as one bounded, operator-facing line.
     *
     * @return a stable single-line description of the dimension and the two values.
     */
    public String describe() {
        return dimension.name() + ": pinned=" + pinned + " observed=" + observed;
    }
}
