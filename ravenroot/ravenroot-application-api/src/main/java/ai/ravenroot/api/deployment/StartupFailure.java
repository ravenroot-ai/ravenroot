package ai.ravenroot.api.deployment;

import ai.ravenroot.api.application.DiagnosticIdentifier;
import ai.ravenroot.api.application.GraphAdmissionPhase;

import java.util.Objects;
import java.util.Optional;

/** Same safe startup failure carried by engine, deployment, and source-session status projections. */
public record StartupFailure(String contract, GraphAdmissionPhase phase, String reason,
                             Optional<String> nodeId, Optional<String> nodeRef, String incidentId) {
    public static final String CONTRACT = "ravenroot.startup-failure/1";
    public static final String GENERIC_REASON = "STARTUP_FAILED";

    public StartupFailure {
        if (!CONTRACT.equals(contract)) throw new IllegalArgumentException("unsupported startup failure contract");
        Objects.requireNonNull(phase, "phase");
        reason = requireReason(reason);
        nodeId = nodeId == null ? Optional.empty() : nodeId;
        nodeRef = nodeRef == null ? Optional.empty() : nodeRef;
        if (nodeId.filter(value -> !DiagnosticIdentifier.isSafeDisplay(
                value, DiagnosticIdentifier.MAX_IDENTIFIER_UTF8_BYTES)).isPresent()) {
            throw new IllegalArgumentException("startup nodeId is not a safe bounded display token");
        }
        if (nodeRef.filter(value -> !value.matches("sha256:[0-9a-f]{32}")).isPresent()) {
            throw new IllegalArgumentException("startup nodeRef is malformed");
        }
        if (nodeId.isPresent() != nodeRef.isPresent()) {
            throw new IllegalArgumentException("startup node display and reference must travel together");
        }
        if (incidentId == null || !incidentId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("startup incidentId is malformed");
        }
    }

    public static StartupFailure generic(GraphAdmissionPhase phase, String incidentId) {
        return new StartupFailure(CONTRACT, phase, GENERIC_REASON,
                Optional.empty(), Optional.empty(), incidentId);
    }

    public static StartupFailure declared(GraphAdmissionPhase phase, String reason,
                                          String rawNodeId, String incidentId) {
        SourceStartFailureCode.requireValid(reason);
        var node = DiagnosticIdentifier.node(rawNodeId);
        return new StartupFailure(CONTRACT, phase, reason,
                Optional.of(node.display()), Optional.of(node.reference()), incidentId);
    }

    private static String requireReason(String value) {
        if (GENERIC_REASON.equals(value)) return value;
        return SourceStartFailureCode.requireValid(value);
    }
}
