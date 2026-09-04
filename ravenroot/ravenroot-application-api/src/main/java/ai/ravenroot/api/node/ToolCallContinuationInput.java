package ai.ravenroot.api.node;

import ai.ravenroot.api.execution.NodeMessage;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Exact server-minted input to a trusted node package's durable tool-call continuation. */
public final class ToolCallContinuationInput {
    /** Exact durable decision being resumed. */
    public enum Decision {
        /** Approval granted; the effect may execute once after redemption. */
        APPROVED,
        /** Approval denied; continuation must produce no effect. */
        DENIED,
        /** Approval expired; continuation must produce no effect. */
        EXPIRED,
        /** Approval cancelled; continuation must produce no effect. */
        CANCELLED
    }

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

    /**
     * Creates an exact immutable continuation input.
     * @param message trusted re-entry invocation message
     * @param approvalId stored approval identifier
     * @param originalTraversalId traversal that requested the tool call
     * @param originalInvocationId invocation that requested the tool call
     * @param originalAttemptId attempt that requested the tool call
     * @param tool canonical tool name
     * @param canonicalArguments canonical immutable argument bytes
     * @param argumentsDigest SHA-256 binding for the arguments
     * @param decision exact stored decision
     * @param version checkpoint format version
     * @param checkpoint bounded checkpoint bytes
     * @param checkpointDigest SHA-256 binding for the checkpoint
     */
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

    /**
     * Returns the trusted re-entry message.
     * @return trusted re-entry invocation message
     */
    public NodeMessage message() { return message; }
    /**
     * Returns the approval identity.
     * @return stored approval identifier
     */
    public UUID approvalId() { return approvalId; }
    /**
     * Returns the original traversal identity.
     * @return traversal that requested the tool call
     */
    public UUID originalTraversalId() { return originalTraversalId; }
    /**
     * Returns the original invocation identity.
     * @return invocation that requested the tool call
     */
    public UUID originalInvocationId() { return originalInvocationId; }
    /**
     * Returns the original attempt identity.
     * @return attempt that requested the tool call
     */
    public UUID originalAttemptId() { return originalAttemptId; }
    /**
     * Returns the tool name.
     * @return canonical tool name
     */
    public String tool() { return tool; }
    /**
     * Returns the canonical arguments.
     * @return defensive copy of canonical argument bytes
     */
    public byte[] canonicalArguments() { return canonicalArguments.clone(); }
    /**
     * Returns the argument digest.
     * @return SHA-256 argument binding
     */
    public String argumentsDigest() { return argumentsDigest; }
    /**
     * Returns the durable decision.
     * @return exact stored decision
     */
    public Decision decision() { return decision; }
    /**
     * Returns the checkpoint version.
     * @return checkpoint format version
     */
    public int version() { return version; }
    /**
     * Returns the checkpoint.
     * @return defensive copy of checkpoint bytes
     */
    public byte[] checkpoint() { return checkpoint.clone(); }
    /**
     * Returns the checkpoint digest.
     * @return SHA-256 checkpoint binding
     */
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
