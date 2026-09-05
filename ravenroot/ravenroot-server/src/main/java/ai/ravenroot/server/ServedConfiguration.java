package ai.ravenroot.server;

import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.core.graph.GraphMlLimits;

import java.util.Objects;

/** Typed, immutable subset of operator configuration that the connected authoring UI may consume. */
record ServedConfiguration(int schemaVersion, int graphDocumentMaxBytes) {
    static final int CURRENT_SCHEMA_VERSION = 1;

    ServedConfiguration {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported served-configuration schema version");
        }
        if (graphDocumentMaxBytes < 1) {
            throw new IllegalArgumentException("graphDocumentMaxBytes must be positive");
        }
        if (graphDocumentMaxBytes > GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES) {
            throw new IllegalArgumentException("graphDocumentMaxBytes exceeds the supported safety ceiling");
        }
    }

    static ServedConfiguration from(GraphMlLimits limits) {
        Objects.requireNonNull(limits, "limits");
        return new ServedConfiguration(CURRENT_SCHEMA_VERSION, limits.maxBytes());
    }

    String json() {
        return "{\"schemaVersion\":" + schemaVersion
                + ",\"graphDocumentMaxBytes\":" + graphDocumentMaxBytes + "}";
    }
}
