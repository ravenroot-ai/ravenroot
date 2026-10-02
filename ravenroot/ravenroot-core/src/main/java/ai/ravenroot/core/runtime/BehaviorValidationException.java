package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.GraphAdmissionReason;

/** A value-free, property-addressable refusal from a behavior's cross-field validator. */
public final class BehaviorValidationException extends IllegalArgumentException {
    private final String propertyName;
    private final GraphAdmissionReason reason;

    public BehaviorValidationException(String propertyName, GraphAdmissionReason reason) {
        super("Invalid behavior configuration property");
        if (propertyName == null || propertyName.isBlank()) {
            throw new IllegalArgumentException("propertyName cannot be blank");
        }
        this.propertyName = propertyName;
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public String propertyName() { return propertyName; }
    public GraphAdmissionReason reason() { return reason; }
}
