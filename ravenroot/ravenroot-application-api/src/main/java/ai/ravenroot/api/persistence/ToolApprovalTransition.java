package ai.ravenroot.api.persistence;

import java.util.UUID;

/** One compare-and-set lifecycle transition for a durable tool approval. */
public sealed interface ToolApprovalTransition {
    /**
     * Returns the approval targeted by this transition.
     *
     * @return approval targeted by this transition
     */
    UUID approvalId();

    /**
     * Returns the lifecycle state produced by this transition.
     *
     * @return lifecycle state produced by this transition
     */
    ToolApprovalStatus next();

    /**
     * Returns the actor responsible for this transition.
     *
     * @return authenticated decision actor, or an empty string for system transitions
     */
    String actor();

    /**
     * Records an authorized approval decision.
     *
     * @param approvalId target approval identity
     * @param actor authenticated approving actor
     */
    record Approved(UUID approvalId, String actor) implements ToolApprovalTransition {
        /** Validates the target and actor. */
        public Approved { requireId(approvalId); actor = requireActor(actor); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.APPROVED; }
    }

    /**
     * Records an authorized denial decision.
     *
     * @param approvalId target approval identity
     * @param actor authenticated denying actor
     */
    record Denied(UUID approvalId, String actor) implements ToolApprovalTransition {
        /** Validates the target and actor. */
        public Denied { requireId(approvalId); actor = requireActor(actor); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.DENIED; }
    }

    /**
     * Records expiration by the durable store clock.
     *
     * @param approvalId target approval identity
     */
    record Expired(UUID approvalId) implements ToolApprovalTransition {
        /** Validates the target approval identity. */
        public Expired { requireId(approvalId); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.EXPIRED; }
        @Override public String actor() { return ""; }
    }

    /**
     * Records cancellation before effect consumption.
     *
     * @param approvalId target approval identity
     * @param actor authenticated cancellation actor
     */
    record Cancelled(UUID approvalId, String actor) implements ToolApprovalTransition {
        /** Validates the target and actor. */
        public Cancelled { requireId(approvalId); actor = requireActor(actor); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.CANCELLED; }
    }

    /**
     * Consumes the approval's one-time effect authority.
     *
     * @param approvalId target approval identity
     */
    record Consumed(UUID approvalId) implements ToolApprovalTransition {
        /** Validates the target approval identity. */
        public Consumed { requireId(approvalId); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.CONSUMED; }
        @Override public String actor() { return ""; }
    }

    /**
     * Records successful completion of the consumed effect.
     *
     * @param approvalId target approval identity
     */
    record Succeeded(UUID approvalId) implements ToolApprovalTransition {
        /** Validates the target approval identity. */
        public Succeeded { requireId(approvalId); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.SUCCEEDED; }
        @Override public String actor() { return ""; }
    }

    /**
     * Records a known failure of the consumed effect.
     *
     * @param approvalId target approval identity
     */
    record Failed(UUID approvalId) implements ToolApprovalTransition {
        /** Validates the target approval identity. */
        public Failed { requireId(approvalId); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.FAILED; }
        @Override public String actor() { return ""; }
    }

    /**
     * Records an effect whose outcome cannot be established safely.
     *
     * @param approvalId target approval identity
     */
    record Indeterminate(UUID approvalId) implements ToolApprovalTransition {
        /** Validates the target approval identity. */
        public Indeterminate { requireId(approvalId); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.INDETERMINATE; }
        @Override public String actor() { return ""; }
    }

    private static void requireId(UUID id) {
        if (id == null) throw new IllegalArgumentException("approvalId cannot be null");
    }

    private static String requireActor(String actor) {
        return HandlerRegistration.requireBoundedKey(actor, "actor");
    }
}
