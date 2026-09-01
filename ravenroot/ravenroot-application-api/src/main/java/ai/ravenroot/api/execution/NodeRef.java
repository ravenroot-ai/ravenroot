package ai.ravenroot.api.execution;

/**
 * Defines the node ref contract exposed to Ravenroot integrators.
 * @param value non-blank opaque identifier issued by one execution engine
 */
public record NodeRef(String value) {
/**
 * Rejects blank identifiers so a reference is always meaningful in logs and errors.
 */
    public NodeRef {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A node reference cannot be blank");
        }
    }
}
