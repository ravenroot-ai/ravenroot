package ai.ravenroot.core.approval;

import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HandlerRegistration;

import java.time.Duration;
import java.util.Objects;

/** Trusted operator settings applied when a managed tool decision requires approval. */
public record ToolApprovalSettings(String policyVersion, Duration timeToLive,
                                   HandlerAuthorization approverRequirements,
                                   boolean requesterMayApprove) {
    /** Validates and snapshots an approval policy that no model or node payload can alter. */
    public ToolApprovalSettings {
        policyVersion = HandlerRegistration.requireBoundedKey(policyVersion, "policyVersion");
        Objects.requireNonNull(timeToLive, "timeToLive");
        if (timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("timeToLive must be positive");
        }
        approverRequirements = Objects.requireNonNull(approverRequirements, "approverRequirements");
    }
}
