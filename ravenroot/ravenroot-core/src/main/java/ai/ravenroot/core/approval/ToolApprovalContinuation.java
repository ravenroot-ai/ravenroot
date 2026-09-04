package ai.ravenroot.core.approval;

import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.ToolApprovalStatus;
import ai.ravenroot.api.security.SecurityContext;

import java.util.Objects;
import java.util.UUID;

/** Exact stored input handed to a trusted versioned continuation executor after re-entry. */
public record ToolApprovalContinuation(UUID approvalId, UUID processInstanceId,
                                       UUID originalTraversalId, UUID originalInvocationId,
                                       UUID originalAttemptId, UUID resumeTraversalId,
                                       SecurityContext requester, GraphVersionPin graphVersionPin,
                                       String nodeId, String tool, byte[] canonicalArguments,
                                       String argumentsDigest, ToolApprovalStatus decision,
                                       int version, byte[] checkpoint, String checkpointDigest) {
    /** Snapshots both byte arrays and rejects missing structural identity. */
    public ToolApprovalContinuation {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        Objects.requireNonNull(originalTraversalId, "originalTraversalId");
        Objects.requireNonNull(originalInvocationId, "originalInvocationId");
        Objects.requireNonNull(originalAttemptId, "originalAttemptId");
        Objects.requireNonNull(resumeTraversalId, "resumeTraversalId");
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(graphVersionPin, "graphVersionPin");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(tool, "tool");
        canonicalArguments = Objects.requireNonNull(canonicalArguments, "canonicalArguments").clone();
        Objects.requireNonNull(argumentsDigest, "argumentsDigest");
        Objects.requireNonNull(decision, "decision");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        checkpoint = Objects.requireNonNull(checkpoint, "checkpoint").clone();
        Objects.requireNonNull(checkpointDigest, "checkpointDigest");
    }

    @Override public byte[] canonicalArguments() { return canonicalArguments.clone(); }
    @Override public byte[] checkpoint() { return checkpoint.clone(); }
}
