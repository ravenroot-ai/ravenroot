package ai.ravenroot.core.approval;

import ai.ravenroot.api.persistence.DurableToolApproval;

import java.util.UUID;

/** Sanitized outcome of one approval lifecycle operation. */
public record ToolApprovalResult(Code code, DurableToolApproval approval, UUID resumeTraversalId) {
    public enum Code {
        CREATED,
        APPROVED,
        DENIED,
        EXPIRED,
        CANCELLED,
        CONSUMED,
        SUCCEEDED,
        FAILED,
        INDETERMINATE,
        ALREADY_APPLIED,
        ALREADY_SETTLED,
        NOT_FOUND,
        UNAUTHORIZED,
        SCOPE_MISMATCH,
        POLICY_REVOKED,
        UNAVAILABLE
    }

    public boolean accepted() {
        return switch (code) {
            case CREATED, APPROVED, DENIED, EXPIRED, CANCELLED, CONSUMED, SUCCEEDED, FAILED,
                    INDETERMINATE, ALREADY_APPLIED -> true;
            default -> false;
        };
    }
}
