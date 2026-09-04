package ai.ravenroot.api.node;

import ai.ravenroot.api.execution.NodeMessage;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Exact server-minted input to a trusted node package's durable tool-call continuation. */
public final class ToolCallContinuationInput {
    /** Stored terminal or consumable decision delivered to the trusted continuation. */
    public enum Decision {
        /** The exact call may perform its one-time authorized effect. */
        APPROVED,
        /** The exact call was refused by an authorized actor. */
        DENIED,
        /** The request expired before effect consumption. */
        EXPIRED,
        /** The request was cancelled before effect consumption. */
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
     *
     * @param message message delivered to the fresh re-entry traversal
     * @param approvalId approval being resumed
     * @param originalTraversalId traversal that proposed the call
     * @param originalInvocationId invocation that proposed the call
     * @param originalAttemptId attempt that proposed the call
     * @param tool canonical tool name
     * @param canonicalArguments bounded canonical argument bytes
     * @param argumentsDigest content binding of the canonical arguments
     * @param decision exact stored decision being resumed
     * @param version package-owned checkpoint format version
     * @param checkpoint bounded opaque checkpoint bytes
     * @param checkpointDigest content binding of the checkpoint
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
     * Returns the re-entry message.
     *
     * @return message delivered to the fresh re-entry traversal
     */
    public NodeMessage message() { return message; }

    /**
     * Returns the approval identity.
     *
     * @return approval being resumed
     */
    public UUID approvalId() { return approvalId; }

    /**
     * Returns the original traversal identity.
     *
     * @return traversal that proposed the call
     */
    public UUID originalTraversalId() { return originalTraversalId; }

    /**
     * Returns the original invocation identity.
     *
     * @return invocation that proposed the call
     */
    public UUID originalInvocationId() { return originalInvocationId; }

    /**
     * Returns the original attempt identity.
     *
     * @return attempt that proposed the call
     */
    public UUID originalAttemptId() { return originalAttemptId; }

    /**
     * Returns the requested tool.
     *
     * @return canonical tool name
     */
    public String tool() { return tool; }

    /**
     * Returns the canonical arguments without exposing internal mutable state.
     *
     * @return defensive copy of the canonical argument bytes
     */
    public byte[] canonicalArguments() { return canonicalArguments.clone(); }

    /**
     * Returns the canonical argument binding.
     *
     * @return content binding of the canonical arguments
     */
    public String argumentsDigest() { return argumentsDigest; }

    /**
     * Returns the approval decision.
     *
     * @return exact stored decision being resumed
     */
    public Decision decision() { return decision; }

    /**
     * Returns the checkpoint format version.
     *
     * @return package-owned checkpoint format version
     */
    public int version() { return version; }

    /**
     * Returns the checkpoint without exposing internal mutable state.
     *
     * @return defensive copy of the opaque checkpoint bytes
     */
    public byte[] checkpoint() { return checkpoint.clone(); }

    /**
     * Returns the checkpoint binding.
     *
     * @return content binding of the checkpoint
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
