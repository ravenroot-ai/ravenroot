package ai.ravenroot.api.node;

import ai.ravenroot.api.execution.NodeMessage;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Exact server-minted input to a trusted node package's durable tool-call continuation. */
public final class ToolCallContinuationInput {
    public enum Decision { APPROVED, DENIED, EXPIRED, CANCELLED }

    private final NodeMessage message;
    private final UUID approvalId;
    private final UUID originalTraversalId;
    private final UUID originalInvocationId;
    private final UUID originalAttemptId;
    private final String tool;
    private final byte[] canonicalArguments;
    private final String argumentsDigest;
    private final Decision decision;
    private final int version;
    private final byte[] checkpoint;
    private final String checkpointDigest;

    public ToolCallContinuationInput(NodeMessage message, UUID approvalId,
                                     UUID originalTraversalId, UUID originalInvocationId,
                                     UUID originalAttemptId, String tool, byte[] canonicalArguments,
                                     String argumentsDigest, Decision decision, int version,
                                     byte[] checkpoint, String checkpointDigest) {
        this.message = Objects.requireNonNull(message, "message");
        this.approvalId = Objects.requireNonNull(approvalId, "approvalId");
        this.originalTraversalId = Objects.requireNonNull(originalTraversalId, "originalTraversalId");
        this.originalInvocationId = Objects.requireNonNull(originalInvocationId, "originalInvocationId");
        this.originalAttemptId = Objects.requireNonNull(originalAttemptId, "originalAttemptId");
        this.tool = requireText(tool, "tool");
        this.canonicalArguments = Objects.requireNonNull(canonicalArguments, "canonicalArguments").clone();
        this.argumentsDigest = requireText(argumentsDigest, "argumentsDigest");
        this.decision = Objects.requireNonNull(decision, "decision");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        this.version = version;
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint").clone();
        this.checkpointDigest = requireText(checkpointDigest, "checkpointDigest");
    }

    public NodeMessage message() { return message; }
    public UUID approvalId() { return approvalId; }
    public UUID originalTraversalId() { return originalTraversalId; }
    public UUID originalInvocationId() { return originalInvocationId; }
    public UUID originalAttemptId() { return originalAttemptId; }
    public String tool() { return tool; }
    public byte[] canonicalArguments() { return canonicalArguments.clone(); }
    public String argumentsDigest() { return argumentsDigest; }
    public Decision decision() { return decision; }
    public int version() { return version; }
    public byte[] checkpoint() { return checkpoint.clone(); }
    public String checkpointDigest() { return checkpointDigest; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ToolCallContinuationInput that)) return false;
        return version == that.version && message.equals(that.message) && approvalId.equals(that.approvalId)
                && originalTraversalId.equals(that.originalTraversalId)
                && originalInvocationId.equals(that.originalInvocationId)
                && originalAttemptId.equals(that.originalAttemptId) && tool.equals(that.tool)
                && Arrays.equals(canonicalArguments, that.canonicalArguments)
                && argumentsDigest.equals(that.argumentsDigest) && decision == that.decision
                && Arrays.equals(checkpoint, that.checkpoint) && checkpointDigest.equals(that.checkpointDigest);
    }

    @Override public int hashCode() {
        int result = Objects.hash(message, approvalId, originalTraversalId, originalInvocationId,
                originalAttemptId, tool, argumentsDigest, decision, version, checkpointDigest);
        result = 31 * result + Arrays.hashCode(canonicalArguments);
        return 31 * result + Arrays.hashCode(checkpoint);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        return value;
    }
}
