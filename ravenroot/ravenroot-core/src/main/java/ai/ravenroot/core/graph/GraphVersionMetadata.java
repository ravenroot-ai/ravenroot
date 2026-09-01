package ai.ravenroot.core.graph;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable identity and integrity metadata carried with a graph snapshot. */
public record GraphVersionMetadata(GraphVersionKey key, int schemaVersion, String canonicalHash) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public GraphVersionMetadata {
        Objects.requireNonNull(key, "key");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (canonicalHash == null || !SHA_256.matcher(canonicalHash).matches()) {
            throw new IllegalArgumentException("canonicalHash must be a lowercase SHA-256 hex digest");
        }
    }
}
