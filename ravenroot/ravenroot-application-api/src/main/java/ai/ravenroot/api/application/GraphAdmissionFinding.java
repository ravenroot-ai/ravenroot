package ai.ravenroot.api.application;

import java.util.Objects;

/** Versioned, bounded, value-free public finding for graph admission. */
public record GraphAdmissionFinding(String contract, GraphAdmissionPhase phase,
                                    GraphAdmissionReason reason, String nodeId, String nodeRef,
                                    String propertyName, String incidentId) {
    public static final String CONTRACT = "ravenroot.graph-admission/1";
    public static final int MAX_INCIDENT_LENGTH = 128;

    public GraphAdmissionFinding {
        if (!CONTRACT.equals(contract)) throw new IllegalArgumentException("unsupported finding contract");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(reason, "reason");
        if (nodeId != null && !DiagnosticIdentifier.isSafeDisplay(
                nodeId, DiagnosticIdentifier.MAX_IDENTIFIER_UTF8_BYTES)) {
            throw new IllegalArgumentException("nodeId is not a safe bounded display token");
        }
        if (nodeRef != null && !nodeRef.matches("sha256:[0-9a-f]{32}")) {
            throw new IllegalArgumentException("nodeRef is malformed");
        }
        if ((nodeId == null) != (nodeRef == null)) {
            throw new IllegalArgumentException("node display and reference must travel together");
        }
        if (propertyName != null && !DiagnosticIdentifier.isSafePropertyToken(propertyName)) {
            throw new IllegalArgumentException("propertyName is not a safe bounded property token");
        }
        if (incidentId == null || !incidentId.matches("[A-Za-z0-9._:-]{1," + MAX_INCIDENT_LENGTH + "}")) {
            throw new IllegalArgumentException("incidentId is malformed");
        }
    }

    public static GraphAdmissionFinding of(GraphAdmissionPhase phase, GraphAdmissionReason reason,
                                           String nodeId, String propertyName, String incidentId) {
        DiagnosticIdentifier.Formatted node = nodeId == null ? null : DiagnosticIdentifier.node(nodeId);
        DiagnosticIdentifier.Formatted property = propertyName == null
                ? null : DiagnosticIdentifier.property(propertyName);
        return new GraphAdmissionFinding(CONTRACT, phase, reason,
                node == null ? null : node.display(), node == null ? null : node.reference(),
                property == null ? null : property.display(), incidentId);
    }
}
