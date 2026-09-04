package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeRuntimeNatureProperty;
import ai.ravenroot.api.catalog.NodeRuntimeMaxConcurrencyProperty;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.EdgeTraversalEventData;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.provenance.SyntheticProvenance;
import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.ExecutionDomain;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.execution.NodeDirective;
import ai.ravenroot.api.execution.NodeFailurePayload;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.node.ToolCallContinuationAction;
import ai.ravenroot.api.node.ToolCallContinuationInput;
import ai.ravenroot.api.node.ToolCallContinuationResult;
import ai.ravenroot.api.persistence.JoinStore;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphCanonicalForm;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphExecutionPin;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.GraphVersionSnapshot;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import ai.ravenroot.core.security.nodepackage.DurableToolApprovalSuspension;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Framework-neutral graph execution semantics, including fan-out and fan-in. */
public final class GraphRunner implements AutoCloseable {
    private static final Runnable NO_TIMEOUT_RELINQUISHED_OBSERVER = () -> { };

    /**
     * How long {@link #close()} waits for a stop, and then for the cancellation it escalates to.
     *
     * <p>It matches the bound both supported adapters already apply inside
     * {@code ExecutionEngine.close()}, so a runner and the engine underneath it do not give up on the
     * same node at different times.</p>
     */
    public static final Duration DEFAULT_SHUTDOWN_BOUND = Duration.ofSeconds(10);

    /**
     * The topology this runner executes, and the <em>only</em> one it consults (ARC-02).
     *
     * <p>Materialised once at construction from the pinned snapshot. This runner deliberately does
     * <strong>not</strong> retain the {@link GraphManager} it was built from. It used to, and it
     * re-read {@code start()} on every traversal and {@code next()} on every dispatch, so a mutation
     * that reached the manager after construction changed the topology of a run already in flight:
     * dropping a node mid-run produced a <em>successful</em> execution that had silently stopped
     * early. A truncated traversal that reports success is indistinguishable to a caller from a
     * traversal that legitimately had nothing left to do.</p>
     *
     * <p>Holding the definition rather than the manager makes that structural rather than
     * defended-against: there is no live reference through which a later mutation could arrive.
     * ARC-05's {@code ReadOnlyStrategy} on {@link GraphManager#query} is a separate and complementary
     * control — it stops the mutation being expressed at all — but the isolation here does not depend
     * on it, and holds even for a mutation that arrives through the {@code getGraph()} escape no
     * strategy closes.</p>
     */
    private final GraphDefinition graph;

    /**
     * Immutable identity of exactly the definition {@link #graph} holds (ARC-02).
     *
     * <p>Additive, and deliberately <em>not</em> the {@code graphVersion} that flows through
     * {@link #execute} into events, logs and the acceptance response. That identifier is the raw-byte
     * submission hash and is already published; this is the canonical semantic hash plus the logical
     * {@code (graphId, versionId)} identity. They answer different questions — "which bytes were
     * submitted" versus "which definition is this, semantically" — and collapsing them would
     * retroactively change the meaning of every event already recorded.</p>
     */
    private final GraphExecutionPin pin;

    private final ExecutionEngine engine;
    /**
     * Where a node actor is created (ADR 0021 D1/D2). {@code engine::spawn} for every
     * pre-existing constructor -- the one-shot/playground path this runner has always served, and it
     * must keep spawning directly on the shared engine exactly as before. A deployment's runner
     * supplies its {@link ExecutionDomain#spawn(String, ai.ravenroot.api.execution.RavenNode)} instead,
     * so its nodes are the domain's own and {@code ExecutionDomain#close()} releases exactly them. The
     * returned {@code NodeRef} is a normal engine reference either way (see {@link ExecutionDomain}'s
     * own contract), so every other operation below keeps addressing {@link #engine} unchanged.
     */
    private final java.util.function.BiFunction<String, RavenNode, NodeRef> spawner;
    private final BehaviorRegistry behaviors;
    private final ExecutionMonitor monitor;
    private final ExecutionIdentitySource identitySource;
    private final Duration shutdownBound;

    /**
     * The immutable runtime definition of every graph node (ADR 0024 §1/§3).
     *
     * <p>This replaced {@code Map<String, NodeRef> nodeRefs}, and the distinction is central. A
     * {@code NodeRef} is runtime state; the logical node is a <em>definition</em>, and holding an
     * actor reference under a node id conflated the two so thoroughly that both of its costs looked
     * inherent. They were not: a five-hundred-node migration graph paid five hundred actors from the
     * moment its deployment started, whether or not a traversal ever reached them, and every arrival
     * at one logical node queued behind that node's one mailbox — so naturally parallel work such as
     * OCR was serialised by an accident of representation rather than by anything the author asked
     * for.
     *
     * <p>Composed once, here, and never again. That is not an optimisation: {@code NodeBehavior.create}
     * is where a behavior parses configuration, resolves credentials and loads plugin code, so
     * composing per invocation would move all of it onto the dispatch path. The definition is what is
     * shared between concurrent invocations; the actor is what is not.
     */
    private final Map<String, NodeRuntimeDefinition> runtimeDefinitions;

    /**
     * The nodes that really are resident, which today means every nature except {@code WORKER}.
     *
     * <p>Empty for an ordinary graph: startup actor count is
     * a function of the special nodes a graph declares, not of how many nodes it has.
     *
     * <h2>What this map is, stated correctly</h2>
     * <p>These are <strong>dispatch actors</strong>. A node whose nature is not {@code WORKER} is
     * still an ordinary vertex of the graph — a traversal can be routed to it by an edge like any
     * other — and this is where such an arrival is delivered. One actor per node, spawned at
     * construction, shared by every arrival, which preserves the resident behavior of those natures.
     *
     * <h2>Source actors and inbound resources are separate</h2>
     * <p>The spawn loop below creates an actor for every nature that is not {@code WORKER}, including
     * {@code SOURCE}. {@link DefaultGraphDeployment} separately drives a source's
     * <em>inbound resource</em> through {@code InboundSourceCapable}.
     *
     * <p>A {@code SOURCE} node has <strong>two independent runtime aspects</strong>, and neither is a
     * substitute for the other:
     *
     * <ul>
     *   <li><em>Its inbound resource</em> — the {@code InboundSource} that polls, subscribes or
     *       listens. Deployment-scoped, created and driven by {@code DefaultGraphDeployment}, and it
     *       is what must be ready before the deployment reports {@code READY}. It does not exist at
     *       all on the one-shot and playground paths, which never open a deployment.</li>
     *   <li><em>Its dispatch actor</em> — this entry. It exists on every path, deployment or not,
     *       because the node can be an edge target regardless of where its events come from.</li>
     * </ul>
     *
     * <p>Their lifecycles are already correctly ordered and that ordering is load-bearing: the runner
     * is built first, so the dispatch actor exists before any inbound event can arrive; and on stop,
     * {@code DefaultGraphDeployment} stops every source before closing this runner, so admission ends
     * before the actor that would serve it goes away. What was missing was any statement that the two
     * aspects are distinct, which is how one of them came to be described as the absence of the other.
     *
     * <p>{@code AUTHORITY} and {@code KEYED} cannot reach this map: {@link NodeRuntimeNatureValidator}
     * refuses them in the constructor, before it runs. That refusal remains — there is no
     * durable node-scoped lease or fencing token in the reactor, so a locally resident actor could not
     * honour either contract and would claim an authority it cannot verify it holds.
     */
    private final Map<String, NodeRef> residentRefs;

    /** The worker instances currently alive. Sized by work in flight, never by graph size. */
    private final WorkerInstanceRegistry workers;
    /** Demand-created actors whose identity and lifetime are one traversal plus one logical node. */
    private final TraversalInstanceRegistry traversalInstances;
    /** Independent per-node/per-traversal admission; never a pod/tenant/deployment hierarchy. */
    private final TraversalAdmissionRegistry traversalAdmission = new TraversalAdmissionRegistry();
    /** nodeId -> catalog key, for registry-known behaviors only. See {@link #resolveCatalogKeys}. */
    private final Map<String, String> nodeCatalogKeys;

    /** Validated fan-in configuration, one entry per join node. Fixed for this runner's lifetime. */
    private final Map<String, JoinSpec> joinSpecs;

    /**
     * Which join branches a node's failure invalidates, precomputed once from the topology.
     *
     * <p>Bounded by the graph, not by executions: one entry per node, each holding at most one pair
     * per join branch in the graph. Nothing is added at run time.</p>
     */
    private final Map<String, List<JoinBranchRef>> failureBranches;

    /**
     * For every node, the joins of which it is <em>directly</em> a branch — its distinct successors
     * that are joins, expressed as branch references.
     *
     * <p>Deliberately not a reachability closure, unlike {@link #failureBranches}. Liveness is
     * propagated node by node, so transitivity is already handled by the propagation itself; using a
     * closure here would let one dead node mark a branch that a different, live path still reaches.
     * </p>
     */
    private final Map<String, List<JoinBranchRef>> directJoinBranches;

    /** Distinct successors of each node. The unit of dispatch is a target node, not an edge. */
    private final Map<String, List<String>> distinctSuccessors;

    /** How many distinct predecessors each node has. Only a join can have more than one. */
    private final Map<String, Integer> distinctPredecessorCount;

    /** Nodes no execution can ever reach. Dead before the first node runs, on every traversal. */
    private final List<String> unreachableFromStart;

    private final JoinStore joinStore;
    private final boolean ownsJoinStore;
    private final Clock clock;
    private final Runnable timeoutRelinquishedObserver;

    /**
     * SEC-09's decision point. Defaults to {@link UnknownBehaviorPolicy#passThrough()}, while a
     * composition root may supply the fail-closed policy. See {@link UnknownBehaviorPolicy}.
     */
    private final UnknownBehaviorPolicy unknownBehaviors;

    /** Fixed at composition: a graph cannot promote itself from Test to production behavior. */
    private final ExecutionPolicy executionPolicy;

    /** Nodes that can receive a non-passthrough command under this runner's fixed policy. */
    private final Set<String> operationallyReachableNodes;

    /**
     * Nodes this runner composed as the unknown-behavior pass-through, decided here and never read
     * back from a node's own result (SEC-09).
     *
     * <p>The pass-through marker used to be an attribute the handler returned,
     * {@code ravenroot.defaultedNode}, and the runner believed whatever it found. That made the one
     * statement only the runtime is entitled to make — "this deployment does not have that behavior,
     * so the node did nothing" — writable by any registered behavior, including a third-party node
     * package, which CORE-06 makes an ordinary deployment concern. A behavior could run its real code
     * and then be reported as a no-op, in the {@code NODE_DEFAULTED} event, in the fallback flag on
     * {@code NODE_COMPLETED}, and in {@link GraphExecutionResult#defaultedNodes()}, which is public
     * API. The {@code ravenroot.} namespace is reserved from graph content for exactly this reason;
     * the reservation simply never covered what a node returns.</p>
     *
     * <p>Populated while nodes are composed, which is the moment the runner itself decides to use the
     * fallback handler, so the fact is structural rather than self-declared. The attribute is still
     * emitted for consumers that read it, but nothing here trusts it.</p>
     */
    private final Set<String> passThroughNodes = ConcurrentHashMap.newKeySet();

    /**
     * Nodes the graph's author switched off with {@code execution.bypass=true}. Fixed at
     * construction from the pinned definition, so it cannot change under a run in flight.
     *
     * <h2>Deliberately NOT routed through {@link #passThrough(GraphNode, String)}</h2>
     * <p>That method exists, it produces exactly the {@code NodeResult} an authored bypass needs, and
     * reusing it would have been one line. It is the wrong line, because its side effect is the
     * point: it adds the node to {@link #passThroughNodes}, which feeds
     * {@link GraphExecutionResult#defaultedNodes()} and the {@code NODE_DEFAULTED} event. Those report
     * one specific fact — <em>this deployment does not have that behavior, so the runtime substituted
     * a no-op</em>. An authored bypass is a different fact with the same shape: the behavior may be
     * perfectly well installed, and the author chose to skip it. Merging them would tell an operator
     * that their catalog is missing something when it is not, and would hide a genuinely missing
     * behavior inside a set the author can populate at will. The two sets stay disjoint, and this one
     * is a plain immutable set with no handler side effect.</p>
     *
     * <h2>And not routed through {@code NodeCommand.PASSTHROUGH} either</h2>
     * <p>The other tempting reuse — deliver {@code PASSTHROUGH} to this node instead of
     * {@code PROCESS} — would have been worse, because the command is <em>sticky</em> by design: the
     * routing in {@code operationallyReachableNodes} and in {@code run} propagates it to every
     * successor and no downstream edge can revoke it. That stickiness is a safety ceiling
     * {@code ExecutionPolicy.TEST_PASSTHROUGH} depends on and an authored bypass does not alter. An
     * authored bypass has to end at the node that declares it, so it cannot be expressed as a command
     * at all; it is a property of the node, read where the node is dispatched.</p>
     */
    private final Set<String> authoredBypassNodes;

    /** Coordinators for traversals currently in flight. One entry per live execution, removed on completion. */
    private final ConcurrentHashMap<UUID, JoinCoordinator> coordinators = new ConcurrentHashMap<>();

    /**
     * Traversals asked to stop; {@link #run} refuses to dispatch further hops for them.
     *
     * <h2>Why stopping the actors was not enough</h2>
     * <p>{@link #close()} stops the actors this runner is <em>currently</em> responsible for — the
     * residents, plus the worker instances outstanding at the instant it snapshots them. For a
     * traversal that keeps moving, that snapshot is not the traversal: {@link NodeRuntimeNature#WORKER}
     * is the default nature, so each hop acquires a fresh instance and the traversal's own
     * continuation spawns the next one after the snapshot was taken. A graph that loops therefore
     * outlived a cancel that reported success, because nothing on the dispatch path ever asked
     * whether the traversal it was continuing was still wanted. Measured, on a three-node self-loop
     * under {@code TEST_PASSTHROUGH}: {@code cancelTraversal} returned {@code true} and the loop was
     * still advancing thirty seconds later.</p>
     *
     * <p>This is the cooperative half ADR 0023 describes, and it is the half that has to exist here
     * rather than in the engine: only the runner knows which traversal a hop belongs to. A node is
     * shared by many traversals (ADR 0012), so no engine-level stop can express "this traversal and
     * not the others" — which is exactly what a cancel of one execution means.</p>
     *
     * <h2>Keyed by the traversal, not by whether a coordinator exists yet</h2>
     * <p>An entry is added for the id, without first asking {@link #coordinators} whether that id is
     * registered. That distinction closes a startup race: {@code cancelTraversal} previously tested
     * the map and returned {@code false}
     * without writing anything when the id was absent — and absent covers two very different cases:
     * a traversal that has finished, and a traversal that has been accepted and whose
     * {@code execute} has not reached its {@code putIfAbsent} yet. The second case is a real window,
     * as wide as two durable writes and a lease, during which the traversal is already listed by
     * {@code DefaultRavenrootApplication.liveExecutions} and therefore already cancellable. A
     * refusal that was not written there was a refusal nobody would ever read, while the caller was
     * told the traversal had been stopped. Written against the id instead, it is read by the first
     * hop — the start node's own dispatch goes through {@link #run}'s gate like every other.</p>
     *
     * <p>Bounded by traversals, not by time. {@link #release} drops the entry of every traversal that
     * runs here, so a mark set during the startup window is cleared when the traversal it refused
     * terminates. What no longer withdraws itself is a mark set for a traversal that had already
     * retired: nothing on this runner will call {@link #release} for it again. Both in-tree callers
     * keep that bounded — {@link #close()} only ever passes ids it read out of {@link #coordinators},
     * and {@code DefaultRavenrootApplication.cancelTraversal} reaches this at most once per
     * submission (an atomic removal from its own map guards the call), on that submission's own
     * single-traversal runner, which it hands to {@code close()} on the next line.</p>
     */
    private final Set<UUID> cancelledTraversals = ConcurrentHashMap.newKeySet();

    /**
     * Traversals asked to hold, and the gate each parked hop is waiting on.
     *
     * <h2>The recorded semantics, expressed as where the gate sits</h2>
     * <p>Pause means: the node in flight finishes, and then the traversal stops. That sentence is not
     * implemented by interrupting anything — it is implemented by putting the gate at the same place
     * the cancellation check sits, <em>between</em> two hops. Whatever is inside a behaviour when the
     * pause arrives runs to its own end and publishes its own completion, because nothing asks it to
     * stop; the hop it would have triggered is what waits. So "the in-flight node finishes" is a
     * property of the placement rather than a promise made in prose.</p>
     *
     * <p>Resume is its own transition, not the absence of a pause: completing the gate is what lets
     * the parked hop proceed, and a hop that arrives while the entry is present parks on the same
     * gate rather than starting. A pause that is never resumed therefore holds the traversal open
     * indefinitely — deliberately, because a paused execution an operator can still inspect is the
     * whole point — and {@link #cancelTraversal} is what ends one.</p>
     */
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> pausedTraversals = new ConcurrentHashMap<>();

    public GraphRunner(GraphManager graphManager, ExecutionEngine engine, BehaviorRegistry behaviors,
                       ExecutionMonitor monitor) {
        this(graphManager, engine, behaviors, monitor, ExecutionIdentitySource.randomUuids());
    }

    public GraphRunner(GraphManager graphManager, ExecutionEngine engine, BehaviorRegistry behaviors,
                       ExecutionMonitor monitor, ExecutionIdentitySource identitySource) {
        this(graphManager, engine, behaviors, monitor, identitySource, null, Clock.systemUTC(),
                DEFAULT_SHUTDOWN_BOUND);
    }

    /** Composes an inline runner under an explicit server-selected policy (ADR 0023). */
    public GraphRunner(GraphManager graphManager, ExecutionEngine engine, BehaviorRegistry behaviors,
                       ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                       ExecutionPolicy executionPolicy) {
        this(graphManager, engine, behaviors, monitor, identitySource, null, Clock.systemUTC(),
                DEFAULT_SHUTDOWN_BOUND, UnknownBehaviorPolicy.passThrough(), null, null, executionPolicy,
                NO_TIMEOUT_RELINQUISHED_OBSERVER);
    }

    /**
     * @param shutdownBound how long {@link #close()} waits for a stop, and again for the cancellation
     *                      it escalates to. It is a parameter for the same reason the SPI refuses to
     *                      build a timeout into {@code stop}: how long a caller is willing to wait for
     *                      its own nodes is that caller's policy, not the runner's.
     */
    public GraphRunner(GraphManager graphManager, ExecutionEngine engine, BehaviorRegistry behaviors,
                       ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                       Duration shutdownBound) {
        this(graphManager, engine, behaviors, monitor, identitySource, null, Clock.systemUTC(), shutdownBound);
    }

    /**
     * Composes a one-shot submission runner under an explicit {@link UnknownBehaviorPolicy}.
     *
     * <p>The overload {@code DefaultRavenrootApplication} uses once the policy stopped being a
     * constant. Deliberately mirrors the five-argument overload above rather than exposing the join
     * store, the clock and the shutdown bound to a caller that has no opinion about any of them: the
     * application selects a refusal policy, it does not select a join strategy.</p>
     */
    public GraphRunner(GraphManager graphManager, ExecutionEngine engine, BehaviorRegistry behaviors,
                       ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                       UnknownBehaviorPolicy unknownBehaviors) {
        this(graphManager, engine, behaviors, monitor, identitySource, null, Clock.systemUTC(),
                DEFAULT_SHUTDOWN_BOUND, unknownBehaviors);
    }

    /** Composes an inline runner with both independently selected execution policies. */
    public GraphRunner(GraphManager graphManager, ExecutionEngine engine, BehaviorRegistry behaviors,
                       ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                       UnknownBehaviorPolicy unknownBehaviors, ExecutionPolicy executionPolicy) {
        this(graphManager, engine, behaviors, monitor, identitySource, null, Clock.systemUTC(),
                DEFAULT_SHUTDOWN_BOUND, unknownBehaviors, null, null, executionPolicy,
                NO_TIMEOUT_RELINQUISHED_OBSERVER);
    }

    /**
     * Composes a runner over an explicit {@link JoinStore}.
     *
     * <p>Passing {@code null} creates a process-local {@link InMemoryJoinStore} owned by this runner
     * and closed with it, which reproduces the pre-CORE-03 behaviour exactly: joins are correlated
     * but not durable. A supplied store is <strong>not</strong> closed by {@link #close()}, because
     * the caller that built it may share it across runners and across restarts — which is the entire
     * point of supplying one.</p>
     */
    public GraphRunner(GraphManager graphManager, ExecutionEngine engine, BehaviorRegistry behaviors,
                       ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                       JoinStore joinStore, Clock clock) {
        this(graphManager, engine, behaviors, monitor, identitySource, joinStore, clock,
                DEFAULT_SHUTDOWN_BOUND);
    }

    /** Composes a runner whose unknown behaviors execute as the pass-through ADR 0003 describes. */
    public GraphRunner(GraphManager graphManager, ExecutionEngine engine, BehaviorRegistry behaviors,
                       ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                       JoinStore joinStore, Clock clock, Duration shutdownBound) {
        this(graphManager, engine, behaviors, monitor, identitySource, joinStore, clock, shutdownBound,
                UnknownBehaviorPolicy.passThrough());
    }

    /**
     * Test-only composition seam for observing the terminal timeout handoff without changing the
     * public runner contract. The observer runs only after shutdown has relinquished a join timeout
     * while holding that timeout's handoff lock.
     */
    GraphRunner(GraphManager graphManager, ExecutionEngine engine, BehaviorRegistry behaviors,
                ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                JoinStore joinStore, Clock clock, Duration shutdownBound,
                Runnable timeoutRelinquishedObserver) {
        this(graphManager, engine, behaviors, monitor, identitySource, joinStore, clock, shutdownBound,
                UnknownBehaviorPolicy.passThrough(), null, null, ExecutionPolicy.STANDARD,
                timeoutRelinquishedObserver);
    }

    /**
     * Composes a runner whose nodes are spawned into {@code domain} rather than directly on
     * {@code engine} (ADR 0021 D1/D2): the deployment-hosting path, additive next to every
     * constructor above, none of which is touched. {@code null} is accepted and behaves exactly like
     * the domain-less overloads -- the boundary is this parameter's presence, not a second code path.
     *
     * <p>The playground and every one-shot {@code startGraphMl} submission must keep spawning directly
     * on the shared engine exactly as before; only a long-lived deployment supplies a real domain here,
     * so that closing it releases exactly its own nodes and nothing this runner ever spawned outside
     * it.</p>
     */
    public GraphRunner(GraphManager graphManager, ExecutionEngine engine, ExecutionDomain domain,
                       BehaviorRegistry behaviors, ExecutionMonitor monitor,
                       ExecutionIdentitySource identitySource, Duration shutdownBound) {
        this(graphManager, engine, behaviors, monitor, identitySource, null, Clock.systemUTC(), shutdownBound,
                UnknownBehaviorPolicy.passThrough(), domain, null, ExecutionPolicy.STANDARD,
                NO_TIMEOUT_RELINQUISHED_OBSERVER);
    }

    /**
     * The one constructor that assigns state; every other overload delegates here.
     *
     * <p>Deliberately a single terminal constructor rather than several independent ones. CORE-03's
     * join store and CORE-04's shutdown bound are independent state, and two constructors each
     * defaulting the other's parameter create precisely the shape in which a later overload quietly
     * leaves one of them unset — a runner with no clock, or an unbounded {@code close()} — which
     * nothing observable fails on until it is in production. SEC-09's policy is another independently
     * defaulted parameter and belongs here for the same reason rather than beside it. {@code domain}
     * adds a fourth, defaulted to {@code null} by every overload above, so every existing call site
     * keeps spawning directly on {@code engine}.</p>
     *
     * @param unknownBehaviors SEC-09 policy. Every other overload passes
     *                         {@link UnknownBehaviorPolicy#passThrough()}, which is today's
     *                         behaviour, so no shipped path can select a refusing policy. It is a
     *                         parameter rather than a configuration lookup because core has no
     *                         configuration channel and inventing one here would decide, as a side
     *                         effect, where platform-wide configuration lives. See
     *                         {@link UnknownBehaviorPolicy}.
     * @param domain           {@code null} to spawn directly on {@code engine}; a real
     *                         {@link ExecutionDomain} to spawn this runner's nodes
     *                         into it instead, so the domain's own close releases exactly them.
     */
    public GraphRunner(GraphManager graphManager, ExecutionEngine engine, BehaviorRegistry behaviors,
                       ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                       JoinStore joinStore, Clock clock, Duration shutdownBound,
                       UnknownBehaviorPolicy unknownBehaviors) {
        this(graphManager, engine, behaviors, monitor, identitySource, joinStore, clock, shutdownBound,
                unknownBehaviors, null, null, ExecutionPolicy.STANDARD, NO_TIMEOUT_RELINQUISHED_OBSERVER);
    }

    /**
     * Composes a runner pinned to an explicit, already-canonicalised {@code snapshot} (ARC-02): the
     * lifecycle path, where the definition was validated, published and activated through
     * {@link ai.ravenroot.core.graph.GraphDefinitionLifecycle} rather than submitted inline.
     *
     * <p>Additive next to every constructor above, none of which changes: they pin to a snapshot
     * derived from the manager's own current definition, which is what a one-shot submission means.
     * Supplying a snapshot that does not describe {@code graphManager}'s topology is rejected here
     * rather than at first divergence, because a runner pinned to a definition the manager never held
     * would execute a graph nobody submitted.</p>
     */
    public GraphRunner(GraphManager graphManager, GraphVersionSnapshot snapshot, ExecutionEngine engine,
                       BehaviorRegistry behaviors, ExecutionMonitor monitor,
                       ExecutionIdentitySource identitySource, Duration shutdownBound) {
        this(graphManager, engine, behaviors, monitor, identitySource, null, Clock.systemUTC(), shutdownBound,
                UnknownBehaviorPolicy.passThrough(), null,
                java.util.Objects.requireNonNull(snapshot, "snapshot"), ExecutionPolicy.STANDARD,
                NO_TIMEOUT_RELINQUISHED_OBSERVER);
    }

    private GraphRunner(GraphManager graphManager, ExecutionEngine engine, BehaviorRegistry behaviors,
                       ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                       JoinStore joinStore, Clock clock, Duration shutdownBound,
                       UnknownBehaviorPolicy unknownBehaviors, ExecutionDomain domain,
                       GraphVersionSnapshot snapshot, ExecutionPolicy executionPolicy,
                       Runnable timeoutRelinquishedObserver) {
        this.unknownBehaviors = java.util.Objects.requireNonNull(unknownBehaviors, "unknownBehaviors");
        this.executionPolicy = java.util.Objects.requireNonNull(executionPolicy, "executionPolicy");
        // Read the manager exactly once, here, and never again. Everything below routes through the
        // materialised definition, so no later mutation of the manager can reach a run in flight.
        GraphDefinition submitted = graphManager.definition();
        GraphVersionSnapshot pinned = snapshot == null
                ? GraphVersionSnapshot.submission(submitted)
                : requireDescribes(snapshot, submitted);
        this.graph = pinned.definition();
        this.behaviors = java.util.Objects.requireNonNull(behaviors, "behaviors");
        validateAdmittedCommands(this.graph, this.behaviors, executionPolicy);
        this.operationallyReachableNodes = operationallyReachableNodes(this.graph, executionPolicy);
        this.pin = GraphExecutionPin.from(pinned);
        this.engine = engine;
        this.spawner = domain != null ? domain::spawn : engine::spawn;
        this.monitor = monitor;
        this.identitySource = java.util.Objects.requireNonNull(identitySource, "identitySource");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.ownsJoinStore = joinStore == null;
        this.joinStore = joinStore == null ? new InMemoryJoinStore() : joinStore;
        this.shutdownBound = java.util.Objects.requireNonNull(shutdownBound, "shutdownBound");
        this.timeoutRelinquishedObserver = java.util.Objects.requireNonNull(timeoutRelinquishedObserver,
                "timeoutRelinquishedObserver");
        if (shutdownBound.isNegative() || shutdownBound.isZero()) {
            throw new IllegalArgumentException("shutdownBound must be positive: " + shutdownBound);
        }
        // The whole graph is checked against the trusted catalog before a single actor is
        // spawned. Validating later would let a graph with a malformed operative property be
        // accepted, hashed, recorded and partly executed before the faulty node was reached, so the
        // failure would arrive after upstream nodes had already produced their effects.
        new BehaviorPropertySchema(behaviors).validate(graph);
        // ADR 0024 §2: the declared runtime nature is checked on the same fail-first path and
        // for a stronger reason -- a nature is a privilege, so a graph that claims one the catalog
        // withheld must be refused before anything it could affect exists. Deliberately a separate
        // validator rather than another rule inside BehaviorPropertySchema: that class returns early
        // for non-behavior nodes and for uncatalogued behaviors, and both early returns would be holes
        // here. This also runs before DefaultGraphDeployment starts any inbound source, because the
        // runner is built first.
        new NodeRuntimeNatureValidator(behaviors).validate(graph);
        // The authored bypass flag joins the same fail-first group, and is a separate validator
        // for the same reason the nature is -- BehaviorPropertySchema returns early on exactly the
        // node kinds this rule is about (START, END, ERROR), where the flag names a behaviour that
        // does not exist. Unlike the nature it does NOT refuse an uncatalogued behavior: a bypass
        // subtracts execution rather than granting a privilege, and a node the deployment cannot
        // provision is precisely the case this bypass supports. See NodeBypassValidator.
        new NodeBypassValidator().validate(graph);
        new NodeRuntimeConcurrencyValidator(behaviors).validate(graph);
        // Read once, here, from the same pinned definition every other precomputation reads. A run in
        // flight therefore cannot observe the flag changing, the way it cannot observe the topology
        // changing -- see the `graphManager.definition()` comment at the top of this constructor.
        this.authoredBypassNodes = graph.nodes().stream()
                .filter(node -> ai.ravenroot.api.catalog.NodeBypassProperty.isBypassed(node.properties()))
                .map(GraphNode::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        // CORE-03 joins the same fail-first group, for the same reason: a quorum larger than the
        // branch count is unsatisfiable for every input this graph will ever receive, so finding out
        // on the first execution that happens to reach the join is finding out too late.
        this.joinSpecs = JoinSpec.validate(graph);
        this.failureBranches = precomputeFailureBranches(graph, joinSpecs);
        this.directJoinBranches = precomputeDirectJoinBranches(joinSpecs);
        this.distinctSuccessors = precomputeDistinctSuccessors(graph);
        this.distinctPredecessorCount = precomputeDistinctPredecessorCount(graph);
        this.unreachableFromStart = precomputeUnreachable(graph, graph.start().id(), distinctSuccessors);
        // ADR 0024 §3's startup sequence: load the definitions, and do NOT spawn ordinary workers.
        // The loop below used to be `spawner.apply(...)` for every node in the graph; what it creates
        // now is a definition, and an actor only for a nature that is resident by contract.
        var definitions = new LinkedHashMap<String, NodeRuntimeDefinition>();
        var residents = new LinkedHashMap<String, NodeRef>();
        graph.nodes().forEach(node -> {
            NodeRuntimeNature nature = NodeRuntimeNatureProperty.effectiveNature(
                    node.kind() == NodeKind.BEHAVIOR ? behaviors.descriptor(node.behavior()).orElse(null) : null,
                    node.properties());
            NodeTypeDescriptor descriptor = node.kind() == NodeKind.BEHAVIOR
                    ? behaviors.descriptor(node.behavior()).orElse(null) : null;
            int maxConcurrency = NodeRuntimeMaxConcurrencyProperty.effectiveValue(descriptor, node.properties());
            RavenNode runtime = runtimeNode(node);
            definitions.put(node.id(), new NodeRuntimeDefinition(node, nature, maxConcurrency, runtime));
            if (nature != NodeRuntimeNature.WORKER && nature != NodeRuntimeNature.TRAVERSAL) {
                residents.put(node.id(), spawner.apply(node.id(), runtime));
            }
        });
        this.runtimeDefinitions = Map.copyOf(definitions);
        this.residentRefs = Map.copyOf(residents);
        this.workers = new WorkerInstanceRegistry(spawner, engine::stop);
        this.traversalInstances = new TraversalInstanceRegistry(spawner);
        this.nodeCatalogKeys = resolveCatalogKeys(graph, behaviors);
    }

    /**
     * The node-type dimension for observability (ADR 0021 D5), resolved once per graph.
     *
     * <p>This class is the only place holding both the {@link GraphNode} and the
     * {@link BehaviorRegistry}, which is the same reason {@code markSyntheticProvenance} resolves its
     * descriptor here and not in the monitor.
     *
     * <p><b>Only registry-known behaviors are included, and that is the cardinality control.</b> A
     * node's {@code behavior} string is graph-author text; an unregistered one is unbounded and is
     * exactly what the PLAT-01 rule forbids as a metric label. Resolving through
     * {@link BehaviorRegistry#descriptor(String)} makes the value domain precisely the installed
     * catalog, so an unknown behavior contributes no entry and its events carry no key rather than
     * carrying the author's string.
     */
    /**
     * The immutable definition identity this runner is pinned to (ARC-02).
     *
     * <p>Fixed at construction and never reassigned, so two calls always answer the same thing even
     * if the {@link GraphManager} this runner was built from has since changed or been closed.</p>
     */
    public GraphExecutionPin pin() {
        return pin;
    }

    /**
     * Fails closed when an explicit snapshot does not describe the topology the manager actually
     * holds. Compared by canonical hash rather than by object identity, so an equivalent definition
     * built independently is accepted and a divergent one is not.
     */
    private static GraphVersionSnapshot requireDescribes(GraphVersionSnapshot snapshot,
                                                         GraphDefinition submitted) {
        String actual = GraphCanonicalForm.sha256(submitted);
        if (!snapshot.canonicalHash().equals(actual)) {
            throw new IllegalArgumentException("Pinned snapshot " + snapshot.key()
                    + " does not describe the submitted graph: snapshot canonical hash "
                    + snapshot.canonicalHash() + " but the manager holds " + actual);
        }
        return snapshot;
    }

    static Map<String, String> resolveCatalogKeys(GraphDefinition graph, BehaviorRegistry behaviors) {
        var keys = new LinkedHashMap<String, String>();
        graph.nodes().forEach(node -> {
            if (node.kind() != NodeKind.BEHAVIOR || node.behavior() == null) {
                return;
            }
            behaviors.descriptor(node.behavior())
                    .ifPresent(descriptor -> keys.put(node.id(), descriptor.behavior()));
        });
        return Map.copyOf(keys);
    }

    public CompletionStage<GraphExecutionResult> execute(SecurityContext security, Object payload) {
        return execute(security, identitySource.nextProcessInstanceId(), identitySource.nextTraversalId(), payload,
                "embedded");
    }

    public CompletionStage<GraphExecutionResult> execute(SecurityContext security, UUID executionId, Object payload,
                                                         String graphVersion) {
        return execute(security, identitySource.nextProcessInstanceId(), executionId, payload, graphVersion);
    }

    /**
     * Runs one traversal on behalf of {@code security}.
     *
     * <p>The identity is a parameter rather than ambient state. A thread-local or scoped value would
     * not survive this class's own control flow: dispatch composes through {@code thenCompose}, the
     * engine completes on an actor dispatcher rather than the submitting thread, and fan-in resumes a
     * branch on whichever thread happened to arrive last.</p>
     */
    public CompletionStage<GraphExecutionResult> execute(SecurityContext security, UUID processInstanceId,
                                                         UUID traversalId, Object payload, String graphVersion) {
        return execute(security, processInstanceId, traversalId, payload, graphVersion, null, null);
    }

    /**
     * Runs one traversal on behalf of {@code security}, stamped with a deployment's identity
     * (ADR 0021 D5).
     *
     * @param deploymentId the owning deployment's identity, or {@code null} for a one-shot/playground
     *                     submission that never opened a deployment domain -- every overload above
     *                     resolves here with both new parameters {@code null}, so nothing that predates
     *                     deployments changes behaviour.
     * @param workloadId   the unit-of-work identity ADR 0021 D3's sharding key names, or {@code null}
     *                     outside a deployment. Fixed for this one traversal: passed once here and
     *                     carried unchanged by the {@code ExecutionIdentity} every event this call
     *                     produces is stamped from, which is what makes it a correlation key rather
     *                     than a value that merely happens to be present on some events.
     */
    public CompletionStage<GraphExecutionResult> execute(SecurityContext security, UUID processInstanceId,
                                                         UUID traversalId, Object payload, String graphVersion,
                                                         String deploymentId, String workloadId) {
        return execute(security, processInstanceId, traversalId, payload, graphVersion, deploymentId,
                workloadId, null);
    }

    /**
     * Runs one traversal, mirroring its lifecycle transitions through {@code recorder}.
     *
     * @param recorder holds this instance's lease and writes each transition under it before the
     *                 action it describes reaches the engine; {@code null} keeps the
     *                 in-memory-only behaviour for callers composed without an execution store
     */
    public CompletionStage<GraphExecutionResult> execute(SecurityContext security, UUID processInstanceId,
                                                         UUID traversalId, Object payload, String graphVersion,
                                                         String deploymentId, String workloadId,
                                                         ExecutionRecorder recorder) {
        java.util.Objects.requireNonNull(security, "security");
        if (processInstanceId == null) throw new IllegalArgumentException("processInstanceId cannot be null");
        if (traversalId == null) throw new IllegalArgumentException("traversalId cannot be null");
        var identity = new ExecutionMonitor.ExecutionIdentity(security, engine.id(), graphVersion, processInstanceId,
                traversalId, nodeCatalogKeys, deploymentId, workloadId);
        GraphNode start = graph.start();
        var state = new ExecutionState(processInstanceId, traversalId, start.id(), new BranchLiveness(start.id()),
                recorder, identity, identitySource, clock);
        var coordinator = new JoinCoordinator(joinStore, engine.scheduler(), monitor, identity, joinSpecs, clock,
                timeoutRelinquishedObserver);
        if (coordinators.putIfAbsent(traversalId, coordinator) != null) {
            throw new IllegalStateException("Traversal " + traversalId + " is already running on this runner");
        }
        monitor.executionStarted(identity);
        // Nodes no path can reach are dead before anything runs, and a join waiting on one of them
        // would wait for the life of the process. Reported alongside the first dispatch rather than
        // before it, so a join proven impossible fails the traversal instead of being a verdict with
        // no branch to carry it.
        // The traversal starts with an empty iteration context, so every join reads lap 0.
        // Nodes proven dead before anything runs are dead on that first lap by construction: nothing
        // has fired yet, so there is no later lap for them to be dead on.
        var opening = new ArrayList<>(state.liveness.reportDead(unreachableFromStart, coordinator,
                IterationContext.EMPTY));
        // The start node's dispatch was triggered by the traversal being accepted, and that is the
        // one event this journal holds whose own cause lies outside it — the authenticated request.
        NodeCommand initialCommand = executionPolicy == ExecutionPolicy.TEST_PASSTHROUGH
                ? NodeCommand.PASSTHROUGH : NodeCommand.PROCESS;
        opening.add(dispatch(start, null, payload, Map.of(), Set.of(), initialCommand,
                state.traversalAcceptedEventId(),
                state, identity, coordinator, IterationContext.EMPTY).toCompletableFuture());
        return allOrFirstFailure(opening)
                .handle((ignored, error) -> error)
                // Cleanup is sequenced *into* the returned stage rather than hung off a whenComplete.
                // A caller that has seen this stage complete must be able to assert that the join
                // records of this traversal are gone; with cleanup running concurrently that
                // assertion would be a race, and a leak would show up only as a flaky test.
                //
                // Termination is also where an abandoned branch is discovered. allOrFirstFailure
                // abandons the siblings of a branch that failed, and absorbIntoJoins can then turn
                // that failure into a success for the parent stage, leaving a branch parked at a
                // join with nobody waiting for it. Releasing that branch is what termination has to
                // do anyway, so it is the one place that sees it — and a traversal with an abandoned
                // branch is reported as that branch's join failure rather than as a success with no
                // result and an end node that never ran.
                .thenCompose(error -> release(traversalId, coordinator)
                        .handle((done, cleanupError) -> error != null ? error
                                : cleanupError != null ? cleanupError : coordinator.abandonedBranchFailure()))
                .thenCompose(error -> {
                    Throwable outcome = error;
                    if (unwrap(outcome) instanceof VerifiedToolApprovalSuspension verified) {
                        return CompletableFuture.<GraphExecutionResult>failedFuture(verified.signal());
                    }
                    if (outcome == null) {
                        try {
                            state.executionCompleted();
                        } catch (RuntimeException refused) {
                            // The PERS-01 aggregate is the second line of the same defence: it
                            // refuses to become COMPLETED while an invocation is still non-terminal,
                            // which is the abandoned-branch case caught one step earlier than a
                            // parked join arrival — the branch had not reached its join yet, it was
                            // still inside a node. Letting the refusal escape from here would end
                            // the traversal with neither a completed nor a failed event published
                            // and the aggregate left RUNNING, so the refusal is converted into the
                            // traversal's failure instead of thrown through it.
                            outcome = refused;
                        }
                    }
                    if (outcome == null) {
                        // Read once and passed to both the event and the result, so the log and
                        // the returned object cannot disagree about whether this run suffered a fault.
                        // Before this, only the result knew: the event said "execution completed" over
                        // a run in which every node that did anything had failed and been routed.
                        Set<String> handledFailures = state.handledFailureNodes();
                        monitor.executionCompleted(identity, handledFailures);
                        return CompletableFuture.completedFuture(new GraphExecutionResult(processInstanceId,
                                traversalId, state.resultPayload(), state.visitedNodes, state.defaultedNodes,
                                state.bypassedNodes, handledFailures, state.untakenEdges));
                    }
                    state.executionFailed();
                    monitor.executionFailed(identity, outcome);
                    return CompletableFuture.<GraphExecutionResult>failedFuture(outcome);
                });
    }

    /**
     * Continues from the exact node named by a stored approval on its already-created re-entry
     * traversal. The package action receives a fresh invocation identity and core owns all routing.
     */
    public CompletionStage<Boolean> executeFrom(SecurityContext security, UUID processInstanceId,
                                                UUID traversalId, String nodeId, String graphVersion,
                                                ExecutionRecorder recorder,
                                                java.util.function.Function<NodeMessage,
                                                        ToolCallContinuationInput> inputFactory,
                                                ToolCallContinuationAction action) {
        java.util.Objects.requireNonNull(action, "action");
        GraphNode node = graph.node(nodeId);
        var identity = new ExecutionMonitor.ExecutionIdentity(security, engine.id(), graphVersion,
                processInstanceId, traversalId, nodeCatalogKeys, null, null);
        var state = new ExecutionState(processInstanceId, traversalId, node.id(),
                new BranchLiveness(node.id()), recorder, identity, identitySource, clock,
                recorder.storedState());
        var coordinator = new JoinCoordinator(joinStore, engine.scheduler(), monitor, identity,
                joinSpecs, clock, timeoutRelinquishedObserver);
        if (coordinators.putIfAbsent(traversalId, coordinator) != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Traversal is already running on this runner"));
        }
        state.reentryStarted();
        monitor.executionStarted(identity);
        UUID invocationId = identitySource.nextNodeInvocationId();
        UUID attemptId = identitySource.nextNodeAttemptId();
        UUID startedEventId = state.nodeStarted(node.id(), Set.of(), invocationId, attemptId,
                NodeCommand.PROCESS, null);
        NodeMessage delivered = new NodeMessage(security, processInstanceId, traversalId,
                invocationId, attemptId, Set.of(), node.id(), null, Map.of(), NodeCommand.PROCESS);
        CompletionStage<ToolCallContinuationResult> resumed;
        try {
            resumed = java.util.Objects.requireNonNull(action.resume(inputFactory.apply(delivered)),
                    "continuation result stage");
        } catch (RuntimeException failure) {
            resumed = CompletableFuture.failedFuture(failure);
        }
        return resumed.handle((continued, failure) -> {
                    if (failure != null) {
                        Throwable cause = unwrap(failure);
                        if (cause instanceof DurableToolApprovalSuspension suspension
                                && state.acceptsApprovalSuspension(suspension.approvalId(), delivered)) {
                            throw new CompletionException(new VerifiedToolApprovalSuspension(suspension));
                        }
                        state.nodeFailed(invocationId, attemptId, startedEventId);
                        throw new CompletionException(cause);
                    }
                    UUID completedEventId = state.nodeCompleted(
                            invocationId, attemptId, startedEventId, false, false);
                    NodeResult result = markSyntheticProvenance(node, delivered, continued.nodeResult());
                    List<GraphEdge> next = graph.nextEdges(node.id(), result.outcome());
                    if (next.isEmpty() && !"continue".equals(result.outcome())) {
                        next = graph.nextEdges(node.id(), "continue");
                    }
                    return new java.util.AbstractMap.SimpleImmutableEntry<>(continued.effectSucceeded(),
                            dispatchSuccessors(next, node, result, delivered, completedEventId,
                                    state, identity, coordinator, IterationContext.EMPTY));
                })
                .thenCompose(pair -> pair.getValue().thenApply(ignored -> pair.getKey()))
                .handle((succeeded, failure) -> {
                    Throwable outcome = unwrap(failure);
                    try {
                        if (failure == null) state.executionCompleted();
                        else if (!(outcome instanceof VerifiedToolApprovalSuspension)) state.executionFailed();
                    } finally {
                        release(traversalId, coordinator).toCompletableFuture().join();
                    }
                    if (outcome instanceof VerifiedToolApprovalSuspension verified) {
                        throw new CompletionException(verified.signal());
                    }
                    if (failure != null) throw new CompletionException(outcome);
                    return succeeded;
                });
    }

    private CompletionStage<Void> release(UUID traversalId, JoinCoordinator coordinator) {
        coordinators.remove(traversalId, coordinator);
        cancelledTraversals.remove(traversalId);
        traversalAdmission.release(traversalId);
        // ON_CALLER: this runs on the traversal's own completion path, not on anyone's request
        // thread, and a gate found here has no hop waiting on it in the ordinary case -- the
        // traversal reached its end, so nothing is parked before its next hop.
        releasePauseGate(traversalId, GateRelease.ON_CALLER);
        CompletionStage<Void> joins = coordinator.terminate().exceptionally(ignored -> null);
        CompletionStage<Void> actors = releaseTraversalInstances(traversalId);
        return CompletableFuture.allOf(joins.toCompletableFuture(), actors.toCompletableFuture());
    }

    private CompletionStage<Void> releaseTraversalInstances(UUID traversalId) {
        List<TraversalInstanceRegistry.TraversalInstance> instances = traversalInstances.deregister(traversalId);
        List<Terminable> targets = instances.stream()
                .map(instance -> new Terminable(instance.identity().nodeId() + "#" + traversalId,
                        instance.ref()))
                .toList();
        if (targets.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            boolean terminated = awaitTermination(targets, engine::stop)
                    || awaitTermination(targets, engine::cancel);
            if (!terminated) {
                throw new IllegalStateException("Traversal nodes did not terminate within " + shutdownBound
                        + " of stop and cancellation: " + unterminated(targets));
            }
            traversalInstances.retired(instances);
        });
    }

    /**
     * Asks one traversal running on this runner to stop, cooperatively (ADR 0023).
     *
     * <p>Refuses every hop this traversal has not started yet: the node currently in flight is not
     * interrupted and the effects it has already issued are not undone — that is the concession
     * {@code cancel} has always declared — but nothing further is dispatched, so a traversal that
     * loops stops looping. This is what a stop of a <em>traversal</em> can mean, as opposed to a stop
     * of a node, which is all {@link ExecutionEngine} can express: a node is shared by many
     * traversals, so stopping it would stop the innocent ones too.</p>
     *
     * <p>It does not, on its own, end a traversal that has <em>stalled</em> — one parked inside a
     * behaviour that never returns never reaches another hop and so never reads this. That case is
     * the forced teardown's, and the two are complementary rather than alternatives: the caller
     * signals here and closes after. See {@code DefaultRavenrootApplication.cancelTraversal}, which
     * is the composition of both.</p>
     *
     * <h2>What this method does not ask, and why</h2>
     * <p>It does not ask whether the traversal is registered. It used to, and the answer was being
     * read as "is this traversal running here" when what it actually measured was "has
     * {@link #execute} reached its {@code putIfAbsent} yet" — a different question, and one that is
     * answered {@code no} for the whole startup window of a traversal that is on its way. Publishing
     * the refusal against the id is unconditional here precisely so that the answer no longer
     * depends on a race this method cannot win: a refusal written before the first hop is read by
     * the first hop, and a refusal written after it is read by the next one.</p>
     *
     * <p>The trade is deliberate and stated: this no longer distinguishes a traversal that never ran
     * here from one that is about to, so the caller must be the one that knows. It is —
     * {@code DefaultRavenrootApplication.cancelTraversal} reaches this line only after removing the
     * execution from its own {@code activeExecutions}, which is the bookkeeping that decides whether
     * there was anything to stop.</p>
     *
     * @return {@code true} if this call published the refusal; {@code false} if this traversal had
     *         already been asked to stop, in which case an earlier call published it
     */
    public boolean cancelTraversal(UUID traversalId) {
        return cancelTraversal(traversalId, GateRelease.OFF_CALLER);
    }

    /**
     * @param release where a hop parked on this traversal's gate resumes. {@link #close()} passes
     *                {@link GateRelease#ON_CALLER} because it is not a control endpoint and because
     *                its own ordering — every traversal released, then the actors stopped, then the
     *                join store closed — is what keeps the released hop from unwinding against a
     *                runner being dismantled underneath it.
     */
    private boolean cancelTraversal(UUID traversalId, GateRelease release) {
        java.util.Objects.requireNonNull(traversalId, "traversalId");
        if (!cancelledTraversals.add(traversalId)) {
            return false;
        }
        // A paused traversal has a hop waiting on its gate. Releasing it here is what makes cancel
        // reach a paused execution at all: the released hop re-enters run(), reads the mark set
        // above and ends the traversal. Without this, cancelling a paused execution would report
        // success and leave it parked for the life of the process -- the same shape of false
        // false success this cancellation path prevents.
        //
        // The refusal is published on THIS thread, above, and only the hop's resumption is
        // handed elsewhere. So what this method's `true` asserts is unchanged -- when it returns,
        // no hop that has not already started can start -- while the failure propagation the
        // released hop performs, which terminates the coordinator and notifies the monitor
        // synchronously, is no longer charged to whoever called cancel.
        releasePauseGate(traversalId, release);
        return true;
    }

    /**
     * Asks one traversal on this runner to hold before its next hop (ADR 0023).
     *
     * <h2>What this method does not ask, and why</h2>
     * <p>It does not ask whether the traversal is registered. It used to — {@link #cancelTraversal}
     * used to as well, and it was removed there for a reason that applies here word for word: the
     * coordinator map is written inside {@link #execute}, and the answer it gives before that write
     * is "{@code execute} has not reached its {@code putIfAbsent} yet", not "this traversal is not
     * running here". The two differ for the whole startup window of a traversal that is on its way,
     * and inside that window {@code DefaultRavenrootApplication.startGraphMl} has already published
     * the execution to {@code activeExecutions} — so the traversal is listed live, is pausable by an
     * operator who never submitted it, and used to be answered {@code false}. One layer up,
     * {@code AuthorizedRavenrootApplication.pauseExecution} reads {@code false} plus "still live" as
     * {@code ALREADY_PAUSED}, whose note says the traversal was already paused and that the request
     * changed nothing. Neither half was true: nothing was holding it, and nothing changed <em>because
     * no gate had been installed</em>.</p>
     *
     * <p>Installing the gate unconditionally is what makes the answer stop depending on a race this
     * method cannot win. It is sufficient because {@link #run} reads {@code pausedTraversals} <em>by
     * traversal id</em> and does so on every hop including the start node's own dispatch, so a gate
     * written before the first hop is read by the first hop, and one written after it is read by the
     * next one. That is the same argument used for the refusal, and it is <em>not</em> the same
     * repair: a refusal only has to be seen, whereas a hold has to be releasable, and
     * {@link #resumeTraversal} opens this gate by the same id from the same map.</p>
     *
     * <h2>What is given up, and who covers it</h2>
     * <p>This no longer distinguishes a traversal that never ran here from one that is about to, so
     * the caller must be the one that knows — and it is, exactly as for cancel:
     * {@code DefaultRavenrootApplication.pauseTraversal} reaches this line only for an id present in
     * its own {@code activeExecutions}, which is the bookkeeping {@code liveExecutions} reads. A
     * caller composing this runner directly gets a gate for any id it names, and pays for it with an
     * entry that lives until {@link #close()} — see below.</p>
     *
     * <p>The gate's lifetime must be bounded. Re-reading the coordinator map after the write cannot
     * safely decide whether to remove the gate: inside the startup window, a traversal that finished
     * during this call is indistinguishable from one that is about to start. The safe discriminator
     * is a shorter lifetime: {@code close()} drains {@code pausedTraversals}, so no gate outlives the
     * runner that owns it. In the application this is a tight bound because
     * {@code startGraphMl} builds one runner per submission and closes it on completion, on
     * cancellation and in its startup-failure catch.</p>
     *
     * <p>A pause that lands after that close installs a gate on a runner nobody holds a reference to
     * any more — {@code activeExecutions} released the only one before closing it — so it is
     * collected with the runner rather than retained by it.</p>
     *
     * <p>The cancellation check below is kept and is not the one that was removed: it reads the
     * traversal's <em>id</em>, not a map's membership, so it does not go false during the startup
     * window. It refuses to hold a traversal that has already been asked to stop, which would
     * otherwise report a hold over an execution that is ending.</p>
     *
     * @return {@code true} if this call installed the hold; {@code false} if this traversal was
     *         already holding, or had already been asked to stop
     */
    public boolean pauseTraversal(UUID traversalId) {
        java.util.Objects.requireNonNull(traversalId, "traversalId");
        if (cancelledTraversals.contains(traversalId)) {
            return false;
        }
        return pausedTraversals.putIfAbsent(traversalId, new CompletableFuture<>()) == null;
    }

    /**
     * Releases a paused traversal, letting the hop it parked proceed.
     *
     * <p>The released hop's journal write may commit after this method returns. Consequently,
     * {@code ResumeResult.resumed}'s note to the operator — "running again, continuing from the node
     * it was holding" — need not already be durable when the response is read. The note stays true,
     * because the transition it describes is decided by the gate's removal below, on this
     * thread; what is gone is only the guarantee that the next hop's first write had already landed.
     * That is the required property, not a side effect.</p>
     *
     * @return {@code true} if this traversal was paused here and is no longer paused: the gate is
     *         removed, so no further hop parks on it, and the hop that was waiting has been handed a
     *         thread of its own. It deliberately does <em>not</em> claim that hop has run — see
     *         {@link #releasePauseGate} for why a control call must not wait for it
     */
    public boolean resumeTraversal(UUID traversalId) {
        java.util.Objects.requireNonNull(traversalId, "traversalId");
        return releasePauseGate(traversalId, GateRelease.OFF_CALLER);
    }

    /**
     * Where the hop a pause gate parked resumes.
     *
     * <p>The distinction is about the caller, not about the work: the work is the same either way.
     * A control endpoint's thread is a request thread and must not be charged for graph work of a
     * duration that belongs to the execution store; the runner's own lifecycle paths are neither
     * request threads nor in a hurry, and one of them — {@link #close()} — is written around the
     * released hop having unwound before the teardown proceeds.</p>
     */
    private enum GateRelease {
        /** The released hop runs on the thread that released the gate, before it returns. */
        ON_CALLER,
        /** The released hop runs on a virtual thread of its own; the releasing thread returns. */
        OFF_CALLER
    }

    /**
     * Removes the gate and completes it, in that order.
     *
     * <p>The order is what makes a resume racing a second resume settle on one winner: the map's own
     * removal decides, and only the caller that removed a non-null gate completes it, so the stage a
     * parked hop is waiting on is completed exactly once by exactly one caller. That is unaffected by
     * where the completion happens — the removal, which is the decision, stays on the caller's
     * thread under both {@link GateRelease} values.</p>
     *
     * <h2>Why {@code OFF_CALLER} exists</h2>
     * <p>A hop parks by registering {@code thenCompose} on this gate, and a dependent already
     * registered when a future completes runs on the thread that completes it. The control paths that
     * release a gate — {@link #resumeTraversal} and {@link #cancelTraversal} — are reached from an
     * HTTP or CLI request thread, so completing the gate there ran graph work on that thread: on
     * resume, the prologue of the next hop, which mints the invocation and attempt ids and performs
     * {@code ExecutionState.nodeStarted}'s durable write under the recorder's lease; on cancel, the
     * failure propagation, which terminates the coordinator — possibly touching the store — and
     * notifies the {@link ExecutionMonitor}, whose delivery to listeners is synchronous by contract.
     * The rest of the traversal is <em>not</em> among them, and the scope matters: {@code engine.send}
     * is asynchronous, so on resume the request thread ran one hop's prologue and then handed off. A
     * short write is still the wrong thread's write, because its duration belongs to the store and
     * not to the request — which is the very reason
     * {@code DefaultRavenrootApplication.cancelTraversal} already hands its teardown to a virtual
     * thread.</p>
     *
     * <p>{@code pauseTraversal}'s former losing branch was a third such path. The branch was removed
     * rather than the routing: the pause installs its gate unconditionally now, so it has
     * no losing branch that has to take one back. What it released there is released by
     * {@link #close()} instead, and that is a shutdown rather than a request — see the drain there.</p>
     *
     * <h2>A virtual thread rather than an executor</h2>
     * <p>Two other shapes were available and are rejected for reasons worth keeping, because the
     * cheapest of them is the one a later reader will reach for.</p>
     * <ul>
     *   <li>{@code gate.completeAsync(() -> null)} — the default asynchronous executor is the common
     *       {@link java.util.concurrent.ForkJoinPool} <em>whenever that pool's parallelism is greater
     *       than one</em>, and a thread-per-task executor below that. In the case
     *       that matters on any real deployment the pool is sized to the machine's cores and shared
     *       with every other user of it in the JVM. The work released here blocks on store I/O, so a
     *       slow store would starve the common pool exactly when it is slowest — the same
     *       hostage-to-one-dependency the move exists to remove, relocated rather than removed.</li>
     *   <li>A dedicated executor owned by this runner — it would have to be sized, closed, and
     *       waited on, adding a fourth bound to a {@link #close()} that already has three; and a
     *       saturated one re-serialises precisely the control operations that must remain independent,
     *       failing worst under the load that motivates it.</li>
     * </ul>
     * <p>A virtual thread has nothing to size, own or shut down, and no queue in which one slow
     * release delays the next. Its carrier cost differs across JDK versions because JEP 491 changes
     * monitor pinning, as traced below.</p>
     *
     * <p>The chain, at the source. {@code ExecutionRecorder.record} is {@code synchronized} and waits
     * on the write with {@code join()} inside that monitor. But the write itself does not run on the
     * waiting thread: {@code SqliteExecutionStore.apply} goes through its {@code async} helper, which
     * is {@code supplyAsync(operation, worker)}, and {@code worker} is a single-thread executor whose
     * thread is an ordinary <em>platform</em> thread, {@code ravenroot-sqlite-<file>}. So the store's
     * JNI frame — {@code sqlite-jdbc} does ship {@code org.sqlite.core.NativeDB} and a native library
     * per platform — is <strong>never on the hop's virtual thread</strong>. That thread only waits.</p>
     *
     * <p>From which, by version:</p>
     * <ul>
     *   <li><strong>JDK 21–23:</strong> the virtual thread parks in {@code join()} <em>inside a
     *       monitor</em>, so its carrier is pinned for the duration of a slow write. Real, and it is
     *       the monitor that costs it — not the native frame.</li>
     *   <li><strong>JDK 24+, so this project's supported ceiling of LTS 25:</strong> JEP 491 lifts
     *       monitor pinning, that park becomes an ordinary one, and the carrier <em>unmounts</em>. No
     *       carrier is charged by this path at all. The native frame still occupies
     *       {@code ravenroot-sqlite-<file>} — but that is the store's own dedicated platform thread,
     *       not a carrier, and it is occupied identically on every version and for every caller.</li>
     * </ul>
     *
     * <p>What the control endpoint gives up therefore differs by version: on 21–23 it trades its own
     * thread's wait for a scheduler carrier's, one per control call that finds a parked hop and never
     * one per hop; on 24+ it gives
     * up only the wait, and nothing is charged anywhere else. No claim is made here about the
     * scheduler growing to compensate for a pinned carrier; the argument does not need it, because
     * the required property is delivered by {@code Thread.start()} returning — the request
     * thread is free before the scheduler does anything at all.</p>
     */
    private boolean releasePauseGate(UUID traversalId, GateRelease release) {
        CompletableFuture<Void> gate = pausedTraversals.remove(traversalId);
        if (gate == null) {
            return false;
        }
        if (release == GateRelease.ON_CALLER) {
            gate.complete(null);
            return true;
        }
        // Named so that a thread dump or a profile attributes this work to the traversal it belongs
        // to, which is the attribution the test establishes.
        Thread.ofVirtual().name("ravenroot-gate-release-" + traversalId).start(() -> gate.complete(null));
        return true;
    }

    /**
     * @param causedBy the identity of the journalled event that triggered this dispatch, or
     *                 {@code null} when nothing is journalling. It is carried rather than derived,
     *                 because the trigger is known here and nowhere downstream — see the fan-in note
     *                 inside.
     */
    private CompletionStage<Void> dispatch(GraphNode node, String sourceNodeId, Object payload,
                                           Map<String, Object> attributes, Set<UUID> parentInvocationIds,
                                           NodeCommand command, UUID causedBy, ExecutionState state,
                                           ExecutionMonitor.ExecutionIdentity identity,
                                           JoinCoordinator coordinator, IterationContext iteration) {
        if (coordinator.isJoin(node.id())) {
            // The lap this delivery belongs to, read from the runtime's own scope. Deliberately NOT
            // from `payload` or `attributes`: those come back from NodeResult, which is user code, and
            // a behaviour able to choose its join's iteration could decide which arrivals correlate
            // together. Deliberately not from invocationId or attemptId either — see IterationContext
            // for why that keeps a retry and a second lap distinguishable under either answer to
            // PERS-04.
            int lap = iteration.lapOf(node.id());
            var arrival = new JoinArrival(BranchId.of(java.util.Objects.requireNonNull(sourceNodeId,
                    "a fan-in node can only be reached from a predecessor")).atLap(lap), payload, attributes,
                    parentInvocationIds, command, iteration);
            return coordinator.arrive(node.id(), arrival).thenCompose(decision -> switch (decision) {
                // Not the branch that met the quorum: this branch stops here and the traversal
                // continues on the branch that did. Never an error — see JoinDecision.Discarded.
                case JoinDecision.Discarded ignored -> CompletableFuture.<Void>completedFuture(null);
                case JoinDecision.Wait ignored -> CompletableFuture.<Void>completedFuture(null);
                case JoinDecision.Failed failed -> CompletableFuture.<Void>failedFuture(failed.failure());
                case JoinDecision.Proceed proceed -> {
                    JoinArrival merged = merge(proceed.arrivals());
                    // THE JOIN-SATISFYING ARRIVAL. `causedBy` is this branch's own trigger, and this
                    // branch is by construction the one that crossed the threshold: exactly one
                    // arrival per join ever receives Proceed, decided by the store's compare-and-set.
                    // So the cause is carried through unchanged, and deliberately NOT taken from
                    // `merged` or from `proceed.arrivals()`. Those hold every contributor, and
                    // picking one of them — the first, the earliest, the lowest branch id — would
                    // name a branch that did not trigger this dispatch on any run where it was not
                    // also the last to arrive. The other contributors are necessary conditions, not
                    // triggers, and they are already durably recorded as structure: the join record
                    // and each branch's own node events. Every ordinary-node assertion stays green
                    // under the wrong choice here, which is why it is stated rather than inferred.
                    //
                    // The token downstream is the merged context of the contributors with THIS join
                    // advanced past the lap it just fired. Merged rather than taken from the
                    // satisfying branch: a sibling that passed through an inner join more times knows
                    // something this branch does not, and dropping it would put a later message back
                    // into an earlier bucket of that inner join.
                    yield run(node, merged.payload(), merged.attributes(), merged.parentInvocationIds(),
                            merged.command(), causedBy, state, identity, coordinator,
                            merged.context().firing(node.id(), lap));
                }
            });
        }
        // An ordinary node inherits the context unchanged: it is not a join, so nothing about it
        // advances any lap.
        return run(node, payload, attributes, parentInvocationIds, command, causedBy, state, identity,
                coordinator, iteration);
    }

    /**
     * How many runtime instances of {@code nodeId}'s actor are alive right now — the number the elastic
     * view shows.
     *
     * <p>The two branches cover the two runtime natures, and neither is an approximation.
     *
     * <ul>
     *   <li><b>{@link NodeRuntimeNature#WORKER}, the default:</b> read from the instance registry, which
     *       holds one entry per invocation being served. Ten concurrent invocations are ten instances,
     *       and a scarce thread pool makes them wait rather than making them fewer — the count is of
     *       actors that exist, not of actors currently holding a thread.</li>
     *   <li><b>Every resident nature:</b> exactly 1, and <em>by construction rather than by
     *       observation</em>. The constructor spawns one actor per non-{@code WORKER} node and puts it in
     *       {@link #residentRefs}, keyed by node id, which is a {@code Map} and therefore holds one value
     *       per key; {@link #run} then sends every arrival to that one reference. So there is no traffic
     *       pattern that can make this 2, which is what makes "a singleton stays 1 however many times you
     *       pass through it" a property of the code and not a hope about it.</li>
     * </ul>
     *
     * <p>Returns 0 for a node with neither, which is unreachable for a node this runner is dispatching —
     * every node got a definition at construction — and is the honest answer rather than a guess if the
     * two maps ever disagree.
     */
    private int liveInstances(String nodeId, NodeRuntimeNature nature) {
        if (nature == NodeRuntimeNature.WORKER) {
            return workers.liveCount(nodeId);
        }
        if (nature == NodeRuntimeNature.TRAVERSAL) {
            return traversalInstances.liveCount(nodeId);
        }
        return residentRefs.containsKey(nodeId) ? 1 : 0;
    }

    private CompletionStage<Void> run(GraphNode node, Object payload, Map<String, Object> attributes,
                                      Set<UUID> parentInvocationIds, NodeCommand command, UUID causedBy,
                                      ExecutionState state,
                                      ExecutionMonitor.ExecutionIdentity identity, JoinCoordinator coordinator,
                                      IterationContext iteration) {
        // The one gate every hop passes through, including the start node's own dispatch.
        //
        // It is placed BEFORE the invocation and attempt identifiers are minted and before
        // state.nodeStarted, so a refused hop leaves no half-recorded invocation in the PERS-01
        // aggregate: the traversal's record ends at the last node that actually ran, which is what a
        // reader reconstructing what happened needs it to say. A gate placed after the send instead
        // would be a gate on the node that already started.
        if (cancelledTraversals.contains(identity.traversalId())) {
            return CompletableFuture.failedFuture(new TraversalCancelledException(identity.traversalId(), node.id()));
        }
        // Pause parks the hop here, on the far side of the cancellation check and on the near
        // side of everything else. Re-entering run() on release rather than falling through is
        // deliberate: the traversal may have been cancelled, or paused again, while it was parked,
        // and both are read by the checks at the top rather than by a second copy of them here.
        CompletableFuture<Void> gate = pausedTraversals.get(identity.traversalId());
        if (gate != null) {
            return gate.thenCompose(released -> run(node, payload, attributes, parentInvocationIds, command,
                    causedBy, state, identity, coordinator, iteration));
        }
        NodeRuntimeDefinition definition = runtimeDefinitions.get(node.id());
        var admissionKey = new TraversalAdmissionRegistry.Key(identity.security().tenantId(),
                identity.deploymentId(), identity.graphVersion(), identity.traversalId(), node.id());
        return traversalAdmission.acquire(admissionKey, definition.maxConcurrency())
                .thenCompose(lease -> runAdmitted(node, payload, attributes, parentInvocationIds, command,
                        causedBy, state, identity, coordinator, iteration, definition, lease)
                        .whenComplete((ignored, error) -> lease.close()));
    }

    private CompletionStage<Void> runAdmitted(GraphNode node, Object payload, Map<String, Object> attributes,
                                               Set<UUID> parentInvocationIds, NodeCommand command, UUID causedBy,
                                               ExecutionState state,
                                               ExecutionMonitor.ExecutionIdentity identity,
                                               JoinCoordinator coordinator, IterationContext iteration,
                                               NodeRuntimeDefinition definition,
                                               TraversalAdmissionRegistry.Lease admissionLease) {
        // A queued admission may have been cancelled before its permit became available.
        if (cancelledTraversals.contains(identity.traversalId())) {
            admissionLease.close();
            return CompletableFuture.failedFuture(
                    new TraversalCancelledException(identity.traversalId(), node.id()));
        }
        UUID invocationId = identitySource.nextNodeInvocationId();
        UUID attemptId = identitySource.nextNodeAttemptId();
        UUID startedEventId = state.nodeStarted(node.id(), parentInvocationIds, invocationId, attemptId, command,
                causedBy);
        // Held across the two stages below rather than recomputed: the completion event's identity is
        // minted where the completion is recorded, and read again where the successors are dispatched.
        // The write happens-before the read through the stage boundary, so no further synchronisation.
        var completedEventId = new java.util.concurrent.atomic.AtomicReference<UUID>();
        // identity.security() is the ingress context. It is not read from `attributes`, which the
        // previous node controlled, nor from the node's GraphML properties.
        NodeMessage delivered = new NodeMessage(identity.security(), identity.processInstanceId(),
                identity.traversalId(), invocationId, attemptId, parentInvocationIds, node.id(), payload, attributes,
                command);
        // ADR 0024 §3's dispatch sequence, and the one place demand-driven workers change each message:
        // create an instance for THIS invocation, deliver exactly one message, release. Two traversals
        // arriving here at the same time for the same node get two actors and run at the same time,
        // instead of the second waiting behind the first in one mailbox. Creation is never refused for
        // being "too many": the only ceiling left is the actor model's own thread availability,
        // which is not a decision this method makes -- a scarce pool simply runs fewer instances at
        // once and queues the rest, the same as it would for any other actor.
        //
        // Creation failure -- an engine that will not spawn -- is deliberately routed into the same
        // stage as a node failure rather than thrown from here. That is what settles the persisted
        // lifecycle truth: the handler below records the attempt and the invocation as FAILED
        // unconditionally, publishes NODE_FAILED, and applies the node's declared failure route
        // if it has one. Throwing instead would leave the invocation RUNNING in the aggregate forever,
        // which is the one outcome an execution store must never be left in.
        WorkerInstanceRegistry.WorkerInstance acquired = null;
        TraversalInstanceRegistry.TraversalInstance traversalInstance = null;
        CompletionStage<NodeResult> attempt;
        RuntimeException creationFailed = null;
        try {
            if (definition.nature() == NodeRuntimeNature.WORKER) {
                acquired = workers.acquire(workerIdentity(identity, node.id(), invocationId, attemptId),
                        definition.runtime());
            } else if (definition.nature() == NodeRuntimeNature.TRAVERSAL) {
                traversalInstance = traversalInstances.acquire(traversalIdentity(identity, node.id()),
                        definition.runtime());
            }
        } catch (RuntimeException creationFailure) {
            creationFailed = creationFailure;
        }
        // NODE_STARTED is published HERE -- after the instance exists, before the message is sent.
        //
        // The order is required, not incidental. Publishing before the acquire means the event could
        // only ever carry a count taken when this invocation's own instance did not exist
        // yet, so the number would be short by one on every start and the view would show a node running
        // nothing at the instant it began. Published after the send it could race the completion handler
        // and report a node as started once it had already finished.
        //
        // It stays before the send, and outside the failure branch, for a second reason: a spawn that
        // fails still produces NODE_FAILED below, and an event stream in which a node fails without ever
        // having started is not something a consumer can reconcile. So the start is published on both
        // paths -- with the count that is actually true on each, which for a failed spawn is whatever
        // OTHER instances of this node are alive, not this one.
        monitor.nodeStarted(identity, node.id(), invocationId, attemptId,
                liveInstances(node.id(), definition.nature()));
        try {
            if (creationFailed != null) {
                throw creationFailed;
            }
            if (definition.nature() == NodeRuntimeNature.WORKER) {
                attempt = engine.send(acquired.ref(), delivered);
            } else if (definition.nature() == NodeRuntimeNature.TRAVERSAL) {
                attempt = engine.send(traversalInstance.ref(), delivered);
            } else {
                // A nature that is resident by contract keeps one actor, addressed by node id and
                // shared by every arrival. Making those lifecycles demand-driven here would silently
                // change their residency contract.
                attempt = engine.send(residentRefs.get(node.id()), delivered);
            }
        } catch (RuntimeException creationFailure) {
            attempt = CompletableFuture.failedFuture(creationFailure);
        }
        WorkerInstanceRegistry.WorkerInstance instance = acquired;
        return attempt
                .handle((result, error) -> {
                    // Capacity protects this node attempt, not its downstream subtree. Releasing
                    // here is what lets a cycle re-enter a maxConcurrency=1 node without deadlock.
                    admissionLease.close();
                    // Released as soon as this node's own attempt settles, NOT when the traversal
                    // finishes: releasing later would keep every ancestor's actor alive for the whole
                    // downstream subtree, making live instances a function of graph depth and
                    // reintroducing proportional allocation. The traversal still waits
                    // for the release at the end of this method -- see the tail below.
                    if (instance != null) {
                        workers.release(instance);
                    }
                    if (error != null) {
                        Throwable failure = unwrap(error);
                        if (failure instanceof DurableToolApprovalSuspension suspension
                                && state.acceptsApprovalSuspension(suspension.approvalId(), delivered)) {
                            throw new CompletionException(new VerifiedToolApprovalSuspension(suspension));
                        }
                        // Recording stays unconditional: the attempt and invocation are FAILED
                        // whether or not the author wired anything to route from here. Only
                        // what happens *after* this point is new.
                        UUID failedEventId = state.nodeFailed(invocationId, attemptId, startedEventId);
                        // Measured after the release above, so the count no longer includes the instance
                        // that just finished -- what is reported is what is still carrying work.
                        monitor.nodeFailed(identity, node.id(), invocationId, attemptId, error,
                                liveInstances(node.id(), definition.nature()));
                        // Asked of the definition, never recomputed here. A failure route
                        // may be declared by the author or defaulted from the target being an ERROR
                        // node, and the same resolution has to decide BOTH which edges fire on a
                        // failure (this call) and which edges must NOT fire on a success (the
                        // nextEdges call below) -- a defaulted route carries DEFAULT_OUTCOME, so
                        // leaving the second half out would fire the error terminal on a clean run.
                        // Both halves therefore live in GraphDefinition.failureRouted, which is also
                        // what makes the answer available to anything that materialises a definition
                        // without going through this class. Today this runner is the only code in main
                        // that ACTS on the answer: GraphDefinition.next(String,String) is the other
                        // caller of nextEdges, and it has no callers of its own. The inspector is
                        // the reader that must not have to re-derive the rule and reach a different
                        // conclusion.
                        List<GraphEdge> failureRoute = graph.failureEdges(node.id());
                        if (failureRoute.isEmpty()) {
                            // No failure route at all: exactly today's behaviour. The traversal
                            // stops here, but not at END — no end node runs, no result payload is
                            // set, and this rethrow is what makes the read report failure.
                            throw new CompletionException(error);
                        }
                        completedEventId.set(failedEventId);
                        // The failure branch's payload is sanitized failure metadata plus the input
                        // the failing node received -- never a fabricated "result" of a node
                        // that produced none.
                        Object failurePayload = NodeFailurePayload.of(node.id(), error, delivered.payload());
                        var routedAttributes = new LinkedHashMap<String, Object>(delivered.attributes());
                        // The provenance marker, if any, described the payload that failed to
                        // produce; it must not survive onto failure metadata that is not that
                        // payload, for the same reason markSyntheticProvenance drops it below when a
                        // node substitutes its own payload.
                        routedAttributes.keySet().removeIf(SyntheticProvenance::isProvenanceKey);
                        return NodeOutcome.failed(
                                new NodeResult(GraphEdge.DEFAULT_OUTCOME, failurePayload, routedAttributes),
                                failureRoute);
                    }
                    // Two bypasses, one flag: the traversal imposed one (sticky command / test mode),
                    // or the author switched this single node off. Everything downstream of
                    // this line -- the journal row, bypassedNodes, the event type -- is the same fact
                    // either way, "this node completed without executing", so they share the boolean.
                    // Only the event's own detail distinguishes them, below.
                    boolean commandBypass = delivered.command().directive() == NodeDirective.PASSTHROUGH;
                    boolean authoredBypass = !commandBypass && authoredBypassNodes.contains(node.id());
                    boolean bypassed = commandBypass || authoredBypass;
                    // Unchanged in meaning: an authored bypass never composes a handler, so it is
                    // never in passThroughNodes, so it can never be reported as a defaulted node.
                    boolean fallback = !bypassed && passThroughNodes.contains(node.id());
                    // `fallback` now reaches the journal as well as the monitor, read from
                    // passThroughNodes on this one line, so the two projections cannot disagree about
                    // WHICH invocations defaulted. They can still disagree about whether the row
                    // exists at all: nodeCompleted returns early on `terminal`, so a branch that
                    // outlived its traversal reaches the monitor -- called unconditionally below --
                    // and never the journal. That divergence is the `terminal` guard's own, declared
                    // in its Javadoc; this path neither widens nor narrows it.
                    completedEventId.set(state.nodeCompleted(invocationId, attemptId, startedEventId,
                            bypassed, fallback));
                    int stillAlive = liveInstances(node.id(), definition.nature());
                    if (bypassed) {
                        state.bypassedNodes.add(node.id());
                        monitor.nodeBypassed(identity, node.id(), invocationId, attemptId, stillAlive,
                                authoredBypass);
                    } else {
                        monitor.nodeCompleted(identity, node.id(), invocationId, attemptId, fallback,
                                result.outcome(), stillAlive, result.actionDiagnostic());
                    }
                    // The single point where the runner holds both the node and the trusted
                    // catalog, so the only point where provenance can be decided from the descriptor.
                    return NodeOutcome.completed(markSyntheticProvenance(node, delivered, result));
                })
                .thenCompose(outcome -> {
                    NodeResult result = outcome.result();
                    if (outcome.failureRouted()) {
                        // Handled failure: the failed attempt and invocation stay FAILED,
                        // recorded above unconditionally -- handled routing changes what happens
                        // next, never what happened. Ordinary routing continues from here through the
                        // exact machinery an ordinary successor uses below, so the join-invalidation
                        // accounting is identical on both paths by construction: the normal branches
                        // this node did not reach are proven dead, and the failure branch is proven
                        // live, by the same call.
                        return dispatchSuccessors(outcome.failureEdges(), node, result, delivered,
                                completedEventId.get(), state, identity, coordinator, iteration);
                    }
                    if (passThroughNodes.contains(node.id())) {
                        state.defaultedNodes.add(node.id());
                    }
                    // A traversal now has two terminals, so "the result payload" and "the END
                    // node" stop being the same condition. Each terminal records its own arrival;
                    // which of the two the result reports is decided once, at read time, by
                    // ExecutionState.resultPayload(). Deliberately NOT one shared field written by
                    // both: see that method for the race that cost, measured.
                    if (node.kind() == NodeKind.END) {
                        state.endTerminalPayload = new Object[] {result.payload()};
                    } else if (node.kind() == NodeKind.ERROR) {
                        state.errorTerminalPayload = new Object[] {result.payload()};
                    }
                    if (node.kind() == NodeKind.END) {
                        // An end node continues to nothing, so whatever is wired after it is dead by
                        // definition rather than merely not selected.
                        return allOrFirstFailure(state.liveness.reportUntaken(node.id(), List.of(), coordinator,
                                iteration));
                    }
                    // ERROR deliberately falls through to ordinary successor dispatch instead of
                    // joining the branch above. It is a terminal by intent, not by rule: error-terminal routing contract
                    // describes continuing from it into logging, alerting, retry or remediation, and
                    // nothing in GraphDefinition forbids its outgoing edges. When it has none,
                    // dispatchSuccessors makes the same reportUntaken(id, List.of(), ...) call the END
                    // branch makes explicitly, so the liveness accounting is identical either way.
                    List<GraphEdge> next = graph.nextEdges(node.id(), result.outcome());
                    if (next.isEmpty() && !"continue".equals(result.outcome())) {
                        next = graph.nextEdges(node.id(), "continue");
                    }
                    // A bypassed node's own outcome is always the hardcoded "continue"
                    // (see runtimeNode()), so `next` above is always exactly nextEdges(node, "continue")
                    // regardless of what this node's edges are actually named. Every sibling edge that
                    // carries a different, explicit outcome was therefore never a candidate -- not
                    // merely not selected this run -- and NodeCommandRoutingTest
                    // .branchPointBehindOnlyCustomOutcomeEdgesIsUnreachableInTestPassthrough is the
                    // executed proof. Recorded only when this node was actually bypassed, so an
                    // ordinary run's untaken branches (the normal shape of any if/else) are not
                    // reported as if they were the same gap. Recomputed here rather than reusing the
                    // `bypassed` local from the .handle() stage above: that one is out of scope in
                    // this lambda, but the check is the exact same expression on the exact same
                    // `delivered` message, which dispatchSuccessors below also still relies on.
                    // The same argument applies to the authored bypass:
                    // it word for word: a node switched off by its author emits DEFAULT_OUTCOME by
                    // rule, so every sibling edge carrying a different explicit outcome was never a
                    // candidate. That is precisely the consequence the author must be
                    // able to see -- switching off a decision node silently removes its non-default
                    // branches -- so reporting it here is not an incidental reuse, it is the
                    // declaration the feature owes.
                    boolean bypassed = delivered.command().directive() == NodeDirective.PASSTHROUGH
                            || authoredBypassNodes.contains(node.id());
                    if (bypassed) {
                        List<GraphEdge> finalNext = next;
                        graph.edges().stream()
                                .filter(candidate -> candidate.source().equals(node.id())
                                        && !finalNext.contains(candidate))
                                .forEach(candidate -> state.untakenEdges.add(node.id() + "->"
                                        + candidate.target() + " [outcome=" + candidate.outcome() + "]"));
                    }
                    if (next.isEmpty() && node.kind() != NodeKind.ERROR) {
                        // This branch has run out of edges on a node that is
                        // neither terminal by kind. It succeeded, it produced a payload, and before
                        // this line that payload was dropped on the floor for no reason other than
                        // that the node it ended on was not called END -- a traversal whose only
                        // branch ended here reported COMPLETED with payload null.
                        //
                        // Recorded, not dispatched: the branch still ends here and the liveness
                        // accounting below is untouched, because dispatchSuccessors makes the same
                        // reportUntaken(id, List.of(), ...) call for an empty edge list that the END
                        // branch makes explicitly. This adds a THIRD terminal arrival after the two
                        // explicit terminal kinds, and it ranks last -- see resultPayload().
                        state.recordDanglingTerminal(result.payload());
                    }
                    return dispatchSuccessors(next, node, result, delivered, completedEventId.get(), state,
                            identity, coordinator, iteration);
                })
                .handle((ignored, error) -> error == null
                        ? CompletableFuture.<Void>completedFuture(null)
                        : absorbIntoJoins(node, error, coordinator, iteration))
                .thenCompose(stage -> stage);
        // Deliberately NOT followed by a wait on the instance's actor having terminated.
        //
        // Ending this method with such a wait would make "zero orphans on completion" a guarantee
        // rather than a poll. It is the wrong guarantee to buy here, and the shutdown suite
        // caught it: ExecutionEngine says in as many words that a stop is bounded by the node's own
        // work and that "a node that never completes never stops". Waiting on it would therefore make
        // every traversal's completion hostage to a single node's onStop -- exactly the unbounded wait
        // GraphRunner.close() had to be given a bound and an escalation to fix, reintroduced one level
        // up, where there is no bound at all.
        //
        // Nothing is lost, because the two halves of "released" settle at different times on purpose.
        // The registry entry -- this runner's own accounting of what is alive, and what the orphan
        // assertions read -- is dropped SYNCHRONOUSLY inside release(), so it is already deterministic.
        // Only the actor's termination is asynchronous, and an asynchronous termination is what an
        // actor runtime provides; close() is where an unbounded one is bounded and escalated.
    }

    /**
     * Binds one worker instance to the eight identifiers ADR 0024 §1 requires.
     *
     * <p>Every value is read from the identity this traversal was stamped with, never minted here.
     * The tenant comes from the ingress {@code SecurityContext} for the same reason the delivered
     * message's does: it is the authenticated fact about who this work belongs to, and taking it from
     * anything the graph or a previous node could influence would make tenancy a property of content.
     */
    private static WorkerInstanceIdentity workerIdentity(ExecutionMonitor.ExecutionIdentity identity,
                                                         String nodeId, UUID invocationId, UUID attemptId) {
        return new WorkerInstanceIdentity(identity.security().tenantId(), identity.deploymentId(),
                identity.graphVersion(), identity.processInstanceId(), identity.traversalId(), nodeId,
                invocationId, attemptId);
    }

    private static TraversalInstanceIdentity traversalIdentity(
            ExecutionMonitor.ExecutionIdentity identity, String nodeId) {
        return new TraversalInstanceIdentity(identity.security().tenantId(), identity.deploymentId(),
                identity.graphVersion(), identity.processInstanceId(), identity.traversalId(), nodeId);
    }

    /**
     * One logical node as the runtime sees it: a definition plus the composed behavior, never an actor
     * (ADR 0024 §1).
     *
     * @param runtime the composed {@link RavenNode}, shared by every instance of this node.
     *                <strong>Shared, and therefore invoked concurrently</strong> — which is the SPI
     *                consequence of demand-driven allocation, stated on {@code NodeBehavior.create} and
     *                {@code NodeAction} and pinned by {@code NodeBehaviorContract}. Composing one per
     *                invocation instead would put credential parsing and plugin loading on the
     *                dispatch path.
     */
    private record NodeRuntimeDefinition(GraphNode node, NodeRuntimeNature nature, int maxConcurrency,
                                         RavenNode runtime) {
    }

    /**
     * Dispatches to the targets of {@code edges} and reports every other distinct successor of
     * {@code node} as not taken, through the one join-liveness accounting path an ordinary outcome
     * selection and a handled failure route both use. The successors this call does not take
     * can now be proven dead, and a join downstream of one of them has to be told; nothing else ever
     * will, because the branch does not arrive late, does not time out and does not fail -- it never
     * runs at all.
     */
    private CompletableFuture<Void> dispatchSuccessors(List<GraphEdge> edges, GraphNode node, NodeResult result,
                                                        NodeMessage delivered, UUID causedBy, ExecutionState state,
                                                        ExecutionMonitor.ExecutionIdentity identity,
                                                        JoinCoordinator coordinator, IterationContext iteration) {
        var deliveries = targetDeliveries(edges, delivered.command());
        var taken = deliveries.stream().map(TargetDelivery::targetId).toList();
        // `iteration` is read from this runner's scope and from `delivered`, never from `result`.
        // That is the whole of why the token cannot be forged: result is what the node returned.
        var dispatches = new ArrayList<>(state.liveness.reportUntaken(node.id(), taken, coordinator, iteration));
        for (TargetDelivery target : deliveries) {
            // Duplicate edges to one target are one route by ADR 0024. Their authored identities are
            // nevertheless ambiguous, so no traversal event is fabricated for that collapsed route.
            // Every unambiguous delivery is observed here, immediately before the actual dispatch.
            if (target.edge() != null) {
                monitor.requireEdgeTraversalAccepted(identity, target.edge().id(), node.id(),
                        target.edge().outcome());
                state.edgeTraversed(target.edge(), delivered.invocationId(),
                        delivered.attemptId(), causedBy);
                monitor.edgeTraversed(identity, target.edge().id(), node.id(), delivered.invocationId(),
                        delivered.attemptId(), target.edge().outcome());
            }
            dispatches.add(dispatch(graph.node(target.targetId()), node.id(), result.payload(),
                    result.attributes(), Set.of(delivered.invocationId()), target.command(),
                    causedBy, state, identity, coordinator, iteration).toCompletableFuture());
        }
        return allOrFirstFailure(dispatches);
    }

    /**
     * What one node attempt produced, and how its successors are selected.
     *
     * <p>{@code failureEdges} is {@code null} for an ordinary completion, where the successors are
     * whichever edges match {@code result.outcome()}. It holds the node's declared failure-route
     * edges when the attempt failed and at least one such edge exists -- the one case where edge
     * selection is not driven by an outcome at all, because a crashed node produced none.</p>
     */
    private record NodeOutcome(NodeResult result, List<GraphEdge> failureEdges) {
        private static NodeOutcome completed(NodeResult result) {
            return new NodeOutcome(result, null);
        }

        private static NodeOutcome failed(NodeResult result, List<GraphEdge> failureEdges) {
            return new NodeOutcome(result, failureEdges);
        }

        private boolean failureRouted() {
            return failureEdges != null;
        }
    }

    /**
     * Applies the runtime's synthetic-provenance marker (REG-03) to what this node produced. This is
     * not the art. 50(2) marking; see {@link SyntheticProvenance}.
     *
     * <h2>Why here and nowhere else</h2>
     * <p>This is the one place that holds the {@link GraphNode} and the {@link BehaviorRegistry} at
     * the same time, and therefore the one place that can consult the trusted
     * {@code NodeTypeDescriptor}. Marking derives from the descriptor's declared capabilities, so it
     * is never a list of node names to keep in step with the catalog, and a behavior registered from
     * outside the core with a generative capability is marked without this method learning about it.
     * The core ships no generative node type, so every
     * marker this method stamps comes from a plugin bundle's or an embedder's descriptor.</p>
     *
     * <h2>The node has no syntax for this key</h2>
     * <p>Whatever the node returned under {@link SyntheticProvenance#KEY_PREFIX} is dropped
     * <em>unread</em> before anything is computed, and the value written back is the runtime's own.
     * A behavior therefore cannot forge a marker over content it produced deterministically, and
     * cannot suppress one either, because suppression is a claim about the result map while the
     * surviving marker is recomputed from {@code delivered} — the map <em>this runner</em> wrote for
     * the inbound hop. This is the SEC-07 argument applied to provenance: what a node cannot express
     * needs no filter.</p>
     *
     * <h2>Per content, not per execution</h2>
     * <p>A generative node mints a marker bound to the digest of the payload it just produced. Every
     * other node inherits the inbound marker <strong>only while it still describes the outgoing
     * payload</strong>. So a node that substitutes the payload — which
     * {@code NodeMessage.next(...)} does while carrying attributes forward verbatim — drops the
     * marker by construction rather than by remembering to. Content derived from generated content,
     * including a fan-in that merges a generated branch with a deterministic one, is deliberately
     * left unmarked: Ravenroot cannot verify the claim would still be true, and an unfounded
     * provenance assertion is worse than a missing one.</p>
     */
    private NodeResult markSyntheticProvenance(GraphNode node, NodeMessage delivered, NodeResult result) {
        var attributes = new LinkedHashMap<String, Object>(result.attributes());
        // Unread and unconditional: a node's opinion about provenance is not an input.
        attributes.keySet().removeIf(SyntheticProvenance::isProvenanceKey);
        Optional<NodeTypeDescriptor> descriptor = node.kind() == NodeKind.BEHAVIOR
                && !passThroughNodes.contains(node.id())
                && delivered.command().directive() != NodeDirective.PASSTHROUGH
                // A node switched off by its author generated nothing, so it must not mint a
                // synthetic-provenance marker, for exactly the reason the two conditions above exist:
                // the marker asserts that THIS content was produced by a generative capability, and
                // the content here is the inbound payload passing through untouched. The inbound
                // marker, if any, still survives by the `.or(...)` below when it still describes that
                // payload -- which is right, because a bypass does not change the payload and so
                // cannot invalidate a claim made about it upstream.
                && !authoredBypassNodes.contains(node.id())
                ? behaviors.descriptor(node.behavior())
                : Optional.empty();
        Optional<Map<String, Object>> marker = descriptor
                .flatMap(entry -> SyntheticProvenance.mint(node.id(), entry, result.payload()))
                // Not generative: the marker survives only as far as the content it describes does.
                .or(() -> SyntheticProvenance.read(delivered.attributes())
                        .filter(inbound -> SyntheticProvenance.describes(inbound, result.payload())));
        marker.ifPresent(value -> attributes.put(SyntheticProvenance.ATTRIBUTE, value));
        return new NodeResult(result.outcome(), result.payload(), attributes);
    }

    /**
     * Reports a branch failure to every join it makes undeliverable, and decides whether the
     * traversal survives it.
     *
     * <p>The rule is: a failure inside a join's branch belongs to that join, not to the traversal.
     * A {@code k of n} join exists precisely so that a branch may fail, so propagating the first
     * branch failure straight to the traversal — which is what happened before CORE-03 — made every
     * quorum below {@code n} unreachable in exactly the circumstances it was configured for.</p>
     *
     * <p>A failure that is already a join's <em>verdict</em> is attributed from that join rather
     * than from the node reporting it. Re-reporting it against the same branch would find the branch
     * settled and absorb a failure nothing had recorded, silently discarding the join's outcome as
     * it travelled up a chain of nested dispatches. Attributing from the failed join instead means a
     * join nested inside another join's branch correctly kills that branch, and a join at the top
     * level correctly kills the traversal.</p>
     */
    private CompletableFuture<Void> absorbIntoJoins(GraphNode node, Throwable error, JoinCoordinator coordinator,
                                                   IterationContext iteration) {
        JoinFailureException verdict = joinVerdictIn(error);
        String origin = verdict == null ? node.id() : verdict.nodeId();
        List<JoinBranchRef> affected = failureBranches.getOrDefault(origin, List.of());
        if (affected.isEmpty()) {
            return CompletableFuture.failedFuture(error);
        }
        var reports = affected.stream()
                // The lap the failing branch was going to contribute to. A branch does not
                // just fail, it fails on an iteration: reporting every failure into bucket 0 would let
                // a broken third lap fail a first lap that had already fired.
                .map(ref -> coordinator.fail(ref.joinNodeId(), ref.branchId(), unwrap(error),
                        iteration.lapOf(ref.joinNodeId())).toCompletableFuture())
                .toList();
        return CompletableFuture.allOf(reports.toArray(CompletableFuture[]::new))
                .handle((ignored, reportError) -> {
                    if (reportError != null) {
                        var propagated = new CompletionException(unwrap(error));
                        propagated.addSuppressed(unwrap(reportError));
                        return CompletableFuture.<Void>failedFuture(propagated);
                    }
                    var decisions = reports.stream().map(CompletableFuture::join).toList();
                    for (JoinDecision decision : decisions) {
                        if (decision instanceof JoinDecision.Failed failed) {
                            failed.failure().addSuppressed(unwrap(error));
                            return CompletableFuture.<Void>failedFuture(failed.failure());
                        }
                    }
                    // Absorbed even when every affected join had already settled. A branch that
                    // fails after its join fired is the failure-side twin of a late arrival, and a
                    // late arrival is explicitly not an error; treating the failure as fatal would
                    // make the traversal's outcome depend on whether a superseded branch happened to
                    // finish or happened to break.
                    return CompletableFuture.<Void>completedFuture(null);
                })
                .thenCompose(stage -> stage);
    }

    /**
     * Completes when every branch completes, or fails as soon as any one of them does.
     *
     * <p>{@link CompletableFuture#allOf} waits for all of them even after one has failed, so a join
     * that failed or timed out could not surface while a sibling branch was still blocked — which is
     * precisely the situation a timeout exists for.</p>
     *
     * <p>The asymmetry between failure and success is deliberate and is imposed by the PERS-01
     * aggregate: {@code Traversal} refuses to become {@code COMPLETED} while any invocation is
     * non-terminal, and permits {@code FAILED} with work still in flight. So a quorum decides the
     * join's <em>result</em> as soon as it is met, but the traversal's <em>completion</em> still
     * waits for the branches it superseded to finish.</p>
     *
     * <p>Completing sooner would need either a terminal "superseded" invocation state — a change to
     * a persisted lifecycle enum, which is PERS-01's contract and not CORE-03's to widen — or the
     * ability to stop the branch, which is CORE-04's cancellation and does not exist yet. Until one
     * of those lands, a quorum buys the right answer, not a shorter wall clock, and a branch node
     * that never returns still holds its traversal open exactly as it did before CORE-03.</p>
     */
    private static CompletableFuture<Void> allOrFirstFailure(List<CompletableFuture<Void>> branches) {
        var result = new CompletableFuture<Void>();
        if (branches.isEmpty()) {
            result.complete(null);
            return result;
        }
        var remaining = new java.util.concurrent.atomic.AtomicInteger(branches.size());
        for (CompletableFuture<Void> branch : branches) {
            branch.whenComplete((ignored, error) -> {
                if (error != null) {
                    result.completeExceptionally(error);
                } else if (remaining.decrementAndGet() == 0) {
                    result.complete(null);
                }
            });
        }
        return result;
    }

    private static JoinFailureException joinVerdictIn(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof JoinFailureException verdict) {
                return verdict;
            }
            current = current.getCause();
        }
        return null;
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    /** Marker created only after the recorder confirms the core-minted signal's durable wait. */
    private static final class VerifiedToolApprovalSuspension extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final DurableToolApprovalSuspension signal;

        private VerifiedToolApprovalSuspension(DurableToolApprovalSuspension signal) {
            super(null, null, false, false);
            this.signal = signal;
        }

        private DurableToolApprovalSuspension signal() {
            return signal;
        }
    }

    /**
     * For every node, the join branches its failure makes undeliverable.
     *
     * <p>Forward reachability that <strong>stops at every join node other than the origin</strong>.
     * A join is a re-synchronisation point: a failure upstream of it is that join's problem, and
     * whether anything downstream of it still happens is decided by whether that join is satisfied.
     * Expanding past it would let one failed branch of an {@code any} join mark branches of a later
     * join as dead while the {@code any} join was about to succeed through its other branch.</p>
     */
    private static Map<String, List<JoinBranchRef>> precomputeFailureBranches(GraphDefinition graph,
                                                                              Map<String, JoinSpec> joinSpecs) {
        var branchOwner = new LinkedHashMap<String, List<JoinBranchRef>>();
        joinSpecs.values().forEach(spec -> spec.branches().forEach(branch ->
                branchOwner.computeIfAbsent(branch, ignored -> new ArrayList<>())
                        .add(new JoinBranchRef(spec.nodeId(), branch))));

        var successors = new LinkedHashMap<String, List<String>>();
        graph.edges().forEach(edge -> successors.computeIfAbsent(edge.source(), ignored -> new ArrayList<>())
                .add(edge.target()));

        var result = new LinkedHashMap<String, List<JoinBranchRef>>();
        for (GraphNode origin : graph.nodes()) {
            var reached = new LinkedHashSet<String>();
            var queue = new ArrayDeque<String>();
            queue.add(origin.id());
            reached.add(origin.id());
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (!current.equals(origin.id()) && joinSpecs.containsKey(current)) {
                    continue;
                }
                for (String successor : successors.getOrDefault(current, List.of())) {
                    if (reached.add(successor)) {
                        queue.add(successor);
                    }
                }
            }
            var refs = reached.stream()
                    .flatMap(reachedNode -> branchOwner.getOrDefault(reachedNode, List.of()).stream())
                    .distinct()
                    .toList();
            if (!refs.isEmpty()) {
                result.put(origin.id(), refs);
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, List<JoinBranchRef>> precomputeDirectJoinBranches(Map<String, JoinSpec> joinSpecs) {
        var owner = new LinkedHashMap<String, List<JoinBranchRef>>();
        joinSpecs.values().forEach(spec -> spec.branches().forEach(branch ->
                owner.computeIfAbsent(branch, ignored -> new ArrayList<>())
                        .add(new JoinBranchRef(spec.nodeId(), branch))));
        return Map.copyOf(owner);
    }

    private static Map<String, List<String>> precomputeDistinctSuccessors(GraphDefinition graph) {
        var successors = new LinkedHashMap<String, LinkedHashSet<String>>();
        graph.edges().forEach(edge -> successors
                .computeIfAbsent(edge.source(), ignored -> new LinkedHashSet<>()).add(edge.target()));
        var result = new LinkedHashMap<String, List<String>>();
        successors.forEach((source, targets) -> result.put(source, List.copyOf(targets)));
        return Map.copyOf(result);
    }

    private static Map<String, Integer> precomputeDistinctPredecessorCount(GraphDefinition graph) {
        var predecessors = new LinkedHashMap<String, LinkedHashSet<String>>();
        graph.edges().forEach(edge -> predecessors
                .computeIfAbsent(edge.target(), ignored -> new LinkedHashSet<>()).add(edge.source()));
        var result = new LinkedHashMap<String, Integer>();
        predecessors.forEach((target, sources) -> result.put(target, sources.size()));
        return Map.copyOf(result);
    }

    private static List<String> precomputeUnreachable(GraphDefinition graph, String startNodeId,
                                                      Map<String, List<String>> successors) {
        var reached = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(startNodeId);
        reached.add(startNodeId);
        while (!queue.isEmpty()) {
            for (String successor : successors.getOrDefault(queue.poll(), List.of())) {
                if (reached.add(successor)) {
                    queue.add(successor);
                }
            }
        }
        return graph.nodes().stream().map(GraphNode::id).filter(id -> !reached.contains(id)).toList();
    }

    /**
     * Which branches of which joins can still deliver, for one traversal.
     *
     * <p>This exists because a join had no way to distinguish "the branch has not arrived yet" from
     * "the branch is never going to run". The second is the ordinary consequence of a decision:
     * {@code decision -[accepted]-> x -> join} and {@code decision -[rejected]-> y -> join} give the
     * join two branches, and every execution takes exactly one of them. Under the default
     * {@code all} policy the join then waited for a branch that could not come, and because a
     * waiting branch parks on a future nobody completes, the traversal never finished, was never
     * released, and held its actors, payloads and security context until the process died.</p>
     *
     * <h2>Why liveness is propagated rather than computed from reachability</h2>
     * <p>The tempting implementation is the one {@link #failureBranches} uses: from an untaken
     * successor, walk forward and mark every join branch it reaches. That is wrong here, and wrong
     * in the direction that silently breaks working graphs. In a diamond — {@code n -[a]-> x},
     * {@code n -[b]-> y}, both feeding {@code z} — taking {@code a} makes {@code y} dead, and
     * forward reachability from {@code y} reaches {@code z}, which is very much alive by way of
     * {@code x}. The join would be told its live branch was dead and would fail a traversal that
     * was proceeding perfectly well.</p>
     *
     * <p>So deadness is propagated one hop at a time, against the <em>set</em> of predecessors that
     * have resolved. A node dies only when <em>every</em> distinct predecessor has resolved without
     * selecting it, and a predecessor counts once however many times it runs: it can resolve once per
     * arrival under {@code joinPolicy=each}, and once for each child that lands in a data-parallel
     * fan-out. Because the set is insert-only and idempotent per predecessor, no live branch is ever
     * marked, and a branch is marked as soon as the last path to it closes.</p>
     *
     * <p>A counter seeded from the topology would assume that each predecessor resolves exactly once.
     * That premise is false for every node whose predecessor resolves more than once: two resolutions
     * of one predecessor can drive the count to zero and
     * mark a node dead while another predecessor is still a live path to it. The wrongly dead node
     * then had its fan-in branches reported {@code NOT_TAKEN}, and a spurious {@code NOT_TAKEN} on a
     * join with a quorum above one is fatal at once and is not repaired by the real arrival that
     * follows. Tracking predecessor identities instead makes repeat resolutions idempotent while
     * retaining the information needed to close the last live path.</p>
     *
     * <h2>Why a dead branch is not the same as a verdict to carry</h2>
     * <p>Knowing which <em>nodes</em> are dead is not yet knowing what to tell a join, because a
     * join whose every branch is dead is itself a dead node. Its quorum is unreachable, but that is
     * a property of the sub-graph the execution did not select, in the same way the branches
     * themselves are: nothing waits for it, and nothing downstream of it was going to run. Failing
     * the traversal on it fails an ordinary graph — {@code d -[a]-> x -> end} beside
     * {@code d -[b]-> y -> {p, q} -> J} — for a join the taken outcome never went near.</p>
     *
     * <p>That verdict also cannot be suppressed by a check made as each report is emitted. In that
     * graph {@code p} is proven dead before {@code q} is, and at that instant {@code J} still has an
     * outstanding branch and is indistinguishable from a live one. So the walk runs in two phases:
     * the dead set is closed completely first, and only then is each join classified against it.</p>
     *
     * <p>The classification has three outcomes, not two, because a join's own liveness is not always
     * decided by the time one of its branches dies. A join already dispatched is live and hears the
     * report immediately; a join in the closed dead set hears nothing, ever; and a join that is
     * neither yet has its report <em>held</em>, until the predecessor that dispatches it proves it
     * live or the predecessor that closes its last path proves it dead. Holding is what makes the
     * rule correct across separate walks: two independent decisions can kill a join's two branches
     * at different moments, and the first of them says nothing about whether the join survives.</p>
     *
     * <p>A held report cannot deadlock the join it belongs to. A branch can only arrive at a join
     * that was dispatched, dispatch is recorded before the message is sent, and the release happens
     * under the same lock — so the report is emitted no later than the arrival it must be weighed
     * against, and in the same batch of stages the dispatching node waits on. A join that is never
     * dispatched and never fully closed keeps its held reports until the traversal ends, which costs
     * one list per join and reaches nobody, because nothing ever arrives there to wait.</p>
     *
     * <p><strong>Bounded by</strong> the graph: at most one resolved-predecessor set per node — itself
     * bounded by that node's distinct predecessor count — one flag per node, and one held report per
     * join branch, for one traversal. Still nothing added per arrival, which survives the move from a
     * counter to a set precisely because the set is keyed by predecessor rather than by arrival: a
     * node that resolves a hundred times under {@code joinPolicy=each} adds at most one entry. It dies
     * with the traversal's {@link ExecutionState}.</p>
     *
     * <p>All four collections are guarded by one monitor rather than being individually concurrent.
     * The decision to hold or release a report reads {@code dispatched} and {@code dead} and writes
     * {@code held}, so it is only sound if those three move together: a release that interleaved
     * with a hold would drop a report on a join that is live and waiting. The store call itself is
     * made outside the lock, so nothing blocking is ever held across it.</p>
     */
    private final class BranchLiveness {
        private final Object lock = new Object();
        /**
         * Which distinct predecessors of each node have resolved, rather than how many resolutions
         * have happened.
         *
         * <p>This was {@code Map<String, Integer> undecided}, seeded from
         * {@code distinctPredecessorCount} and decremented once per resolution. Counting resolutions
         * equals counting predecessors only while every predecessor resolves exactly once, which is
         * false. A node resolves once per arrival under
         * {@code joinPolicy=each}, and once per child in a data-parallel fan-out, so two
         * resolutions of ONE predecessor drove the counter to zero and marked a node dead while
         * another predecessor was still a live path to it. That is not cosmetic: the wrongly dead node
         * has its fan-in branches reported {@code NOT_TAKEN}, and a spurious {@code NOT_TAKEN} on a
         * join with a quorum above one is fatal at once and is not repaired by the real arrival.
         *
         * <p>A set makes the operation idempotent per predecessor, which is what the invariant always
         * meant. Insert-only, like the coordinator's own maps: a predecessor that has resolved never
         * un-resolves.
         */
        private final Map<String, LinkedHashSet<String>> resolvedPredecessors = new LinkedHashMap<>();
        private final Set<String> dispatched = new LinkedHashSet<>();
        private final Set<String> dead = new LinkedHashSet<>();

        /**
         * Not-taken reports for joins whose own liveness is still undecided, keyed by join node id.
         * Released when the join is dispatched, discarded when the join is proven dead.
         */
        private final Map<String, List<JoinBranchRef>> held = new LinkedHashMap<>();

        private BranchLiveness(String startNodeId) {
            dispatched.add(startNodeId);
        }

        /**
         * Records that {@code nodeId} selected {@code taken} and reports the rest as never taken.
         *
         * @return one stage per join branch that is due a report, each failing if that verdict kills
         *         a join that is genuinely part of this execution
         */
        private List<CompletableFuture<Void>> reportUntaken(String nodeId, List<String> taken,
                                                            JoinCoordinator coordinator,
                                                            IterationContext iteration) {
            List<JoinBranchRef> due;
            synchronized (lock) {
                due = new ArrayList<>();
                for (String target : taken) {
                    dispatched.add(target);
                    // Proven live by this very dispatch, so whatever was held for it is now owed to
                    // it — and owed to the node that is about to send it a branch, which is this one.
                    List<JoinBranchRef> release = held.remove(target);
                    if (release != null) {
                        due.addAll(release);
                    }
                }
                var worklist = new ArrayDeque<String>();
                var declined = new ArrayList<JoinBranchRef>();
                for (String successor : distinctSuccessors.getOrDefault(nodeId, List.of())) {
                    if (taken.contains(successor)) {
                        continue;
                    }
                    Resolution resolution = resolve(nodeId, successor);
                    if (resolution == Resolution.SETTLED) {
                        continue;
                    }
                    if (resolution == Resolution.CLOSED) {
                        // The successor itself is now dead: closeThenClassify propagates that and
                        // reports the joins IT is a branch of.
                        worklist.add(successor);
                    } else {
                        // The successor is still alive -- another predecessor can reach it -- but THIS
                        // one never will, and if the successor is a fan-in that is a permanently dead
                        // branch of it.
                        //
                        // Node liveness and branch liveness are different questions, and only the
                        // first was being asked. `notTaken` is emitted from closeThenClassify solely
                        // when a branch's own node is dead; a node that is alive and simply routed
                        // elsewhere is not dead, so nothing reported it and the join waited for an
                        // arrival that could not exist. Its own javadoc already describes this case --
                        // "its absence did not merely make the join wrong, it made the join
                        // non-terminating" -- so the mechanism was right and only this call site was
                        // missing.
                        declined.addAll(branchesOf(nodeId, successor));
                    }
                }
                due.addAll(closeThenClassify(worklist));
                due.addAll(classify(declined));
            }
            return emit(due, coordinator, iteration);
        }

        /** Reports nodes that are dead outright rather than dead because a predecessor declined. */
        private List<CompletableFuture<Void>> reportDead(List<String> nodes, JoinCoordinator coordinator,
                                                        IterationContext iteration) {
            List<JoinBranchRef> due;
            synchronized (lock) {
                due = closeThenClassify(new ArrayDeque<>(nodes));
            }
            return emit(due, coordinator, iteration);
        }

        /**
         * Phase one closes the dead set; phase two decides what each newly dead branch is owed.
         *
         * <p>Iterative rather than recursive: the walk is as deep as the graph, and a deep pipeline
         * is a perfectly ordinary graph to author.</p>
         */
        private List<JoinBranchRef> closeThenClassify(ArrayDeque<String> worklist) {
            var candidates = new ArrayList<JoinBranchRef>();
            while (!worklist.isEmpty()) {
                String node = worklist.poll();
                if (!dead.add(node)) {
                    continue;
                }
                // A join proven dead is owed nothing and will never be owed anything again.
                held.remove(node);
                candidates.addAll(directJoinBranches.getOrDefault(node, List.of()));
                for (String successor : distinctSuccessors.getOrDefault(node, List.of())) {
                    if (resolve(node, successor) == Resolution.CLOSED) {
                        worklist.add(successor);
                    }
                }
            }
            return classify(candidates);
        }

        /**
         * Decides what each newly dead branch is owed: reported now, held, or nothing.
         *
         * <p>A join already proven dead is owed nothing and never will be. A join nothing has
         * dispatched to yet is <em>held</em> rather than told, because it may still turn out to be
         * dead itself, and a join that never runs needs no verdict about its branches.
         */
        private List<JoinBranchRef> classify(List<JoinBranchRef> candidates) {
            var due = new ArrayList<JoinBranchRef>();
            for (JoinBranchRef ref : candidates) {
                if (dead.contains(ref.joinNodeId())) {
                    continue;
                }
                if (dispatched.contains(ref.joinNodeId())) {
                    due.add(ref);
                } else {
                    held.computeIfAbsent(ref.joinNodeId(), ignored -> new ArrayList<>()).add(ref);
                }
            }
            return due;
        }

        /** {@code source}'s branch references into {@code join}, or empty when it is not a fan-in. */
        private List<JoinBranchRef> branchesOf(String source, String join) {
            return directJoinBranches.getOrDefault(source, List.of()).stream()
                    .filter(ref -> ref.joinNodeId().equals(join))
                    .toList();
        }

        /**
         * @param iteration the context of the resolution that produced these reports. A branch is not
         *                  taken <em>on a lap</em>, so the verdict is recorded into that lap's bucket;
         *                  reporting into bucket 0 unconditionally would let a route declined on the
         *                  third pass retroactively kill the first, which had already fired.
         */
        private List<CompletableFuture<Void>> emit(List<JoinBranchRef> due, JoinCoordinator coordinator,
                                                   IterationContext iteration) {
            return due.stream()
                    .map(ref -> coordinator.notTaken(ref.joinNodeId(), ref.branchId(),
                                    iteration.lapOf(ref.joinNodeId()))
                            .thenCompose(GraphRunner::carryJoinVerdict).toCompletableFuture())
                    .toList();
        }

        /**
         * What one predecessor's resolution proves about a successor it did not take: that the last
         * path to it has closed, that another predecessor can still reach it, or that nothing new was
         * learned.
         *
         * <p>The three outcomes are distinct: {@link Resolution#CLOSED} closes the last path,
         * {@link Resolution#STILL_LIVE} leaves another path available while still reporting this
         * predecessor's declined branch, and {@link Resolution#SETTLED} adds no information because
         * this predecessor already resolved.</p>
         */
        private Resolution resolve(String predecessor, String node) {
            var resolved = resolvedPredecessors.computeIfAbsent(node, id -> new LinkedHashSet<>());
            if (!resolved.add(predecessor)) {
                // This predecessor has already resolved for this node. It adds no information, and
                // treating it as new recreates the counter defect. It must not fall through to the declined
                // path either: that would report the same dead branch once per resolution.
                return Resolution.SETTLED;
            }
            if (dispatched.contains(node)) {
                // Something already dispatched to it, so it is provably alive and cannot be closed.
                // But THIS predecessor still declined it, and if it is a fan-in that branch is still
                // dead and still owed a report. Returning SETTLED here suppressed that report
                // whenever another predecessor happened to arrive first, and the join waited forever
                // -- caught by the boundary-case test.
                return Resolution.STILL_LIVE;
            }
            return resolved.size() >= distinctPredecessorCount.getOrDefault(node, 0)
                    ? Resolution.CLOSED
                    : Resolution.STILL_LIVE;
        }

        /** What one predecessor's resolution proves about a successor it did not take. */
        private enum Resolution {
            /** Every distinct predecessor has now resolved without taking it: the node is dead. */
            CLOSED,
            /** Another predecessor can still reach it, so only THIS branch into it is dead. */
            STILL_LIVE,
            /** Nothing new: this predecessor had already resolved. */
            SETTLED
        }
    }

    /**
     * Turns a join's answer to a not-taken report into the branch's own outcome. Only a verdict that
     * the join is now impossible propagates; everything else is a fact recorded and nothing more.
     *
     * <p>Reached only for joins {@link BranchLiveness} has classified as part of this execution. A
     * join that is itself dead is never told about its branches at all, so no verdict of its exists
     * to be carried — which is the distinction, because a dead join's quorum is unreachable by
     * construction and turning that into a traversal failure fails graphs that are perfectly well
     * formed on the outcome they took.</p>
     */
    private static CompletionStage<Void> carryJoinVerdict(JoinDecision decision) {
        return decision instanceof JoinDecision.Failed failed
                ? CompletableFuture.failedFuture(failed.failure())
                : CompletableFuture.completedFuture(null);
    }

    private RavenNode runtimeNode(GraphNode node) {
        // An authored bypass suppresses composition for the same reason the passthrough ceiling
        // does, and for a sharper version of the same argument. The motivating case is a node the
        // deployment CANNOT provision -- an AI node with no adapter configured, a programmable
        // artifact not created yet -- so composing it is not merely wasteful, it is the failure the
        // author switched the node off to avoid. Note what this also avoids: behaviors.create on an
        // unresolvable behavior falls through to fallback(node), which registers the node in
        // passThroughNodes and would report it as NODE_DEFAULTED. See authoredBypassNodes for why
        // that fact must not be merged with this one.
        boolean authoredBypass = authoredBypassNodes.contains(node.id());
        // Preserve composition-time snapshots for operational graphs while never creating a factory
        // for a node whose every possible arrival is under the sticky passthrough ceiling.
        NodeHandler composed = node.kind() == NodeKind.BEHAVIOR && !authoredBypass
                && operationallyReachableNodes.contains(node.id())
                ? behaviors.create(node).orElseGet(() -> fallback(node))
                : null;
        return new RavenNode() {
            private volatile NodeHandler operational = composed;

            @Override
            public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                if (message.command().directive() == NodeDirective.PASSTHROUGH) {
                    // This branch precedes factory creation. NodeBehavior#create may itself parse
                    // credentials, initialize providers or load plugin code, so constructing and
                    // merely declining to invoke the handler would already violate passthrough.
                    return CompletableFuture.completedFuture(new NodeResult("continue", message.payload(),
                            message.attributes()));
                }
                if (authoredBypass) {
                    // The author switched this node off. GraphEdge.DEFAULT_OUTCOME and the
                    // inbound payload, verbatim -- ALWAYS, including for a node that would normally
                    // choose among several outcomes. A cel-decision or an http-request that did not
                    // run has no branch to pick, and inventing one would be a fabricated answer
                    // wearing the node's name; the edges consequently not taken are declared in
                    // state.untakenEdges instead, where the author can see them.
                    //
                    // Placed after the PASSTHROUGH branch and before every other check on purpose. A
                    // node under the command ceiling is bypassed for a reason that already has its
                    // own event detail, and a node whose arrival carries an application command has
                    // nothing left to admit once it is not going to run -- the graph-wide check in
                    // validateAdmittedCommands is what refuses an inadmissible command, at
                    // construction, and the authored bypass leaves it unchanged.
                    return CompletableFuture.completedFuture(new NodeResult(GraphEdge.DEFAULT_OUTCOME,
                            message.payload(), message.attributes()));
                }
                if (node.kind() != NodeKind.BEHAVIOR) {
                    return CompletableFuture.completedFuture(new NodeResult("continue", message.payload(),
                            message.attributes()));
                }
                if (message.command().directive() == NodeDirective.APPLICATION) {
                    boolean admitted = behaviors.descriptor(node.behavior())
                            .map(descriptor -> descriptor.commands().contains(message.command().name()))
                            .orElse(false);
                    if (!admitted) {
                        return CompletableFuture.failedFuture(
                                new NodeCommandAdmissionException(node.id(), message.command().name()));
                    }
                }
                return operational().handle(message);
            }

            private NodeHandler operational() {
                NodeHandler ready = operational;
                if (ready != null) return ready;
                synchronized (this) {
                    if (operational == null) {
                        operational = behaviors.create(node).orElseGet(() -> fallback(node));
                    }
                    return operational;
                }
            }
        };
    }

    /**
     * Conservative command-flow analysis. Every outcome edge is considered, so a factory is skipped
     * only when no possible route can deliver an operational command to that node.
     */
    private static Set<String> operationallyReachableNodes(GraphDefinition graph, ExecutionPolicy policy) {
        NodeCommand initial = policy == ExecutionPolicy.TEST_PASSTHROUGH
                ? NodeCommand.PASSTHROUGH : NodeCommand.PROCESS;
        record Reachability(String nodeId, NodeCommand command) { }
        var visited = new java.util.HashSet<Reachability>();
        var pending = new java.util.ArrayDeque<Reachability>();
        var operational = new java.util.HashSet<String>();
        pending.add(new Reachability(graph.start().id(), initial));
        while (!pending.isEmpty()) {
            Reachability current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current.command().directive() != NodeDirective.PASSTHROUGH) {
                operational.add(current.nodeId());
            }
            graph.edges().stream()
                    .filter(edge -> edge.source().equals(current.nodeId()))
                    .forEach(edge -> {
                        NodeCommand next = current.command().directive() == NodeDirective.PASSTHROUGH
                                ? NodeCommand.PASSTHROUGH : edge.command().orElse(current.command());
                        pending.addLast(new Reachability(edge.target(), next));
                    });
        }
        return Set.copyOf(operational);
    }

    /** Refuses every graph-delivered application command before any node factory is created. */
    private static void validateAdmittedCommands(GraphDefinition graph, BehaviorRegistry behaviors,
                                                  ExecutionPolicy policy) {
        NodeCommand initial = policy == ExecutionPolicy.TEST_PASSTHROUGH
                ? NodeCommand.PASSTHROUGH : NodeCommand.PROCESS;
        record Reachability(String nodeId, NodeCommand command) { }
        var visited = new java.util.HashSet<Reachability>();
        var pending = new java.util.ArrayDeque<Reachability>();
        pending.add(new Reachability(graph.start().id(), initial));
        while (!pending.isEmpty()) {
            Reachability current = pending.removeFirst();
            if (!visited.add(current)) continue;
            GraphNode node = graph.node(current.nodeId());
            if (node.kind() == NodeKind.BEHAVIOR
                    && current.command().directive() == NodeDirective.APPLICATION) {
                boolean admitted = behaviors.descriptor(node.behavior())
                        .map(descriptor -> descriptor.commands().contains(current.command().name()))
                        .orElse(false);
                if (!admitted) {
                    throw new NodeCommandAdmissionException(node.id(), current.command().name());
                }
            }
            graph.edges().stream()
                    .filter(edge -> edge.source().equals(current.nodeId()))
                    .forEach(edge -> {
                        NodeCommand next = current.command().directive() == NodeDirective.PASSTHROUGH
                                ? NodeCommand.PASSTHROUGH : edge.command().orElse(current.command());
                        pending.addLast(new Reachability(edge.target(), next));
                    });
        }
    }

    /**
     * The handler for a behavior the trusted catalog does not contain (SEC-09 rule 3).
     *
     * <p>Reached under exactly one condition: {@link BehaviorRegistry#create} found no factory, which
     * is the same condition that makes {@link BehaviorRegistry#descriptor} empty. A <em>known</em>
     * behavior whose adapter or runtime is unconfigured never arrives here — CORE-05 and CORE-07 own
     * that refusal and it must keep failing as itself.</p>
     *
     * <p>The policy is asked once, here, while the node is being composed rather than on each
     * message. That is the rule {@code GraphMlCapabilityEscalationTest} already pins for an
     * unregistered agent runtime: a graph that was admitted must not be armed retroactively by a
     * later change, and the mirror of it holds too — a graph refused at composition stays refused.</p>
     */
    private NodeHandler fallback(GraphNode node) {
        if (!unknownBehaviors.admits(node.behavior())) {
            // Refused when the traversal reaches it, not at composition. CORE-05 established that
            // shape for an unresolvable runtime id so the graph still constructs and stays
            // inspectable in the editor, and a refusal that is a different kind of event depending on
            // which unconfigured thing caused it is one an operator cannot learn.
            return message -> CompletableFuture.failedFuture(new IllegalStateException(
                    "Node '" + node.id() + "' names behavior '" + node.behavior()
                            + "', which is not in this deployment's trusted catalog"));
        }
        // Recorded here, where the runner itself decides to use the pass-through, so that reporting
        // it later does not depend on trusting an attribute the node supplies. See passThroughNodes.
        return passThrough(node, "ravenroot.defaultedNode");
    }

    /** Structural no-op selected by the runner, never by graph or behavior output. */
    private NodeHandler passThrough(GraphNode node, String attribute) {
        passThroughNodes.add(node.id());
        return message -> CompletableFuture.completedFuture(new NodeResult("continue", message.payload(),
                Map.of(attribute, node.id())));
    }

    /**
     * Stops every node of this graph, bounded, and releases everything this runner accumulated.
     *
     * <h2>Bounded, escalating to cancellation rather than waiting (CORE-04)</h2>
     * <p>{@code stop} is bounded by the node's own work — "a node that never completes never stops"
     * is the SPI's own wording — and this method used to wait for that with an unbounded
     * {@code join()}. That made every {@code GraphRunner} user's shutdown hostage to a single node:
     * one node killed by an {@link Error} left a stop stage that nothing could ever complete, because
     * the actor that was going to complete it was the thing that died, and {@code close()} blocked
     * forever. An {@code Error} is not an {@code Exception}, so no adapter catch clause saw it and no
     * timeout existed to make the wait give up.</p>
     *
     * <p>Escalation is the bounded operation the SPI actually offers: {@code cancel} abandons accepted
     * messages and completes regardless of what a node is computing. A stop that <em>fails</em> is not
     * escalated — a node whose {@code onStop} threw has stopped, badly, and the caller should see the
     * failure rather than have it converted into a cancellation.</p>
     *
     * <h2>In-flight traversals are released first (CORE-03)</h2>
     * <p>Their parked branches are completed rather than left pending, their scheduled join timeouts
     * are cancelled, and their join records are discarded. A runner that stopped its actors while
     * leaving a join timeout scheduled would keep the traversal's state reachable from the scheduler
     * until the deadline passed, which is a leak whose lifetime is set by graph configuration rather
     * than by the runner's own lifecycle. That is why this runs before the stop and not after it.</p>
     *
     * <p>It is bounded by the same {@code shutdownBound}, for the reason the node stop is: draining
     * a coordinator waits for store operations already in flight, so an unbounded wait here would
     * reintroduce exactly the hostage-to-one-slow-dependency shutdown the bound exists to prevent,
     * one step earlier. The worst case is therefore three bounds — the drain, the stop, and the
     * cancellation it escalates to.</p>
     *
     * @throws IllegalStateException if a node is still not terminal after both node bounds elapsed
     */
    @Override
    public void close() {
        // Every traversal still in flight is asked to stop before anything is torn down, and
        // its pause gate released, so a hop that was parked -- or that would have been dispatched
        // during the teardown below -- ends the traversal instead of running against a runner whose
        // actors, join store and graph manager are being closed underneath it. Stopping the actors
        // alone never expressed this: the default nature is WORKER, so a traversal that is still
        // moving spawns its next instance after terminable() has taken its snapshot.
        //
        // Control endpoints move the gate release off the caller's thread, but shutdown deliberately
        // does not. This is a shutdown, not a request: nothing is waiting on it to
        // answer quickly, and the ordering it asserts -- every parked hop unwound before the actors
        // are stopped and the join store is closed -- is exactly what an off-caller release would
        // give up. The reason for the move does not apply either, because the thread paying for the
        // released hop's writes here is the one that asked for the shutdown.
        //
        // Stated as an argument rather than as a measurement, because it was measured and nothing
        // held it: the whole core module passes with OFF_CALLER here too, so no test constrains this
        // line. Left conservative deliberately, and recorded so the next reader knows the suite will
        // not stop them from widening it.
        coordinators.keySet().forEach(traversalId -> cancelTraversal(traversalId, GateRelease.ON_CALLER));
        // Refuse every queued admission before draining traversal continuations. Active leases are
        // released by their attempt handlers; queued hops fail immediately and cannot spawn actors.
        traversalAdmission.close();
        // The loop above reaches only the gates of traversals this runner registered, and since
        // pauseTraversal stopped requiring a coordinator there can be gates it does not reach -- one
        // per pause that landed inside a submission's startup window and whose traversal never got to
        // runner.execute, which is what a failed submission looks like from here. This is where those
        // end, so a gate's lifetime is bounded by the runner's and pausedTraversals cannot grow past
        // it. Iterating the key set while releasePauseGate removes from the same map is safe on a
        // ConcurrentHashMap, whose iterators are weakly consistent by contract.
        //
        // Completed rather than merely dropped, and ON_CALLER like the line above, for the same
        // ordering: a hop parked on one of these unwinds before the actors are stopped and the join
        // store is closed. In the ordinary case there is no such hop -- a parked hop means execute()
        // ran, and every traversal execute() registered was handled above -- but "in the ordinary
        // case" is the whole of that claim, and completing a gate nobody waits on costs nothing.
        pausedTraversals.keySet().forEach(traversalId -> releasePauseGate(traversalId, GateRelease.ON_CALLER));
        releaseTraversals();
        // Every worker instance still serving an invocation is released before the stop below, so the
        // stop it escalates from is a stop of things that are supposed to still be here. A worker
        // whose traversal was abandoned by releaseTraversals() has nobody left to release it, and
        // leaving it to the domain's structural backstop would make "no orphan outlives its
        // deployment" (ADR 0024 §3) a property of the adapter rather than of this runner.
        // Snapshotted BEFORE the registry is cleared, and that order is the whole of it. Clearing
        // first drops the instances out of terminable(), so the bounded stop and the cancellation it
        // escalates to would find nothing to escalate and close() would return promptly while live
        // worker actors kept running -- a shutdown that reports success by forgetting what it owed.
        List<Terminable> targets = terminable();
        workers.deregisterAll();
        traversalInstances.deregisterAll();
        try {
            if (!awaitTermination(targets, engine::stop) && !awaitTermination(targets, engine::cancel)) {
                throw new IllegalStateException("Nodes did not terminate within " + shutdownBound
                        + " of a stop and a further " + shutdownBound + " of a cancellation: "
                        + unterminated(targets));
            }
        } finally {
            // In the finally because the store must be released on the escalation path too: a runner
            // that owns its store and fails to stop a node would otherwise leak the store as well as
            // the node, and the caller has no handle on it to close itself.
            if (ownsJoinStore) {
                joinStore.close();
            }
        }
    }

    /**
     * Terminates every in-flight traversal's join state, and never lets that fail or delay shutdown.
     *
     * <h2>What a drain that times out actually leaves behind</h2>
     * <p>Not the traversal's process memory. {@link JoinCoordinator#terminate()} cancels the
     * scheduled join timeouts and completes the parked branches before it waits for anything, so
     * those are gone whether the store answers or not. What survives is one thing: the
     * <strong>store record</strong> of each join whose {@code discard} was never issued, because
     * issuing it has to wait for the settle that may still be inside its compare-and-set. That
     * record is an orphan of exactly the shape a crashed runtime leaves, and
     * {@code purgeSettledBefore} is what reclaims it — on the operator's purge schedule, not on
     * this method's. Nothing here reclaims it, and nothing here waits for it.</p>
     *
     * <p>This distinction is worth stating because the reverse claim was made here for a while and
     * was materially wrong: {@code purgeSettledBefore} reclaims records. It does not reclaim a
     * scheduled timeout, a {@code SecurityContext}, an arrival payload or a parked branch, and when
     * those were released behind the drain a store that never answered kept all four for up to
     * {@link JoinSpec#MAX_TIMEOUT}.</p>
     *
     * <h2>Why the handle is not dropped</h2>
     * <p>A coordinator whose drain has not finished stays in {@code coordinators}. Clearing the map
     * unconditionally reset {@link #liveJoinTimeoutCount()} to zero as a side effect of giving up,
     * so the diagnostic read zero at the one moment it exists to report on, and no assertion written
     * against it could see anything. The entry is retired when the drain completes — including
     * after the bound has expired — and in the worst case dies with the runner.</p>
     */
    private void releaseTraversals() {
        var pending = new ArrayList<Termination>();
        coordinators.forEach((traversalId, coordinator) -> pending.add(
                new Termination(traversalId, coordinator, coordinator.terminate().toCompletableFuture())));
        // A drain that lands after the bound still retires its own entry, so a slow store leaves a
        // temporary observation rather than a permanent one.
        pending.forEach(termination -> termination.stage().whenComplete((ignored, error) ->
                coordinators.remove(termination.traversalId(), termination.coordinator())));
        try {
            CompletableFuture.allOf(pending.stream().map(Termination::stage)
                            .toArray(CompletableFuture[]::new))
                    .get(shutdownBound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException | RuntimeException ignored) {
            // Shutdown must not depend on store health, exactly as ExecutionStore.close() requires.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            // Retire the traversals that finished, synchronously, so a caller that has seen close()
            // return can assert on the count rather than race the completion callbacks above.
            pending.stream().filter(termination -> termination.stage().isDone()).forEach(termination ->
                    coordinators.remove(termination.traversalId(), termination.coordinator()));
        }
    }

    /** One in-flight traversal being released by {@link #releaseTraversals()}. */
    private record Termination(UUID traversalId, JoinCoordinator coordinator,
                               CompletableFuture<Void> stage) {
    }

    /**
     * @return whether every node reached a terminal outcome within the bound; {@code false} only when
     *         the wait timed out, which is the single case escalation can help with
     */
    private boolean awaitTermination(List<Terminable> targets,
                                     java.util.function.Function<NodeRef, CompletionStage<Void>> request) {
        var pending = CompletableFuture.allOf(targets.stream()
                .map(Terminable::ref)
                .map(request)
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new));
        try {
            pending.get(shutdownBound.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException timeout) {
            return false;
        } catch (ExecutionException failure) {
            throw new CompletionException(failure.getCause());
        } catch (InterruptedException interrupted) {
            // Restore the flag and stop escalating. close() typically runs in a finally block or a
            // try-with-resources, where swallowing the interrupt would strand whoever asked this
            // thread to stop, and starting a fresh cancellation round would ignore them twice.
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /**
     * Everything this runner owns an actor for right now: its resident nodes, plus the worker
     * instances still serving an invocation.
     *
     * <p>Read fresh on each call rather than snapshotted at construction, because the second half did
     * not exist at construction and is not supposed to be stable. That is the point: a stop is bounded
     * by live work, not by graph size, so a five-hundred-node graph with two invocations in flight
     * stops two actors and not five hundred and two.
     */
    private List<Terminable> terminable() {
        var targets = new ArrayList<Terminable>();
        residentRefs.forEach((nodeId, ref) -> targets.add(new Terminable(nodeId, ref)));
        // outstanding(), not live(): an instance whose stop was requested but never confirmed is still
        // an actor this runner is responsible for, and it is the one a bounded close must escalate.
        workers.outstanding().forEach(instance -> targets.add(new Terminable(
                instance.identity().nodeId() + "#" + instance.identity().invocationId(), instance.ref())));
        traversalInstances.outstanding().forEach(instance -> targets.add(new Terminable(
                instance.identity().nodeId() + "#" + instance.identity().traversalId(), instance.ref())));
        return List.copyOf(targets);
    }

    /**
     * One actor this runner is responsible for terminating, and the name to call it by in a
     * diagnostic.
     *
     * <p>A resident is named by its node id. A worker instance is named by node id AND invocation,
     * because one logical node may have many instances alive at once and "work did not stop" would
     * not tell an operator which of them is holding the shutdown open.
     */
    private record Terminable(String label, NodeRef ref) {
    }

    /**
     * The nodes whose engine actor is still not terminal, for the diagnostic.
     *
     * <p>A resident is named by its node id; a worker instance is named by node id and invocation, so
     * a message about a stuck shutdown says <em>which</em> invocation of a node is holding it rather
     * than naming a node that may have a dozen instances alive.
     */
    private List<String> unterminated(List<Terminable> targets) {
        return targets.stream()
                .filter(target -> engine.status(target.ref())
                        .map(status -> !status.state().terminal())
                        .orElse(false))
                .map(Terminable::label)
                .sorted()
                .toList();
    }

    /** Worker instances alive on this runner. Diagnostics, and the orphan accounting tests measure it. */
    int liveWorkerInstanceCount() {
        return workers.liveCount();
    }

    /** Traversal-scoped actors currently owned by this runner. */
    int liveTraversalInstanceCount() { return traversalInstances.liveCount(); }

    /**
     * Actors this runner created at construction, before any traversal ran. Diagnostics.
     *
     * <p>The number ADR 0024 is about: it counts the natures that are resident by contract, so for an
     * ordinary graph it is zero however many nodes the graph has.
     */
    int residentActorCount() {
        return residentRefs.size();
    }

    /** Scheduled join timeouts that are still live across every in-flight traversal. Diagnostics. */
    int liveJoinTimeoutCount() {
        return coordinators.values().stream().mapToInt(JoinCoordinator::liveTimeoutCount).sum();
    }

    /** In-flight traversals holding join state on this runner. Diagnostics. */
    int liveCoordinatorCount() {
        return coordinators.size();
    }

    /** Branches parked across every in-flight traversal's joins on this runner. Diagnostics only. */
    int liveParkedBranchCount() {
        return coordinators.values().stream().mapToInt(JoinCoordinator::liveParkedBranchCount).sum();
    }

    /**
     * Traversal ids on this runner that can never make further progress on their own.
     *
     * <p>A traversal qualifies when, for its own coordinator: no worker instance is running on its
     * behalf, no join timeout is armed for it, and yet one of its joins is holding a branch parked
     * in {@link JoinDecision.Wait} -- arrived, short of quorum, and waiting for an outcome. The third
     * condition is load-bearing and deliberately an OR-partner rather than a third zero-clause: a
     * parked branch with nothing left running or scheduled has nothing that could ever settle it,
     * whereas requiring it to be zero as well would describe an idle runner with nothing to detect,
     * not a stuck one -- {@code liveCoordinatorCount()} cannot serve that third clause instead,
     * because a traversal's coordinator entry is removed only once the traversal's own top-level
     * stage settles (see {@link #release}), which for a traversal that can never settle never
     * happens; the same non-removal that harmlessly spans the ordinary node-to-node dispatch
     * handoff also spans the entire life of a genuinely stuck traversal, so it cannot distinguish
     * the two. See {@code UnreachableExecutionCriterionTest} for both halves of that evidence.</p>
     *
     * <p>This establishes the verdict; it deliberately does not define a recovery action.</p>
     */
    Set<UUID> unreachableTraversalIds() {
        var unreachable = new LinkedHashSet<UUID>();
        coordinators.forEach((traversalId, coordinator) -> {
            if (workers.liveCount(traversalId) == 0
                    && traversalAdmission.active(traversalId) == 0
                    && coordinator.liveTimeoutCount() == 0
                    && coordinator.liveParkedBranchCount() > 0) {
                unreachable.add(traversalId);
            }
        });
        return Set.copyOf(unreachable);
    }

    /**
     * Combines the arrivals that satisfied a join into the single message the join node receives.
     *
     * <p>Ordered by branch id. Ordering by the arrivals' parent invocation identifiers, which are
     * random UUIDs, makes an {@code all} join produce its list in a
     * different order on every execution — a graph whose output depended on element position was
     * therefore nondeterministic without anything in the graph saying so.</p>
     *
     * <p>Package-private rather than {@code private} so {@code GraphRunnerMergeOrderTest}, which
     * lives in this same package, can call it directly instead of through reflection: nothing
     * outside {@code ai.ravenroot.core.runtime} gains visibility that it did not already lack, and a
     * future rename now fails the build instead of turning that test's reflective lookup into a
     * silent {@code NoSuchMethodException}.</p>
     */
    static JoinArrival merge(List<JoinArrival> arrivals) {
        var ordered = new ArrayList<>(arrivals);
        ordered.sort(Comparator.comparing(JoinArrival::branchId));
        var payloads = new ArrayList<>();
        var attributes = new LinkedHashMap<String, Object>();
        var parents = new TreeSet<UUID>();
        ordered.forEach(arrival -> {
            payloads.add(arrival.payload());
            attributes.putAll(arrival.attributes());
            parents.addAll(arrival.parentInvocationIds());
        });
        // The third of three distinct failures around a missing value. This was List.copyOf,
        // which rejects nulls: a branch that produced no value made the join throw NullPointerException
        // from deep inside a collection factory, naming neither the join nor the branch. The absence is
        // now stated where it happens. Deliberately not "carry the null along": a list containing a
        // hole would move the problem to whichever downstream node dereferenced it first, which is the
        // lucky dereference this guard prevents.
        //
        // UNPROVEN, and said so rather than implied. Unlike the other two absent-value fixes,
        // no test reaches this branch: neither a GraphML fan-in nor a programmatic PASSTHROUGH quorum
        // join delivered a null arrival here in the attempts made, so a mutation that removes the guard
        // reds nothing. The guard is strictly safer than the NullPointerException it replaces and is
        // kept for that reason, but establishing how a null arrival actually reaches merge() is join
        // semantics, which this guard deliberately does not widen into.
        Object payload;
        if (ordered.size() == 1) {
            payload = ordered.getFirst().payload();
        } else {
            for (JoinArrival arrival : ordered) {
                if (arrival.payload() == null) {
                    throw new IllegalStateException("Join branch '" + arrival.branchId()
                            + "' produced no value, so the joined list cannot be formed");
                }
            }
            payload = List.copyOf(payloads);
        }
        NodeCommand command = ordered.getFirst().command();
        if (ordered.stream().anyMatch(arrival -> !arrival.command().equals(command))) {
            throw new IllegalStateException("Fan-in arrivals carry conflicting node commands");
        }
        // Pointwise maximum over the contributors. Every contributor agrees about THIS join —
        // they are the arrivals of one bucket, so they all read the same lap for it — and they may
        // legitimately disagree about an inner join one branch went round more often than another.
        // Taking the maximum is what carries that knowledge across the fan-in; taking the first
        // contributor's would silently send a later message back into an earlier bucket of the inner
        // join. Order-independent, so it does not add a second thing the merge's determinism rests on.
        IterationContext context = IterationContext.merge(ordered.stream().map(JoinArrival::context).toList());
        return new JoinArrival(ordered.getFirst().branchId(), payload, Map.copyOf(attributes), Set.copyOf(parents),
                command, context);
    }

    /** One delivery per target; two matching edges may not disagree about that target's command. */
    private static List<TargetDelivery> targetDeliveries(List<GraphEdge> edges, NodeCommand incoming) {
        var byTarget = new LinkedHashMap<String, TargetDelivery>();
        for (GraphEdge edge : edges) {
            NodeCommand selected = incoming.equals(NodeCommand.PASSTHROUGH) ? NodeCommand.PASSTHROUGH
                    : edge.command().orElse(incoming);
            TargetDelivery previous = byTarget.putIfAbsent(edge.target(),
                    new TargetDelivery(edge.target(), selected, edge));
            if (previous != null && !previous.command().equals(selected)) {
                throw new NodeCommandConflictException(edge.target(), previous.command(), selected);
            }
            if (previous != null) {
                byTarget.put(edge.target(), new TargetDelivery(edge.target(), selected, null));
            }
        }
        return List.copyOf(byTarget.values());
    }

    private record TargetDelivery(String targetId, NodeCommand command, GraphEdge edge) { }

    /** One join and one of its branches. Precomputed; never allocated per execution. */
    private record JoinBranchRef(String joinNodeId, String branchId) {
    }

    private static final class ExecutionState {
        /**
         * The content type of every envelope this runner publishes.
         *
         * <p>The payload is <strong>empty</strong>, and that is a decision. The one fact a consumer
         * needs that the envelope's own fields do not carry is the node id — and it is not lost,
         * because {@code InvocationAdded} records the invocation-to-node binding as a transition in
         * the very same transaction, so a projection resolves it by joining on
         * {@link EventEnvelope#invocationId()}. That is the same argument the causal model makes
         * about a join's non-triggering contributors: what is already durably recorded as structure
         * does not need restating in an append-only row. Choosing a payload <em>schema</em> here would
         * be a second irreversible decision on top of the causal one, so this event deliberately keeps
         * an empty payload and leaves the projection's wire format unchanged.</p>
         */
        private static final String EVENT_CONTENT_TYPE = "application/vnd.ravenroot.execution-event";

        private final Set<String> visitedNodes = ConcurrentHashMap.newKeySet();
        private final Set<String> defaultedNodes = ConcurrentHashMap.newKeySet();
        private final Set<String> bypassedNodes = ConcurrentHashMap.newKeySet();
        /**
         * Edges a bypassed node's own hardcoded {@code "continue"} outcome could never
         * select. Populated only for nodes actually bypassed -- see the write site next to {@code
         * graph.nextEdges} in {@code run()} -- which is not the same condition as "the policy was
         * {@code TEST_PASSTHROUGH}": an edge naming the {@code passthrough} command bypasses its own
         * target under {@code STANDARD} too, and this set fires for that node exactly as it would
         * under a test submission. Names, for whichever node was bypassed, which outgoing edges the
         * bypass did not take, instead of leaving that to
         * be inferred from a branch point the traversal silently never reached.
         */
        private final Set<String> untakenEdges = ConcurrentHashMap.newKeySet();
        private final UUID traversalId;
        private final ExecutionRecorder recorder;
        /** Which join branches this traversal can still deliver on. Bounded by the graph. */
        private final BranchLiveness liveness;
        private final ExecutionMonitor.ExecutionIdentity identity;
        private final ExecutionIdentitySource identitySource;
        private final Clock clock;
        /**
         * The traversal-accepted event, minted with the traversal and published with the first batch
         * that carries anything. Null when nothing is journalling.
         *
         * <p>It is minted at construction rather than at publication because the start node's
         * dispatch names it as its cause, and a cause has to have an identity before its effect is
         * authored. It is <em>published</em> in the first batch this state writes, which is the start
         * node's, so it precedes that node's own event in
         * {@link ai.ravenroot.api.persistence.JournalRecord#streamSequence()} order — batch event
         * order is stream order by contract.</p>
         */
        private final UUID traversalAcceptedEventId;
        private boolean traversalAcceptedPublished;
        private ProcessInstance lifecycle;
        /**
         * What reached each terminal, boxed so that "no arrival" is distinguishable from "arrived
         * carrying null" — a node is entitled to produce a null payload, so a bare field could not
         * tell the two apart.
         *
         * <p>One <em>kind</em> writes each field, which is not the same as one write. The following two
         * conclusions are false.</p>
         *
         * <p><strong>First: "the terminals fire once" is false.</strong> Nothing in
         * {@link ai.ravenroot.core.graph.GraphDefinition} forbids cycles, and a cycle back through a
         * terminal reaches it again. Measured on {@code start -> probe -(retry)-> error -> probe},
         * thirty runs: the error node completes twice every time.</p>
         *
         * <p><strong>Second: "the repeat writes are causally ordered" is false in general.</strong>
         * It is a property of the <em>topology under the default join configuration</em>, not of this
         * mechanism. A node carrying {@code joinPolicy=each} is not registered as a fan-in at all, so
         * no coordinator serialises its arrivals and it is invoked once per arrival — concurrently.
         * Two such invocations reaching a terminal write this field with no ordering between them.
         * Measured over 200 traversals each: with {@code each} on the terminal, two completions in
         * 200 of 200 and a payload of {@code {second=179, first=21}}; with {@code each} on an
         * ordinary merge and the terminal untouched, two completions in 200 of 200 and
         * {@code {B-lap=155, A-lap=45}}. The second needs no cycle — a fan-out into an {@code each}
         * merge is enough.</p>
         *
         * <h2>The race precondition</h2>
         * <p>Graph-wide conditions such as "the terminals fire once", "the graph preserves a
         * terminal's fan-in coordination" or "every fan-in on a route into that terminal is
         * coordinated" are insufficient. The last condition holds vacuously when
         * {@code JoinSpec.validate} returns an empty map, yet a terminal can still be entered twice
         * and its payload can vary. The invariant is therefore stated in the other direction below:
         * what a race <em>requires</em>.</p>
         *
         * <p><strong>The cardinality premise.</strong> These fields are keyed by terminal
         * <em>kind</em>, not by
         * node identity: one field for {@code END}, one for {@code ERROR}. So "two writers of this
         * field" and "this terminal invoked twice" are the same statement only while a graph holds
         * <strong>at most</strong> one node of each kind — which
         * {@link ai.ravenroot.core.graph.GraphDefinition} enforces, on every path that materialises a
         * definition. Were that to change, a fan-out whose branches failed into two <em>different</em>
         * error terminals would produce two concurrent writers of one field through none of the three
         * mechanisms below: no fan-in, no re-entry, no double delivery. Widening the ceiling to one or
         * more error terminals would therefore add a mechanism. {@code CyclicTerminalPayloadTest}
         * pins the premise itself, so its loss fails a test
         * instead of quietly invalidating this reduction.</p>
         *
         * <p><strong>"At most", not "exactly".</strong> The <em>ceiling</em> is load-bearing: two
         * writers of one field break the reduction. A floor is not — with zero nodes of a kind the field has no
         * writer at all, so the traversal reports the other terminal's arrival or none, and no
         * mechanism can be introduced by removing a node. {@code AbsentErrorTerminalTest} verifies
         * that conclusion across three topologies —
         * a coordinated fan-in, an {@code each} fan-in and a {@code START} re-entry — with and without
         * an error terminal, and finds the payload outcome spaces identical cell by cell, including
         * the two that race.</p>
         *
         * <p>Given that premise, two writers contend for this field only if the terminal is invoked
         * twice concurrently. Walking that back: a node is invoked twice concurrently only through
         * one of <strong>three</strong> mechanisms, and each is checkable.</p>
         * <ol>
         *   <li><strong>A fan-in on the route that is not coordinated.</strong> "Fan-in" here means
         *       <em>operationally</em> what {@link JoinSpec#validate} returns a spec for, not
         *       structurally "a node with two or more predecessors" — the two differ, and that gap is
         *       the whole of items 1 and 2. {@code joinPolicy=each} removes a node from the result
         *       while leaving its predecessors in place, so its arrivals are neither merged nor
         *       serialised.
         *       <p><strong>This is reachable with no node property anywhere, and by default.</strong>
         *       A document carrying the graph-level marker
         *       {@code join.semantics=declared} is read with joins only where the author declared
         *       one, so an ordinary multi-predecessor node — including {@code END} — gets no spec and
         *       its arrivals are uncoordinated. That is the intended declared-join semantics, not a defect:
         *       a node becomes a barrier because its author asked, and the gap between the structural
         *       and operational readings of "fan-in" is now the <em>ordinary</em> case in such a
         *       document rather than an opt-out from it. {@code ERROR} is carved out and keeps its
         *       quorum of one — see {@link ai.ravenroot.core.graph.JoinSemantics} for why that
         *       carve-out is behaviour-preserving and why {@code END} could not have the same one.
         *       The cost at {@code END} is measured and pinned by
         *       {@code UndeclaredFanInIntoEndTerminalPayloadTest}: the escape hatch is one declared
         *       property, and this item is the reason an author needs it.</p></li>
         *   <li><strong>{@code START} re-entered by a return transition.</strong> {@code validate}
         *       excludes {@code START} <em>unconditionally, before the predecessor count is even
         *       consulted</em>, and deliberately: legacy state-machine graphs route back into their
         *       entry point, and treating those edges as a join would
         *       stall the traversal before {@code START} ran. The cost is that a re-entered
         *       {@code START} is a structural fan-in nobody can coordinate — there is no property that
         *       turns it into one — and everything downstream inherits the concurrency. This is the
         *       third case, alongside {@code each}, and it is why the operational reading is the one
         *       used above: under the structural reading the condition would be unsatisfiable for
         *       such a graph rather than false, which is not a useful thing to tell a reader.</li>
         *   <li><strong>A node delivering more than once to the same successor.</strong> Not possible
         *       today, and closed at the dispatch site rather than by absence of a node type:
         *       {@code targetDeliveries} collapses a completion's edges into a
         *       {@code LinkedHashMap} keyed by target id, so parallel edges to one target yield one
         *       delivery. A node type that multiplied over a collection would add a mechanism
         *       here.</li>
         * </ol>
         *
         * <p><strong>Derived, not measured — and that label covers the enumeration, not the step
         * above it.</strong> Items 1 and 2 are each pinned by a test in
         * {@code CyclicTerminalPayloadTest}, item 3 is closed by the code cited, and nothing proves
         * there is no fourth mechanism; what would falsify that is another way for one node to be
         * invoked twice concurrently, not another graph built from these three. The reduction that
         * precedes the list is a separate claim with its own premise, which is why that premise is now
         * named and pinned rather than treated as free: labelling the enumeration while leaving the
         * reduction unexamined is how a true derivation ends up resting on an unstated axiom.
         * Measured
         * instances: {@code each} on the terminal races, {@code each} on an upstream merge races with
         * the terminal untouched, {@code START} re-entry races with no property anywhere, and an
         * undeclared fan-in into {@code END} races in any document carrying the
         * {@code join.semantics=declared} marker, also with no property anywhere.</p>
         *
         * <p><strong>Declared-join semantics.</strong> In a marker-present document, removing
         * {@code each} from an undeclared fan-in does not add coordination; it removes an explicit
         * synonym for the absence the node already has. Therefore "no {@code each} anywhere" is not
         * evidence that mechanism 1 is absent. Coordination depends on the document's semantics
         * version and the author's declarations, and {@link JoinSpec#validate} is the authoritative
         * answer.</p>
         *
         * <p>Where no mechanism applies, repeat arrivals are serialised — by the coordinator when they
         * are concurrent, by causality when they are laps of a cycle — and the payload is stable
         * across runs. Where one applies, it varies.
         * The cross-terminal choice below is unaffected in every case: it never depended on how many
         * times a terminal was entered.</p>
         */
        private volatile Object[] endTerminalPayload;

        /** @see #endTerminalPayload */
        private volatile Object[] errorTerminalPayload;

        /**
         * What a branch that ran out of edges on an ordinary node produced, or {@code null}
         * if no branch ended that way.
         *
         * <p>The third terminal arrival, after the two explicit terminal kinds, and the one nobody had
         * declared was a terminal at all. A node with no outgoing edge for the outcome it produced —
         * and none for {@code continue} either, after the retry above — ends its branch as surely as
         * {@code END} does; it simply does not say so in its {@code kind}. Previously that meant the
         * branch's result was discarded, and a traversal whose only branch ended this way reported
         * {@code COMPLETED} with a {@code null} payload.</p>
         *
         * <h2>First writer wins, and what that does not settle</h2>
         * <p>Written under {@code synchronized} and only when unset, so a graph with several
         * dead-ending branches reports one of them rather than interleaving. That makes the value
         * <em>non-null</em>; it does not make it <em>determined</em>. When several branches dead-end,
         * which one is reported remains a semantic choice rather than a data race this field resolves.</p>
         *
         * <p>This is the dead-ending-branch selection contract and <b>not</b> the neighbouring
         * failure-aggregation contract, which concerns a different set of cases: it asks which of
         * two branches that both <em>failed</em> into one error terminal
         * the run reports. A branch that dead-ends has not failed — it succeeded and ran out of
         * edges — so that answer would not decide this, and folding the two together would apply a
         * failure-aggregation rule to a case it does not cover.</p>
         */
        private Object[] danglingTerminalPayload;

        /**
         * Records a dead-ending branch's payload, keeping the first.
         *
         * <p>{@code synchronized} rather than {@code volatile} because this is a read-modify-write:
         * the two dead-ending branches of one graph settle on different threads, and a plain
         * check-then-assign would let both pass the check. It shares the monitor with the lifecycle
         * writers above for the same reason they share it with each other.</p>
         */
        private synchronized void recordDanglingTerminal(Object payload) {
            if (danglingTerminalPayload == null) {
                danglingTerminalPayload = new Object[] {payload};
            }
        }

        /** @see #danglingTerminalPayload */
        private synchronized Object[] danglingTerminal() {
            return danglingTerminalPayload;
        }

        /**
         * The traversal's result payload: {@code END}'s if the traversal reached it, otherwise
         * {@code ERROR}'s, otherwise a branch that ran out of edges on an ordinary node,
         * otherwise none.
         *
         * <h2>Why the third rank is last, and why it exists</h2>
         * <p>It is last for the same reason {@code ERROR} is second: ranking it higher would change
         * what an existing graph reports. A graph that reaches {@code END} on one branch while another
         * dead-ends reported {@code END}'s payload before this rank existed, and still does. The rank
         * therefore only fills the case that was previously undefined and silently {@code null} — a
         * traversal in which <em>no</em> branch reached either declared terminal: the branch produced
         * a payload, the final node received it,
         * and the run returned nothing because that node's {@code kind} was not {@code END}.</p>
         *
         * <h2>Why this is a read-time decision and not an assignment</h2>
         * <p>Previously there was exactly one writer — {@code END}, unique per graph and fired once —
         * so a single field was deterministic by construction. Two terminals made it two writers of
         * one {@code volatile} field with no ordering between them, and the last one home won. On a
         * graph whose branches reach {@code END} once and {@code ERROR} twice, forty identical
         * traversals returned three different payloads:
         * {@code {END-PAYLOAD=5, ERROR(first)=20, ERROR(second)=15}}.</p>
         *
         * <p>Recording each terminal separately and choosing at read time removes the ordering from
         * <strong>the choice between the two terminals</strong>: neither field is written by the
         * other's arrivals, and which one is reported is decided once, on the completion path, after
         * every branch has settled. "First writer wins" was the other candidate and was measured
         * rather than assumed — a compare-and-set in the same place returned
         * {@code {END-PAYLOAD=33, ERROR(first)=7}} over the same forty runs. It moves the odds; it
         * does not remove the race, because which terminal a scheduler reaches first is exactly the
         * thing that was never fixed.</p>
         *
         * <h2>What this deliberately does not remove</h2>
         * <p>The scope above does not remove ordering "entirely". One case survives: <strong>two branches that
         * both fail while {@code END} is never reached</strong>. Both arrive at the one error
         * terminal, its default quorum is one, so it fires on whichever arrives first and the
         * other is discarded — and <em>which</em> failure is therefore reported still varies between
         * identical runs. Measured over two hundred traversals: {@code {first=175, second=25}}.
         * This code does not choose between them, because it is not a data race to close but a question to answer —
         * "when two things fail at once, which one does the run report?" — a semantic choice not made
         * here. {@code ErrorTerminalPayloadTest} pins the invariants that do hold around it:
         * the terminal fires exactly once, exactly one arrival is discarded, and the payload is one
         * of the two failures.</p>
         *
         * <p>A second case survives for a different reason and is documented on
         * {@link #endTerminalPayload}: a graph carrying {@code joinPolicy=each} where a terminal's
         * arrivals would otherwise be coordinated, which the editor stamps onto legacy state-machine
         * imports without excluding terminals. Both are races <em>within</em> a single terminal's own
         * field. Neither reopens the one this method exists to close, because which of the two fields
         * is reported never depended on how, or how often, either was written.</p>
         *
         * <h2>Why END outranks ERROR, and what that deliberately does not decide</h2>
         * <p>{@code END} outranks {@code ERROR} to preserve the existing outcome: a traversal that
         * reaches {@code END} reports {@code END}'s payload, including traversals in which a failure
         * route ran and was handled ({@code GraphRunnerFailureRouteTest} pins exactly that:
         * {@code boom} fails, the handler recovers, and the result carries the handler's value
         * through {@code END}). Ranking
         * {@code END} first preserves that outcome untouched. A traversal that reaches {@code ERROR}
         * and never {@code END} instead reports the error-terminal payload rather than {@code null}.</p>
         *
         * <p>The opposite ranking — a traversal that touched {@code ERROR} reports the error even
         * though it also completed — would be a different semantic contract and is not applied here.</p>
         */
        Object resultPayload() {
            Object[] arrival = endTerminalPayload != null ? endTerminalPayload
                    : errorTerminalPayload != null ? errorTerminalPayload : danglingTerminal();
            return arrival == null ? null : arrival[0];
        }

        private ExecutionState(UUID processInstanceId, UUID traversalId, String ingressNodeId,
                               BranchLiveness liveness, ExecutionRecorder recorder,
                               ExecutionMonitor.ExecutionIdentity identity,
                               ExecutionIdentitySource identitySource, Clock clock) {
            this.traversalId = traversalId;
            this.recorder = recorder;
            this.liveness = liveness;
            this.identity = identity;
            this.identitySource = identitySource;
            this.clock = clock;
            this.traversalAcceptedEventId = journalling() ? identitySource.nextEventId() : null;
            lifecycle = new ProcessInstance(processInstanceId, ProcessInstanceStatus.ACCEPTED, Map.of())
                    .addTraversal(new Traversal(traversalId, ingressNodeId, TraversalStatus.ACCEPTED, Map.of()))
                    .transitionTo(ProcessInstanceStatus.RUNNING)
                    .transitionTraversal(traversalId, TraversalStatus.RUNNING);
        }

        private ExecutionState(UUID processInstanceId, UUID traversalId, String ingressNodeId,
                               BranchLiveness liveness, ExecutionRecorder recorder,
                               ExecutionMonitor.ExecutionIdentity identity,
                               ExecutionIdentitySource identitySource, Clock clock,
                               ProcessInstance storedLifecycle) {
            this.traversalId = traversalId;
            this.recorder = recorder;
            this.liveness = liveness;
            this.identity = identity;
            this.identitySource = identitySource;
            this.clock = clock;
            this.traversalAcceptedEventId = null;
            this.traversalAcceptedPublished = true;
            this.lifecycle = java.util.Objects.requireNonNull(storedLifecycle, "storedLifecycle");
            if (!lifecycle.processInstanceId().equals(processInstanceId)
                    || !lifecycle.traversals().containsKey(traversalId)
                    || !lifecycle.traversals().get(traversalId).ingressNodeId().equals(ingressNodeId)) {
                throw new IllegalArgumentException("stored re-entry traversal scope mismatch");
            }
        }

        private synchronized void reentryStarted() {
            var transition = new ExecutionTransition.TraversalTransitioned(
                    traversalId, TraversalStatus.RUNNING);
            record(List.of(transition), List.of());
            lifecycle = fold(lifecycle, List.of(transition));
        }

        private UUID traversalAcceptedEventId() {
            return traversalAcceptedEventId;
        }

        /** Accepts a suspension only when this exact delivered invocation is durably waiting. */
        private boolean acceptsApprovalSuspension(UUID approvalId, NodeMessage delivered) {
            return recorder != null && recorder.confirmsToolApproval(approvalId, delivered);
        }

        /**
         * Whether this traversal publishes to a journal at all.
         *
         * <p>Both halves are load-bearing. Without a recorder there is no transaction to publish
         * inside, and an external forwarder was rejected. Without the capability the adapter would
         * reject the whole batch, taking the transitions down with it — so the journal is omitted and
         * the transitions, which are the durability guarantee, survive.</p>
         */
        private boolean journalling() {
            return recorder != null && recorder.supportsJournal();
        }

        /**
         * Whether the traversal has already reached a terminal status.
         *
         * <p>The aggregate refuses to transition an invocation once its traversal is terminal, and
         * that rule is right: a finished traversal is a snapshot, not a ledger still taking entries.
         * The runtime has to respect it rather than fight it, because a fan-in that fails or times
         * out now ends the traversal while superseded branches are still running — and those
         * branches will finish, and will try to record it.
         *
         * <h2>What a branch that outlives its traversal loses, stated as measured</h2>
         * <p><strong>It loses both durable registers, not one.</strong> The three guards below return
         * before {@link #record(List, List)} is reached, and that single call carries the aggregate
         * transitions <em>and</em> the journalled events — so a post-closure branch contributes no
         * aggregate row and no journal row. Only {@link ExecutionMonitor}'s live, in-process stream
         * keeps them, because {@code run()} calls the monitor unconditionally next to every guarded
         * call below; that is the observability an operator gets for work that outlived its traversal,
         * and it is not durable.</p>
         *
         * <p>The wider loss is deliberate rather than incidental, and the discarded alternative is what
         * shows it: publishing the event without the transition writes a row whose {@code invocationId}
         * the aggregate never accepted, so nothing can ever resolve it to a node — the journal's rows
         * carry no node id, and {@code InvocationAdded} is the only binding. On an append-only log that
         * row is permanently unreadable.
         * {@code SurvivingBranchAfterFailedTraversalTest#theDiscardedAlternativeWritesJournalRowsThatResolveToNoNode}
         * performs that exact call — {@code record(List.of(), events)} on a closed traversal, through
         * this same recorder — and measures the two rows resolving to no node at all.</p>
         *
         * <p>The residue this leaves is worth knowing before reading a stored instance: a branch caught
         * mid-flight stays {@code RUNNING}, with a {@code RUNNING} attempt, inside a {@code FAILED}
         * traversal, and its journalled {@code NODE_STARTED} never gets a settle row.
         * <strong>It is distinguishable from a crashed worker</strong> — a crash leaves the traversal
         * {@code RUNNING}, because nobody was alive to write its failure, and that status is durable
         * beside the frozen invocation. What today's {@code ExecutionRecoveryService.recover} reads is
         * {@code attempt.status()} alone, and "the code does not read the field" is not "the field is
         * not there". The measured consequence is narrower and worse than a misread: the frozen attempt
         * <em>is</em> claimable — neither {@code InMemoryExecutionStore#scheduledAttempts} nor
         * {@code SqliteExecutionStore#claimableAttempts} filters on the traversal's status — and once
         * claimed it can be neither dispatched nor parked, because both write through this aggregate
         * and meet {@link ProcessInstance}'s terminal guard. The exception leaves {@code sweepOnce()},
         * abandoning every other item claimed in that tenant's batch undecided. The fault is
         * <strong>latent</strong>: {@code sweepOnce()} has no production caller in this repository
         * today. Changing recovery behavior is outside this code path.</p>
         *
         * <p>The three guards are also not written alike, and the difference is worth noting rather
         * than relying on: {@link #nodeCompleted} and {@link #nodeFailed} test this field as their
         * first statement, while {@link #nodeStarted} adds to {@link #visitedNodes} before testing it —
         * as {@code run()} does for {@code defaultedNodes}, {@code bypassedNodes} and the terminal
         * payload fields after a guarded call has returned. None of that is observable: every one of
         * those fields is read only by the {@link GraphExecutionResult} built on the success branch of
         * {@code run()}, and these guards can only fire after {@link #executionFailed()}, which yields
         * a failed stage and no result.</p>
         */
        private boolean terminal;

        /**
         * @param causedBy the journalled event that triggered this dispatch — the predecessor's
         *                 completion, the traversal-accepted event for the start node, or the
         *                 join-satisfying arrival's completion for a fan-in. Never {@code null} while
         *                 journalling, because the cause of a node event is always inside this
         *                 journal; {@link EventEnvelope}'s contract makes absence mean "the cause is
         *                 outside", so a null here would publish a false statement.
         * @return the identity of the node-started event, for its own completion to name as cause,
         *         or {@code null} when nothing was journalled
         */
        private synchronized UUID nodeStarted(String nodeId, Set<UUID> parentInvocationIds,
                                              UUID invocationId, UUID attemptId, NodeCommand command,
                                              UUID causedBy) {
            visitedNodes.add(nodeId);
            if (terminal) {
                // A branch that outlived its traversal writes neither transitions nor events. The
                // aggregate refuses the transition, and publishing the event alone would put an
                // effect in the journal whose cause is there but whose own effects never can be.
                // It stays visible on the monitor's live stream, which run() publishes unconditionally.
                return null;
            }
            var transitions = List.<ExecutionTransition>of(
                    new ExecutionTransition.InvocationAdded(traversalId,
                            new NodeInvocation(invocationId, nodeId, parentInvocationIds,
                                    NodeInvocationStatus.SCHEDULED, List.of(), command)),
                    new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                            NodeInvocationStatus.RUNNING),
                    new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                            new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)),
                    new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                            NodeAttemptStatus.RUNNING));
            UUID startedEventId = eventId();
            // Durable before the engine send, because the caller invokes this immediately before it.
            // This is the ordering PERS-04 reads as "sent, outcome unknown"; reversing it would make
            // a crashed attempt indistinguishable from one that never started.
            record(transitions, events(ExecutionEventType.NODE_STARTED, startedEventId, causedBy,
                    invocationId, attemptId));
            lifecycle = fold(lifecycle, transitions);
            return startedEventId;
        }

        /**
         * <h2>{@code NODE_DEFAULTED} is journalled as its own type, not as a flag on the completion
         * </h2>
         * <p>Previously this was the one node event type published only to {@link ExecutionMonitor}'s
         * in-memory stream and never to the journal, so a deployment that composed a journal-capable
         * store could not answer <em>how many times</em> a node defaulted from any durable source —
         * only <em>which</em> nodes did, from {@link GraphExecutionResult#defaultedNodes()}, which is
         * a set. The cheaper-looking alternative was a boolean on the {@code NODE_COMPLETED} row that
         * is written anyway. It was considered and rejected, for reasons worth keeping because the
         * journal is append-only and this shape cannot be revised later.</p>
         *
         * <p><strong>The bypass precedent decides the shape, and it points at a type.</strong>
         * {@code NODE_BYPASSED} is the same kind of fact — a completion the framework qualifies,
         * where the node's own behavior did not do the work — and it is journalled by <em>replacing</em>
         * the completion's type, which is what {@code completionType} below selects. Adding a
         * defaulted flag would mean this runtime spells two members of one family two different ways
         * in one journal, and a reader would have to know which fact is a type and which is a field.
         * Symmetry here is not aesthetics: it is the difference between one rule and two.</p>
         *
         * <p><strong>The cheap flag makes old rows lie — but that is a property of the cheap
         * implementation, not of append-only storage.</strong> A <em>primitive</em>
         * {@code boolean} is what makes every legacy {@code NODE_COMPLETED} row written without this fact
         * read {@code false} — "this completion was not defaulted" — where the truth is "nobody
         * recorded whether it was", permanently wrong for exactly the rows that did default. Absence
         * is nevertheless representable: {@link EventEnvelope}'s canonical form encodes every absent
         * field with a distinct {@code ABSENT} marker rather than an empty one, expressly "so
         * {@code null} and {@code ""} do not collide either", so a nullable {@code Boolean} would
         * leave old rows at <em>absent</em> — the same silence a new type gives them. The lie is
         * therefore a choice, and a reader who opens {@link EventEnvelope} can see that it is one.</p>
         *
         * <p><strong>What actually decides it is mechanical, and it is the cost of the two shapes on
         * the port, the schema and the digest.</strong> {@link EventEnvelope#eventType()} is a
         * {@code String} and not an enum precisely so that "every new event type would be a port
         * change and a schema migration" is <em>not</em> true here: a new type costs nothing on
         * either. A field is the opposite on all three counts — {@code EventEnvelope}'s
         * {@code canonicalForm} is the format the digest is taken over, and its own contract is that
         * "adding a field changes every digest this build produces, so it must be accompanied by a
         * {@link EventEnvelope#CURRENT_VERSION} bump", whose contract in turn is to be bumped "only
         * for a change a consumer must notice". So the flag costs a port change, a schema migration
         * and an envelope-version bump that every existing consumer must be told about; the type
         * costs none of the three. That is the argument that closes the question, and the nullable
         * {@code Boolean} above does not escape it — it is still a field.</p>
         *
         * <p>Silence over a wrong row is still the standard being applied, and
         * {@link #executionCompleted()} already applies it when it refuses to journal a
         * traversal-terminal event whose causation would sometimes be false.</p>
         *
         * <p><strong>That same refusal does not extend to this type, and the difference is the
         * reason it does not.</strong> A traversal terminal has no single answer to the causal
         * model's removal test: sometimes a journalled completion triggers it, sometimes a join
         * timeout that is not journalled at all. {@code NODE_DEFAULTED} has one answer on every
         * path — it is emitted because this node's attempt was running, and the attempt was running
         * because this node started — which is the identical cause {@link #nodeFailed} already names.
         * So it is published with {@code startedEventId} as its causation, and the completion beside
         * it keeps naming that same start rather than naming the defaulted row: apply the removal
         * test and deleting the defaulted event changes nothing about why the completion was emitted.
         * Two siblings sharing one cause is already ordinary here — a fan-out's several
         * {@code NODE_STARTED} events all name one predecessor's completion.</p>
         *
         * <p><strong>What the row carries, and why nothing more.</strong> Exactly the envelope every
         * other node event carries: identity, tenant, instance, traversal, invocation, attempt,
         * causation, correlation, graph version, timestamp, and an empty payload. Specifically it does
         * <em>not</em> carry the human-readable detail string the in-memory event has
         * ("unknown behavior executed as pass-through"), nor the behavior name that was not found.
         * The node id is not on the envelope either, by the same rule every other node event follows —
         * it is resolved by joining {@code invocationId} against the durably recorded
         * {@code InvocationAdded}. The behavior name is graph content, therefore caller-supplied
         * unbounded text, and putting it in a permanent row is the shape SEC-09 already refused for
         * the pass-through marker. What an operator needs from a durable row is <em>that</em> this
         * invocation defaulted, and the invocation resolves to the node, and the node's declared
         * behavior is in the pinned {@code graphVersion} the row already names.</p>
         *
         * <p>Ordering is list order, which {@link ai.ravenroot.api.persistence.ExecutionBatch#events()} preserves into
         * {@code streamSequence}: the defaulted row precedes the completion in the same transaction,
         * matching the in-memory stream's own order, which {@code TelemetryBridge} depends on when it
         * declines to end a node span on a defaulted event.</p>
         *
         * @param startedEventId this node's own start, which is what made this completion be emitted
         * @param defaulted      whether the runner composed this node as the unknown-behavior
         *                       pass-through — structural, decided at composition time and never read
         *                       back from the node's own result (SEC-09). Mutually exclusive with
         *                       {@code bypassed} by construction at the call site.
         * @return the identity of the completion event, for the successors it dispatches to name
         */
        private synchronized UUID nodeCompleted(UUID invocationId, UUID attemptId, UUID startedEventId,
                                                boolean bypassed, boolean defaulted) {
            if (terminal) {
                return null;
            }
            var transitions = List.<ExecutionTransition>of(
                    new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                            NodeAttemptStatus.COMPLETED),
                    new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                            NodeInvocationStatus.COMPLETED));
            UUID defaultedEventId = defaulted ? eventId() : null;
            UUID completedEventId = eventId();
            ExecutionEventType completionType = bypassed
                    ? ExecutionEventType.NODE_BYPASSED : ExecutionEventType.NODE_COMPLETED;
            var published = defaulted
                    ? List.of(new PublishedEvent(ExecutionEventType.NODE_DEFAULTED, defaultedEventId),
                            new PublishedEvent(completionType, completedEventId))
                    : List.of(new PublishedEvent(completionType, completedEventId));
            record(transitions, events(published, startedEventId, invocationId, attemptId));
            lifecycle = fold(lifecycle, transitions);
            return completedEventId;
        }

        /**
         * Journals the stable edge identity between the source completion and successor start.
         * The successor retains the source terminal event as its direct cause, preserving the
         * established causation contract for existing readers; stream order relates both effects.
         */
        private synchronized void edgeTraversed(GraphEdge edge, UUID sourceInvocationId,
                                                UUID sourceAttemptId, UUID causedBy) {
            if (terminal || !journalling()) {
                return;
            }
            UUID traversalEventId = eventId();
            EventEnvelope event = envelope(ExecutionEventType.EDGE_TRAVERSED, traversalEventId,
                    java.util.Objects.requireNonNull(causedBy,
                            "an EDGE_TRAVERSED event must name the source terminal event as its cause"),
                    sourceInvocationId, sourceAttemptId, EdgeTraversalEventData.payload(edge.id()));
            record(List.of(), List.of(event));
        }

        /**
         * @return the identity of the failure event, for a declared failure route's dispatch
         *         to name as its cause — the same role {@link #nodeCompleted}'s return plays for an
         *         ordinary successor — or {@code null} when nothing was journalled
         */
        private synchronized UUID nodeFailed(UUID invocationId, UUID attemptId, UUID startedEventId) {
            if (terminal) {
                return null;
            }
            var transitions = List.<ExecutionTransition>of(
                    new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                            NodeAttemptStatus.FAILED),
                    new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                            NodeInvocationStatus.FAILED));
            UUID failedEventId = eventId();
            record(transitions, events(ExecutionEventType.NODE_FAILED, failedEventId, startedEventId,
                    invocationId, attemptId));
            lifecycle = fold(lifecycle, transitions);
            return failedEventId;
        }

        /**
         * The nodes this traversal recorded as {@code FAILED} and survived.
         *
         * <p>Read out of {@link #lifecycle}, which {@link #nodeFailed} already folded the
         * {@code FAILED} invocation transition into — so this adds no capture of its own, and cannot
         * drift from what was recorded. Called only on the completion path, which is what makes
         * every failure it finds a <em>handled</em> one: an unhandled failure ends the traversal
         * through {@link #executionFailed()} and produces no result to annotate.</p>
         *
         * <p>{@code synchronized} for the same reason every writer above is: {@code lifecycle} is a
         * plain field, and the last branch to settle may not be the thread that wrote the failure.</p>
         */
        private synchronized Set<String> handledFailureNodes() {
            Traversal traversal = lifecycle.traversals().get(traversalId);
            if (traversal == null) {
                return Set.of();
            }
            var failed = new LinkedHashSet<String>();
            for (NodeInvocation invocation : traversal.invocations().values()) {
                if (invocation.status() == NodeInvocationStatus.FAILED) {
                    failed.add(invocation.nodeId());
                }
            }
            return failed;
        }

        /**
         * <h2>Why no traversal-terminal event is journalled, and it is a decision</h2>
         * <p>The causal model's removal test has no single answer here. A traversal completes when
         * the last of its branches settles, which is a journalled event; a traversal <em>fails</em>
         * on a join timeout, an abandoned branch or the aggregate's own refusal, none of which is a
         * journalled event at all. So the same event type would sometimes name a cause and sometimes
         * have to claim its cause lies outside the journal — and the second claim would be false on
         * every run where a node's completion did trigger it. On an append-only log that is a
         * permanently wrong row, so the terminal transitions are recorded and no event is published
         * until the runtime can distinguish the two cases at the point of emission. The traversal's
         * end remains observable in the aggregate, which commits in this same transaction.</p>
         */
        private synchronized void executionCompleted() {
            var transitions = List.<ExecutionTransition>of(
                    new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.COMPLETED),
                    new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.COMPLETED));
            record(transitions, List.of());
            lifecycle = fold(lifecycle, transitions);
            terminal = true;
        }

        private synchronized void executionFailed() {
            var transitions = new ArrayList<ExecutionTransition>();
            Traversal traversal = lifecycle.traversals().get(traversalId);
            if (!traversal.status().terminal()) {
                transitions.add(new ExecutionTransition.TraversalTransitioned(traversalId,
                        TraversalStatus.FAILED));
            }
            if (!lifecycle.status().terminal()) {
                transitions.add(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.FAILED));
            }
            // No event; see executionCompleted().
            record(transitions, List.of());
            lifecycle = fold(lifecycle, transitions);
            terminal = true;
        }

        /**
         * Mirrors the transition list into the store, when one is wired, together with the events
         * journalled inside that same transaction.
         *
         * <p>Nothing here is synthesized: the invocation and attempt identities were minted by
         * {@link ExecutionIdentitySource} at the call site and are already carried on the message the
         * engine receives, and the causation identity was carried across the dispatch from the event
         * that triggered it. That is the difference from the rejected best-effort journal bridge —
         * a forwarder sitting outside the runner had to invent a causation identifier because the
         * identity did not exist where it stood, and would have moved durability outside the
         * transaction into a path whose exceptions are swallowed by design. Inside the runner the
         * identity exists and the boundary is the store's own.</p>
         */
        private void record(List<ExecutionTransition> transitions, List<EventEnvelope> events) {
            if (recorder == null || (transitions.isEmpty() && events.isEmpty())) {
                return;
            }
            recorder.record(transitions, events);
        }

        private UUID eventId() {
            return journalling() ? identitySource.nextEventId() : null;
        }

        /**
         * The envelopes one batch publishes: the event just described, preceded on the first batch
         * only by the traversal-accepted event that the start node names as its cause.
         *
         * <p>Empty when nothing is journalling, which is what makes every {@code causedBy} threaded
         * through {@code dispatch} either a real journalled identity or {@code null} throughout —
         * never a mixture, and never an identity for a row that was not written.</p>
         */
        private List<EventEnvelope> events(ExecutionEventType type, UUID eventId, UUID causedBy,
                                           UUID invocationId, UUID attemptId) {
            return events(List.of(new PublishedEvent(type, eventId)), causedBy, invocationId, attemptId);
        }

        /**
         * The multi-event form, for a batch that journals more than one event about one transition —
         * today only a defaulted completion, which publishes {@code NODE_DEFAULTED} and then its
         * completion. List order becomes {@code streamSequence} order, which
         * {@link ai.ravenroot.api.persistence.ExecutionBatch#events()} guarantees, so the caller's
         * order is the journal's.
         *
         * <p>Every event in one call shares one {@code causedBy}. That is a property of the only
         * caller rather than a rule imposed here: both of its events are emitted because this node's
         * attempt was running. A future caller whose events have different causes must not reach for
         * this method with a cause that is merely true of the first one.</p>
         */
        private List<EventEnvelope> events(List<PublishedEvent> toPublish, UUID causedBy,
                                           UUID invocationId, UUID attemptId) {
            if (!journalling()) {
                return List.of();
            }
            var published = new ArrayList<EventEnvelope>(toPublish.size() + 1);
            if (!traversalAcceptedPublished) {
                traversalAcceptedPublished = true;
                // Absent causation, and the only event here entitled to it: the traversal was
                // accepted because an authenticated request asked for it, and that request is not in
                // this journal. EventEnvelope's contract is that absence means exactly this.
                published.add(envelope(ExecutionEventType.EXECUTION_STARTED, traversalAcceptedEventId,
                        null, null, null));
            }
            for (PublishedEvent each : toPublish) {
                published.add(envelope(each.type(), each.eventId(), java.util.Objects.requireNonNull(causedBy,
                        () -> "a " + each.type() + " event is caused by another event in this journal; "
                                + "publishing it with absent causation would state, permanently and falsely, "
                                + "that its cause lies outside"), invocationId, attemptId));
            }
            return List.copyOf(published);
        }

        /** One event a batch is about to journal, paired with the identity minted for it. */
        private record PublishedEvent(ExecutionEventType type, UUID eventId) {
        }

        private EventEnvelope envelope(ExecutionEventType type, UUID eventId, UUID causationId,
                                       UUID invocationId, UUID attemptId) {
            return envelope(type, eventId, causationId, invocationId, attemptId,
                    OpaquePayload.empty(EVENT_CONTENT_TYPE));
        }

        private EventEnvelope envelope(ExecutionEventType type, UUID eventId, UUID causationId,
                                       UUID invocationId, UUID attemptId, OpaquePayload payload) {
            return EventEnvelope.of(eventId,
                    // From the batch's own key, never from a second source that merely agrees today.
                    recorder.tenantId(), type.name(), recorder.processInstanceId(), traversalId,
                    invocationId, attemptId, causationId, identity.security().requestId(),
                    identity.graphVersion(), clock.instant(), payload);
        }

        /**
         * Applies the same transitions to the in-memory aggregate the store just accepted.
         *
         * <p>One list, folded twice, so the two can never describe different histories. The previous
         * shape called the aggregate's mutators directly and would have needed a second, parallel
         * list of transitions for the store — two spellings of one truth, which drift.</p>
         */
        private static ProcessInstance fold(ProcessInstance current, List<ExecutionTransition> transitions) {
            ProcessInstance folded = current;
            for (ExecutionTransition transition : transitions) {
                folded = transition.applyTo(folded);
            }
            return folded;
        }
    }
}
