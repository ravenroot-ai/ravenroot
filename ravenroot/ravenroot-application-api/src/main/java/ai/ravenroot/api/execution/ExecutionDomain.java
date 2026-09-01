package ai.ravenroot.api.execution;

import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * A segregated group of nodes inside one engine, closable as a unit (ADR 0021 D1).
 *
 * <h2>The one mandatory guarantee</h2>
 * <p><b>Closing a domain terminates exactly the nodes spawned into it, and nothing else.</b> Both
 * halves are contract and both are enforced by the conformance suite. Without the first a deployment
 * cannot be stopped; without the second one deployment's shutdown would take another's nodes with
 * it, which is the failure segregation exists to prevent.
 *
 * <p>This is mandatory single-pod semantics, not a capability. An adapter that cannot group natively
 * still owes the guarantee and meets it by its own means — see
 * {@link ExecutionEngine#openDomain(String)}'s default, which tracks membership explicitly. What an
 * adapter may differ on is the <em>isolation</em> it achieves underneath: both shipped adapters place
 * a domain's nodes in a real supervision subtree, so a failure escalating past a node stops at the
 * domain root rather than at the system guardian, and may run them on a dedicated dispatcher so one
 * domain cannot starve another's threads.
 *
 * <h2>What a domain is not</h2>
 * <p>Not a unit of distribution. Cross-pod placement requires {@link EngineCapability#CLUSTERING} and
 * {@link EngineCapability#SHARDING} and belongs to Phase B; a domain is a boundary inside one engine
 * on one pod. Nor does it change the node-level contract: {@code stop}, {@code cancel}, {@code drain}
 * and resume-on-failure mean exactly what {@link ExecutionEngine} says they mean for a node in a
 * domain, and a domain never makes them optional.
 *
 * <h2>Closing is bounded, and independent of how many domains exist</h2>
 * <p>{@link #close()} is bounded in time by the adapter, like {@link ExecutionEngine#close()}, and
 * closing one domain must not be delayed by another closing concurrently. That independence is what
 * lets the operational cap be defined per pod without the shutdown budget growing with the number of
 * active deployments, and the conformance suite asserts it rather than assuming it.
 */
public interface ExecutionDomain {
/**
 * This domain's name within its engine. Stable for the life of the domain.
 * @return the immutable diagnostic name assigned when the domain was opened
 */
    String name();

    /**
     * Spawns a node that belongs to this domain.
     *
     * <p>Identical to {@link ExecutionEngine#spawn(String, RavenNode)} in every respect except
     * membership: the reference it returns is a normal {@link NodeRef} usable with {@code send},
     * {@code stop}, {@code cancel} and {@code status} on the engine.
     *
     * @throws IllegalStateException if this domain is closing or closed, or its engine is not
     *                               accepting
 * @param logicalName the graph-visible role used for diagnostics within this domain
 * @param node the behavior to start as a member of this domain
 * @return the engine-issued reference for the new domain member
     */
    NodeRef spawn(String logicalName, RavenNode node);

    /**
     * The nodes currently belonging to this domain. Snapshot; never {@code null}.
     *
     * <h4>Currently, and it is enforced (ADR 0024 §3)</h4>
     * <p>A node that has terminated is no longer a member, whatever terminated it -- its own
     * completion, {@code stop}, {@code cancel}, or a failure the adapter supervised by stopping it.
     * The word was always in this contract; previously no implementation honoured it, which was
     * invisible while membership was fixed at deployment startup and equal to the graph's node count.
     *
     * <p>ADR 0024 makes a {@code WORKER} actor exist per invocation rather than per graph node, so
     * membership that never shrank would grow with every invocation a deployment ever ran. That is
     * not merely a leak: {@link #close()} would then have to settle every reference the domain had
     * ever issued, making shutdown time scale with lifetime invocations instead of with live work --
     * the exact property the deployment-admission contract's per-pod cap is defined against -- and this method could no
     * longer answer the question the zero-orphan guarantee is asserted with.
     *
     * <p>The corollary an adapter must not miss: an empty set means nothing of this domain is alive,
     * which is precisely what "closing leaves no orphans" is checked by.
 * @return an immutable snapshot of members that have not yet terminated
     */
    Set<NodeRef> nodes();

    /**
     * Terminates exactly this domain's nodes and releases the domain.
     *
     * <p>Idempotent, and completes the same stage for every caller. Nodes are stopped first and
     * escalated to cancellation for whatever has not terminated within the adapter's bound, which is
     * the same treatment {@link ExecutionEngine#close()} gives the engine as a whole — a domain that
     * refused to close would otherwise be a deployment that could never be stopped.
 * @return a stage completed after every domain member has been terminated
     */
    CompletionStage<Void> close();
}
