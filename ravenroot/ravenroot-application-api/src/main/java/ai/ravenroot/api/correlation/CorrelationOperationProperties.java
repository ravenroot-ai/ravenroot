package ai.ravenroot.api.correlation;

import ai.ravenroot.api.catalog.*;
import java.util.*;

/** Descriptor-driven explicit business operations; payload replacement never changes identity.
 */
public final class CorrelationOperationProperties {
    /** Catalog behavior for an explicit close barrier. */
    public static final String CLOSE = "correlation-close";
    /** Catalog behavior for a new audited context after an ordinary transform. */
    public static final String DERIVE = "correlation-derive";
    /** Bounded authored non-secret reason retained with original causal coordinates. */
    public static final String AUDIT_REASON = "auditReason";
    /** Requested explicit business closure outcome. */
    public static final String DISPOSITION = "disposition";
    private CorrelationOperationProperties() { }
    /** Validates the literal authored reason without interpreting payload data.
     * @param properties exact authored node properties
     * @return original bounded non-secret audit reason
     */
    public static String auditReason(Map<String, ?> properties) {
        Object raw = properties.get(AUDIT_REASON);
        if (!(raw instanceof String reason) || reason.isBlank() || reason.length() > 500 || reason.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid correlation auditReason");
        return reason;
    }
    /** Validates a requested close outcome; OPEN and expiry are never authored completion signals.
     * @param properties exact authored node properties
     * @return explicit completed, failed or cancelled requested outcome
     */
    public static BusinessHistory.Lifecycle disposition(Map<String, ?> properties) {
        Object raw = properties.get(DISPOSITION); if (raw == null) return BusinessHistory.Lifecycle.COMPLETED;
        if (!(raw instanceof String text)) throw new IllegalArgumentException("invalid correlation disposition");
        try {
            var value = BusinessHistory.Lifecycle.valueOf(text);
            if (value == BusinessHistory.Lifecycle.OPEN || value == BusinessHistory.Lifecycle.EXPIRED) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("invalid correlation disposition"); }
    }
    /** Returns public close-node authoring controls.
     * @return explicit requested outcome and required audit reason descriptors
     */
    public static List<NodePropertyDescriptor> closeDescriptors() {
        return List.of(new NodePropertyDescriptor(DISPOSITION, "Requested business outcome", NodePropertyType.STRING, false,
                "Blocks new external admissions while existing causal work settles. Missing contributions or failures cannot become success.",
                "COMPLETED", List.of("COMPLETED", "FAILED", "CANCELLED"), false, null, null), reasonDescriptor());
    }
    /** Returns explicit derivation controls using the same extraction contract as entry and preview.
     * @param limits effective operator-owned selector bounds
     * @return required reason and shared bounded mapping descriptors
     */
    public static List<NodePropertyDescriptor> deriveDescriptors(CorrelationLimits limits) {
        var fields = new ArrayList<NodePropertyDescriptor>(); fields.add(reasonDescriptor());
        fields.addAll(List.of(CorrelationMappingProperties.descriptors(limits))); return List.copyOf(fields);
    }
    private static NodePropertyDescriptor reasonDescriptor() {
        return NodePropertyDescriptor.boundedText(AUDIT_REASON, "Audit reason", NodePropertyType.STRING, true,
                "Literal non-secret reason for this explicit authored operation; retained with original process, traversal, visit and attempt identities.", "", 500, 1, 500);
    }
}
