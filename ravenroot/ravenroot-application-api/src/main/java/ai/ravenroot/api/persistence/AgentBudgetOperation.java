package ai.ravenroot.api.persistence;

import java.util.Objects;
import java.util.UUID;

/** Ordered mutations folded atomically with an execution batch. */
public sealed interface AgentBudgetOperation {
    record RegisterRoot(AgentAuthorityRootRegistration root) implements AgentBudgetOperation {
        public RegisterRoot { Objects.requireNonNull(root, "root"); }
    }
    record RegisterGrant(AgentAuthorityGrantRegistration grant, AgentAuthorityBinding binding,
                         long bootEpoch, long controlEpoch)
            implements AgentBudgetOperation {
        public RegisterGrant {
            Objects.requireNonNull(grant, "grant"); Objects.requireNonNull(binding, "binding");
            if (!grant.grantId().equals(binding.grantId())) throw new IllegalArgumentException("grant binding mismatch");
        }
    }
    record Hold(AgentBudgetReservation reservation, long bootEpoch, long controlEpoch) implements AgentBudgetOperation {
        public Hold {
            Objects.requireNonNull(reservation, "reservation");
            if (reservation.state() != AgentReservationState.HELD
                    || !reservation.actual().equals(AgentBudgetVector.ZERO)) {
                throw new IllegalArgumentException("new reservations must be empty and held");
            }
        }
    }
    record Dispatch(UUID reservationId, long bootEpoch, long controlEpoch) implements AgentBudgetOperation {
        public Dispatch { Objects.requireNonNull(reservationId, "reservationId"); }
    }
    record Settle(UUID reservationId, AgentBudgetVector actual) implements AgentBudgetOperation {
        public Settle { Objects.requireNonNull(reservationId, "reservationId"); Objects.requireNonNull(actual, "actual"); }
    }
    record MarkIndeterminate(UUID reservationId) implements AgentBudgetOperation {
        public MarkIndeterminate { Objects.requireNonNull(reservationId, "reservationId"); }
    }
    record Release(UUID reservationId) implements AgentBudgetOperation {
        public Release { Objects.requireNonNull(reservationId, "reservationId"); }
    }
    record CancelGrant(UUID grantId) implements AgentBudgetOperation {
        public CancelGrant { Objects.requireNonNull(grantId, "grantId"); }
    }
    record ExhaustGrant(UUID grantId) implements AgentBudgetOperation {
        public ExhaustGrant { Objects.requireNonNull(grantId, "grantId"); }
    }
    record CancelRoot() implements AgentBudgetOperation { }
    record KillRoot(long expectedControlEpoch) implements AgentBudgetOperation { }
    record ResetRoot(AgentAuthorityRootRegistration replacement, long expectedControlEpoch)
            implements AgentBudgetOperation {
        public ResetRoot { Objects.requireNonNull(replacement, "replacement"); }
    }
    record RebootRoot(AgentAuthorityRootRegistration replacement, long expectedControlEpoch)
            implements AgentBudgetOperation {
        public RebootRoot { Objects.requireNonNull(replacement, "replacement"); }
    }
}
