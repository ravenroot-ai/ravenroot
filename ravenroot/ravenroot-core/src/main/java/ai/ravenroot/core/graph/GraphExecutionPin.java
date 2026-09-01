package ai.ravenroot.core.graph;

import java.util.Objects;

/** Immutable execution reference to one exact graph definition snapshot. */
public record GraphExecutionPin(GraphVersionMetadata metadata) {
    public GraphExecutionPin {
        Objects.requireNonNull(metadata, "metadata");
    }

    public static GraphExecutionPin from(GraphVersionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new GraphExecutionPin(snapshot.metadata());
    }
}
