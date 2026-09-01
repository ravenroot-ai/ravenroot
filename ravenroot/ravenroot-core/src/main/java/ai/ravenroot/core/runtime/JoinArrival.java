package ai.ravenroot.core.runtime;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One branch's contribution to a fan-in join (CORE-03).
 *
 * <p>{@code branchId} is the identity that makes two deliveries "the same arrival", and it is the
 * id of the <em>distinct predecessor node</em> the result came from. It is deliberately not an
 * invocation or attempt identifier. {@link ai.ravenroot.api.persistence.PendingWork} records that
 * fan-in "models arrivals that precede invocation creation", so the join has no invocation of its
 * own to key on; and keying on the <em>upstream</em> invocation would make deduplication depend on
 * whether a retry is modelled as a new attempt or a new invocation — a question PERS-04 owns and has
 * not answered. Branch identity is correct under either answer, because a retried upstream node
 * re-emits along the same edge no matter how its retry is counted.</p>
 *
 * <h2>Iteration</h2>
 * <p>Two things about laps ride here, and they are different things. {@link #iteration()} is the
 * bucket <em>this</em> join correlates the arrival into, and it lives inside {@link #branchId()}
 * rather than in a field of its own, so the key the arrival is stored under and the bucket it counts
 * toward cannot drift apart. {@link #context()} is the whole inherited {@link IterationContext},
 * carried because the join's own downstream dispatch is stamped with the <em>merged</em> context of
 * every contributor — a branch that passed through an inner join more often than its sibling has to
 * keep that knowledge across the fan-in.</p>
 *
 * @param context the laps this arrival's causal past carries, from the runtime's scope and never
 *                from anything a node returned
 */
record JoinArrival(BranchId branchId, Object payload, Map<String, Object> attributes,
                   Set<UUID> parentInvocationIds, ai.ravenroot.api.execution.NodeCommand command,
                   IterationContext context) {
    public JoinArrival {
        if (branchId == null) {
            throw new IllegalArgumentException("branchId cannot be null");
        }
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        parentInvocationIds = Set.copyOf(parentInvocationIds == null ? Set.of() : parentInvocationIds);
        command = command == null ? ai.ravenroot.api.execution.NodeCommand.PROCESS : command;
        context = context == null ? IterationContext.EMPTY : context;
    }

    /**
     * Which firing of the arriving join this contributes to.
     *
     * <p>Derived from {@link #branchId()} rather than stored beside it. The design this implements
     * called for a field; one value in two places is one value that can disagree with itself, and the
     * thing that must not disagree here is "the bucket this counts toward" with "the key it is stored
     * under" — which are the same number precisely because there is only one.</p>
     */
    int iteration() {
        return branchId.lap();
    }

    JoinArrival(BranchId branchId, Object payload, Map<String, Object> attributes,
                Set<UUID> parentInvocationIds, ai.ravenroot.api.execution.NodeCommand command) {
        this(branchId, payload, attributes, parentInvocationIds, command, IterationContext.EMPTY);
    }

    JoinArrival(BranchId branchId, Object payload, Map<String, Object> attributes,
                Set<UUID> parentInvocationIds) {
        this(branchId, payload, attributes, parentInvocationIds,
                ai.ravenroot.api.execution.NodeCommand.PROCESS);
    }

    /**
     * An arrival that is a predecessor's whole output.
     *
     * <p>Kept so that every call site that genuinely means "this branch is the whole of node X" says
     * so in one place rather than repeating {@code BranchId.of}. A child arrival has no such
     * shorthand on purpose: minting one is a decision, and it should look like one.
     */
    JoinArrival(String nodeId, Object payload, Map<String, Object> attributes,
                Set<UUID> parentInvocationIds) {
        this(BranchId.of(nodeId), payload, attributes, parentInvocationIds,
                ai.ravenroot.api.execution.NodeCommand.PROCESS);
    }

    JoinArrival(String nodeId, Object payload, Map<String, Object> attributes,
                Set<UUID> parentInvocationIds, ai.ravenroot.api.execution.NodeCommand command) {
        this(BranchId.of(nodeId), payload, attributes, parentInvocationIds, command);
    }
}
