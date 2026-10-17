package ai.ravenroot.api.flow;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque runtime-issued reference to one child-flow invocation.
 *
 * <p>The UUID is a bearer reference, not a deployment or execution identifier. Graph content may
 * carry a handle returned by {@code start-flow}; it cannot choose one when the invocation is
 * created. Tenant and caller checks are still applied on every use, so possession alone does not
 * cross either boundary.</p>
 *
 * @param value random identifier minted by the invocation runtime
 */
public record FlowHandle(UUID value) {
    /** Validates a newly minted opaque handle. */
    public FlowHandle {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Parses the wire form while preserving the opaque type at the capability boundary.
     * @param value UUID text returned by the invocation runtime
     * @return the parsed opaque handle
     */
    public static FlowHandle parse(String value) {
        try {
            return new FlowHandle(UUID.fromString(Objects.requireNonNull(value, "value")));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("flow handle is malformed", malformed);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return value.toString();
    }
}
