package ai.ravenroot.api.execution;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The engine-neutral {@link ExecutionDomain}: membership tracked explicitly, termination performed
 * through the engine's own node contract.
 *
 * <h2>What it is for</h2>
 * <p>{@link ExecutionEngine#openDomain(String)} must be a {@code default} method — an
 * existing {@code ExecutionEngine} implementations must remain binary compatible, and an abstract
 * method would break them. A default that threw would make every such implementation unusable for
 * deployments; a default that silently did nothing would make segregation unverifiable. This class is
 * the third option: it honours the mandatory guarantee for any adapter, by bookkeeping.
 *
 * <p><b>It delivers the contract, not the isolation.</b> Closing it terminates exactly its own nodes,
 * which is what {@link ExecutionDomain} requires and what the conformance suite tests. It does not
 * give a supervision subtree or a dedicated dispatcher, because those are native facilities an
 * adapter either has or does not. Both shipped adapters override {@code openDomain} to provide them,
 * and their own tests assert the structure rather than the bookkeeping — otherwise "graphs are
 * distinguishable inside the engine" would quietly become "graphs are distinguishable in the logs".
 */
public final class TrackedExecutionDomain implements ExecutionDomain {
    /** Smallest membership size worth sweeping. Below it the sweep costs more than the entries do. */
    private static final int PRUNE_THRESHOLD = 64;

    private final String name;
    private final ExecutionEngine engine;
    private final Set<NodeRef> members = new LinkedHashSet<>();
    private final Object lock = new Object();
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile CompletionStage<Void> closure;
    /** Membership size at which the next sweep runs. See {@link #pruneTerminated()}. */
    private int nextPrune = PRUNE_THRESHOLD;

/**
 * Creates the portable domain implementation that records member references for an engine.
 *
 * @param name the immutable diagnostic name of the domain
 * @param engine the engine through which members are spawned and terminated
 */
    public TrackedExecutionDomain(String name, ExecutionEngine engine) {
        this.name = Objects.requireNonNull(name, "name");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public NodeRef spawn(String logicalName, RavenNode node) {
        synchronized (lock) {
            if (closing.get()) {
                throw new IllegalStateException("Execution domain " + name + " is closing or closed");
            }
            // Recorded under the same monitor close() snapshots under, so a node cannot be created
            // into a domain that has already decided which nodes it is terminating -- the same
            // ordering rule the engine's own spawn/drain pair follows.
            NodeRef ref = engine.spawn(logicalName, node);
            members.add(ref);
            if (members.size() >= nextPrune) {
                pruneTerminated();
                // Halving down to twice the live count keeps this amortised O(1) per spawn while
                // still bounding the set at a constant factor of concurrent liveness. A fixed
                // threshold would prune on every spawn once the live count reached it.
                nextPrune = Math.max(PRUNE_THRESHOLD, members.size() * 2);
            }
            return ref;
        }
    }

    @Override
    public Set<NodeRef> nodes() {
        synchronized (lock) {
            pruneTerminated();
            return Set.copyOf(members);
        }
    }

    /**
     * Drops the members whose nodes have terminated, so membership is <em>live</em> membership.
     *
     * <h2>Why this is polled rather than pushed</h2>
     * <p>Both shipped adapters release a domain member from the node's own lifecycle, because they
     * own it. This class does not: {@link ExecutionEngine} exposes no termination notification, only
     * {@link ExecutionEngine#status(NodeRef)}. Polling is therefore the only mechanism available to
     * the neutral implementation, and it is enough -- membership is read at exactly two points, and
     * this runs at both.
     *
     * <h2>Why absence counts as terminated</h2>
     * <p>An engine's memory of a terminated node is bounded by contract, so a reference this domain
     * really spawned can age out of it entirely. For any other caller an empty {@code status} is
     * ambiguous between "never issued" and "issued and forgotten"; here it is not, because this set
     * only ever contained references this domain received from {@code spawn}. Treating absence as
     * still-live would make the set unbounded again for exactly the nodes that have been gone
     * longest.
     *
     * <h2>What this costs, stated rather than hidden</h2>
     * <p>Previously nothing removed a member and it did not matter, because membership was fixed at
     * deployment startup. With one worker actor per invocation the set would otherwise grow with
     * every invocation the deployment ever ran, and {@link #close()} would stop and cancel every
     * historical reference -- making shutdown time scale with lifetime invocations instead of live
     * work, which is the property the deployment-admission contract's per-pod cap is defined against.
     */
    private void pruneTerminated() {
        members.removeIf(ref -> engine.status(ref)
                .map(status -> status.state().terminal())
                .orElse(true));
    }

    @Override
    public CompletionStage<Void> close() {
        if (!closing.compareAndSet(false, true)) {
            return closure;
        }
        Set<NodeRef> doomed;
        synchronized (lock) {
            // Swept first, so the work this close does is bounded by what is still live rather than
            // by everything this domain has ever spawned.
            pruneTerminated();
            doomed = Set.copyOf(members);
        }
        // Stop first, then escalate whatever did not settle -- the treatment ExecutionEngine.close()
        // gives the engine as a whole. A domain that could not be closed would be a deployment that
        // could never be stopped.
        var stops = doomed.stream().map(engine::stop).map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);
        closure = CompletableFuture.allOf(stops)
                .handle((ignored, error) -> error == null
                        ? CompletableFuture.<Void>completedFuture(null)
                        : CompletableFuture.allOf(doomed.stream().map(engine::cancel)
                                .map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new)))
                .thenCompose(stage -> stage);
        return closure;
    }
}
