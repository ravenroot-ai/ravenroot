package ai.ravenroot.api.execution;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * The runtime-neutral execution contract every adapter implements.
 *
 * <h2>Supervision (CORE-04)</h2>
 * <p>A message whose {@code onMessage} fails completes the caller's stage exceptionally and
 * <strong>leaves the node running with its state intact</strong>. Resume is the mandatory Ravenroot
 * supervision decision, not a per-adapter choice and not something a capability may switch off: a
 * node is a graph vertex that many traversals share, so restarting or stopping it on one failed
 * invocation would let one traversal's bad input take out the others.</p>
 *
 * <h2>Stop, cancel and drain</h2>
 * <p>The three differ in what they promise about work already accepted, and that is the only
 * distinction that matters to a caller:</p>
 * <ul>
 *   <li>{@link #stop(NodeRef)} refuses new messages and lets accepted ones finish. It is bounded by
 *       the node's own work, so a node that never completes never stops. No timeout is imposed here,
 *       because a timeout is a policy the caller owns; escalate with {@link #cancel(NodeRef)}, which
 *       is legal while a node is draining.</li>
 *   <li>{@link #cancel(NodeRef)} refuses new messages and abandons accepted ones, failing each with
 *       {@link NodeCancelledException}. It is bounded in time regardless of what the node does. That
 *       bound is the whole reason it exists next to {@code stop}.</li>
 *   <li>{@link #drain()} applies {@code stop} to every node and refuses further {@code spawn}.</li>
 * </ul>
 *
 * <p>Both node-scoped operations are idempotent, complete the same stage for every caller, and run
 * {@code RavenNode.onStop} exactly once on either path.</p>
 *
 * <h2>What an engine remembers, and for how long</h2>
 * <p>A node that has terminated stays distinguishable from a reference the engine never issued, and
 * that memory is <strong>bounded</strong>. Both halves are contract. Without the first, {@code send}
 * has to report a caller's benign lost race as a caller defect; without the second, an engine shared
 * for the life of an application grows with every node that has ever run on it, and Ravenroot spawns
 * one node per graph vertex per {@code GraphRunner} rather than one per application.</p>
 *
 * <p>The size of the bound is an adapter's policy, but its existence is not, and neither is what
 * ages out first: a node that still exists is never forgotten, so bounding can only ever affect a
 * node that has already terminated. Past the bound, such a reference degrades to the answer given for
 * one that was never issued — that is the honest cost of being bounded, and an adapter states its own
 * capacity rather than implying the memory is free.</p>
 */
public interface ExecutionEngine extends AutoCloseable {
/**
 * Returns the adapter-defined identifier used in diagnostics and execution events.
 *
 * @return a stable non-blank identifier for this engine instance
 */
    String id();

    /**
     * Optional native facilities this engine actually provides, never the mandatory semantics above.
     *
     * <p>The set must be immutable and identical on every call for the life of the engine, so that a
     * caller may read it once. An adapter declares a capability only when its current dependencies
     * really deliver it; an adapter without one is expected to meet the mandatory contract by its own
     * means rather than degrade or fail.</p>
 * @return the immutable facilities this adapter natively supports
     */
    Set<EngineCapability> capabilities();

/**
 * Scheduler with the same lifecycle as this engine.
 * @return the scheduler that is shut down with this engine
 */
    Scheduler scheduler();

/**
 * The engine's own observable state.
 * @return the current engine admission and shutdown state
 */
    EngineState state();

    /**
     * Starts a node under the supplied logical name.
     * @throws IllegalStateException if the engine is draining or closed
 * @param logicalName the human-readable graph role used to identify the new node
 * @param node the behavior invoked for messages admitted to the new node
 * @return the unique reference assigned to the running node
     */
    NodeRef spawn(String logicalName, RavenNode node);

    /**
     * Delivers one message.
     *
     * <p>The returned stage always settles. It fails with {@link NodeNotAcceptingException} when the
     * node is known but no longer admits work, with {@link IllegalArgumentException} when the
     * reference was never issued by this engine or is no longer retained, and with a plain
     * {@link IllegalStateException} naming the engine once the engine itself is
     * {@link EngineState#CLOSED} — a closed engine has released what it knew, and reporting an unknown
     * node would blame the caller for that.</p>
 * @param target the engine-issued node reference to receive the message
 * @param message the input delivered after admission succeeds
 * @return a stage that yields the node result or completes exceptionally with the delivery outcome
     */
    CompletionStage<NodeResult> send(NodeRef target, NodeMessage message);

    /**
     * The observable status of a node this engine spawned, or empty if it never issued that reference
     * or no longer retains it.
 * @param target the reference whose known lifecycle state is requested
 * @return the status while retained, or empty for an unknown or evicted reference
     */
    Optional<NodeStatus> status(NodeRef target);

    /**
     * Opens a segregated group of nodes that can be closed as a unit (ADR 0021 D1).
     *
     * <h4>Additive, and mandatory rather than a capability</h4>
     * <p>This adds a grouping boundary and changes nothing about the node contract above: stop,
     * cancel, drain and resume-on-failure mean exactly what they mean for any other node, and a
     * domain never makes them conditional. Cross-pod placement is a different question and stays
     * behind {@link EngineCapability#CLUSTERING} and {@link EngineCapability#SHARDING}.
     *
     * <h4>Why this is a default method rather than an abstract one</h4>
     * <p>Not convenience. Existing {@code ExecutionEngine} implementations live in other modules,
     * so an abstract method would break their binary compatibility. The default
     * therefore has to honour the guarantee rather than refuse or ignore it, which
     * {@link TrackedExecutionDomain} does by tracking membership and terminating through this
     * engine's own node contract.
     *
     * <p>An adapter with native grouping overrides this to get real isolation underneath the same
     * guarantee -- both shipped adapters place a domain's nodes in a supervision subtree so an
     * escalating failure stops at the domain root rather than at the system guardian, and may run
     * them on a dedicated dispatcher so one domain cannot starve another's threads. The guarantee is
     * uniform; the isolation is native.
     *
     * @throws IllegalStateException if the engine is draining or closed
 * @param domainName a diagnostic name for the isolated node group
 * @return a domain that owns only nodes subsequently spawned through it
     */
    default ExecutionDomain openDomain(String domainName) {
        if (!state().accepting()) {
            throw new IllegalStateException("Execution engine is " + state());
        }
        return new TrackedExecutionDomain(domainName, this);
    }

/**
 * Stops a node after messages already accepted for it have completed.
 * @param target the node to stop after its accepted messages settle
 * @return a stage completed when the node's stop hook has finished
 */
    CompletionStage<Void> stop(NodeRef target);

    /**
     * Stops a node without waiting for messages already accepted for it, failing each of them with
     * {@link NodeCancelledException} and raising the node's {@link CancellationSignal}.
     *
     * <p>Unless {@link EngineCapability#PREEMPTIVE_CANCELLATION} is declared, an {@code onMessage}
     * already in flight is not interrupted: it runs to completion and its outcome is discarded.</p>
 * @param target the node whose unsettled messages must fail immediately
 * @return a stage completed when cancellation and the stop hook have finished
     */
    CompletionStage<Void> cancel(NodeRef target);

    /**
     * Refuses further {@code spawn} and stops every node gracefully.
     *
     * <p>The returned stage completes when every node has terminated. The underlying runtime is left
     * up, so an application may drain, observe, and only then {@link #close()}.</p>
 * @return a stage completed when all nodes have terminated gracefully
     */
    CompletionStage<Void> drain();

    /**
     * Drains, escalating to cancellation for whatever has not terminated within the adapter's bound,
     * shuts the underlying runtime down, and releases the node state it retained. Idempotent.
     */
    @Override
    void close();
}
