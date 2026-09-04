package ai.ravenroot.api.persistence;

import java.util.UUID;

/** One compare-and-set lifecycle transition for a durable tool approval. */
public sealed interface ToolApprovalTransition {
    /**
     * Returns the target approval identifier.
     * @return target approval identifier
     */
    UUID approvalId();
    /**
     * Returns the successor approval status.
     * @return successor approval status
     */
    ToolApprovalStatus next();
    /**
     * Returns the bounded actor identity.
     * @return actor identity, or empty for server-driven transitions
     */
    String actor();

    /**
     * An authorized approval decision.
     * @param approvalId target approval
     * @param actor approving identity
     */
    record Approved(UUID approvalId, String actor) implements ToolApprovalTransition {
        /** Validates transition inputs. */
        public Approved { requireId(approvalId); actor = requireActor(actor); }
        /** {@inheritDoc} */
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.APPROVED; }
    }

    /**
     * An authorized denial decision.
     * @param approvalId target approval
     * @param actor denying identity
     */
    record Denied(UUID approvalId, String actor) implements ToolApprovalTransition {
        /** Validates transition inputs. */
        public Denied { requireId(approvalId); actor = requireActor(actor); }
        /** {@inheritDoc} */
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.DENIED; }
    }

    /**
     * A store-clock expiry decision.
     * @param approvalId target approval
     */
    record Expired(UUID approvalId) implements ToolApprovalTransition {
        /** Validates transition inputs. */
        public Expired { requireId(approvalId); }
        /** {@inheritDoc} */
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.EXPIRED; }
        /** {@inheritDoc} */
        @Override public String actor() { return ""; }
    }

    /**
     * An authorized cancellation decision.
     * @param approvalId target approval
     * @param actor cancelling identity
     */
    record Cancelled(UUID approvalId, String actor) implements ToolApprovalTransition {
        /** Validates transition inputs. */
        public Cancelled { requireId(approvalId); actor = requireActor(actor); }
        /** {@inheritDoc} */
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.CANCELLED; }
    }

    /**
     * A single-use redemption transition.
     * @param approvalId target approval
     */
    record Consumed(UUID approvalId) implements ToolApprovalTransition {
        /** Validates transition inputs. */
        public Consumed { requireId(approvalId); }
        /** {@inheritDoc} */
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.CONSUMED; }
        /** {@inheritDoc} */
        @Override public String actor() { return ""; }
    }

    /**
     * A successful effect outcome.
     * @param approvalId target approval
     */
    record Succeeded(UUID approvalId) implements ToolApprovalTransition {
        /** Validates transition inputs. */
        public Succeeded { requireId(approvalId); }
        /** {@inheritDoc} */
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.SUCCEEDED; }
        /** {@inheritDoc} */
        @Override public String actor() { return ""; }
    }

    /**
     * A known failed effect outcome.
     * @param approvalId target approval
     */
    record Failed(UUID approvalId) implements ToolApprovalTransition {
        /** Validates transition inputs. */
        public Failed { requireId(approvalId); }
        /** {@inheritDoc} */
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.FAILED; }
        /** {@inheritDoc} */
        @Override public String actor() { return ""; }
    }

    /**
     * An effect outcome that cannot be determined safely.
     * @param approvalId target approval
     */
    record Indeterminate(UUID approvalId) implements ToolApprovalTransition {
        /** Validates transition inputs. */
        public Indeterminate { requireId(approvalId); }
        /** {@inheritDoc} */
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.INDETERMINATE; }
        /** {@inheritDoc} */
        @Override public String actor() { return ""; }
    }

    private static void requireId(UUID id) {
        if (id == null) throw new IllegalArgumentException("approvalId cannot be null");
    }

    private static String requireActor(String actor) {
        return HandlerRegistration.requireBoundedKey(actor, "actor");
    }
}
