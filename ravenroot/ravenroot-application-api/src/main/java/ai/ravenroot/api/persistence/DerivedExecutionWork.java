package ai.ravenroot.api.persistence;

import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.security.SecurityContext;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, self-contained first dispatch of an admitted derived execution.
 *
 * <p>The retained source is consulted while this value is admitted, then the seed bytes are copied
 * into the derived execution's own transaction. Recovery therefore never depends on the source
 * still being retained. The invocation and attempt identities are also admitted here and in the
 * aggregate, so the ordinary pending-work recovery loop can claim exactly this dispatch after a
 * crash without manufacturing a second visit to the boundary node.</p>
 * @param derived fresh derived execution
 * @param traversalId admitted traversal
 * @param invocationId admitted boundary invocation
 * @param attemptId admitted boundary attempt
 * @param boundary selected pending boundary
 * @param sourceNodeId predecessor source node
 * @param command routed command
 * @param payload copied retained output
 * @param attributes copied retained attributes
 * @param requesterContext authenticated requester copied for recovery
 */
public record DerivedExecutionWork(ExecutionKey derived, UUID traversalId, UUID invocationId,
                                   UUID attemptId, ReplayBoundarySeed boundary,
                                   String sourceNodeId, NodeCommand command,
                                   OpaquePayload payload, OpaquePayload attributes,
                                   SecurityContext requesterContext) {
    /** Validates identities, tenant scope, and node bounds. */
    public DerivedExecutionWork {
        Objects.requireNonNull(derived, "derived");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(requesterContext, "requesterContext");
        if (!derived.tenantId().equals(requesterContext.tenantId())) {
            throw new IllegalArgumentException("derived work cannot cross tenants");
        }
        if (sourceNodeId == null || sourceNodeId.isBlank()
                || sourceNodeId.length() > ReplayBoundarySeed.MAX_NODE_ID_LENGTH) {
            throw new IllegalArgumentException("sourceNodeId is outside supported bounds");
        }
    }
}
