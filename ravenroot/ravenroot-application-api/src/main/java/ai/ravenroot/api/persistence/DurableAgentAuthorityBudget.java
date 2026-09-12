package ai.ravenroot.api.persistence;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable process-rooted agent authority and accounting snapshot.
 *
 * @param key owning execution identity
 * @param root immutable root authority registration
 * @param state root lifecycle state
 * @param controlEpoch global control epoch under which the root was admitted
 * @param spent durably charged resources
 * @param reserved resources still held for nonterminal operations
 * @param grants grants belonging to this root, keyed by grant id
 * @param reservations reservations belonging to this root, keyed by reservation id
 */
public record DurableAgentAuthorityBudget(ExecutionKey key, AgentAuthorityRootRegistration root,
                                          AgentAuthorityState state, long controlEpoch, AgentBudgetVector spent,
                                          AgentBudgetVector reserved,
                                          Map<UUID, DurableAgentGrant> grants,
                                          Map<UUID, AgentBudgetReservation> reservations) {
    /** Validates and snapshots the durable root projection. */
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

    /**
     * One grant's durable accounting projection.
     *
     * @param registration immutable grant registration
     * @param binding trusted invocation binding
     * @param state grant lifecycle state
     * @param spent resources charged to the grant
     * @param reserved resources currently held by the grant
     */
    public record DurableAgentGrant(AgentAuthorityGrantRegistration registration,
                                    AgentAuthorityBinding binding, AgentGrantState state,
                                    AgentBudgetVector spent, AgentBudgetVector reserved) {
        /** Validates the durable grant projection. */
        public DurableAgentGrant {
            Objects.requireNonNull(registration, "registration"); Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(state, "state"); Objects.requireNonNull(spent, "spent");
            Objects.requireNonNull(reserved, "reserved");
        }
    }
}
