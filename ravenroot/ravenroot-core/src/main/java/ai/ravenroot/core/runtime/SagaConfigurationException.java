package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.GraphAdmissionReason;

/** Typed internal refusal; only its bounded identifiers and closed reason cross admission. */
final class SagaConfigurationException extends IllegalArgumentException {
    private final String nodeId;
    private final String propertyName;
    private final GraphAdmissionReason reason;

    SagaConfigurationException(String nodeId, String propertyName, GraphAdmissionReason reason, String detail) {
        super("Invalid saga configuration at node '" + nodeId + "': " + detail);
        this.nodeId = nodeId;
        this.propertyName = propertyName;
        this.reason = reason;
    }

    String nodeId() { return nodeId; }
    String propertyName() { return propertyName; }
    GraphAdmissionReason reason() { return reason; }
}
