package ai.ravenroot.core.graph;

import java.util.Objects;

/** Immutable lifecycle view of one canonical graph snapshot. */
public record GraphVersionRecord(GraphVersionSnapshot snapshot, GraphVersionState state) {
    public GraphVersionRecord {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(state, "state");
    }

    GraphVersionRecord transition(GraphVersionState target) {
        return new GraphVersionRecord(snapshot, target);
    }
}
