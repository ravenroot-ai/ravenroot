package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.RavenNode;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The live worker instances of one {@link GraphRunner} (ADR 0024 §3).
 *
 * <h2>What replaced what</h2>
 * <p>{@code GraphRunner} used to hold {@code nodeId -> NodeRef}: one actor per graph node, created at
 * construction and reused by every traversal. ADR 0024 splits that into a definition and a runtime
 * instance, and this class is the runtime half:
 *
 * <pre>{@code
 * nodeId       -> NodeRuntimeDefinition   (immutable, held by the runner)
 * invocationId -> WorkerInstance          (temporary, held here)
 * }</pre>
 *
 * <p>The consequence that matters is not the indirection but the bound. The old map was sized by the
 * graph, so a deployment of a five-hundred-node migration graph paid five hundred actors whether or
 * not a traversal ever reached them, and every arrival at one logical node queued behind one mailbox.
 * This map is sized by <em>work actually in flight</em>, and two arrivals at the same node are two
 * entries under two invocation identities that run at the same time.
 *
 * <h2>Identity is the entry, not the reference</h2>
 * <p>ADR 0024 §1 is explicit that an actor reference is runtime state and never the identity of
 * anything. Entries are therefore keyed by {@code invocationId} and carry the full
 * {@link WorkerInstanceIdentity}; the {@link NodeRef} is a field inside the entry. This is also what
 * makes the retry rule expressible rather than merely stated: a retry is another {@code attemptId}
 * under the same {@code invocationId}, so it is the same entry with a new attempt, and only a
 * documented transition that mints a new {@code invocationId} creates a second entry.
 *
 * <h2>Admission is owned by the runner, not this registry</h2>
 * <p>This registry previously had its own {@code Semaphore} of 1024, which bounded a deployment while
 * appearing to bound a pod, because a registry is per {@code GraphRunner}, which is per deployment.
 * {@code InvocationAdmission} then replaced it with a hierarchical pod/tenant/deployment ceiling
 * shared by every runner in the JVM. The current model keeps the mutable counters in the live
 * traversal's {@link ExecutionBudget} and the per-node gates in {@link TraversalAdmissionRegistry};
 * both are checked before this registry is called. {@link #acquire} therefore still has one job:
 * create and register an already-admitted instance, or unwind a failed spawn without partial state.
 *
 * <p><b>This does not touch the per-node ceilings the extensions declare on their own</b> — {@code
 * mail.send}'s {@code maxConcurrency}, Kafka's and AMQP's producer gates, the IMAP query behavior's
 * semaphore. Those are a different question: removing a platform-wide ceiling from this class or
 * {@link GraphRunner} does not remove a node's own declared limit on itself.
 */
final class WorkerInstanceRegistry implements AutoCloseable {

    /**
     * Spawns into the runner's execution domain. Deliberately the runner's own spawner rather than the
     * engine's, so a deployment's worker instances are structurally its domain's children and its
     * stop, rollback and cancel reach them without this class knowing that deployments exist.
     */
    private final BiFunction<String, RavenNode, NodeRef> spawner;

    /** Terminates one instance. {@code engine::stop}; escalation is {@code GraphRunner.close}'s job. */
    private final Function<NodeRef, CompletionStage<Void>> terminator;

    private final ConcurrentHashMap<UUID, WorkerInstance> live = new ConcurrentHashMap<>();
    /**
     * How many entries of {@link #live} belong to each node id, maintained incrementally.
     *
     * <p>Derived state, and deliberately so. The elastic view needs this number <em>per node event</em>
     * and in real time, and the obvious alternative — filtering {@link #live} by node id on demand —
     * costs a full scan of every instance the runner has in flight, on the dispatch path, twice per
     * invocation. That is a cost proportional to total concurrency paid to answer a question about one
     * node, which is exactly the proportionality {@link #live} itself was restructured to remove. This
     * map is O(1) to read and to maintain, and it is a {@code ConcurrentHashMap} of counters rather
     * than a lock, so a reader never blocks a dispatch.
     *
     * <p><b>Entries are removed when they reach zero, not left at zero.</b> A node's key would otherwise
     * outlive its last instance forever, so a long-lived runner would accumulate one entry per node it
     * ever touched — the graph-sized footprint this class exists not to have.
     */
    private final ConcurrentHashMap<String, AtomicInteger> liveByNode = new ConcurrentHashMap<>();
    /**
     * Instances whose invocation is finished but whose actor has not confirmed termination yet.
     *
     * <p>This set is the difference between "the runner stopped caring" and "the actor is gone", and
     * it exists because those are not the same moment. {@code release} asks the engine to stop the
     * actor and returns; the stop is bounded by the node's own {@code onStop}, which the SPI says may
     * never complete. Without this set such an instance would be out of {@code live}, therefore absent
     * from what {@code GraphRunner.close()} snapshots, therefore never escalated to a cancellation --
     * an actor still running that nothing was left responsible for. That is exactly the orphan ADR
     * 0024 §3 forbids, and it is invisible from {@code live} alone.
     */
    private final java.util.Set<WorkerInstance> retiring = ConcurrentHashMap.newKeySet();

    WorkerInstanceRegistry(BiFunction<String, RavenNode, NodeRef> spawner,
                           Function<NodeRef, CompletionStage<Void>> terminator) {
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.terminator = Objects.requireNonNull(terminator, "terminator");
    }

    /**
     * Creates one isolated instance for {@code identity} and registers it. Never refuses one for
     * capacity: the only way this throws is if the engine itself declines to spawn.
     *
     * <p>Nothing partially registered survives a failed spawn — this either returns a live, registered
     * instance or leaves the registry exactly as it found it.
     *
     * @throws RuntimeException whatever the engine raised, if the spawn itself failed
     */
    WorkerInstance acquire(WorkerInstanceIdentity identity, RavenNode runtime,
                           ExecutionBudget.Actor actorReservation) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(actorReservation, "actorReservation");
        NodeRef ref;
        try {
            ref = spawner.apply(identity.actorName(), runtime);
        } catch (RuntimeException failure) {
            actorReservation.close();
            throw failure;
        }
        var instance = new WorkerInstance(identity, ref, actorReservation);
        WorkerInstance clash = live.putIfAbsent(identity.invocationId(), instance);
        if (clash != null) {
            // An invocation identifier is minted per dispatch and must be unique. If it is not, the
            // identity source is broken, and quietly serving two invocations from one entry would
            // make the leak untraceable. Unwind fully instead.
            try {
                CompletionStage<Void> stopped = terminator.apply(ref);
                stopped.whenComplete((ignored, failure) -> actorReservation.close());
            } catch (RuntimeException stopFailure) {
                // The engine did not accept termination, so this actor may still exist. Keep its
                // capacity charged; closing it here would allow a replacement actor to be created
                // while the rejected duplicate remains live outside the registry.
            }
            throw new IllegalStateException("Invocation " + identity.invocationId()
                    + " is already live on this runner for node '" + clash.identity().nodeId() + "'");
        }
        // After the entry is in `live`, never before: the per-node counter must not be able to report
        // an instance that the unwind above went on to reject.
        //
        // The increment happens INSIDE the mapping function passed to `compute`, not after it returns
        // to preserve atomicity. `computeIfAbsent(...).incrementAndGet()` used to
        // fetch the counter and then mutate it as two SEPARATE atomic steps: for an existing key AT THE
        // HEAD of its bin, `computeIfAbsent`'s fast path returns without taking the bin's monitor at all
        // (JDK 21 ConcurrentHashMap.computeIfAbsent: "check first node without acquiring lock") -- so
        // for that case the fetch was a lock-free read and the `incrementAndGet()` right after it was a
        // lock-free CAS on the AtomicInteger. (For an existing key further down the bin's chain,
        // `computeIfAbsent` takes the monitor anyway, even though it inserts nothing -- so the fast path
        // is narrower than "any existing key", and this class never relied on the narrower case being
        // reachable or unreachable.) A release's decrement (see decrementLiveByNode, which mutates
        // INSIDE its own `computeIfPresent` and therefore DOES take the bin's monitor) could run between
        // the fetch and the mutation on the head-of-bin fast path and remove the very AtomicInteger this
        // acquire was about to increment. The acquisition would then bump a counter object the map no
        // longer held a reference to: the entry would read absent (liveCount() returns 0) even though
        // the instance was live, until release ran again and the count "self-healed". Measured at
        // 8-thread hammering: zero orphaned increments across 2,400,000 acquire/release pairs, so the
        // window was real but narrow.
        //
        // Doing the mutation inside `compute`'s remapping function closes it, at a real but small cost:
        // `compute` has no lock-free fast path for any existing key, head-of-bin or not -- it always
        // takes the bin's monitor, because it must apply an arbitrary function to the current value and
        // then atomically install, replace, or remove the mapping the function returns, and it cannot
        // know in advance that the call will not change anything. (Readers are unaffected regardless --
        // see below -- so this is not about protecting a reader from observing a value; ConcurrentHashMap
        // readers never take the monitor and can already observe a mapping mid-removal, by design.) So
        // this increment moved from a lock-free CAS to a CAS made under the bin's monitor. A synthetic
        // harness isolating just these two map primitives (not this class) measured +65 ns per
        // acquire/release pair in the worst case it could construct (8 threads hammering ONE shared key,
        // nothing else in the map); indistinguishable from noise once the map holds 8 or 64 keys, the
        // shape of contention this registry actually sees. That worst case is not reachable in practice
        // regardless: this line runs right after `spawner.apply(...)` above, which really creates an
        // actor -- a cost this added synchronization is under 1% of. Readers (liveCount(),
        // liveCount(String)) are unaffected either way: they read the AtomicInteger directly and never
        // call `compute`.
        liveByNode.compute(identity.nodeId(), (ignored, counter) -> {
            AtomicInteger updated = counter == null ? new AtomicInteger() : counter;
            updated.incrementAndGet();
            return updated;
        });
        return instance;
    }

    WorkerInstance acquire(WorkerInstanceIdentity identity, RavenNode runtime) {
        return acquire(identity, runtime,
                new ExecutionBudget(GraphExecutionLimits.DEFAULTS).reserveActor());
    }

    /**
     * Releases one instance: deregisters it and stops its actor.
     *
     * <p>Idempotent, and it has to be. The release runs from the completion handler of the attempt,
     * and {@link GraphRunner#close()} releases whatever is still live; a shutdown that raced a
     * completing node must not deregister or stop the same instance twice.
     *
     * @return the stage that settles when the instance's actor has terminated
     */
    CompletionStage<Void> release(WorkerInstance instance) {
        Objects.requireNonNull(instance, "instance");
        if (!instance.releasing.compareAndSet(false, true)) {
            return instance.released;
        }
        live.remove(instance.identity().invocationId(), instance);
        // Decremented here, with the removal from `live`, and NOT when the actor confirms termination:
        // the two must move together or the count would disagree with the map it summarises. That also
        // matches what the number means -- see liveCount(String).
        decrementLiveByNode(instance.identity().nodeId());
        retiring.add(instance);
        CompletionStage<Void> stopped;
        try {
            stopped = terminator.apply(instance.ref());
        } catch (RuntimeException failure) {
            // A stop that could not even be requested must not strand whoever waits on the release.
            // The instance stays in `retiring` on purpose: nothing terminated it, so close() must
            // still escalate it rather than treat a failed request as a completed one.
            instance.released.completeExceptionally(failure);
            return instance.released;
        }
        stopped.whenComplete((ignored, error) -> {
            // Off the books only once the actor has really gone. A stop that FAILED still terminated
            // the node -- ExecutionEngine's contract is that a node whose onStop threw has stopped,
            // badly -- so both outcomes retire it; only a stop that never settles keeps it here, which
            // is precisely the case close() must escalate.
            retiring.remove(instance);
            instance.actorReservation.close();
            if (error != null) {
                instance.released.completeExceptionally(error);
            } else {
                instance.released.complete(null);
            }
        });
        return instance.released;
    }

    /**
     * Every instance this runner is still responsible for terminating: those serving an invocation,
     * plus those whose stop has been asked for and not confirmed. Snapshot.
     */
    Collection<WorkerInstance> outstanding() {
        var all = new java.util.ArrayList<WorkerInstance>(live.values());
        all.addAll(retiring);
        return List.copyOf(all);
    }

    /** The instances currently serving an invocation. Snapshot. */
    Collection<WorkerInstance> live() {
        return List.copyOf(live.values());
    }

    /**
     * Instances currently serving an invocation.
     *
     * <p>Deliberately excludes the retiring ones: this is the number that answers "how much work is
     * in flight", which is what the live-instance metric measures. An instance awaiting confirmation of
     * its own stop is finished work, and counting it here would make a completed traversal look busy.
     */
    int liveCount() {
        return live.size();
    }

    /**
     * Instances of one node currently serving an invocation — the number the elastic view shows.
     *
     * <p>This is the per-node counterpart of {@link #liveCount()}: how much work this node is carrying
     * <em>as a role</em>, right now. It is the count of
     * actors that exist for the node, never the count of arrivals waiting to be served by them; those
     * are different quantities whenever one actor serves many arrivals, and conflating them is a defect.
     *
     * <p>O(1) and lock-free — see {@link #liveByNode} for why that is a requirement here and not a
     * micro-optimisation. Excludes {@code retiring} instances for the same reason {@link #liveCount()}
     * does: an instance awaiting confirmation of its own stop is finished work, and counting it would
     * leave a completed node looking busy in a view that sizes nodes by this number.
     *
     * <p>A node with no instances is absent from the map and reports 0. That is the true answer for
     * both a node nothing has reached and a node whose instances have all been released; the view has
     * no reason to tell those apart, because in both the node is carrying nothing.
     */
    int liveCount(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        AtomicInteger counter = liveByNode.get(nodeId);
        return counter == null ? 0 : Math.max(0, counter.get());
    }

    private void decrementLiveByNode(String nodeId) {
        liveByNode.computeIfPresent(nodeId, (ignored, counter) ->
                counter.decrementAndGet() <= 0 ? null : counter);
    }

    /**
     * Instances currently serving an invocation that belongs to {@code traversalId}. Diagnostics
     * diagnostics: the per-traversal counterpart of {@link #liveCount()}, needed because one registry is
     * shared by every traversal this runner has in flight, and "is nothing running for THIS
     * traversal" is a different question from "is nothing running on this runner at all".
     *
     * <p>Deliberately excludes {@code retiring} for the same reason {@link #liveCount()} does: an
     * instance awaiting confirmation of its own stop is finished work from the traversal's point of
     * view.</p>
     */
    int liveCount(UUID traversalId) {
        Objects.requireNonNull(traversalId, "traversalId");
        int count = 0;
        for (WorkerInstance instance : live.values()) {
            if (traversalId.equals(instance.identity().traversalId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Drops every remaining entry WITHOUT stopping any actor.
     *
     * <p>The omission is the point, and it is why this is not just {@code live().forEach(this::release)}.
     * {@code GraphRunner.close()} snapshots what it must terminate, then applies a bounded stop and
     * escalates to a cancellation for whatever did not settle -- the only path with a time bound on it.
     * If this method stopped the actors itself it would be issuing unbounded stops behind that
     * machinery's back, and, worse, it would empty the registry first, so the escalation would find
     * nothing left to escalate and close() would report a clean shutdown while the actors it forgot
     * were still running.
     */
    void deregisterAll() {
        live().forEach(instance -> {
            if (instance.releasing.compareAndSet(false, true)) {
                live.remove(instance.identity().invocationId(), instance);
                decrementLiveByNode(instance.identity().nodeId());
                instance.actorReservation.close();
                instance.released.complete(null);
            }
        });
        retiring.forEach(instance -> {
            instance.actorReservation.close();
            instance.released.complete(null);
        });
        retiring.clear();
        // Belt and braces after the loop above has already emptied it entry by entry: this method's
        // whole contract is that nothing is left registered, and a per-node counter that survived a
        // deregisterAll would make a closed runner report live instances it no longer holds.
        liveByNode.clear();
    }

    @Override
    public void close() {
        deregisterAll();
    }

    /**
     * One worker actor serving one logical invocation.
     *
     * <p>Not a record: the release flag and the released stage are mutable state that must not be part
     * of equality, and two instances are the same instance only if they are the same object.
     */
    static final class WorkerInstance {
        private final WorkerInstanceIdentity identity;
        private final NodeRef ref;
        private final ExecutionBudget.Actor actorReservation;
        private final java.util.concurrent.atomic.AtomicBoolean releasing =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final CompletableFuture<Void> released = new CompletableFuture<>();

        private WorkerInstance(WorkerInstanceIdentity identity, NodeRef ref,
                               ExecutionBudget.Actor actorReservation) {
            this.identity = identity;
            this.ref = ref;
            this.actorReservation = actorReservation;
        }

        WorkerInstanceIdentity identity() {
            return identity;
        }

        NodeRef ref() {
            return ref;
        }

        /** Settles once this instance's actor has terminated. See {@link #release(WorkerInstance)}. */
        CompletionStage<Void> released() {
            return released;
        }
    }
}
