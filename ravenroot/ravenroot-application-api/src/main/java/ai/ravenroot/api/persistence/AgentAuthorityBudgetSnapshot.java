package ai.ravenroot.api.persistence;

import java.util.Objects;
import java.util.Optional;

/**
 * One atomic budget and its optional configuration provenance. An absent pin means unverified
 * legacy authority; it never requests reconstruction from current configuration.
 *
 * @param budget accounting and root projection retained by the original API
 * @param pinnedRoot present only when explicit root provenance was persisted
 */
public record AgentAuthorityBudgetSnapshot(DurableAgentAuthorityBudget budget,
                                           Optional<PinnedAgentAuthorityRoot> pinnedRoot) {
    public AgentAuthorityBudgetSnapshot {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(pinnedRoot, "pinnedRoot");
        if (pinnedRoot.isPresent() && !pinnedRoot.get().root().equals(budget.root())) {
            throw new IllegalArgumentException("pinned root must equal the budget root");
        }
    }

    /** Wraps historical authority without manufacturing configuration evidence. */
    public static AgentAuthorityBudgetSnapshot legacy(DurableAgentAuthorityBudget budget) {
        return new AgentAuthorityBudgetSnapshot(budget, Optional.empty());
    }

    /** Binds explicit persisted evidence to its exact budget projection. */
    public static AgentAuthorityBudgetSnapshot pinned(DurableAgentAuthorityBudget budget,
                                                       PinnedAgentAuthorityRoot root) {
        return new AgentAuthorityBudgetSnapshot(budget, Optional.of(root));
    }
}
