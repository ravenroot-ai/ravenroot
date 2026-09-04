package ai.ravenroot.api.persistence;

import java.util.UUID;

/** Durable accounting for one exact, idempotently keyed agent operation. */
public record AgentBudgetReservation(UUID reservationId, UUID grantId, String operationKey,
                                     AgentBudgetVector requested, AgentBudgetVector actual,
                                     AgentReservationState state) {
    public AgentBudgetReservation {
        if (reservationId == null || grantId == null) throw new IllegalArgumentException("reservation ids are required");
        operationKey = AgentAuthorityRootRegistration.token(operationKey, "operationKey", 96);
        if (requested == null || actual == null || state == null) {
            throw new IllegalArgumentException("reservation accounting is required");
        }
        if (!actual.componentwiseAtMost(requested)) {
            throw new IllegalArgumentException("actual usage cannot exceed its reservation");
        }
    }
}
