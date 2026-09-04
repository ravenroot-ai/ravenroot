package ai.ravenroot.api.persistence;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable process-rooted agent authority and accounting snapshot. */
public record DurableAgentAuthorityBudget(ExecutionKey key, AgentAuthorityRootRegistration root,
                                          AgentAuthorityState state, long controlEpoch, AgentBudgetVector spent,
                                          AgentBudgetVector reserved,
                                          Map<UUID, DurableAgentGrant> grants,
                                          Map<UUID, AgentBudgetReservation> reservations) {
    public DurableAgentAuthorityBudget {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(root, "root");
        Objects.requireNonNull(state, "state"); Objects.requireNonNull(spent, "spent");
        if (controlEpoch < 0) throw new IllegalArgumentException("controlEpoch cannot be negative");
        Objects.requireNonNull(reserved, "reserved");
        grants = Map.copyOf(grants); reservations = Map.copyOf(reservations);
        if (!key.tenantId().equals(root.security().tenantId())) {
            throw new IllegalArgumentException("root tenant does not match execution key");
        }
    }

    /** One grant's durable accounting projection. */
    public record DurableAgentGrant(AgentAuthorityGrantRegistration registration,
                                    AgentAuthorityBinding binding, AgentGrantState state,
                                    AgentBudgetVector spent, AgentBudgetVector reserved) {
        public DurableAgentGrant {
            Objects.requireNonNull(registration, "registration"); Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(state, "state"); Objects.requireNonNull(spent, "spent");
            Objects.requireNonNull(reserved, "reserved");
        }
    }
}
