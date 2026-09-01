package ai.ravenroot.core.graph;

import java.util.Objects;

/** Canonical, immutable definition captured before publication or execution. */
public final class GraphVersionSnapshot {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final GraphVersionMetadata metadata;
    private final GraphDefinition definition;

    private GraphVersionSnapshot(GraphVersionMetadata metadata, GraphDefinition definition) {
        this.metadata = metadata;
        this.definition = definition;
    }

    public static GraphVersionSnapshot create(GraphVersionKey key, GraphDefinition definition) {
        Objects.requireNonNull(key, "key");
        GraphDefinition immutable = GraphCanonicalForm.immutableCopy(definition);
        String hash = GraphCanonicalForm.sha256(immutable);
        return new GraphVersionSnapshot(
                new GraphVersionMetadata(key, CURRENT_SCHEMA_VERSION, hash), immutable);
    }

    /** Content-addressed snapshot for the direct GraphML submission path. */
    public static GraphVersionSnapshot submission(GraphDefinition definition) {
        GraphDefinition immutable = GraphCanonicalForm.immutableCopy(definition);
        String hash = GraphCanonicalForm.sha256(immutable);
        return new GraphVersionSnapshot(
                new GraphVersionMetadata(new GraphVersionKey("submission", hash),
                        CURRENT_SCHEMA_VERSION, hash),
                immutable);
    }

    public GraphVersionMetadata metadata() {
        return metadata;
    }

    public GraphDefinition definition() {
        return definition;
    }

    public GraphVersionKey key() {
        return metadata.key();
    }

    public String canonicalHash() {
        return metadata.canonicalHash();
    }
}
