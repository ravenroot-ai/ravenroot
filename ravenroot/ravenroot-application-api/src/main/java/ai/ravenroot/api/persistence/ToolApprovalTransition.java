package ai.ravenroot.api.persistence;

import java.util.UUID;

/** One compare-and-set lifecycle transition for a durable tool approval. */
public sealed interface ToolApprovalTransition {
    UUID approvalId();
    ToolApprovalStatus next();
    String actor();

    record Approved(UUID approvalId, String actor) implements ToolApprovalTransition {
        public Approved { requireId(approvalId); actor = requireActor(actor); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.APPROVED; }
    }

    record Denied(UUID approvalId, String actor) implements ToolApprovalTransition {
        public Denied { requireId(approvalId); actor = requireActor(actor); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.DENIED; }
    }

    record Expired(UUID approvalId) implements ToolApprovalTransition {
        public Expired { requireId(approvalId); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.EXPIRED; }
        @Override public String actor() { return ""; }
    }

    record Cancelled(UUID approvalId, String actor) implements ToolApprovalTransition {
        public Cancelled { requireId(approvalId); actor = requireActor(actor); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.CANCELLED; }
    }

    record Consumed(UUID approvalId) implements ToolApprovalTransition {
        public Consumed { requireId(approvalId); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.CONSUMED; }
        @Override public String actor() { return ""; }
    }

    record Succeeded(UUID approvalId) implements ToolApprovalTransition {
        public Succeeded { requireId(approvalId); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.SUCCEEDED; }
        @Override public String actor() { return ""; }
    }

    record Failed(UUID approvalId) implements ToolApprovalTransition {
        public Failed { requireId(approvalId); }
        @Override public ToolApprovalStatus next() { return ToolApprovalStatus.FAILED; }
        @Override public String actor() { return ""; }
    }

    record Indeterminate(UUID approvalId) implements ToolApprovalTransition {
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
