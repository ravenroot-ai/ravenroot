package ai.ravenroot.akka;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.ExecutionDomain;
import ai.ravenroot.api.execution.Mailbox;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycle;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeNotAcceptingException;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.api.execution.TerminalNodeHistory;
import akka.actor.Cancellable;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.Props;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.StashBuffer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional Akka Typed adapter. No Akka type crosses the ExecutionEngine boundary.
 *
 * <p>Supervision, admission and the stop/cancel/drain state machine are not implemented here: they
 * live in the framework-neutral {@link NodeLifecycle}, which both supported adapters share so the two
 * cannot drift. What this class contributes is what only an actor runtime can: serialised message
 * handling per node, a scheduler and a system shutdown.</p>
 *
 * <p>The node registry is two-tier and neither tier grows with the number of executions: a node lives
 * in {@code nodes} until it terminates and is then retired into a bounded
 * {@link TerminalNodeHistory}, which is what keeps a terminated node distinguishable from a reference
 * this engine never issued without remembering every node it ever spawned.</p>
 *
 * <h2>THIS FILE IS NOT COMPILED OR TESTED IN THIS REPOSITORY'S ENVIRONMENT</h2>
 * <p>Read this before changing anything here, because it changes what your green build means.</p>
 *
 * <p>This module sits behind the opt-in {@code akka} Maven profile and is excluded from the default
 * reactor. Its dependency {@code com.typesafe.akka:akka-actor-typed_2.13:2.10.20} is BSL-licensed and
 * is <b>not available from Maven Central</b>; the shared local repository holds only
 * {@code .lastUpdated} failure markers for it, so the artifact has never resolved on this machine and
 * this module has never been built here <em>by anyone</em>.</p>
 *
 * <p><b>The practical consequence: a change to this file cannot be verified here.</b> A full reactor
 * build passing says nothing about it — the module is not in the reactor. Do not read a green build
 * as covering this class, and do not assume a compile error would have been caught.</p>
 *
 * <p>This adapter is therefore maintained as a <b>mechanical mirror</b> of
 * {@code PekkoExecutionEngine}, which is compiled and tested on every build. The two differ only by
 * package, imports, class name, {@code id()} and prose; the correct way to change this file is to
 * make the equivalent change to the Pekko adapter, verify it there, and mirror it — never to edit
 * this one alone. An asymmetry introduced here would be invisible until someone with access to the
 * BSL artifact built the profile.</p>
 *
 * <p>The execution-domain support below was added exactly that way: mirrored from the Pekko
 * implementation that passes the shared conformance suite, and <b>not compiled and not tested
 * here</b>.</p>
 *
 * <p><b>The ADR 0024 changes were mirrored the same way.</b> They made a domain's
 * membership its <em>live</em> nodes rather than every node it ever spawned, moved actor creation out
 * of {@code stateLock} so a domain spawn no longer serialises the whole engine behind a blocking
 * round trip through the domain guardian, and ordered the membership departure before the stage
 * {@code stop} and {@code cancel} hand back. On the Pekko side those three properties are pinned by
 * new cases in {@code ExecutionEngineContract} -- {@code aTerminatedNodeLeavesItsDomainMembership},
 * {@code membershipIsEmptyOnceEveryNodeHasTerminated} and
 * {@code concurrentSpawnsIntoDomainsDoNotSerialiseOnTheEngine} -- which this module would run too if
 * it could be built. Here they have never executed. Together with execution-domain support, the
 * mirror is now two contract changes deep: each change is individually plausible and the drift they
 * can accumulate is not observable from inside this environment.</p>
 */
public final class AkkaExecutionEngine implements ExecutionEngine {
    private static final int STASH_CAPACITY = 10_000;
    private static final long TERMINATION_BOUND_SECONDS = 10;

    private final ActorSystem<Void> actorSystem;
    /**
     * Nodes that still exist. An entry leaves the moment its lifecycle terminates, so this map is
     * bounded by concurrent liveness rather than by how many nodes the engine has ever spawned.
     */
    private final Map<NodeRef, NodeEntry> nodes = new ConcurrentHashMap<>();
    /** What is remembered about the nodes that have already gone, bounded and least-recently-used. */
    private final TerminalNodeHistory history;
    private final Object stateLock = new Object();
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile EngineState state = EngineState.RUNNING;

    public AkkaExecutionEngine() {
        this("ravenroot");
    }

    public AkkaExecutionEngine(String systemName) {
        this(systemName, TerminalNodeHistory.DEFAULT_CAPACITY);
    }

    /**
     * @param terminalHistoryCapacity how many terminated nodes stay observable; see
     *                                {@link TerminalNodeHistory}
     */
    public AkkaExecutionEngine(String systemName, int terminalHistoryCapacity) {
        this.history = new TerminalNodeHistory(terminalHistoryCapacity);
        this.actorSystem = ActorSystem.create(Behaviors.empty(), systemName);
    }

    @Override
    public String id() {
        return "akka";
    }

    /**
     * Visible for the adapter's own structural tests: a handle onto the real
     * {@code ActorSystem}, for DeathWatch on a domain's guardian. Mechanical mirror of
     * {@code PekkoExecutionEngine#actorSystem()}; unverifiable in this environment.
     */
    ActorSystem<Void> actorSystem() {
        return actorSystem;
    }

    @Override
    public Set<EngineCapability> capabilities() {
        // Deliberately empty: clustering, sharding, durable timers and telemetry are separate
        // artifacts that this adapter does not depend on, and cancellation here is cooperative.
        // Declaring a capability the dependency set cannot deliver would make the declaration
        // useless to the only caller it exists for.
        return Set.of();
    }

    @Override
    public Scheduler scheduler() {
        return (delay, task) -> {
            ensureUsable();
            Cancellable cancellable = actorSystem.scheduler()
                    .scheduleOnce(delay, task, actorSystem.executionContext());
            return cancellable::cancel;
        };
    }

    @Override
    public EngineState state() {
        return state;
    }

    @Override
    public NodeRef spawn(String logicalName, RavenNode node) {
        return spawnInto(logicalName, node, (behavior, uniqueName) ->
                actorSystem.systemActorOf(behavior, uniqueName, Props.empty()), null);
    }

    /**
     * The shared body of every spawn, parameterised only by WHERE the actor is created and WHICH
     * domain, if any, owns the result.
     *
     * <p>Engine-level spawns remain direct children of the system guardian, exactly as before. A
     * domain spawn asks its own guardian actor to create the child instead, so the actor is a real
     * child of the domain root rather than a flat sibling that merely records which group it belongs
     * to. Everything after creation -- the lifecycle, the registry entry, the retire callback and
     * their ordering -- is identical, because a node in a domain is a node.
     *
     * <h2>The actor is created OUTSIDE {@code stateLock}, deliberately</h2>
     * <p>This method used to hold {@code stateLock} across {@code placement.create}. For an
     * engine-level spawn that is nearly free, but a domain placement blocks the calling thread on a
     * round trip through the domain's guardian actor, so every domain spawn on the pod serialised
     * behind one monitor while a thread waited for an actor mailbox. That was affordable while
     * spawning happened once per graph node at deployment startup. ADR 0024 moves worker creation onto
     * the dispatch path, where it happens once per invocation.
     *
     * <h2>Read this before widening the lock back for safety</h2>
     * <p><b>The narrow scope is deliberate and there is no test at this level that will stop you.</b>
     * That absence is structural, not an oversight, and it is recorded here because it is the only
     * place it can survive.
     *
     * <p>The throughput property cannot be asserted through {@code ExecutionEngine}. Nothing the
     * caller controls runs inside the critical section, and its duration is bounded by an
     * actor-to-actor round trip that no public API can lengthen: {@code DomainCommand} is private so a
     * caller cannot queue work ahead of the guardian, {@code context.spawn} cannot be slowed from
     * outside, and nothing can be injected into behavior construction. A node's {@code onStart} is not
     * a way in either -- it runs from {@code Behaviors.setup}, after this method has returned, which
     * was measured against the previous engine rather than assumed: with one domain's {@code onStart}
     * blocked for twenty seconds, a spawn into a second domain completed in 0ms.
     *
     * <p>A wall-clock assertion was tried and removed. It compared elapsed time against
     * {@code TERMINATION_BOUND_SECONDS}, which made it equivalent to "no spawn timed out" -- true
     * unconditionally, including on the engine it claimed to distinguish. It was deleted rather than
     * repaired, and not replaced, because a case that cannot fail constrains no adapter; and the
     * engine TCK is exercised by exactly one module today, so an unfalsifiable case there is caught
     * nowhere else either.
     *
     * <p><b>What justifies the narrow scope, stated at its real strength.</b> Not a measured win: the
     * guardian round trip is cheap in the measured case. It removes a process-wide serialisation point
     * whose held duration is bounded only by a ten-second timeout -- cheap normally, unbounded under a
     * GC pause, a saturated dispatcher or a guardian under load -- from a path that is now travelled
     * once per invocation rather than once per graph node. "Not measured" is not "not real". Widening
     * it again would put an engine-wide monitor back across a blocking actor round trip on the dispatch
     * path, reintroducing the serialisation this narrow scope removes one level up.
     *
     * <p>What the lock actually protects is the registry snapshot {@link #drain()} takes, so only the
     * admission test and the {@code nodes} insertion need it. The check is made twice for that
     * reason: once before creating anything, so an engine that is already draining refuses without
     * building an actor, and once after, because the engine may have begun draining while this thread
     * was blocked. The second failure cannot simply throw -- the actor exists by then, and drain's
     * snapshot was taken without it -- so the node is registered, terminated through the ordinary node
     * contract and only then refused. Throwing without that would leak exactly the orphan actor this
     * ordering must never produce.
     *
     * <p><b>That second branch has no test, and the attempt to give it one was measured rather than
     * abandoned.</b> Its outcome is a state, not a duration, so unlike the throughput property it is
     * expressible: either the spawn threw and no actor was left running, or it succeeded and the node
     * terminates. A conformance case was written to assert exactly that, detecting an abandoned actor
     * through {@code onStart}/{@code onStop} counts because a refused caller holds no {@link NodeRef}
     * to ask about. It was then mutation-tested against this adapter with the {@code cancel} below
     * removed -- the precise defect it exists to catch.
     *
     * <p>It caught it <b>0 times in 20 runs</b>: 10 with twelve racing spawns, and 10 more with four
     * hundred spawns queued against the domain guardian and the drain fired mid-queue, which is the
     * only lever the SPI offers for widening the window. The case was stable (10/10 green on correct
     * code) and never flaky -- it simply never lands on the contested branch, because the window
     * between the two admission checks is one sub-millisecond actor round trip and an engine offers
     * exactly one such transition in its life. A case that cannot fail on the defect it targets is the
     * thing this file already deleted once, so it was not kept.
     *
     * <p>The branch is therefore correct-by-construction and reachable only by an interleaving no test
     * at this level can provoke. Keep it. Its cost is a few lines on a path taken once per engine
     * lifetime; removing it would trade that for an actor nobody holds a reference to.
     *
     * @param membership the domain that owns this node, or {@code null} for an engine-level spawn.
     *                   Joined under the same monitor as the registry insertion and released from the
     *                   node's own termination, so a domain's membership is the set of its
     *                   <em>live</em> nodes rather than every node it has ever spawned.
     */
    private NodeRef spawnInto(String logicalName, RavenNode node, ActorPlacement placement,
                              DomainMembership membership) {
        ensureAccepting();
        String uniqueName = sanitize(logicalName) + "-" + UUID.randomUUID();
        NodeRef ref = new NodeRef(uniqueName);
        var lifecycle = new NodeLifecycle(ref);
        ActorRef<Command> actor = placement.create(nodeBehavior(node, lifecycle), uniqueName);
        var entry = new NodeEntry(lifecycle, actor);
        boolean admitted;
        synchronized (stateLock) {
            admitted = state.accepting();
            if (admitted) {
                nodes.put(ref, entry);
                if (membership != null) {
                    membership.joined(ref);
                }
            }
        }
        if (!admitted) {
            // The engine began draining while this actor was being created, so drain()'s snapshot
            // cannot contain it. Register it anyway, purely so the ordinary termination path can
            // reach it, then stop it and refuse the caller.
            nodes.put(ref, entry);
            lifecycle.terminated().whenComplete((ignored, error) -> settle(entry, null, error));
            cancel(ref);
            throw new IllegalStateException("Execution engine is " + state);
        }
        // Registered after the put on purpose. A behaviour that dies during setup can already
        // have terminated by now, in which case this callback runs inline on this thread and
        // must find the entry it is being asked to retire. Registering after the membership join,
        // for the mirror of the same reason: a node that terminates instantly must be seen to leave
        // its domain, which is impossible if the departure could run before the arrival.
        lifecycle.terminated().whenComplete((ignored, error) -> settle(entry, membership, error));
        return ref;
    }

    /**
     * Moves a node out of the live registry once it can no longer do anything.
     *
     * <p>The history is written before the live entry is removed, so there is no instant in which a
     * reference the engine really issued is in neither place and would be reported as one it never
     * issued. The overlap is harmless in the other direction: while both hold the node, the live
     * entry answers first and answers with the same terminal state.</p>
     */
    /**
     * Runs every piece of bookkeeping a terminated node owes, and only then releases the callers
     * waiting on its {@code stop} or {@code cancel}.
     *
     * <p>The order is the contract. {@code settled} is completed last, so a caller that has observed
     * its stop cannot then find the node still in the live registry or still listed by its domain --
     * which is what makes "closing leaves no orphans" assertable rather than merely eventually true.
     * The original outcome is carried through, so a stop whose {@code onStop} threw still fails.
     */
    private void settle(NodeEntry entry, DomainMembership membership, Throwable error) {
        try {
            retire(entry);
            if (membership != null) {
                membership.departed(entry.lifecycle().node());
            }
        } finally {
            if (error != null) {
                entry.settled().completeExceptionally(error);
            } else {
                entry.settled().complete(null);
            }
        }
    }

    private void retire(NodeEntry entry) {
        history.record(entry.lifecycle());
        nodes.remove(entry.lifecycle().node(), entry);
    }

    @Override
    public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
        if (state == EngineState.CLOSED) {
            // A closed engine has released what it knew, so it can no longer tell a node it once ran
            // from a reference it never issued. Saying so is the only honest answer left; reporting
            // "unknown node" would accuse the caller of a defect the engine caused.
            return CompletableFuture.failedFuture(new IllegalStateException("Execution engine is closed"));
        }
        NodeEntry entry = nodes.get(target);
        if (entry == null) {
            return history.get(target).isPresent()
                    ? CompletableFuture.failedFuture(
                            new NodeNotAcceptingException(target, NodeLifecycleState.TERMINATED))
                    : CompletableFuture.failedFuture(
                            new IllegalArgumentException("Unknown node: " + target.value()));
        }
        var reply = new CompletableFuture<NodeResult>();
        // The admission test and the enqueue are one operation. Splitting them is what allowed a
        // message to be told after the actor had already stopped, leaving its caller waiting forever.
        if (!entry.lifecycle().accept(reply, () -> entry.actor().tell(new Execute(message, reply)))) {
            return CompletableFuture.failedFuture(entry.lifecycle().refusal());
        }
        return reply;
    }

    @Override
    public Optional<NodeStatus> status(NodeRef target) {
        NodeEntry entry = nodes.get(target);
        return entry != null
                ? Optional.of(entry.lifecycle().status())
                : history.get(target).map(TerminalNodeHistory.Entry::status);
    }

    @Override
    public CompletionStage<Void> stop(NodeRef target) {
        NodeEntry entry = nodes.get(target);
        if (entry == null) {
            return retiredTermination(target);
        }
        if (entry.lifecycle().beginDrain()) {
            entry.actor().tell(new Drain());
        }
        return entry.settled();
    }

    /**
     * The termination stage of a node that has already left the live registry.
     *
     * <p>It is the node's own stage rather than a fresh completed one, so a caller stopping a node
     * twice still learns that its {@code onStop} failed. A reference nobody remembers is reported as
     * a completed stop, which is what the SPI has always said about stopping a node that is not
     * there.</p>
     */
    private CompletionStage<Void> retiredTermination(NodeRef target) {
        return history.get(target)
                .map(TerminalNodeHistory.Entry::terminated)
                .orElseGet(() -> CompletableFuture.completedFuture(null));
    }

    @Override
    public CompletionStage<Void> cancel(NodeRef target) {
        NodeEntry entry = nodes.get(target);
        if (entry == null) {
            return retiredTermination(target);
        }
        // beginCancel has already failed every unsettled reply, so this stage is bounded by onStop
        // rather than by whatever the node is still computing.
        if (entry.lifecycle().beginCancel()) {
            entry.actor().tell(new Cancel());
        }
        return entry.settled();
    }

    @Override
    public CompletionStage<Void> drain() {
        List<NodeEntry> draining;
        synchronized (stateLock) {
            if (state.canTransitionTo(EngineState.DRAINING)) {
                state = EngineState.DRAINING;
            }
            draining = List.copyOf(nodes.values());
        }
        return allOf(draining, entry -> stop(entry.lifecycle().node()));
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        try {
            drain().toCompletableFuture().get(TERMINATION_BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A node that will not drain within the bound is cancelled below: close must terminate.
        }
        List<NodeEntry> stragglers;
        synchronized (stateLock) {
            stragglers = nodes.values().stream().filter(entry -> !entry.lifecycle().state().terminal()).toList();
        }
        try {
            allOf(stragglers, entry -> cancel(entry.lifecycle().node()))
                    .toCompletableFuture().get(TERMINATION_BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // ActorSystem termination below remains the final cleanup boundary.
        } finally {
            synchronized (stateLock) {
                state = EngineState.CLOSED;
            }
            actorSystem.terminate();
        }
        try {
            actorSystem.getWhenTerminated().toCompletableFuture().get(TERMINATION_BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Akka ActorSystem did not terminate cleanly", exception);
        } finally {
            // A closed engine owes nobody an answer about a node: spawn is refused and send reports
            // the engine, not the node. Holding the registries past that point would keep an engine
            // an application has finished with as large as everything it ever ran.
            release();
        }
    }


    /** Where a node actor is created. See {@link #spawnInto}. */
    @FunctionalInterface
    private interface ActorPlacement {
        ActorRef<Command> create(Behavior<Command> behavior, String uniqueName);
    }

    /**
     * A domain's live-membership ledger, written by {@link #spawnInto} rather than by the domain.
     *
     * <p>The domain cannot maintain this itself. Departure has to be driven from the node's own
     * termination, which only the engine holds, and arrival has to be ordered before the departure
     * hook is armed -- a node that dies during setup terminates before {@code spawn} returns, so a
     * domain that added itself afterwards would record an arrival for a node that had already left
     * and keep it forever.
     */
    private interface DomainMembership {
        void joined(NodeRef ref);

        void departed(NodeRef ref);
    }

    /**
     * A segregated group backed by a real supervision subtree (ADR 0021 D1).
     *
     * <p><b>Unverified in this environment.</b> Mirrored from {@code PekkoExecutionEngine}'s
     * implementation, which the shared conformance suite exercises on every build; this copy is not
     * compiled or tested here because the BSL {@code akka-actor-typed} artifact cannot be resolved.
     * See this class's Javadoc before changing it.
     *
     * <p>The domain owns a guardian actor; every node spawned into it is that guardian's child. This
     * is what makes segregation structural rather than a label: a failure escalating past a node
     * stops at the domain root instead of reaching the system guardian, and stopping the guardian
     * stops precisely its own children.
     *
     * <p>Termination still runs through the node contract first -- stop, then cancel for whatever did
     * not settle -- so a domain closes the same way the engine does and {@code onStop} runs exactly
     * once per node on either path. The guardian is stopped afterwards, as the structural backstop
     * for anything the cooperative path did not reach.
     */
    @Override
    public ExecutionDomain openDomain(String domainName) {
        synchronized (stateLock) {
            if (!state.accepting()) {
                throw new IllegalStateException("Execution engine is " + state);
            }
            String uniqueName = "domain-" + sanitize(domainName) + "-" + UUID.randomUUID();
            var guardian = actorSystem.<DomainCommand>systemActorOf(
                    domainBehavior(), uniqueName, Props.empty());
            return new SubtreeDomain(domainName, uniqueName, guardian);
        }
    }

    private static Behavior<DomainCommand> domainBehavior() {
        return Behaviors.setup(context -> Behaviors.receive(DomainCommand.class)
                .onMessage(SpawnInDomain.class, message -> {
                    try {
                        message.reply().complete(
                                context.spawn(message.behavior(), message.uniqueName(), message.props()));
                    } catch (RuntimeException error) {
                        message.reply().completeExceptionally(error);
                    }
                    return Behaviors.same();
                })
                .onMessage(StopDomain.class, message -> {
                    message.reply().complete(null);
                    // Stopping the guardian stops its children and nothing else: the structural
                    // half of "closing a domain releases exactly its nodes".
                    return Behaviors.stopped();
                })
                .build());
    }

    private sealed interface DomainCommand permits SpawnInDomain, StopDomain {
    }

    private record SpawnInDomain(Behavior<Command> behavior, String uniqueName, Props props,
                                 CompletableFuture<ActorRef<Command>> reply) implements DomainCommand {
    }

    private record StopDomain(CompletableFuture<Void> reply) implements DomainCommand {
    }

    /** Package-private so this adapter's own structural test can assert the subtree exists. */
    final class SubtreeDomain implements ExecutionDomain, DomainMembership {
        private final String name;
        private final String guardianName;
        private final ActorRef<DomainCommand> guardian;
        /**
         * This domain's <em>live</em> nodes.
         *
         * <p>Entries leave when their node terminates, through {@link #departed(NodeRef)}, which
         * {@code spawnInto} arms from the node's own lifecycle. Previously nothing ever removed one,
         * which was invisible while membership was fixed at deployment startup and equal to the graph's
         * node count. Once a worker actor exists per invocation it stops being invisible three ways at
         * once: the set grows with every invocation the deployment has ever run, {@link #close()}
         * stops and cancels every historical reference so shutdown time scales with lifetime
         * invocations rather than with live work -- which is exactly the property the deployment-admission contract's per-pod
         * cap is defined against -- and {@link #nodes()} can no longer answer the question the
         * zero-orphan guarantee is asserted with.
         */
        private final Set<NodeRef> members = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean domainClosing = new AtomicBoolean();
        private volatile CompletionStage<Void> closure;

        private SubtreeDomain(String name, String guardianName, ActorRef<DomainCommand> guardian) {
            this.name = name;
            this.guardianName = guardianName;
            this.guardian = guardian;
        }

        @Override
        public String name() {
            return name;
        }

        /** Visible for the adapter's own structural tests: the guardian's actor-path segment. */
        public String guardianName() {
            return guardianName;
        }

        /**
         * Visible for the adapter's own structural tests: an opaque handle onto the real
         * guardian actor, for DeathWatch. Mechanical mirror of
         * {@code PekkoExecutionEngine.SubtreeDomain#guardianRef()}; unverifiable in this environment.
         */
        public ActorRef<?> guardianRef() {
            return guardian;
        }

        @Override
        public NodeRef spawn(String logicalName, RavenNode node) {
            if (domainClosing.get()) {
                throw new IllegalStateException("Execution domain " + name + " is closing or closed");
            }
            // Membership is handed to spawnInto rather than recorded here, so the join happens under
            // the same monitor as the registry insertion and strictly before the departure hook is
            // armed. Recording it after the call returned -- as the previous implementation did --
            // loses that ordering the moment a node can terminate as fast as it is created.
            return spawnInto(logicalName, node, (behavior, uniqueName) -> {
                var reply = new CompletableFuture<ActorRef<Command>>();
                guardian.tell(new SpawnInDomain(behavior, uniqueName, Props.empty(), reply));
                try {
                    return reply.get(TERMINATION_BOUND_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted spawning into domain " + name, interrupted);
                } catch (Exception error) {
                    throw new IllegalStateException("Domain " + name + " did not create the node", error);
                }
            }, this);
        }

        @Override
        public void joined(NodeRef ref) {
            members.add(ref);
        }

        @Override
        public void departed(NodeRef ref) {
            members.remove(ref);
        }

        @Override
        public Set<NodeRef> nodes() {
            return Set.copyOf(members);
        }


        private CompletableFuture<Void> settleAll(Set<NodeRef> targets,
                                                  java.util.function.Function<NodeRef,
                                                          CompletionStage<Void>> operation) {
            return CompletableFuture.allOf(targets.stream().map(operation)
                    .map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new));
        }

        @Override
        public CompletionStage<Void> close() {
            if (!domainClosing.compareAndSet(false, true)) {
                return closure;
            }
            Set<NodeRef> doomed = Set.copyOf(members);
            // Bounded, like ExecutionEngine.close(). stop() is deliberately unbounded by the node
            // contract -- "a node that never completes never stops" -- so without this timeout a
            // single wedged node would hang its domain's close forever, and ExecutionDomain's own
            // promise that closing is bounded would be false. The escalation to cancel is what is
            // bounded in time regardless of what the node does.
            closure = settleAll(doomed, AkkaExecutionEngine.this::stop)
                    .orTimeout(TERMINATION_BOUND_SECONDS, TimeUnit.SECONDS)
                    .handle((ignored, error) -> error == null
                            ? CompletableFuture.<Void>completedFuture(null)
                            : settleAll(doomed, AkkaExecutionEngine.this::cancel)
                                    .orTimeout(TERMINATION_BOUND_SECONDS, TimeUnit.SECONDS))
                    .thenCompose(stage -> stage)
                    .handle((ignored, error) -> {
                        // Reached on every path, including a bound that expired: stopping the
                        // guardian stops whatever the cooperative path could not, which is the
                        // structural backstop a bookkeeping domain does not have.
                        var stopped = new CompletableFuture<Void>();
                        guardian.tell(new StopDomain(stopped));
                        return stopped;
                    })
                    .thenCompose(stage -> stage);
            return closure;
        }
    }

    private void release() {
        nodes.clear();
        history.clear();
    }

    private static CompletionStage<Void> allOf(List<NodeEntry> entries,
                                               java.util.function.Function<NodeEntry, CompletionStage<Void>> action) {
        return CompletableFuture.allOf(entries.stream()
                .map(action)
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new));
    }

    private Behavior<Command> nodeBehavior(RavenNode node, NodeLifecycle lifecycle) {
        return Behaviors.withStash(STASH_CAPACITY, stash -> Behaviors.setup(context -> {
            NodeContext nodeContext = nodeContext(lifecycle);
            try {
                node.onStart(nodeContext);
            } catch (RuntimeException | Error error) {
                // A behaviour that fails during setup is never undeferred, so PostStop is not
                // signalled for it and the net below cannot see this death. The lifecycle has to be
                // settled here or spawn would hand back a reference nothing can ever terminate.
                lifecycle.beginCancel();
                lifecycle.terminate(error);
                // Settled first, then rethrown: a JVM that is on its way out still releases whoever
                // was waiting on this node.
                NodeLifecycle.resumableOrRethrow(error);
                return Behaviors.stopped();
            }
            return idle(context, stash, node, nodeContext, lifecycle);
        }));
    }

    private Behavior<Command> idle(ActorContext<Command> context, StashBuffer<Command> stash,
                                   RavenNode node, NodeContext nodeContext, NodeLifecycle lifecycle) {
        return Behaviors.receive(Command.class)
                .onMessage(Execute.class, command -> {
                    if (command.reply().isDone()) {
                        // Abandoned by a cancellation that overtook it in the mailbox. Its caller has
                        // already been settled, so running the node would only produce a result with
                        // nowhere to go.
                        return Behaviors.same();
                    }
                    CompletionStage<NodeResult> result;
                    // Error, not just RuntimeException. Resume is the mandatory supervision decision
                    // and the reply is registered at admission, so an Error escaping to the actor
                    // costs both guarantees at once: Akka stops the actor, the caller's reply is
                    // never settled, and the lifecycle is stranded in a non-terminal state that no
                    // later stop or cancel can move. An AssertionError from an assertion inside a
                    // node was enough to hang GraphRunner.close() forever.
                    //
                    // A VirtualMachineError is the exception to that: it is the JVM reporting that it
                    // can no longer do its job. Rethrowing hands it back to Akka's own fatal-error
                    // policy, which is where it went before this adapter caught Error at all — the
                    // point of catching Error was to stop losing recoverable failures, not to take
                    // akka.jvm-exit-on-fatal-error away from the operator who enabled it.
                    try {
                        result = node.onMessage(command.message(), nodeContext);
                    } catch (RuntimeException | Error error) {
                        result = CompletableFuture.failedFuture(NodeLifecycle.resumableOrRethrow(error));
                    }
                    context.pipeToSelf(result,
                            (value, error) -> new Completed(command.reply(), value, error));
                    return busy(context, stash, node, nodeContext, lifecycle);
                })
                .onMessage(Drain.class, command -> terminate(node, nodeContext, lifecycle))
                .onMessage(Cancel.class, command -> {
                    stash.clear();
                    return terminate(node, nodeContext, lifecycle);
                })
                .onSignal(PostStop.class, signal -> abandon(node, nodeContext, lifecycle))
                .build();
    }

    private Behavior<Command> busy(ActorContext<Command> context, StashBuffer<Command> stash,
                                   RavenNode node, NodeContext nodeContext, NodeLifecycle lifecycle) {
        return Behaviors.receive(Command.class)
                .onMessage(Execute.class, command -> {
                    stash.stash(command);
                    return Behaviors.same();
                })
                // A drain waits its turn behind the work it promised to let finish.
                .onMessage(Drain.class, command -> {
                    stash.stash(command);
                    return Behaviors.same();
                })
                // A cancellation does not: being bounded regardless of what the node is doing is the
                // only thing that distinguishes it from a drain.
                .onMessage(Cancel.class, command -> {
                    stash.clear();
                    return terminate(node, nodeContext, lifecycle);
                })
                .onMessage(Completed.class, command -> {
                    lifecycle.settle(command.reply(), command.result(), command.error());
                    return stash.unstashAll(idle(context, stash, node, nodeContext, lifecycle));
                })
                .onSignal(PostStop.class, signal -> abandon(node, nodeContext, lifecycle))
                .build();
    }

    private Behavior<Command> terminate(RavenNode node, NodeContext context, NodeLifecycle lifecycle) {
        terminateOnce(node, context, lifecycle);
        return Behaviors.stopped();
    }

    /**
     * The last resort: the actor is gone, however it got there.
     *
     * <p>Every ordinary termination has already settled the lifecycle by the time this signal
     * arrives, so this is a no-op on that path — {@code beginCancel} and {@code terminate} both refuse
     * a terminated node. What it exists for is the path nobody plans: a failure Akka supervised by
     * stopping the actor. Without it the lifecycle stays wherever it was, its accepted messages are
     * never answered, and {@code stop} returns a stage that can no longer be completed by anything,
     * because the only thing that could complete it was the actor that just died.</p>
     *
     * <p>The reason is {@code CANCELLED} rather than {@code STOPPED}, and that is the accurate one:
     * work was accepted and abandoned. Reporting a clean drain here would tell an operator the node
     * finished what it owed, which is precisely what did not happen.</p>
     */
    private Behavior<Command> abandon(RavenNode node, NodeContext context, NodeLifecycle lifecycle) {
        lifecycle.beginCancel();
        terminateOnce(node, context, lifecycle);
        return Behaviors.same();
    }

    private void terminateOnce(RavenNode node, NodeContext context, NodeLifecycle lifecycle) {
        if (lifecycle.state().terminal()) {
            return;
        }
        Throwable failure = null;
        try {
            node.onStop(context);
        } catch (RuntimeException | Error error) {
            if (error instanceof VirtualMachineError fatal) {
                // Terminated first, so that whoever asked for the stop is released rather than left
                // waiting on a stage nothing can complete, and so that anything reaching this method
                // again finds a node that has already run onStop once.
                lifecycle.terminate(error);
                throw fatal;
            }
            failure = error;
        }
        lifecycle.terminate(failure);
    }

    private NodeContext nodeContext(NodeLifecycle lifecycle) {
        return new NodeContext() {
            @Override
            public NodeRef self() {
                return lifecycle.node();
            }

            @Override
            public Scheduler scheduler() {
                return AkkaExecutionEngine.this.scheduler();
            }

            @Override
            public Mailbox mailbox() {
                return lifecycle::acceptedMessages;
            }

            @Override
            public CancellationSignal cancellation() {
                return lifecycle.cancellation();
            }
        };
    }

    private void ensureUsable() {
        if (state == EngineState.CLOSED) {
            throw new IllegalStateException("Execution engine is closed");
        }
    }

    /**
     * The fail-fast half of {@code spawnInto}'s admission test.
     *
     * <p>Deliberately reads the volatile state without taking {@code stateLock}: it exists to refuse
     * before an actor is built, not to decide, and the decision is re-made under the monitor once the
     * actor exists. Taking the lock here would reintroduce exactly the contention removed here, to
     * make an answer that is provisional either way.
     */
    private void ensureAccepting() {
        if (!state.accepting()) {
            throw new IllegalStateException("Execution engine is " + state);
        }
    }

    private static String sanitize(String name) {
        String sanitized = name == null ? "node" : name.replaceAll("[^a-zA-Z0-9_-]", "-");
        return sanitized.isBlank() ? "node" : sanitized;
    }

    private sealed interface Command permits Execute, Completed, Drain, Cancel {
    }

    private record Execute(NodeMessage message, CompletableFuture<NodeResult> reply) implements Command {
    }

    private record Completed(CompletableFuture<NodeResult> reply, NodeResult result,
                             Throwable error) implements Command {
    }

    private record Drain() implements Command {
    }

    private record Cancel() implements Command {
    }

    /**
     * One live node: its lifecycle, its actor, and the stage every caller of {@code stop} and
     * {@code cancel} observes.
     *
     * @param settled completes only after this engine has finished retiring the node -- out of the
     *                live registry and out of its domain's membership. Deliberately not
     *                {@code lifecycle.terminated()} itself, which is what {@code stop} used to
     *                return: a thread waiting on that future is released by the same completion that
     *                triggers the bookkeeping callbacks, so it could observe a node as stopped while
     *                its domain still listed it. That was invisible while membership was fixed at
     *                deployment startup, and becomes the difference between "zero orphans" being a
     *                guarantee and being a poll once a worker actor exists per invocation. The
     *                original outcome is preserved, so a stop whose {@code onStop} threw still
     *                reports the failure.
     */
    private record NodeEntry(NodeLifecycle lifecycle, ActorRef<Command> actor,
                             CompletableFuture<Void> settled) {
        private NodeEntry(NodeLifecycle lifecycle, ActorRef<Command> actor) {
            this(lifecycle, actor, new CompletableFuture<>());
        }

    }
}
