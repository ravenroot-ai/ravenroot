package ai.ravenroot.core.security.nodepackage;

import java.util.Objects;
import java.util.UUID;

/** Core-owned payload-free signal emitted only after the fenced approval batch commits. */
public final class DurableToolApprovalSuspension extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final UUID approvalId;

    DurableToolApprovalSuspension(UUID approvalId) {
        super("Tool call suspended for durable approval", null, false, false);
        this.approvalId = Objects.requireNonNull(approvalId, "approvalId");
    }

    /** Approval identity the runtime verifies against the durable waiting invocation. */
    public UUID approvalId() {
        return approvalId;
    }
}
