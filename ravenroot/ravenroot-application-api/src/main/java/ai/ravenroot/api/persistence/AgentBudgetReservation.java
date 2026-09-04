package ai.ravenroot.api.persistence;

import java.util.UUID;

/**
 * Durable accounting for one exact, idempotently keyed agent operation.
 *
 * @param reservationId stable reservation identifier
 * @param grantId grant charged by the operation
 * @param operationKey stable idempotency key for the exact attempt and operation
 * @param requested resources reserved before dispatch
 * @param actual resources charged after the outcome is known
 * @param state durable reservation lifecycle state
 */
public record AgentBudgetReservation(UUID reservationId, UUID grantId, String operationKey,
                                     AgentBudgetVector requested, AgentBudgetVector actual,
                                     AgentReservationState state) {
    /** Validates the reservation snapshot and its accounting invariant. */
    public AgentBudgetReservation {
        if (reservationId == null || grantId == null) throw new IllegalArgumentException("reservation ids are required");
        operationKey = AgentAuthorityRootRegistration.token(operationKey, "operationKey", 96);
        if (requested == null || actual == null || state == null) {
            throw new IllegalArgumentException("reservation accounting is required");
        }
        if (state != AgentReservationState.BREACHED && !actual.componentwiseAtMost(requested)) {
            throw new IllegalArgumentException("actual usage cannot exceed its reservation");
        }
    }
}
