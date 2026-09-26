package ai.ravenroot.api.application;

import java.util.Objects;

/**
 * Versioned, bounded, value-free public finding for graph admission.
 *
 * @param contract diagnostic contract identifier
 * @param phase closed admission phase
 * @param reason closed platform-owned reason
 * @param nodeId sanitized bounded node display, or {@code null}
 * @param nodeRef opaque deterministic node locator, or {@code null}
 * @param propertyName bounded property-name token, or {@code null}
 * @param incidentId bounded incident handle for trusted diagnostics
 */
public record GraphAdmissionFinding(String contract, GraphAdmissionPhase phase,
                                    GraphAdmissionReason reason, String nodeId, String nodeRef,
                                    String propertyName, String incidentId) {
    /** Current graph-admission diagnostic contract identifier. */
    public static final String CONTRACT = "ravenroot.graph-admission/1";
    /** Maximum number of characters in a public incident handle. */
    public static final int MAX_INCIDENT_LENGTH = 128;

    /** Validates the bounded public finding contract. */
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

    /**
     * Formats authored identifiers and creates one safe finding.
     *
     * @param phase closed admission phase
     * @param reason closed platform-owned reason
     * @param nodeId raw authored node identifier, or {@code null}
     * @param propertyName raw submitted property name, or {@code null}
     * @param incidentId bounded incident handle
     * @return validated value-free public finding
     */
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
