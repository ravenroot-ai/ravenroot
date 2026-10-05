package ai.ravenroot.api.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload-free retained boundary offered to an operator selecting derived work.
 * @param nodeId pending downstream node that may be selected
 * @param predecessorInvocationId completed retained invocation that seeds the node
 * @param predecessorNodeId source node of the retained value
 * @param outcome retained outcome used to prove edge selection
 * @param recordedAt when the retained evidence was committed
 * @param retainedUntil store-authoritative evidence retention deadline
 */
public record DerivedBoundaryOption(String nodeId, UUID predecessorInvocationId,
                                    String predecessorNodeId, String outcome,
                                    Instant recordedAt, Instant retainedUntil) {
    /** Validates the bounded, payload-free operator projection. */
    public DerivedBoundaryOption {
        if (nodeId == null || nodeId.isBlank() || predecessorInvocationId == null
                || predecessorNodeId == null || predecessorNodeId.isBlank()
                || outcome == null || outcome.isBlank() || recordedAt == null || retainedUntil == null) {
            throw new IllegalArgumentException("derived boundary option fields cannot be blank");
        }
    }
}
