package ai.ravenroot.api.application;

import ai.ravenroot.api.persistence.ReplayBoundarySeed;
import java.util.List;

/** Tenant-authorized request to continue retained work from an exact pending boundary.
 * @param boundaries ordered pending boundaries and their retained predecessors
 * @param idempotencyKey caller-selected retry identity
 * @param reason operator reason recorded with ancestry
 * @param repeatabilityDecision operator decision for effects that may repeat
 * @param authorizeExternalEffects whether separately authorized external effects may run
 */
public record DerivedExecutionRequest(List<ReplayBoundarySeed> boundaries, String idempotencyKey,
                                      String reason, String repeatabilityDecision,
                                      boolean authorizeExternalEffects) {
    /** Validates and defensively copies the bounded request. */
    public DerivedExecutionRequest {
        boundaries = List.copyOf(boundaries == null ? List.of() : boundaries);
        if (boundaries.isEmpty()) throw new IllegalArgumentException("at least one boundary is required");
        if (boundaries.size() > 32) throw new IllegalArgumentException("at most 32 boundaries are supported");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey cannot be blank");
        idempotencyKey = idempotencyKey.strip();
        if (idempotencyKey.length() > 200) throw new IllegalArgumentException("idempotencyKey is too long");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason cannot be blank");
        reason = reason.strip();
        if (reason.length() > 500) throw new IllegalArgumentException("reason is too long");
        repeatabilityDecision = repeatabilityDecision == null ? "" : repeatabilityDecision.strip();
        if (repeatabilityDecision.length() > 500) throw new IllegalArgumentException("repeatabilityDecision is too long");
    }
}
