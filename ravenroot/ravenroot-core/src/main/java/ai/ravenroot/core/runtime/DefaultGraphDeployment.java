package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentState;
import ai.ravenroot.api.deployment.DeploymentStatus;
import ai.ravenroot.api.deployment.GraphDeployment;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressOverflowPolicy;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.RequestReplyAdmission;
import ai.ravenroot.api.deployment.RequestReplyIngress;
import ai.ravenroot.api.deployment.RequestReplyLimits;
import ai.ravenroot.api.deployment.RequestReplyProjection;
import ai.ravenroot.api.deployment.RequestReplyRefusal;
import ai.ravenroot.api.deployment.TrustedIngress;
import ai.ravenroot.api.ingress.IngressRouteAuthority;
import ai.ravenroot.api.ingress.IngressRouteOwner;
import ai.ravenroot.api.ingress.ManagedIngress;
import ai.ravenroot.api.node.ManagedIngressSource;
import ai.ravenroot.api.execution.ExecutionDomain;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The engine-neutral {@link GraphDeployment}: a graph hosted continuously in its own
 * {@link ExecutionDomain}, spawned once at {@link #start} and reused by every traversal an accepted
 * ingress event begins (ADR 0021 D1/D2).
 *
 * <h2>Why a deployment can reuse {@link GraphRunner}</h2>
 * <p>{@link GraphRunner} already spawns every graph node exactly once, at construction, and its
 * {@code execute} may be called any number of times afterwards -- each call is one traversal through
 * the same already-spawned nodes, tracked by its own {@link JoinCoordinator} entry. That is exactly
 * the shape a long-lived deployment needs: nodes that exist before any request arrives, and many
 * traversals flowing through them over the deployment's life. The only thing this class adds is
 * <em>where</em> those nodes are spawned -- {@link ExecutionEngine#openDomain(String)}'s domain rather
 * than the engine directly -- and the lifecycle state machine
 * {@link GraphDeployment} requires around that runner's construction and disposal.
 *
 * <h2>Single-flight, and what "in flight" is guarded by</h2>
 * <p>{@link #start} and {@link #stop} each hold at most one in-flight stage at a time, recorded and
 * read under {@link #lock}: a second concurrent caller of the same operation observes the very stage
 * the first caller is already waiting on, never a second attempt. {@link #restart} is a completed
 * {@link #stop} composed with {@link #start} -- nothing more -- so it inherits both operations'
 * single-flight guarantee rather than needing one of its own: two concurrent restarts still produce
 * at most one stop and one start underneath.
 */
public final class DefaultGraphDeployment implements GraphDeployment {
    /**
     * Provisional Phase A default for {@link TrustedIngress#bufferCapacity()}. Not derived from any
     * ADR formula -- there is none for this value -- chosen only to be a real, finite bound rather
     * than an accidentally unlimited one. A caller that needs a different bound passes it explicitly.
     */
    public static final int DEFAULT_INGRESS_BUFFER_CAPACITY = 64;

    /**
     * The bound each {@link InboundSource#stop()} (and {@link InboundSource#rollback()}) is given
     * individually during this deployment's own {@link #stop} or a rolled-back {@link #start}.
     * Not a second admission cap — the shutdown budget does not gate how many deployments this pod
     * admits, and source lifecycle does not add a dimension that would. It is the fact
     * that makes that arithmetic correct: a deployment's close time is no longer just its own graph
     * draining, it is that plus every source it holds stopping within this bound each; the shutdown
     * budget is where that term belongs.
     */
    public static final Duration DEFAULT_SOURCE_STOP_BOUND = Duration.ofSeconds(30);

    /**
     * Provisional default retention for a durable inbox record, passed to
     * {@link ai.ravenroot.api.persistence.ExecutionStore#recordInboxDelivery}. Not derived from any
     * formula, same posture as {@link #DEFAULT_INGRESS_BUFFER_CAPACITY}: chosen to be generous enough
     * that a source's own redelivery window is very unlikely to outlive it, and overridable by a
     * caller who knows their source's actual redelivery bound.
     */
    public static final Duration DEFAULT_INBOX_RETENTION = Duration.ofDays(7);

    /**
     * How long {@link IngressView#offerDurably} waits for {@code ExecutionStore#recordInboxDelivery}
     * before reporting {@link ai.ravenroot.api.deployment.IngressReceipt.Ambiguous} rather than the
     * outcome it cannot yet know. Deliberately much shorter than {@link #DEFAULT_SOURCE_STOP_BOUND}:
     * that bound waits for an orderly shutdown to finish, this one waits on a caller's hot-path call
     * that a source is blocked on for every single event.
     */
    public static final Duration DEFAULT_STORE_CALL_BOUND = Duration.ofSeconds(10);

    private static final Executor VIRTUAL_THREADS = command -> Thread.startVirtualThread(command);

    private final DeploymentId id;
    private final ExecutionEngine engine;
    private final BehaviorRegistry behaviors;
    private final ExecutionMonitor monitor;
    private final ExecutionIdentitySource identitySource;
    private final byte[] graphMl;
    /**
     * The real graph version: the same SHA-256-of-the-document convention
     * {@code DefaultRavenrootApplication.startGraphMl} already uses, computed once here because
     * {@link #graphMl} is fixed for this deployment's life. Every traversal this deployment starts,
     * across any number of restarts, is stamped with this value rather than the placeholder literal
     * {@code "deployment"}.
     */
    private final String graphVersion;
    private final int ingressBufferCapacity;
    /** {@code null} means no durable store is configured; {@link #ingress} then uses volatile custody. */
    private final ai.ravenroot.api.persistence.ExecutionStore executionStore;
    /**
     * The durable authority for the document this deployment hosts, or {@code null} when nothing
     * retains it. Committed once per accepted traversal, before the acceptance that pins it; the
     * write is content-addressed and therefore idempotent, so repeating it across a long-lived
     * deployment's many traversals converges on one stored copy.
     */
    private final ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore;
    private final ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets;
    private final Duration inboxRetention;
    /**
     * This pod's worker identity for lease ownership, and how long a traversal's lease lives.
     * Same convention and default as {@code DefaultRavenrootApplication}: the identity distinguishes
     * this runtime from any other that might claim the same instance, and the TTL is what a crashed
     * worker's instance waits out before a recovery sweep may claim it.
     */
    private final String workerId;
    private final Duration executionLeaseTtl;
    private final TrustedIngress ingress = new IngressView();
    private final RequestReplyIngress requestReply = new DeploymentRequestReplyView();
    private final RequestReplyLimits requestReplyLimits;
    private final Clock clock;
    private final GraphExecutionLimits graphExecutionLimits;
    /** Node ids currently reporting degraded, via {@link InboundSourceContext#reportDegraded}. */
    private final Set<String> degradedSources = ConcurrentHashMap.newKeySet();

    private final ReentrantLock lock = new ReentrantLock();
    private DeploymentStatus status;
    private ExecutionDomain domain;
    private GraphManager manager;
    private GraphRunner runner;
    private List<SourceHandle> sources = List.of();
    private volatile ManagedIngress managedIngress;
    private long ingressGeneration;
    private Semaphore ingressPermits;
    private CompletionStage<DeploymentStatus> inFlightStart;
    private CompletionStage<DeploymentStatus> inFlightStop;
    private RequestReplyCoordinator requestReplyCoordinator;

    /**
     * @param graphMl the GraphML document this deployment hosts, re-parsed into a fresh
     *                {@link GraphManager} on every {@link #start} -- including a {@link #restart} --
     *                so a deployment that starts twice never shares graph state between the two runs.
     *                Copied defensively; the caller's array is never retained.
     */
    public DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                                  ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                                  byte[] graphMl, int ingressBufferCapacity) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity, null,
                DEFAULT_INBOX_RETENTION);
    }

    DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                           ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                           byte[] graphMl, int ingressBufferCapacity,
                           GraphExecutionLimits graphExecutionLimits) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity, null,
                DEFAULT_INBOX_RETENTION, "ravenroot-" + UUID.randomUUID(), Duration.ofSeconds(30),
                RequestReplyLimits.defaults(ingressBufferCapacity), Clock.systemUTC(), null,
                graphExecutionLimits, null);
    }

    /**
     * Composes a deployment that carries a definition store but records nothing durably.
     *
     * <p>Additive next to the constructor above, which every existing caller keeps using unchanged by
     * not passing a definition store at all. <strong>The definition store passed here is retained and
     * never consulted</strong>, because binding a definition happens inside the durable-recording path
     * and this constructor composes no execution store, so that path is skipped. It exists so a
     * composer that will later supply durable execution state does not have to re-thread the
     * definition store at the same time; the constructor that takes both is the one that makes the
     * binding happen.</p>
     *
     * @param id deployment identity.
     * @param engine execution engine hosted traversals dispatch through.
     * @param behaviors behavior registry node kinds are resolved against.
     * @param monitor monitor execution events are published to.
     * @param identitySource source of process-instance identifiers.
     * @param graphMl the GraphML document this deployment hosts; copied defensively.
     * @param ingressBufferCapacity fixed inbound buffer capacity for the deployment's life.
     * @param graphDefinitionStore durable graph definitions, or {@code null} to retain no document.
     */
    public DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                                  ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                                  byte[] graphMl, int ingressBufferCapacity,
                                  ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity, null,
                DEFAULT_INBOX_RETENTION, "ravenroot-" + UUID.randomUUID(), Duration.ofSeconds(30),
                RequestReplyLimits.defaults(ingressBufferCapacity), Clock.systemUTC(), graphDefinitionStore,
                GraphExecutionLimits.DEFAULTS, null);
    }

    DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                           ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                           byte[] graphMl, int ingressBufferCapacity,
                           ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore,
                           GraphExecutionLimits graphExecutionLimits) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity, null,
                DEFAULT_INBOX_RETENTION, "ravenroot-" + UUID.randomUUID(), Duration.ofSeconds(30),
                RequestReplyLimits.defaults(ingressBufferCapacity), Clock.systemUTC(), graphDefinitionStore,
                graphExecutionLimits, null);
    }

    /**
     * Composes a deployment whose {@link #ingress}'s {@link TrustedIngress#offerDurably} is genuinely
     * durable rather than degrading to {@link ai.ravenroot.api.deployment.IngressReceipt.VolatileCustody}.
     * Additive next to the constructor above, which every existing caller keeps using unchanged
     * by passing {@code null} here.
     *
     * @param executionStore {@code null} for no durable ingress (identical to the constructor above);
     *                       otherwise must declare {@link ai.ravenroot.api.persistence.StoreCapability#DURABLE}
     *                       and {@link ai.ravenroot.api.persistence.StoreCapability#EVENT_JOURNAL} --
     *                       checked here, fail-fast, the same discipline
     *                       {@code DefaultRavenrootApplication} already applies to
     *                       {@link ai.ravenroot.api.persistence.StoreCapability#TRANSACTIONAL_BATCH}.
     *                       {@code DURABLE} is required because a store that does not declare it is, by
     *                       {@link ai.ravenroot.api.persistence.StoreCapability#DURABLE}'s own contract,
     *                       one this deployment could not honestly report {@code DurablyCommitted}
     *                       against.
     * @param inboxRetention how long a recorded inbox entry must outlive its own write, passed to
     *                       {@link ai.ravenroot.api.persistence.ExecutionStore#recordInboxDelivery} for
     *                       every event this deployment durably commits
     */
    public DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                                  ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                                  byte[] graphMl, int ingressBufferCapacity,
                                  ai.ravenroot.api.persistence.ExecutionStore executionStore,
                                  Duration inboxRetention) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity,
                executionStore, inboxRetention, "ravenroot-" + UUID.randomUUID(), Duration.ofSeconds(30));
    }

    /**
     * Composes a deployment whose hosted traversals record through {@code executionStore} under a
     * per-traversal lease. Additive: every constructor above resolves here with the shipped
     * worker identity and lease TTL, so no existing caller changes behaviour.
     *
     * @param workerId          lease ownership identity for this runtime
     * @param executionLeaseTtl how long each traversal's lease lives before a sweep may claim it;
     *                          tests drive this down rather than waiting on the shipped default
     */
    public DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                                  ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                                  byte[] graphMl, int ingressBufferCapacity,
                                  ai.ravenroot.api.persistence.ExecutionStore executionStore,
                                  Duration inboxRetention, String workerId, Duration executionLeaseTtl) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity,
                executionStore, inboxRetention, workerId, executionLeaseTtl,
                RequestReplyLimits.defaults(ingressBufferCapacity), Clock.systemUTC());
    }

    /**
     * Additive composition seam for operator request/reply ceilings.
     * Existing constructors retain defaults derived from the deployment's ingress capacity.
     */
    public DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                                  ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                                  byte[] graphMl, int ingressBufferCapacity,
                                  ai.ravenroot.api.persistence.ExecutionStore executionStore,
                                  Duration inboxRetention, String workerId, Duration executionLeaseTtl,
                                  RequestReplyLimits requestReplyLimits) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity,
                executionStore, inboxRetention, workerId, executionLeaseTtl, requestReplyLimits,
                Clock.systemUTC());
    }

    /**
     * Composes a deployment that both records its hosted traversals durably and retains the document
     * those traversals are accepted against.
     *
     * <p>This is the only composition in which a deployment-hosted acceptance is bound to a durable
     * definition, because the binding sits inside the durable-recording path and that path is skipped
     * entirely when no execution store is present. A deployment built through any other constructor
     * keeps exactly its previous behaviour: with no execution store it records nothing, and a
     * definition store passed alongside no execution store is retained but never consulted.</p>
     *
     * <p><strong>The shipped server does not compose deployments this way today.</strong> Deployments
     * are registered without an execution store, so their traversals are not durably recorded and
     * therefore not durably bound. Supplying deployments with durable execution state is a separate
     * change; this constructor is what makes the binding reachable for a composer that already has
     * both, and what lets the behaviour be asserted rather than assumed.</p>
     *
     * @param id deployment identity.
     * @param engine execution engine hosted traversals dispatch through.
     * @param behaviors behavior registry node kinds are resolved against.
     * @param monitor monitor execution events are published to.
     * @param identitySource source of process-instance identifiers.
     * @param graphMl the GraphML document this deployment hosts; copied defensively.
     * @param ingressBufferCapacity fixed inbound buffer capacity for the deployment's life.
     * @param executionStore durable execution state; must declare durability and event journalling.
     * @param inboxRetention how long a recorded inbox entry must outlive its own write.
     * @param workerId lease ownership identity for this runtime.
     * @param executionLeaseTtl how long each traversal's lease lives before a sweep may claim it.
     * @param requestReplyLimits operator ceilings for request/reply ingress.
     * @param graphDefinitionStore durable graph definitions, or {@code null} to retain no document.
     */
    public DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                                  ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                                  byte[] graphMl, int ingressBufferCapacity,
                                  ai.ravenroot.api.persistence.ExecutionStore executionStore,
                                  Duration inboxRetention, String workerId, Duration executionLeaseTtl,
                                  RequestReplyLimits requestReplyLimits,
                                  ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity,
                executionStore, inboxRetention, workerId, executionLeaseTtl, requestReplyLimits,
                Clock.systemUTC(), graphDefinitionStore, GraphExecutionLimits.DEFAULTS, null);
    }

    /**
     * Composes durable hosted traversals with finite first-party agent resources.
     * @param id deployment identity
     * @param engine execution engine
     * @param behaviors trusted behavior registry
     * @param monitor execution event monitor
     * @param identitySource trusted execution identity source
     * @param graphMl immutable graph document
     * @param ingressBufferCapacity bounded ingress capacity
     * @param executionStore durable execution store
     * @param inboxRetention durable inbox retention
     * @param workerId execution worker identity
     * @param executionLeaseTtl execution lease duration
     * @param requestReplyLimits request/reply limits
     * @param graphDefinitionStore pinned graph-definition store
     * @param agentBudgets finite agent authority mediator, or {@code null} when unavailable
     */
    public DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                                  ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                                  byte[] graphMl, int ingressBufferCapacity,
                                  ai.ravenroot.api.persistence.ExecutionStore executionStore,
                                  Duration inboxRetention, String workerId, Duration executionLeaseTtl,
                                  RequestReplyLimits requestReplyLimits,
                                  ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore,
                                  ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity,
                executionStore, inboxRetention, workerId, executionLeaseTtl, requestReplyLimits,
                Clock.systemUTC(), graphDefinitionStore, GraphExecutionLimits.DEFAULTS, agentBudgets);
    }

    /** Full production composition with graph limits and finite first-party agent resources. */
    public DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                                  ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                                  byte[] graphMl, int ingressBufferCapacity,
                                  ai.ravenroot.api.persistence.ExecutionStore executionStore,
                                  Duration inboxRetention, String workerId, Duration executionLeaseTtl,
                                  RequestReplyLimits requestReplyLimits,
                                  ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore,
                                  GraphExecutionLimits graphExecutionLimits,
                                  ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity,
                executionStore, inboxRetention, workerId, executionLeaseTtl, requestReplyLimits,
                Clock.systemUTC(), graphDefinitionStore, graphExecutionLimits, agentBudgets);
    }

    /** Package-private deterministic-clock seam; production constructors always use UTC system time. */
    DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                           ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                           byte[] graphMl, int ingressBufferCapacity,
                           ai.ravenroot.api.persistence.ExecutionStore executionStore,
                           Duration inboxRetention, String workerId, Duration executionLeaseTtl,
                           RequestReplyLimits requestReplyLimits, Clock clock) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity, executionStore,
                inboxRetention, workerId, executionLeaseTtl, requestReplyLimits, clock, null,
                GraphExecutionLimits.DEFAULTS, null);
    }

    private DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                           ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                           byte[] graphMl, int ingressBufferCapacity,
                           ai.ravenroot.api.persistence.ExecutionStore executionStore,
                           Duration inboxRetention, String workerId, Duration executionLeaseTtl,
                           RequestReplyLimits requestReplyLimits, Clock clock,
                           ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore,
                           GraphExecutionLimits graphExecutionLimits,
                           ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets) {
        this.graphDefinitionStore = graphDefinitionStore;
        this.agentBudgets = agentBudgets;
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.executionLeaseTtl = Objects.requireNonNull(executionLeaseTtl, "executionLeaseTtl");
        this.requestReplyLimits = Objects.requireNonNull(requestReplyLimits, "requestReplyLimits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.graphExecutionLimits = Objects.requireNonNull(graphExecutionLimits, "graphExecutionLimits");
        this.id = Objects.requireNonNull(id, "id");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.behaviors = Objects.requireNonNull(behaviors, "behaviors");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.identitySource = Objects.requireNonNull(identitySource, "identitySource");
        this.graphMl = Objects.requireNonNull(graphMl, "graphMl").clone();
        this.graphVersion = sha256Hex(this.graphMl);
        if (ingressBufferCapacity <= 0) {
            throw new IllegalArgumentException(
                    "ingressBufferCapacity must be positive: " + ingressBufferCapacity);
        }
        this.ingressBufferCapacity = ingressBufferCapacity;
        if (executionStore != null) {
            var required = java.util.EnumSet.of(ai.ravenroot.api.persistence.StoreCapability.DURABLE,
                    ai.ravenroot.api.persistence.StoreCapability.EVENT_JOURNAL);
            var missing = java.util.EnumSet.copyOf(required);
            missing.removeAll(executionStore.capabilities());
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(
                        "ExecutionStore for durable ingress must declare " + required + "; this one is "
                                + "missing " + missing + " -- offerDurably would report DurablyCommitted "
                                + "against a store that cannot honour it");
            }
        }
        this.executionStore = executionStore;
        this.inboxRetention = Objects.requireNonNull(inboxRetention, "inboxRetention");
        this.status = DeploymentStatus.of(id, DeploymentState.COLD);
    }

    @Override
    public DeploymentId id() {
        return id;
    }

    /** Composition-root-only installation while cold; source code receives only its attenuated view. */
    public synchronized void installManagedIngress(ManagedIngress managedIngress) {
        if (status.state() != DeploymentState.COLD) throw new IllegalStateException("ingress must be installed while cold");
        this.managedIngress = Objects.requireNonNull(managedIngress, "managedIngress");
    }

    @Override
    public DeploymentStatus status() {
        lock.lock();
        try {
            return status;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CompletionStage<DeploymentStatus> start(SecurityContext security) {
        Objects.requireNonNull(security, "security");
        lock.lock();
        try {
            if (status.state() == DeploymentState.READY) {
                return CompletableFuture.completedFuture(status);
            }
            if (inFlightStart != null) {
                return inFlightStart;
            }
            status = DeploymentStatus.of(id, DeploymentState.STARTING);
            CompletionStage<DeploymentStatus> stage =
                    CompletableFuture.supplyAsync(() -> doStart(security), VIRTUAL_THREADS);
            inFlightStart = stage;
            return stage;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CompletionStage<DeploymentStatus> stop() {
        return stop(SourceRelease.STOP);
    }

    /**
     * Stops this deployment <em>terminally</em>: it will never run again in this process.
     *
     * <p>Identical to {@link #stop()} in every respect except which hook each source is released
     * through: {@link InboundSource#shutdown()} rather than {@link InboundSource#stop()}. The
     * distinction the SPI draws is about the future, not about the process: a source may hold
     * something it deliberately keeps across a stop because its own deployment can start again, and
     * this is the call that tells it no such start is coming.
     *
     * <h2>Two callers, one precondition</h2>
     * <p>Both live in this package and both establish that precondition before calling:</p>
     * <ul>
     *   <li>{@code DefaultRavenrootApplication.close()} -- the process is ending, so nothing starts
     *       again.</li>
     *   <li>{@code DefaultRavenrootApplication.undeployLocalDeployment(...)} -- the
     *       registration is being removed, so this deployment cannot be started again either, and it
     *       is about to become unreachable from the registry {@code close()} sweeps. Releasing it
     *       through {@link #stop()} would strand whatever a source kept for a restart that can no
     *       longer happen, with no remaining reference able to release it.</li>
     * </ul>
     *
     * <p><b>Undeploy is reached from {@code DELETE /v1/deployments/{id}}, so a remote caller does
     * reach this method</b> -- but never by asserting the condition, only by requesting an operation
     * whose own semantics make the condition true. That is why this stays package-private rather than
     * joining the public {@link GraphDeployment} interface: the judgement "nothing will start again"
     * belongs to the composition root that owns the registry, and no caller outside it -- remote or
     * embedded -- is in a position to make it. What a remote caller must never be able to do is claim
     * process shutdown for a stop, and it still cannot: {@link #stop()} is what
     * {@code POST /v1/deployments/{id}/stop} and {@code /restart} reach, and it is unchanged.</p>
     */
    CompletionStage<DeploymentStatus> shutdown() {
        return stop(SourceRelease.SHUTDOWN);
    }

    private CompletionStage<DeploymentStatus> stop(SourceRelease release) {
        lock.lock();
        try {
            DeploymentState current = status.state();
            if (current == DeploymentState.STOPPED) {
                return CompletableFuture.completedFuture(status);
            }
            if (inFlightStop != null) {
                return inFlightStop;
            }
            if (current == DeploymentState.COLD || current == DeploymentState.FAILED) {
                // COLD never started; a FAILED start already rolled back whatever it opened before
                // reporting FAILED. Either way there is nothing left to release.
                status = DeploymentStatus.of(id, DeploymentState.STOPPED);
                return CompletableFuture.completedFuture(status);
            }
            // A stop that arrives mid-start waits for that start to settle before it tears anything
            // down -- restart's own contract ("a stop that completes before a start begins") only
            // holds if stop itself never races what start is still building.
            CompletionStage<Object> readyToStop = current == DeploymentState.STARTING
                    ? inFlightStart.handle((ignoredStatus, ignoredError) -> null)
                    : CompletableFuture.completedFuture(null);
            status = DeploymentStatus.of(id, DeploymentState.STOPPING);
            CompletionStage<DeploymentStatus> stage = readyToStop
                    .thenCompose(ignored -> CompletableFuture.supplyAsync(() -> doStop(release), VIRTUAL_THREADS));
            inFlightStop = stage;
            return stage;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CompletionStage<DeploymentStatus> restart(SecurityContext security) {
        Objects.requireNonNull(security, "security");
        return stop().thenCompose(ignored -> start(security));
    }

    @Override
    public TrustedIngress ingress() {
        return ingress;
    }

    @Override
    public RequestReplyIngress requestReply() {
        return requestReply;
    }

    /**
     * Runs off the calling (virtual) thread: opens the domain, parses the graph, spawns every node,
     * then starts every source the graph names -- readiness is not reported until all of them
     * are, so a caller observing a completed {@link #start} has a deployment whose sources are
     * actually receiving, not merely spawned.
     */
    private DeploymentStatus doStart(SecurityContext security) {
        ExecutionDomain openedDomain = null;
        GraphManager openedManager = null;
        GraphRunner builtRunner = null;
        List<SourceHandle> startedSources;
        long generation;
        try {
            openedDomain = engine.openDomain(id.value());
            openedManager = GraphManager.readGraphMl(new ByteArrayInputStream(graphMl), graphExecutionLimits.graphMl());
            builtRunner = new GraphRunner(openedManager, engine, openedDomain, behaviors, monitor,
                    identitySource, GraphRunner.DEFAULT_SHUTDOWN_BOUND, graphExecutionLimits);
            // Sources are discovered and started here -- while this graph's nodes are being spawned,
            // never earlier -- and only after the runner itself is built, so a source's start failure
            // rolls back a fully-formed runner rather than a half-built one.
            generation = ++ingressGeneration;
            startedSources = startSources(security, openedManager, generation);
        } catch (RuntimeException | Error failure) {
            try {
                rollback(builtRunner, openedManager, openedDomain);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                recordFailure(failure);
            } catch (RuntimeException | Error stateFailure) {
                failure.addSuppressed(stateFailure);
            }
            // The interface contract: "a stage completed exceptionally if startup failed after
            // rolling back". The rollback above already ran; this is what makes the stage exceptional.
            throw failure;
        }
        lock.lock();
        try {
            this.domain = openedDomain;
            this.manager = openedManager;
            this.runner = builtRunner;
            this.sources = startedSources;
            this.ingressPermits = new Semaphore(ingressBufferCapacity);
            GraphRunner readyRunner = builtRunner;
            this.requestReplyCoordinator = new RequestReplyCoordinator(id.value(), generation, security,
                    identitySource, engine.scheduler(), clock, requestReplyLimits, ingressPermits,
                    new RequestReplyCoordinator.TraversalControl() {
                        @Override
                        public CompletionStage<GraphExecutionResult> execute(SecurityContext establishedIdentity,
                                UUID processInstanceId, UUID traversalId, Object payload) {
                            ExecutionRecorder recorder = openTraversalRecorder(
                                    establishedIdentity, processInstanceId, traversalId);
                            return executeHosted(readyRunner, establishedIdentity, processInstanceId,
                                    traversalId, payload, recorder);
                        }

                        @Override
                        public void cancel(UUID traversalId) {
                            readyRunner.cancelTraversal(traversalId);
                        }
                    });
            this.status = DeploymentStatus.of(id, DeploymentState.READY);
            this.inFlightStart = null;
            return this.status;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Builds and starts every source this graph's nodes name, in node-id order, entirely
     * within this one attempt. Discovery walks the parsed graph rather than the catalog: a behavior
     * that is source-capable but unused by this graph creates nothing. A source that fails to start
     * rolls back every sibling that had already reached readiness before rethrowing, so the caller's
     * own rollback of the domain and manager never has to know a source was involved.
     *
     * <p>Sorted by node id rather than {@link GraphManager}'s own iteration order, which
     * {@link ai.ravenroot.core.graph.GraphDefinition} does not promise (it stores nodes in a
     * {@code Map.copyOf}, whose order is unspecified even though the map it copies preserves
     * insertion order). Startup and rollback order should not depend on that.</p>
     */
    private List<SourceHandle> startSources(SecurityContext security, GraphManager parsedGraph, long generation) {
        var nodesById = new java.util.ArrayList<>(parsedGraph.definition().nodes());
        nodesById.sort(java.util.Comparator.comparing(GraphNode::id));
        var started = new java.util.ArrayList<SourceHandle>();
        for (GraphNode node : nodesById) {
            if (node.kind() != NodeKind.BEHAVIOR) {
                continue;
            }
            var descriptor = behaviors.descriptor(node.behavior()).orElse(null);
            if (ai.ravenroot.api.catalog.NodeRuntimeNatureProperty.effectiveNature(
                    descriptor, node.properties()) != ai.ravenroot.api.catalog.NodeRuntimeNature.SOURCE) {
                continue;
            }
            Optional<NodePackages.SdkNodeBehaviorFactory> capableFactory =
                    behaviors.sourceCapableFactory(node.behavior());
            if (capableFactory.isEmpty()) {
                continue;
            }
            String packageId = behaviors.catalogSources().get(node.behavior()).bundleId();
            IngressRouteOwner owner = managedIngress == null ? null : new IngressRouteOwner(packageId,
                    security.tenantId(), id.value(), node.id(), generation);
            InboundSourceContext context = new SourceContext(node.id(), security, owner);
            try {
                InboundSource source = capableFactory.get().createSource(node, context);
                if (source == null) {
                    throw new IllegalStateException("Behavior '" + node.behavior()
                            + "' returned no inbound source for node '" + node.id() + "'");
                }
                joinSourceStart(source.start(context));
                // From this point the source owns live resources. Record it before managed route
                // activation so an acquisition failure rolls back this source and retires any lease
                // it obtained before completing exceptionally, not only earlier siblings.
                started.add(new SourceHandle(node.id(), source, owner));
                if (source instanceof ManagedIngressSource ingressSource) {
                    IngressRouteAuthority authority = context.ingressRoutes().orElseThrow(() ->
                            new IllegalStateException("managed ingress is unavailable for source"));
                    joinSourceStart(ingressSource.activateManagedIngress(authority));
                }
            } catch (RuntimeException | Error failure) {
                rollbackSources(started);
                throw failure;
            }
        }
        return List.copyOf(started);
    }

    /** Rolls back every source in {@code handles}, individually bounded and best-effort. */
    private void rollbackSources(List<SourceHandle> handles) {
        for (SourceHandle handle : handles) {
            retireIngress(handle);
            joinSourceStopBounded(handle.source().rollback());
        }
    }

    /** No half-bound listeners, no orphaned actors: closes whatever this attempt actually opened. */
    private void rollback(GraphRunner openedRunner, GraphManager openedManager, ExecutionDomain openedDomain) {
        try {
            if (openedRunner != null) {
                openedRunner.close();
            }
        } finally {
            try {
                if (openedDomain != null) {
                    joinBounded(openedDomain.close());
                }
            } finally {
                if (openedManager != null) {
                    openedManager.close();
                }
            }
        }
    }

    private void recordFailure(Throwable failure) {
        // Sanitized: the class name only, never the message -- which may carry graph content, a
        // catalog identifier or another node's payload. Same discipline DeploymentStatus's own
        // Javadoc requires of every cause.
        String cause = "startup failed: " + failure.getClass().getSimpleName();
        lock.lock();
        try {
            this.domain = null;
            this.manager = null;
            this.runner = null;
            this.sources = List.of();
            this.ingressPermits = null;
            this.requestReplyCoordinator = null;
            this.status = DeploymentStatus.of(id, DeploymentState.FAILED, cause);
            this.inFlightStart = null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * How each source is released. See {@link #shutdown()} for why the distinction exists and why it
     * is not on the public interface.
     */
    private enum SourceRelease {
        /** An ordinary stop or restart: the source keeps whatever it needs for its next start. */
        STOP,
        /** This deployment will never start again here: the source releases what it kept for that. */
        SHUTDOWN
    }

    private DeploymentStatus doStop(SourceRelease release) {
        ExecutionDomain domainToClose;
        GraphRunner runnerToClose;
        GraphManager managerToClose;
        List<SourceHandle> sourcesToStop;
        RequestReplyCoordinator requestRepliesToClose;
        lock.lock();
        try {
            domainToClose = this.domain;
            runnerToClose = this.runner;
            managerToClose = this.manager;
            sourcesToStop = this.sources;
            requestRepliesToClose = this.requestReplyCoordinator;
        } finally {
            lock.unlock();
        }
        // Request/reply admission closes before source teardown and before the runner. Every waiter
        // receives one CANCELLED terminal and is detached; the underlying traversal is then asked to
        // stop cooperatively before the runner's bounded close provides the structural backstop.
        if (requestRepliesToClose != null) {
            requestRepliesToClose.close();
        }

        // Sources first: closes each one's own admission -- it stops offering new events to
        // this deployment's ingress -- before the runner below is closed, so admission really is shut
        // ahead of the traversals it fed rather than racing them. Each is bounded and released
        // individually, so one slow source cannot consume the time this deployment owes to its
        // siblings.
        //
        // The runner below no longer *drains* what was already accepted: a traversal in flight is
        // refused its next
        // hop, so the node currently running finishes and the traversal ends there instead of being
        // carried to its terminal. That is the better behaviour for a stop -- a graph that loops used
        // to keep looping straight through this call -- but it is a narrower promise than the one
        // GraphDeployment.stop() still makes in its own Javadoc ("Closes admission, drains, releases
        // resources"). That sentence is a public contract and is deliberately not edited from here;
        // the divergence is documented explicitly rather than left to be discovered.
        for (SourceHandle handle : sourcesToStop) {
            retireIngress(handle);
            joinSourceStopBounded(release == SourceRelease.SHUTDOWN
                    ? handle.source().shutdown()
                    : handle.source().stop());
        }
        try {
            if (runnerToClose != null) {
                // Refuses a further hop to every traversal still in flight, then stops every
                // node cooperatively and releases the join store this runner owns.
                runnerToClose.close();
            }
        } finally {
            try {
                if (domainToClose != null) {
                    // The structural backstop: releases exactly this deployment's subtree even if a
                    // node the cooperative path above could not reach is still holding a thread.
                    joinBounded(domainToClose.close());
                }
            } finally {
                if (managerToClose != null) {
                    managerToClose.close();
                }
            }
        }
        lock.lock();
        try {
            this.domain = null;
            this.runner = null;
            this.manager = null;
            this.sources = List.of();
            this.degradedSources.clear();
            this.ingressPermits = null;
            this.requestReplyCoordinator = null;
            this.status = DeploymentStatus.of(id, DeploymentState.STOPPED);
            this.inFlightStop = null;
            return this.status;
        } finally {
            lock.unlock();
        }
    }

    private void reportSourceDegraded(String nodeId, String sanitizedReason) {
        lock.lock();
        try {
            boolean wasEmpty = degradedSources.isEmpty();
            degradedSources.add(nodeId);
            // Only a READY deployment moves to DEGRADED here: a report arriving while this deployment
            // is itself starting, stopping or already failed must not override a transition already in
            // progress -- DeploymentStatus's own constructor refuses a cause on any state but DEGRADED
            // and FAILED, so a report during those states is recorded in the set (and will be acted on
            // once the deployment settles back to READY) without touching status now.
            if (wasEmpty && status.state() == DeploymentState.READY) {
                status = DeploymentStatus.of(id, DeploymentState.DEGRADED,
                        "source '" + nodeId + "' degraded: " + sanitizedReason);
            }
        } finally {
            lock.unlock();
        }
    }

    private void reportSourceHealthy(String nodeId) {
        lock.lock();
        try {
            degradedSources.remove(nodeId);
            if (degradedSources.isEmpty() && status.state() == DeploymentState.DEGRADED) {
                status = DeploymentStatus.of(id, DeploymentState.READY);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Same algorithm and hex convention as {@code DefaultRavenrootApplication}'s own {@code sha256}. */
    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static void joinBounded(CompletionStage<Void> stage) {
        try {
            stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (RuntimeException | ExecutionException | TimeoutException ignored) {
            // Closing is bounded by the adapter already (ExecutionDomain#close's own contract); a
            // caller here must not hang a stop() on a domain that failed to settle in time.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits for a source's {@link InboundSource#start} with no internal bound -- the same
     * posture {@link #doStart} already takes with {@link ExecutionEngine#openDomain} and
     * {@link GraphManager#readGraphMl}: none of a deployment's own startup steps are internally
     * timed, because the caller of {@link #start} already decides how long it is willing to wait, on
     * the returned stage. Propagates the real failure so the caller sees what actually went wrong,
     * not a wrapped timeout.
     */
    private static void joinSourceStart(CompletionStage<Void> stage) {
        try {
            stage.toCompletableFuture().get();
        } catch (ExecutionException executionFailure) {
            Throwable cause = executionFailure.getCause();
            if (cause instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            if (cause instanceof Error errorCause) {
                throw errorCause;
            }
            throw new IllegalStateException("Inbound source failed to start", cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for an inbound source to start", interrupted);
        }
    }

    /**
     * Waits for a source's {@link InboundSource#stop} or {@link InboundSource#rollback}, bounded by
     * {@link #DEFAULT_SOURCE_STOP_BOUND} and best-effort like {@link #joinBounded}: a source
     * that cannot settle within its own bound must not hang this deployment's own {@link #stop}, and
     * must not strand a sibling source that has not been asked to stop yet.
     */
    private static void joinSourceStopBounded(CompletionStage<Void> stage) {
        try {
            stage.toCompletableFuture().get(DEFAULT_SOURCE_STOP_BOUND.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException | ExecutionException | TimeoutException ignored) {
            // Best-effort, matching joinBounded's own discipline for the domain close.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** One node's inbound source, paired with the node id for diagnostics and individual stop/rollback. */
    private void retireIngress(SourceHandle handle) {
        if (managedIngress != null && handle.owner() != null) managedIngress.retire(handle.owner());
    }

    private record SourceHandle(String nodeId, InboundSource source, IngressRouteOwner owner) {
    }

    /**
     * Assembled here, and only here -- see {@link InboundSourceContext}'s own Javadoc for why
     * this being a private inner class, rather than a public constructor a plugin could call, is the
     * whole of the security property it documents.
     */
    private final class SourceContext implements InboundSourceContext {
        private final String nodeId;
        private final SecurityContext identity;

        private final IngressRouteOwner ingressOwner;
        private final RequestReplyIngress requestReplies;
        SourceContext(String nodeId, SecurityContext identity, IngressRouteOwner ingressOwner) {
            this.nodeId = nodeId;
            this.identity = identity;
            this.ingressOwner = ingressOwner;
            this.requestReplies = new SourceRequestReplyView(nodeId, ingressOwner == null
                    ? ingressGeneration : ingressOwner.graphGeneration());
        }

        @Override
        public DeploymentId deploymentId() {
            return id;
        }

        @Override
        public String nodeId() {
            return nodeId;
        }

        @Override
        public SecurityContext identity() {
            return identity;
        }

        @Override
        public TrustedIngress ingress() {
            return ingress;
        }

        @Override
        public RequestReplyIngress requestReply() {
            return requestReplies;
        }

        @Override public Optional<IngressRouteAuthority> ingressRoutes() {
            return ingressOwner == null || managedIngress == null ? Optional.empty()
                    : Optional.of(managedIngress.authorityFor(ingressOwner));
        }

        @Override
        public void reportDegraded(String sanitizedReason) {
            reportSourceDegraded(nodeId, sanitizedReason);
        }

        @Override
        public void reportHealthy() {
            reportSourceHealthy(nodeId);
        }
    }

    /** Dynamic composition-root view: every call resolves the currently ready generation. */
    private final class DeploymentRequestReplyView implements RequestReplyIngress {
        @Override
        public RequestReplyAdmission request(IngressTarget target,
                                             ai.ravenroot.api.payload.PayloadValue payload,
                                             Instant deadline) {
            return requestReplyForGeneration(null, target, payload, deadline);
        }

        /** The deployment-wide view belongs to no single node, so it binds without one. */
        @Override
        public RequestReplyAdmission requestProjected(IngressTarget target,
                                                      RequestReplyProjection projection,
                                                      Instant deadline) {
            return requestReplyProjectedForGeneration(null, Optional.empty(), target, projection, deadline);
        }
    }

    /** Source view fenced to the generation that created and handed out its context. */
    private final class SourceRequestReplyView implements RequestReplyIngress {
        private final String nodeId;
        private final long generation;

        private SourceRequestReplyView(String nodeId, long generation) {
            this.nodeId = nodeId;
            this.generation = generation;
        }

        @Override
        public RequestReplyAdmission request(IngressTarget target,
                                             ai.ravenroot.api.payload.PayloadValue payload,
                                             Instant deadline) {
            return requestReplyForGeneration(generation, target, payload, deadline);
        }

        /**
         * The projected payload's binding carries this view's own node id -- the node that
         * received the {@link InboundSourceContext}, which is not necessarily the traversal's
         * {@link IngressTarget}. It is captured here, at the point the runtime built the view, so a
         * projection cannot claim to be running for a different node.
         */
        @Override
        public RequestReplyAdmission requestProjected(IngressTarget target,
                                                      RequestReplyProjection projection,
                                                      Instant deadline) {
            return requestReplyProjectedForGeneration(generation, Optional.ofNullable(nodeId), target,
                    projection, deadline);
        }
    }

    private RequestReplyAdmission requestReplyForGeneration(Long requiredGeneration, IngressTarget target,
            ai.ravenroot.api.payload.PayloadValue payload, Instant deadline) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(deadline, "deadline");
        RequestReplyGate gate = requestReplyGate(requiredGeneration);
        if (gate.refusal() != null) {
            return new RequestReplyAdmission.Refused(gate.refusal());
        }
        return gate.coordinator().request(target, payload, deadline);
    }

    /**
     * The projected admission path. It shares {@link #requestReplyGate} with the ordinary one,
     * so generation fencing and the READY/DEGRADED gate cannot diverge between the two: a source view
     * from a retired generation is refused {@code ADMISSION_CLOSED} here exactly as it is there, and
     * its projection never runs.
     */
    private RequestReplyAdmission requestReplyProjectedForGeneration(Long requiredGeneration,
            Optional<String> sourceNodeId, IngressTarget target, RequestReplyProjection projection,
            Instant deadline) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(deadline, "deadline");
        RequestReplyGate gate = requestReplyGate(requiredGeneration);
        if (gate.refusal() != null) {
            return new RequestReplyAdmission.Refused(gate.refusal());
        }
        return gate.coordinator().requestProjected(target, sourceNodeId, projection, deadline);
    }

    /** Either the coordinator currently entitled to admit, or the refusal that admits nothing. */
    private RequestReplyGate requestReplyGate(Long requiredGeneration) {
        RequestReplyCoordinator coordinator;
        DeploymentState current;
        long currentGeneration;
        lock.lock();
        try {
            coordinator = requestReplyCoordinator;
            current = status.state();
            currentGeneration = ingressGeneration;
        } finally {
            lock.unlock();
        }
        if (requiredGeneration != null && requiredGeneration.longValue() != currentGeneration) {
            return new RequestReplyGate(null, RequestReplyRefusal.ADMISSION_CLOSED);
        }
        if (current == DeploymentState.STOPPING || current == DeploymentState.STOPPED) {
            return new RequestReplyGate(null, RequestReplyRefusal.ADMISSION_CLOSED);
        }
        if ((current != DeploymentState.READY && current != DeploymentState.DEGRADED) || coordinator == null) {
            return new RequestReplyGate(null, RequestReplyRefusal.NOT_READY);
        }
        return new RequestReplyGate(coordinator, null);
    }

    private record RequestReplyGate(RequestReplyCoordinator coordinator, RequestReplyRefusal refusal) {
    }

    /**
     * The trusted inbound surface. Fixed capacity and policy for the deployment's life; admission
     * itself is read fresh from {@link DefaultGraphDeployment#status} on every {@link #offer}, so it
     * reflects the deployment's real state rather than a snapshot taken when this view was built.
     */
    /**
     * Creates this traversal's process instance, then takes its lease, and returns the recorder that
     * holds it. {@code null} when no store is composed, which keeps in-memory-only behaviour for every
     * deployment built without one.
     *
     * <h2>Why the recorder is per traversal and must never be hoisted</h2>
     * <p>A lease is anchored to <em>one process instance</em>, and a long-lived deployment runs many —
     * one per accepted ingress event. Opening the recorder at deployment level would hold a single
     * lease over instances it does not own: every traversal after the first would execute unfenced
     * while <em>looking</em> fenced, and a recovery sweep would be free to claim them. That is worse
     * than holding no lease at all, because the protection is only apparent.
     *
     * <h2>Ordering, which is the whole of prerequisite 2</h2>
     * <p>The instance is created <b>before</b> the lease, and the lease <b>before</b> any attempt row.
     * A lease on a nonexistent instance is not stale, it is impossible; and taking it before any
     * invocation or attempt exists is what leaves no window in which a recovery sweep could see
     * claimable work on an instance this deployment is about to execute. The direct submission path
     * gets this ordering from its acceptance write returning the revision; this path had no
     * equivalent, and this method is it.
     */
    private ExecutionRecorder openTraversalRecorder(SecurityContext security, UUID processInstanceId,
                                                    UUID traversalId) {
        if (executionStore == null) {
            return null;
        }
        // The definition is made durable BEFORE the acceptance that pins it, for the reason the
        // definition port states: an unreferenced definition is reclaimable, an unrecoverable
        // execution is not. Idempotent by content, so a deployment accepting its thousandth traversal
        // pays a lookup rather than a thousandth copy.
        recordGraphDefinition(security);
        var key = new ai.ravenroot.api.persistence.ExecutionKey(security.tenantId(), processInstanceId);
        var traversal = new ai.ravenroot.api.application.Traversal(traversalId, manager.start().id(),
                ai.ravenroot.api.application.TraversalStatus.ACCEPTED, java.util.Map.of());
        var accepted = new ai.ravenroot.api.application.ProcessInstance(processInstanceId,
                ai.ravenroot.api.application.ProcessInstanceStatus.ACCEPTED,
                java.util.Map.of(traversalId, traversal));

        // Issue 154: every traversal opened here is deployment-hosted, so both halves of ADR 0021 D5's
        // pair are knowable at admission and are recorded exactly as GraphRunner.execute below is
        // stamped with them -- this deployment's own identity as deploymentId, and this traversal's id
        // (the ADR 0021 D3 unit-of-work / sharding key) as workloadId. requestId is SecurityContext's
        // own ingress correlation identifier. Recording all three here, once, at the instance's
        // creation is what keeps the inventory row from ever needing a later, less-informed write to
        // invent them.
        var created = awaitStore(executionStore.apply(
                ai.ravenroot.api.persistence.ExecutionBatch.to(key)
                        .expecting(ai.ravenroot.api.persistence.RevisionExpectation.notPresent())
                        .apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessCreated(accepted,
                                new ai.ravenroot.api.persistence.GraphVersionPin(graphVersion)))
                        .recordOrigin(ai.ravenroot.api.persistence.ExecutionOrigin.of(
                                id.value(), traversalId.toString(), security.requestId()))
                        .build()));
        // RUNNING is committed here, before the engine send below, so a persisted RUNNING means
        // "sent, outcome unknown" rather than "about to be sent" -- the reading PERS-04's recovery
        // loop depends on, carried unchanged from the submission path.
        long revision = awaitStore(executionStore.apply(
                ai.ravenroot.api.persistence.ExecutionBatch.to(key)
                        .expecting(ai.ravenroot.api.persistence.RevisionExpectation.exactly(created.revision()))
                        .apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessTransitioned(
                                ai.ravenroot.api.application.ProcessInstanceStatus.RUNNING))
                        .apply(new ai.ravenroot.api.persistence.ExecutionTransition.TraversalTransitioned(
                                traversalId, ai.ravenroot.api.application.TraversalStatus.RUNNING))
                        .build())).revision();

        return ExecutionRecorder.open(executionStore, key, workerId, executionLeaseTtl, revision);
    }

    /**
     * Commits the document this deployment hosts, so the pin written next addresses bytes the store
     * actually holds. A failure propagates and the traversal is not accepted.
     *
     * <p>Reached only from {@link #openTraversalRecorder}, which returns before this when no execution
     * store is composed. A deployment with a definition store and no execution store therefore
     * commits nothing, correctly: there is no pin to protect.</p>
     */
    private void recordGraphDefinition(SecurityContext security) {
        if (graphDefinitionStore == null) {
            return;
        }
        var canonical = ai.ravenroot.api.persistence.CanonicalGraphMl.of(graphMl);
        try {
            graphDefinitionStore.put(security.tenantId(),
                            ai.ravenroot.api.persistence.GraphDefinitionIdentity.forSubmission(
                                    canonical.contentId()), canonical)
                    .toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException wrapped) {
            var failure = ai.ravenroot.api.persistence.GraphDefinitionStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }

    /**
     * Releases a traversal's lease. Best-effort by the same ADR 0010 section 13.1 invariant the
     * recorder documents: a crash performs no release and must reach the same state by expiry, so
     * making an orderly path depend on release succeeding would give crash and orderly recovery two
     * different paths.
     */
    private static void closeQuietly(ExecutionRecorder recorder) {
        if (recorder == null) {
            return;
        }
        try {
            recorder.close();
        } catch (RuntimeException expiryWillHandleIt) {
            // Expiry is the backstop; a failed release must not fail the traversal that succeeded.
        }
    }

    private CompletionStage<GraphExecutionResult> executeHosted(GraphRunner activeRunner,
            SecurityContext security, UUID processInstanceId, UUID traversalId, Object payload,
            ExecutionRecorder recorder) {
        ai.ravenroot.api.persistence.ExecutionKey key = new ai.ravenroot.api.persistence.ExecutionKey(
                security.tenantId(), processInstanceId);
        AutoCloseable budgetBinding = null;
        try {
            budgetBinding = agentBudgets == null || recorder == null
                    ? null : agentBudgets.bindLive(key, recorder);
            CompletionStage<GraphExecutionResult> execution = activeRunner.execute(security,
                    processInstanceId, traversalId, payload, graphVersion, id.value(),
                    traversalId.toString(), recorder);
            AutoCloseable finalBudgetBinding = budgetBinding;
            return execution.whenComplete((result, failure) -> {
                Throwable cause = unwrapFailure(failure);
                try {
                    if (agentBudgets != null && recorder != null && (cause == null
                            || !(cause instanceof ai.ravenroot.core.security.nodepackage.DurableToolApprovalSuspension
                            || cause instanceof ai.ravenroot.core.humantask.DurableHumanTaskSuspension))) {
                        agentBudgets.finishProcess(key, failure == null && result != null);
                    }
                } finally {
                    closeQuietly(finalBudgetBinding);
                    closeQuietly(recorder);
                }
            });
        } catch (RuntimeException | Error failure) {
            closeQuietly(budgetBinding);
            closeQuietly(recorder);
            throw failure;
        }
    }

    private static Throwable unwrapFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void closeQuietly(AutoCloseable binding) {
        if (binding == null) return;
        try {
            binding.close();
        } catch (Exception ignored) {
            // Store lease expiry and process teardown remain the recovery backstops.
        }
    }

    private static <T> T awaitStore(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException wrapped) {
            var failure = ai.ravenroot.api.persistence.ExecutionStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }

    private final class IngressView implements TrustedIngress {
        @Override
        public IngressDisposition offer(SecurityContext security, IngressTarget target, Object payload) {
            Objects.requireNonNull(security, "security");
            Objects.requireNonNull(target, "target");
            if (target.nodeId().isPresent()) {
                // Phase A implements only ingress at the graph's declared start.
                // Silently starting there anyway for a named target would run the
                // traversal from the wrong node without saying so, which is worse than refusing.
                throw new UnsupportedOperationException(
                        "Ingress to a named node is not yet implemented; use IngressTarget.start()");
            }
            DeploymentState currentState;
            GraphRunner activeRunner;
            Semaphore permits;
            lock.lock();
            try {
                currentState = status.state();
                activeRunner = runner;
                permits = ingressPermits;
            } finally {
                lock.unlock();
            }
            if (currentState == DeploymentState.STOPPING || currentState == DeploymentState.STOPPED) {
                return IngressDisposition.REJECTED_ADMISSION_CLOSED;
            }
            // DEGRADED admits too: it means "serving with reduced capability... work is still being
            // accepted". A source can enter
            // DEGRADED through reportDegraded, so rejecting ingress here would contradict that state.
            boolean admitting = currentState == DeploymentState.READY || currentState == DeploymentState.DEGRADED;
            if (!admitting || activeRunner == null || permits == null) {
                return IngressDisposition.REJECTED_NOT_READY;
            }
            if (!permits.tryAcquire()) {
                return IngressDisposition.REJECTED_BUFFER_FULL;
            }
            UUID processInstanceId = identitySource.nextProcessInstanceId();
            UUID traversalId = identitySource.nextTraversalId();
            ExecutionRecorder recorder;
            try {
                recorder = openTraversalRecorder(security, processInstanceId, traversalId);
            } catch (ExecutionInstanceBusyException busy) {
                // Fail closed. See IngressDisposition.REJECTED_INSTANCE_BUSY for why this is
                // unreachable today and must not be deleted as dead code.
                permits.release();
                return IngressDisposition.REJECTED_INSTANCE_BUSY;
            } catch (RuntimeException | Error recordFailure) {
                permits.release();
                throw recordFailure;
            }
            try {
                // ADR 0021 D5. deploymentId is this deployment's own identity;
                // workloadId is the traversal's -- the "whole item" ADR 0021 D3's sharding key places
                // as a unit, which in the current scope (no cluster, no multi-attempt redelivery) is exactly
                // what one accepted ingress event's traversal already is. Both are fixed for this one
                // execute() call, so every event it produces carries the same pair.
                executeHosted(activeRunner, security, processInstanceId, traversalId, payload, recorder)
                        .whenComplete((ignoredResult, ignoredError) -> {
                            // Closed on TRAVERSAL completion, never on deployment stop: a
                            // deployment that runs for days would otherwise hold every instance it
                            // ever touched, and each held lease is an instance a recovery sweep is
                            // correctly forbidden from reclaiming.
                            permits.release();
                        });
            } catch (RuntimeException | Error dispatchFailure) {
                permits.release();
                throw dispatchFailure;
            }
            return IngressDisposition.ACCEPTED;
        }

        /**
         * Delegates entirely to {@link #offer} when no store is configured -- the exact same
         * degrade-to-volatile the interface default provides, kept here only so this deployment's
         * admission is evaluated once rather than reimplemented. When a store is configured, admission
         * is checked first (a refusal never reaches the store) and the durable commit happens before
         * the traversal is dispatched, never after -- so a crash between the two leaves a durable
         * record with no traversal, recoverable by redelivery being recognised as {@code Duplicate},
         * rather than a traversal with no durable record, which redelivery could not detect at all.
         */
        @Override
        public ai.ravenroot.api.deployment.IngressReceipt offerDurably(SecurityContext security,
                IngressTarget target, Object payload, String sourceId, String idempotentKey) {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(idempotentKey, "idempotentKey");
            if (executionStore == null) {
                return TrustedIngress.super.offerDurably(security, target, payload, sourceId, idempotentKey);
            }
            Objects.requireNonNull(security, "security");
            Objects.requireNonNull(target, "target");
            if (target.nodeId().isPresent()) {
                throw new UnsupportedOperationException(
                        "Ingress to a named node is not yet implemented; use IngressTarget.start()");
            }
            DeploymentState currentState;
            GraphRunner activeRunner;
            Semaphore permits;
            lock.lock();
            try {
                currentState = status.state();
                activeRunner = runner;
                permits = ingressPermits;
            } finally {
                lock.unlock();
            }
            if (currentState == DeploymentState.STOPPING || currentState == DeploymentState.STOPPED) {
                return new ai.ravenroot.api.deployment.IngressReceipt.Refused("admission closed");
            }
            boolean admitting = currentState == DeploymentState.READY || currentState == DeploymentState.DEGRADED;
            if (!admitting || activeRunner == null || permits == null) {
                return new ai.ravenroot.api.deployment.IngressReceipt.Refused("not ready");
            }
            if (!permits.tryAcquire()) {
                return new ai.ravenroot.api.deployment.IngressReceipt.Refused("buffer full");
            }

            String tenantId = security.tenantId();
            String destination = id.value() + "/" + sourceId;
            UUID eventId = UUID.nameUUIDFromBytes(
                    (tenantId + '\0' + destination + '\0' + idempotentKey)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

            boolean firstDelivery;
            try {
                firstDelivery = executionStore.recordInboxDelivery(tenantId, destination, eventId, inboxRetention)
                        .toCompletableFuture().get(DEFAULT_STORE_CALL_BOUND.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RuntimeException | ExecutionException | TimeoutException storeFailure) {
                // The commit is genuinely unknown, not refused (IngressReceipt.Ambiguous's own
                // Javadoc): the traversal is not dispatched, because dispatching now could duplicate
                // work a delayed, eventually-successful write already durably recorded. Reconciliation
                // is re-offering the same idempotentKey once the caller has backed off.
                permits.release();
                return new ai.ravenroot.api.deployment.IngressReceipt.Ambiguous(idempotentKey,
                        "durable commit did not resolve within its bound");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                permits.release();
                return new ai.ravenroot.api.deployment.IngressReceipt.Ambiguous(idempotentKey,
                        "interrupted waiting for durable commit");
            }

            if (!firstDelivery) {
                // Already durably recorded by an earlier call. No second traversal: recordInboxDelivery
                // is what converts the source's at-least-once redelivery into at-most-once effect here.
                permits.release();
                return new ai.ravenroot.api.deployment.IngressReceipt.Duplicate(idempotentKey);
            }

            UUID processInstanceId = identitySource.nextProcessInstanceId();
            UUID traversalId = identitySource.nextTraversalId();
            ExecutionRecorder recorder;
            try {
                recorder = openTraversalRecorder(security, processInstanceId, traversalId);
            } catch (ExecutionInstanceBusyException busy) {
                // Fail closed, expressed in the receipt the caller already knows how to read.
                // Unreachable today; see IngressDisposition.REJECTED_INSTANCE_BUSY for why, and why
                // the refuse-versus-queue choice belongs to the re-entry work rather than here.
                permits.release();
                return new ai.ravenroot.api.deployment.IngressReceipt.Refused(
                        "process instance is already leased by another worker");
            } catch (RuntimeException | Error recordFailure) {
                permits.release();
                throw recordFailure;
            }
            try {
                executeHosted(activeRunner, security, processInstanceId, traversalId, payload, recorder)
                        .whenComplete((ignoredResult, ignoredError) -> {
                            permits.release();
                        });
            } catch (RuntimeException | Error dispatchFailure) {
                permits.release();
                throw dispatchFailure;
            }
            return new ai.ravenroot.api.deployment.IngressReceipt.DurablyCommitted(idempotentKey);
        }

        @Override
        public CompletionStage<JournalCursor> sourceCheckpoint(SecurityContext security, String sourceId) {
            Objects.requireNonNull(security, "security");
            Objects.requireNonNull(sourceId, "sourceId");
            if (executionStore == null) {
                return TrustedIngress.super.sourceCheckpoint(security, sourceId);
            }
            return executionStore.outboxCursor(security.tenantId(), id.value() + "/" + sourceId);
        }

        @Override
        public CompletionStage<JournalCursor> advanceSourceCheckpoint(JournalCursor expected, long throughPosition) {
            Objects.requireNonNull(expected, "expected");
            if (executionStore == null) {
                return TrustedIngress.super.advanceSourceCheckpoint(expected, throughPosition);
            }
            return executionStore.advanceOutboxCursor(expected, throughPosition);
        }

        @Override
        public int bufferCapacity() {
            return ingressBufferCapacity;
        }

        @Override
        public IngressOverflowPolicy overflowPolicy() {
            return IngressOverflowPolicy.REJECT;
        }
    }
}
