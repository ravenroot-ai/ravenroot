package ai.ravenroot.api.node.service;

import java.util.Objects;
import java.util.UUID;

/**
 * One server-side decision over one exact, canonical model-requested tool call.
 *
 * <p>The model supplies neither {@link #callId()} nor {@link #argumentsDigest()}. They are minted by
 * the trusted service from bounded parsed arguments, so audit correlation and argument identity do
 * not depend on attacker-controlled tool-call metadata.</p>
 *
 * <p>An allowed call must be closed with exactly one terminal outcome. Implementations make
 * duplicate terminal reports idempotent; a denied call ignores them because no effect was
 * authorized.</p>
 */
public interface ToolCallAuthorization {
    /**
     * Returns the correlation identity minted independently of model content.
     * @return the server-minted identity shared by decision and terminal audit events
     */
    UUID callId();

    /**
     * Returns whether the call may proceed, is denied, or requires approval.
     * @return the fail-closed disposition returned by the server policy
     */
    Disposition disposition();

    /**
     * Returns the content binding retained by the audit path.
     * @return SHA-256 of the canonical JSON argument object, never the arguments themselves
     */
    String argumentsDigest();

    /**
     * Canonical JSON for the exact immutable object the server evaluated.
     *
     * @return a defensive byte copy; empty when the arguments were invalid or the service absent
     */
    byte[] canonicalArguments();

    /**
     * Persists a versioned continuation for a call that requires approval and returns the typed
     * signal the node must propagate to the runtime.
     *
     * <p>This method is additive so older service implementations remain binary-compatible. Its
     * default refuses without manufacturing a suspension: only a trusted managed service that has
     * durably recorded the exact call may return its core-owned suspension signal.</p>
     *
     * @param continuationVersion positive decoder version owned by the node package
     * @param continuation bounded opaque checkpoint needed to resume after restart
     * @return an exception to propagate; a durable implementation returns a suspension signal
     */
    default RuntimeException suspend(int continuationVersion, byte[] continuation) {
        if (continuationVersion < 1) {
            throw new IllegalArgumentException("continuationVersion must be positive");
        }
        Objects.requireNonNull(continuation, "continuation");
        return new NodePackageServiceException(NodePackageServiceException.Reason.SERVICE_UNAVAILABLE);
    }

    /**
     * Records the terminal result of an allowed effect.
     * @param outcome sanitized effect outcome
     */
    void complete(Outcome outcome);

    /** Server-side decision made immediately before an effect. */
    enum Disposition {
        /** The evaluated call may proceed with the returned canonical arguments. */
        ALLOW,
        /** The evaluated call must perform no effect. */
        DENY,
        /** The call requires external approval and performs no effect in this contract version. */
        REQUIRE_APPROVAL
    }

    /** Terminal result reported for an allowed effect. */
    enum Outcome {
        /** The authorized effect completed successfully. */
        SUCCEEDED,
        /** The authorized effect threw, failed, or returned an unusable result. */
        FAILED
    }

    /**
     * Constructs the immutable deny decision used by the compatibility default.
     * @return a newly identified no-authority decision
     */
    static ToolCallAuthorization unavailable() {
        return new ToolCallAuthorization() {
            private final UUID id = UUID.randomUUID();

            @Override public UUID callId() { return id; }
            @Override public Disposition disposition() { return Disposition.DENY; }
            @Override public String argumentsDigest() { return ""; }
            @Override public byte[] canonicalArguments() { return new byte[0]; }
            @Override public void complete(Outcome outcome) { Objects.requireNonNull(outcome, "outcome"); }
        };
    }
}
