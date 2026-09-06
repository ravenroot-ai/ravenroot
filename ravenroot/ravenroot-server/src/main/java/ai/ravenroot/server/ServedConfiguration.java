package ai.ravenroot.server;

import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.core.graph.GraphMlLimits;
import ai.ravenroot.server.audit.JsonStrings;

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
        return limitsJson() + "}";
    }

    /**
     * The connected browser's server-authoritative workspace scope.
     *
     * <p>The tenant value comes from the authenticated request boundary, never from browser input.
     * It is an equality key for browser-local workspace persistence, not an authorization credential
     * and not a value the UI may interpret.</p>
     */
    String json(String tenantId) {
        return limitsJson() + workspaceJson(tenantId) + "}";
    }

    String json(ai.ravenroot.api.persistence.HumanTaskPolicy humanTaskPolicy) {
        return limitsJson() + humanTasksJson(humanTaskPolicy) + "}";
    }

    String json(ai.ravenroot.api.persistence.HumanTaskPolicy humanTaskPolicy, String tenantId) {
        return limitsJson() + workspaceJson(tenantId) + humanTasksJson(humanTaskPolicy) + "}";
    }

    private String limitsJson() {
        return "{\"schemaVersion\":" + schemaVersion
                + ",\"graphDocumentMaxBytes\":" + graphDocumentMaxBytes;
    }

    private static String workspaceJson(String tenantId) {
        return ",\"workspace\":{\"tenantId\":\""
                + JsonStrings.escape(Objects.requireNonNull(tenantId, "tenantId")) + "\"}";
    }

    private static String humanTasksJson(ai.ravenroot.api.persistence.HumanTaskPolicy humanTaskPolicy) {
        Objects.requireNonNull(humanTaskPolicy, "humanTaskPolicy");
        var confirmation = humanTaskPolicy.confirmation();
        return ",\"humanTasks\":{\"schemaVersion\":1"
                + ",\"confirmationPresentationVersions\":[1]"
                + ",\"confirmationPromptMaxUtf8Bytes\":" + confirmation.maxPromptUtf8Bytes()
                + ",\"confirmationActionLabelMaxUtf8Bytes\":" + confirmation.maxActionLabelUtf8Bytes()
                + ",\"commentMaxUtf8Bytes\":" + confirmation.maxCommentUtf8Bytes()
                + ",\"attentionPollMillis\":" + confirmation.pollAfterMillis()
                + ",\"attentionBackoffMaxMillis\":" + confirmation.pollBackoffMaxMillis()
                + ",\"attentionPageSize\":" + confirmation.attentionDefaultPageSize()
                + ",\"attentionPageSizeMax\":" + confirmation.attentionMaxPageSize()
                + "}}";
    }

}
