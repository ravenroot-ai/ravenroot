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
import ai.ravenroot.api.deployment.lifecycle.DeploymentLifecycleTarget;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.ObservedKind;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 *
 * <h2>Two interfaces, one runtime, and why that is not two lifecycles</h2>
 * <p>This class also implements {@link DeploymentLifecycleTarget}, the port a durable lifecycle
 * authority reaches through (ADR 0038 D9). The two interfaces are not two lifecycles racing each
 * other over one deployment: {@link GraphDeployment} is the embedding process asking for a start or a
 * stop, and the port is an authority carrying out a decision it has already made durable. They meet
 * on the same {@link #lock}, the same {@link #status} and the same {@link #ingressGeneration}, so
 * whichever of them moved the deployment last is what the other one reads. What the port adds is the
 * generation: a level command carries the generation its decision was recorded at, and this class
 * <em>adopts</em> that number rather than keeping a private counter beside it, because two monotonic
 * generations for one deployment can only ever disagree.</p>
 *
 * <p><b>Nothing on the port reaches {@link ExecutionEngine#drain()} or {@link ExecutionEngine#close()},
 * and the widest thing it reaches is {@link #stop()}</b> -- this deployment's own domain, sources and
 * in-flight work. The engine is shared with every sibling deployment hosted beside this one, and ADR
 * 0038 D9 exists to keep an operator draining one deployment from taking all of them down.</p>
 *
 * <h2>The half-open barrier, where it actually happens</h2>
 * <p>{@link IngressView#offer} decides admission and assigns the arrival's generation in one step,
 * under the same {@link #lock} {@link #barrier(long)} takes. That is what makes ADR 0038 D6's
 * half-open interval true of real arrivals rather than of the arithmetic alone: an arrival cannot
 * observe itself admitted and then read a generation a barrier has already closed, because there is
 * no instant between the two for the barrier to run in. Admission closes at {@code G}, work already
 * admitted completes under {@code G}, and everything arriving afterwards enters at {@code G + 1};
 * every comparison below is exact equality and never an ordering test.</p>
 */
public final class DefaultGraphDeployment implements GraphDeployment, DeploymentLifecycleTarget {
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

    /**
     * How often {@link #drain(Duration, long)} re-reads whether the deployment has gone quiet.
     * Short enough that a drain reports promptly, long enough that a long bound is not a spin.
     */
    private static final Duration DRAIN_POLL_INTERVAL = Duration.ofMillis(5);

    private static final Executor VIRTUAL_THREADS = command -> Thread.startVirtualThread(command);

    private final DeploymentId id;
    /**
     * Deployment identity stamped into durable execution context. Local deployments keep their
     * tenant-derived engine id private while publishing and persisting the caller-facing id here.
     */
    private final String executionContextDeploymentId;
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
    /** Durable Human Task coordinator bound for every deployment-hosted traversal. */
    private final ai.ravenroot.core.humantask.HumanTaskService humanTasks;
    /**
     * Records what each accepted traversal's dependencies resolved to, or {@code null} when nothing
     * records them.
     *
     * <p>Composed rather than built here for the same reason the recovery executors take one: a
     * deployment-hosted traversal and a submitted execution must be pinned against one description of
     * the runtime, or a recovery would refuse one of the two for a difference that is really two
     * resolvers disagreeing.</p>
     */
    private final ai.ravenroot.core.manifest.ExecutionManifestService executionManifests;
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
    private final Duration runnerShutdownStepBound;
    /** Node ids currently reporting degraded, via {@link InboundSourceContext#reportDegraded}. */
    private final Set<String> degradedSources = ConcurrentHashMap.newKeySet();

    private final ReentrantLock lock = new ReentrantLock();
    private DeploymentStatus status;
    private ExecutionDomain domain;
    private GraphManager manager;
    private GraphRunner runner;
    private List<SourceHandle> sources = List.of();
    private volatile ManagedIngress managedIngress;
    /**
     * The generation admission is currently open at, and the value every arrival admitted through
     * {@link IngressView} is stamped with.
     *
     * <p>Derived rather than invented: a lifecycle authority naming a generation in
     * {@link #start(long, long)}, {@link #closeAdmission(long)}, {@link #openAdmission(long)} or
     * {@link #barrier(long)} sets this field to exactly that number. The {@code + 1} in
     * {@link #nextIngressGeneration()} is the fallback for an embedded process that has no durable
     * authority at all, not a second source of truth competing with one: a process-local counter that
     * restarts at zero every process cannot fence anything across a restart, and a deployment driven
     * by an authority never uses it.</p>
     */
    private long ingressGeneration;
    /**
     * Whether a barrier governs the current generation, and which generation it opened.
     *
     * <p>Two fields rather than a sentinel generation: {@code DeploymentRegistry.Record} admits a
     * generation of zero, so a zero here would have to mean both "no barrier" and "a barrier at
     * generation zero", and the reading that silently wins is the one that ends nothing.</p>
     *
     * <p>Cleared by {@link #start(long, long)}, {@link #closeAdmission(long)} and
     * {@link #openAdmission(long)}, because none of those ends work: a unit admitted before them
     * completes under the generation it carries (ADR 0038 D6), and leaving a stale barrier standing
     * would refuse it. Kept as the barrier's own generation rather than as a floor so the dispatch
     * test is the exact equality D6 requires and never an ordering test.</p>
     */
    private boolean barrierStanding;
    private long barrierGeneration;
    /**
     * The generation the current activation started at, and the one every source context this
     * activation handed out is fenced to.
     *
     * <p>Not a rival to {@link #ingressGeneration}: it is that field's value at the last
     * {@link #doStart}, and it is separate because the two answer different questions. Admission's
     * generation moves whenever a lifecycle authority acts -- a pause, a resume, a barrier -- while a
     * source's context stays valid across all of those, because none of them replaced the activation
     * that built it. Fencing a source view against the admission generation would retire a live
     * source's request/reply view the first time an operator paused and resumed the deployment, which
     * is precisely the run it was supposed to survive. Only a real start moves this one.</p>
     */
    private long activationGeneration;
    /** Admission closed by a lifecycle authority, with the activation deliberately retained. */
    private boolean admissionFenced;
    /** A {@link #drain(Duration, long)} is running and has not yet reported. */
    private boolean draining;
    /** The most recent drain finished inside its bound with nothing left in flight. */
    private boolean drained;
    /**
     * The graph version a lifecycle authority activated, reported as {@link Reading#activeVersion()}.
     *
     * <p>{@code null} until an authority names one. An ordinary {@link #start(SecurityContext)} does
     * not invent a number here: this deployment hosts one immutable document and {@link #graphVersion}
     * is its real, content-addressed identity, while {@code Reading}'s version is the durable
     * registry's monotonic one. Reporting a made-up long as the second would be evidence nobody
     * recorded.</p>
     */
    private Long activatedVersion;
    /**
     * The identity the last {@link #start(SecurityContext)} ran as, retained so an authority-driven
     * start has somebody to run as.
     *
     * <p>{@link DeploymentLifecycleTarget#start(long, long)} carries a generation and a version and
     * no principal, deliberately: it is a port for effects, not an authorization boundary. Minting a
     * principal here would let a lifecycle authority start a deployment under an identity nobody
     * granted, so the identity is instead the one the composition root already established for this
     * deployment. A deployment that has never been started in this process has none, and an
     * authority-driven start fails rather than guessing.</p>
     */
    private SecurityContext lifecycleIdentity;
    /** Generation the next {@link #doStart} must adopt, or {@code null} to advance the local counter. */
    private Long pendingGeneration;
    /**
     * Every admitted unit of work that has not finished, and the generation it was admitted at.
     *
     * <p>Entries are added under {@link #lock} at admission and removed when the traversal completes.
     * Bounded by {@link #ingressBufferCapacity} rather than by the deployment's lifetime: a unit holds
     * an ingress permit for exactly as long as it holds an entry here.</p>
     */
    private final ConcurrentHashMap<UUID, Long> admitted = new ConcurrentHashMap<>();
    /**
     * The units in {@link #admitted} that {@link IngressView#offer} has assigned a generation but not
     * yet handed to the runner, because it is opening their recorder outside {@link #lock}.
     *
     * <p>A barrier cannot end such a unit -- there is no traversal yet -- so it closes it instead, by
     * taking it out of this set and out of {@link #admitted}. The offer then finds it gone when it
     * returns to dispatch and refuses it. Deciding the refusal here rather than from whether a barrier
     * still governs when the offer returns is what keeps it the barrier's decision: a pause and resume
     * in between adopt a new generation and stand the barrier down, and must not reopen a generation it
     * ended. Written and read under {@link #lock}.</p>
     */
    private final Set<UUID> awaitingDispatch = new HashSet<>();
    /**
     * How many admitted units the most recent {@link #barrier(long)} actually ended.
     *
     * <p>Counted from cancellations the runner accepted over units the runner had actually been handed,
     * never from the size of the barrier's own snapshot. {@link GraphRunner#cancelTraversal} answers
     * {@code true} for any identifier it has not refused before, whether or not a traversal with that
     * identifier ever ran, so only the snapshot can say there was work to end: a unit still
     * {@link #awaitingDispatch} is refused by its offer and counted there, never here, or one arrival
     * would be reported on both sides of the barrier. A dispatched unit that completes between the
     * snapshot and its cancellation is still counted; cooperative cancellation cannot tell that
     * afterwards, which is why the barrier tests park their work until the count has been read.</p>
     */
    private long endedByLastBarrier;
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

    /**
     * Full production composition that also records what each accepted traversal was resolved against.
     *
     * @param id deployment identity.
     * @param engine execution engine this deployment's domain is opened on.
     * @param behaviors trusted behavior catalog.
     * @param monitor execution monitor that observes this deployment's traversals.
     * @param identitySource source of identifiers for accepted traversals.
     * @param graphMl the GraphML document this deployment hosts.
     * @param ingressBufferCapacity bound on the trusted ingress buffer.
     * @param executionStore durable execution state, or {@code null}.
     * @param inboxRetention how long a durable inbox record is retained.
     * @param workerId identity this deployment claims leases under.
     * @param executionLeaseTtl how long a traversal's lease lives.
     * @param requestReplyLimits bounds on live request/reply ingress.
     * @param graphDefinitionStore durable graph definitions, or {@code null} to retain no document.
     * @param graphExecutionLimits operator-owned admission and traversal limits.
     * @param agentBudgets agent authority budget service, or {@code null}.
     * @param executionManifests manifest service, or {@code null} to record none.
     */
    public DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                                  ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                                  byte[] graphMl, int ingressBufferCapacity,
                                  ai.ravenroot.api.persistence.ExecutionStore executionStore,
                                  Duration inboxRetention, String workerId, Duration executionLeaseTtl,
                                  RequestReplyLimits requestReplyLimits,
                                  ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore,
                                  GraphExecutionLimits graphExecutionLimits,
                                  ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets,
                                  ai.ravenroot.core.manifest.ExecutionManifestService executionManifests) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity,
                executionStore, inboxRetention, workerId, executionLeaseTtl, requestReplyLimits,
                Clock.systemUTC(), graphDefinitionStore, graphExecutionLimits, agentBudgets,
                executionManifests);
    }

    /**
     * Full production composition including deployment-hosted durable Human Tasks.
     * @param id deployment identity
     * @param engine execution engine
     * @param behaviors trusted behavior registry
     * @param monitor execution monitor
     * @param identitySource source of runtime identifiers
     * @param graphMl immutable GraphML source
     * @param ingressBufferCapacity bounded inbound queue capacity
     * @param executionStore durable execution store
     * @param inboxRetention request/reply inbox retention
     * @param workerId durable lease owner
     * @param executionLeaseTtl execution lease duration
     * @param requestReplyLimits request/reply limits
     * @param graphDefinitionStore durable graph-definition store
     * @param graphExecutionLimits graph execution limits
     * @param agentBudgets optional durable agent authority budgets
     * @param humanTasks optional durable Human Task service
     * @param executionManifests optional execution-manifest service
     */
    public DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                                  ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                                  byte[] graphMl, int ingressBufferCapacity,
                                  ai.ravenroot.api.persistence.ExecutionStore executionStore,
                                  Duration inboxRetention, String workerId, Duration executionLeaseTtl,
                                  RequestReplyLimits requestReplyLimits,
                                  ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore,
                                  GraphExecutionLimits graphExecutionLimits,
                                  ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets,
                                  ai.ravenroot.core.humantask.HumanTaskService humanTasks,
                                  ai.ravenroot.core.manifest.ExecutionManifestService executionManifests) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity,
                executionStore, inboxRetention, workerId, executionLeaseTtl, requestReplyLimits,
                Clock.systemUTC(), graphDefinitionStore, graphExecutionLimits, agentBudgets,
                humanTasks, executionManifests, null);
    }

    /** Package-private local-deployment seam for its public durable execution-context identity. */
    DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                           ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                           byte[] graphMl, int ingressBufferCapacity,
                           ai.ravenroot.api.persistence.ExecutionStore executionStore,
                           Duration inboxRetention, String workerId, Duration executionLeaseTtl,
                           RequestReplyLimits requestReplyLimits,
                           ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore,
                           GraphExecutionLimits graphExecutionLimits,
                           ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets,
                           ai.ravenroot.core.humantask.HumanTaskService humanTasks,
                           ai.ravenroot.core.manifest.ExecutionManifestService executionManifests,
                           String executionContextDeploymentId) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity,
                executionStore, inboxRetention, workerId, executionLeaseTtl, requestReplyLimits,
                graphDefinitionStore, graphExecutionLimits, agentBudgets, humanTasks,
                executionManifests, executionContextDeploymentId, GraphRunner.DEFAULT_SHUTDOWN_BOUND);
    }

    /** Package-private composition seam for the process-resolved runner shutdown policy. */
    DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                           ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                           byte[] graphMl, int ingressBufferCapacity,
                           ai.ravenroot.api.persistence.ExecutionStore executionStore,
                           Duration inboxRetention, String workerId, Duration executionLeaseTtl,
                           RequestReplyLimits requestReplyLimits,
                           ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore,
                           GraphExecutionLimits graphExecutionLimits,
                           ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets,
                           ai.ravenroot.core.humantask.HumanTaskService humanTasks,
                           ai.ravenroot.core.manifest.ExecutionManifestService executionManifests,
                           String executionContextDeploymentId,
                           Duration runnerShutdownStepBound) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity,
                executionStore, inboxRetention, workerId, executionLeaseTtl, requestReplyLimits,
                Clock.systemUTC(), graphDefinitionStore, graphExecutionLimits, agentBudgets,
                humanTasks, executionManifests, executionContextDeploymentId, runnerShutdownStepBound);
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
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity,
                executionStore, inboxRetention, workerId, executionLeaseTtl, requestReplyLimits, clock,
                graphDefinitionStore, graphExecutionLimits, agentBudgets, null);
    }

    private DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                           ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                           byte[] graphMl, int ingressBufferCapacity,
                           ai.ravenroot.api.persistence.ExecutionStore executionStore,
                           Duration inboxRetention, String workerId, Duration executionLeaseTtl,
                           RequestReplyLimits requestReplyLimits, Clock clock,
                           ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore,
                           GraphExecutionLimits graphExecutionLimits,
                           ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets,
                           ai.ravenroot.core.manifest.ExecutionManifestService executionManifests) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity,
                executionStore, inboxRetention, workerId, executionLeaseTtl, requestReplyLimits, clock,
                graphDefinitionStore, graphExecutionLimits, agentBudgets, null, executionManifests, null);
    }

    private DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                           ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                           byte[] graphMl, int ingressBufferCapacity,
                           ai.ravenroot.api.persistence.ExecutionStore executionStore,
                           Duration inboxRetention, String workerId, Duration executionLeaseTtl,
                           RequestReplyLimits requestReplyLimits, Clock clock,
                           ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore,
                           GraphExecutionLimits graphExecutionLimits,
                           ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets,
                           ai.ravenroot.core.humantask.HumanTaskService humanTasks,
                           ai.ravenroot.core.manifest.ExecutionManifestService executionManifests,
                           String executionContextDeploymentId) {
        this(id, engine, behaviors, monitor, identitySource, graphMl, ingressBufferCapacity,
                executionStore, inboxRetention, workerId, executionLeaseTtl, requestReplyLimits, clock,
                graphDefinitionStore, graphExecutionLimits, agentBudgets, humanTasks,
                executionManifests, executionContextDeploymentId, GraphRunner.DEFAULT_SHUTDOWN_BOUND);
    }

    private DefaultGraphDeployment(DeploymentId id, ExecutionEngine engine, BehaviorRegistry behaviors,
                           ExecutionMonitor monitor, ExecutionIdentitySource identitySource,
                           byte[] graphMl, int ingressBufferCapacity,
                           ai.ravenroot.api.persistence.ExecutionStore executionStore,
                           Duration inboxRetention, String workerId, Duration executionLeaseTtl,
                           RequestReplyLimits requestReplyLimits, Clock clock,
                           ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore,
                           GraphExecutionLimits graphExecutionLimits,
                           ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets,
                           ai.ravenroot.core.humantask.HumanTaskService humanTasks,
                           ai.ravenroot.core.manifest.ExecutionManifestService executionManifests,
                           String executionContextDeploymentId,
                           Duration runnerShutdownStepBound) {
        this.executionManifests = executionManifests;
        this.graphDefinitionStore = graphDefinitionStore;
        this.agentBudgets = agentBudgets;
        this.humanTasks = humanTasks;
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.executionLeaseTtl = Objects.requireNonNull(executionLeaseTtl, "executionLeaseTtl");
        this.requestReplyLimits = Objects.requireNonNull(requestReplyLimits, "requestReplyLimits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.graphExecutionLimits = Objects.requireNonNull(graphExecutionLimits, "graphExecutionLimits");
        this.runnerShutdownStepBound = Objects.requireNonNull(runnerShutdownStepBound, "runnerShutdownStepBound");
        if (runnerShutdownStepBound.isZero() || runnerShutdownStepBound.isNegative()) {
            throw new IllegalArgumentException("runnerShutdownStepBound must be positive");
        }
        this.id = Objects.requireNonNull(id, "id");
        this.executionContextDeploymentId = executionContextDeploymentId == null
                ? this.id.value()
                : ai.ravenroot.api.persistence.HandlerRegistration.requireBoundedKey(
                        executionContextDeploymentId, "executionContextDeploymentId");
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
            // Retained before the early return, so an already-READY deployment still establishes the
            // identity an authority-driven start will later need. See lifecycleIdentity for why this
            // is captured rather than minted.
            this.lifecycleIdentity = security;
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

    // ------------------------------------------------------------------ DeploymentLifecycleTarget

    /**
     * Activates this deployment's document at {@code graphVersion} and opens admission at
     * {@code deploymentGeneration}.
     *
     * <h2>Why a version disagreement fails instead of being ignored</h2>
     * <p>A {@code DefaultGraphDeployment} hosts one immutable GraphML document for its whole life --
     * {@link #graphMl} is final and re-parsed on every start -- so it cannot activate a version it was
     * not built with. An authority naming a different version is asking for something this runtime
     * cannot do, and starting the document it does hold would report success for the wrong graph.
     * Failing the stage classifies it as a lifecycle failure the operator can see; a rollout to a new
     * version is a new registration, not a start.</p>
     *
     * @param graphVersion the durable registry's version for this activation, at least one.
     * @param deploymentGeneration generation this activation is admitted under.
     * @return stage completing when the deployment is activated and admitting.
     */
    @Override
    public CompletionStage<Void> start(long graphVersion, long deploymentGeneration) {
        if (graphVersion < 1) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("graphVersion must be at least 1"));
        }
        SecurityContext identity;
        lock.lock();
        try {
            if (activatedVersion != null && activatedVersion != graphVersion) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "this deployment hosts one graph document and cannot activate another version"));
            }
            identity = lifecycleIdentity;
            if (identity == null) {
                // Stated rather than worked around. See lifecycleIdentity: the port carries effects,
                // not a principal, and inventing one here would start a deployment as somebody nobody
                // authorized.
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "no identity has been established for this deployment in this process"));
            }
            activatedVersion = graphVersion;
            DeploymentState current = status.state();
            if (current == DeploymentState.READY || current == DeploymentState.DEGRADED) {
                // Already activated, so this start is only the admission half. Re-activating a running
                // deployment would discard exactly the work an authority converging on RUNNING asked
                // to keep, and it is what makes this operation idempotent per generation.
                adoptGeneration(deploymentGeneration);
                admissionFenced = false;
                draining = false;
                drained = false;
                return CompletableFuture.completedFuture(null);
            }
            // Consumed by the doStart this call is about to reach. A start already in flight has
            // consumed its own generation; the coordinator's per-deployment single flight is what
            // keeps a second lifecycle command from arriving inside that window.
            pendingGeneration = deploymentGeneration;
        } finally {
            lock.unlock();
        }
        return start(identity).thenAccept(ignoredStatus -> { });
    }

    @Override
    public CompletionStage<Void> closeAdmission(long deploymentGeneration) {
        lock.lock();
        try {
            if (admissionFenced && ingressGeneration == deploymentGeneration) {
                return CompletableFuture.completedFuture(null);
            }
            adoptGeneration(deploymentGeneration);
            admissionFenced = true;
            // A close is not a drain: what was admitted is retained, not finished, and reporting
            // DRAINED or DRAINING here would tell an operator that work is completing when it is not.
            draining = false;
            drained = false;
        } finally {
            lock.unlock();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> openAdmission(long deploymentGeneration) {
        lock.lock();
        try {
            if (!admissionFenced && ingressGeneration == deploymentGeneration) {
                return CompletableFuture.completedFuture(null);
            }
            adoptGeneration(deploymentGeneration);
            admissionFenced = false;
            draining = false;
            drained = false;
        } finally {
            lock.unlock();
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Ends the work admitted before this generation and admits everything afterwards at it.
     *
     * <h2>The half-open interval, as two steps that cannot be interleaved</h2>
     * <p>The snapshot of what is being ended and the advance of {@link #ingressGeneration} happen in
     * one critical section, under the same {@link #lock} {@link IngressView#offer} holds while it
     * admits. An arrival therefore either registered before the snapshot -- in which case this barrier
     * ends it -- or after the advance, in which case it carries this generation and is not in the
     * snapshot. There is no third possibility, and that is the whole of ADR 0038 D6's "cannot escape
     * both generations".</p>
     *
     * <p>The cancellations run outside the lock, because {@link GraphRunner#cancelTraversal} reaches
     * live actors and holding a deployment-wide lock across that would let one slow node block every
     * admission decision. Nothing is lost by releasing it first: the generation has already moved, so
     * anything arriving during the cancellations is on the opening side by construction.</p>
     *
     * <p>This does not change how restrictive the deployment is. A barrier answers "not this work",
     * never "not this deployment": a paused deployment is still paused when it completes, and a
     * running one is still running.</p>
     *
     * @param deploymentGeneration generation the barrier opens; work carrying another one is ended.
     * @return stage completing when the work from before the barrier has been ended.
     */
    @Override
    public CompletionStage<Void> barrier(long deploymentGeneration) {
        List<UUID> ending;
        GraphRunner activeRunner;
        lock.lock();
        try {
            if (barrierStanding && barrierGeneration == deploymentGeneration) {
                return CompletableFuture.completedFuture(null);
            }
            activeRunner = runner;
            ending = new ArrayList<>();
            for (Map.Entry<UUID, Long> entry : admitted.entrySet()) {
                if (entry.getValue() == deploymentGeneration) {
                    continue;
                }
                UUID traversalId = entry.getKey();
                if (awaitingDispatch.remove(traversalId)) {
                    // Assigned the closing generation but never handed to the runner: there is no
                    // traversal to end. Closing it here is what makes its offer refuse it, and
                    // cancelling it as well would count that refusal a second time as ended work.
                    admitted.remove(traversalId);
                } else {
                    ending.add(traversalId);
                }
            }
            ingressGeneration = deploymentGeneration;
            barrierStanding = true;
            barrierGeneration = deploymentGeneration;
            endedByLastBarrier = 0;
        } finally {
            lock.unlock();
        }
        GraphRunner cancelling = activeRunner;
        return CompletableFuture.supplyAsync(() -> {
            long ended = 0;
            if (cancelling != null) {
                for (UUID traversalId : ending) {
                    // Every unit here was handed to the runner; the runner's answer only filters one
                    // an earlier call already asked to stop. It is not evidence that the traversal is
                    // still running -- cancelTraversal publishes its refusal unconditionally -- which
                    // is why units awaiting dispatch were closed above instead of reaching this loop.
                    if (cancelling.cancelTraversal(traversalId)) {
                        ended++;
                    }
                }
            }
            lock.lock();
            try {
                if (barrierStanding && barrierGeneration == deploymentGeneration) {
                    endedByLastBarrier = ended;
                }
            } finally {
                lock.unlock();
            }
            return (Void) null;
        }, VIRTUAL_THREADS);
    }

    /**
     * Carries admitted work to completion within {@code bound}, keeping the activation.
     *
     * <p>The bound is honoured here rather than by the caller, so a drain that ran out of time is
     * reported as {@code false} instead of leaving the runtime draining while the authority records
     * that it stopped. Timed on {@link System#nanoTime()} rather than on {@link #clock}: this is a
     * duration, and a test clock that does not advance would turn a bounded wait into a hang.</p>
     *
     * @param bound positive maximum time admitted work is given to finish.
     * @param deploymentGeneration generation the drain is carried out under.
     * @return stage yielding whether everything finished inside the bound.
     */
    @Override
    public CompletionStage<Boolean> drain(Duration bound, long deploymentGeneration) {
        if (bound == null || bound.isZero() || bound.isNegative()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("a drain requires a positive bound"));
        }
        lock.lock();
        try {
            adoptGeneration(deploymentGeneration);
            admissionFenced = true;
            draining = true;
            drained = false;
        } finally {
            lock.unlock();
        }
        return CompletableFuture.supplyAsync(() -> {
            long deadline = System.nanoTime() + bound.toNanos();
            boolean finished;
            while (true) {
                finished = inFlight() == 0;
                if (finished || System.nanoTime() - deadline >= 0) {
                    break;
                }
                try {
                    Thread.sleep(DRAIN_POLL_INTERVAL.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    finished = inFlight() == 0;
                    break;
                }
            }
            lock.lock();
            try {
                draining = false;
                drained = finished;
            } finally {
                lock.unlock();
            }
            return finished;
        }, VIRTUAL_THREADS);
    }

    /**
     * Releases this deployment's own domain, sources and in-flight work, and nothing it shares.
     *
     * <p>Implemented as {@link #stop()} and deliberately not as {@link #shutdown()}: the port cannot
     * tell a {@code Stop} from an {@code Undeploy}, and only the composition root that owns the
     * registration knows whether this deployment can ever start again. Choosing the terminal release
     * here would strand what a source keeps across a restart the authority is entitled to ask for.</p>
     *
     * <p><b>This is the widest thing on the port, and it stops at this deployment's boundary.</b> The
     * engine and the actor system it hosts are shared with every sibling and are never reached from
     * here -- {@link ExecutionEngine#drain()} and {@link ExecutionEngine#close()} belong to the
     * process's own shutdown, which is the only caller entitled to end them (ADR 0038 D9).</p>
     *
     * @param deploymentGeneration generation at which this deployment's domain is released.
     * @return stage completing when this deployment holds nothing.
     */
    @Override
    public CompletionStage<Void> terminateDomain(long deploymentGeneration) {
        lock.lock();
        try {
            adoptGeneration(deploymentGeneration);
            admissionFenced = true;
            draining = false;
            drained = false;
        } finally {
            lock.unlock();
        }
        return stop().thenAccept(ignoredStatus -> { });
    }

    @Override
    public CompletionStage<Reading> observe() {
        lock.lock();
        try {
            DeploymentState current = status.state();
            ObservedKind kind = switch (current) {
                case COLD -> ObservedKind.COLD;
                case STARTING -> ObservedKind.STARTING;
                case STOPPING -> ObservedKind.STOPPING;
                case STOPPED -> ObservedKind.STOPPED;
                case FAILED -> ObservedKind.FAILED;
                // A held deployment keeps its activation and therefore keeps reporting READY through
                // DeploymentStatus. The distinction an authority needs is which kind of hold it is,
                // and that is the fence, not the state machine.
                case READY -> admissionFenced ? heldKind() : ObservedKind.READY;
                case DEGRADED -> admissionFenced ? heldKind() : ObservedKind.DEGRADED;
            };
            // Reported only while a domain is actually open: a stopped deployment activates nothing,
            // and naming a version there would be evidence of an activation that has been released.
            Long active = runner == null ? null : activatedVersion;
            return CompletableFuture.completedFuture(new Reading(kind, active, inFlight()));
        } finally {
            lock.unlock();
        }
    }

    /** Which kind of hold this deployment is under. Caller holds {@link #lock}. */
    private ObservedKind heldKind() {
        if (draining) {
            return ObservedKind.DRAINING;
        }
        return drained ? ObservedKind.DRAINED : ObservedKind.PAUSED;
    }

    /**
     * Admitted units that have not finished, counted from the ingress permits rather than from
     * {@link #admitted}.
     *
     * <p>The permit is what every admitted unit holds -- request/reply admissions take one through
     * {@link RequestReplyCoordinator} without ever appearing in {@link #admitted} -- so counting
     * permits is the only count that covers all of them. {@link #admitted} answers a different
     * question: which generation each arrival through {@link IngressView} was assigned.</p>
     */
    private long inFlight() {
        Semaphore permits = ingressPermits;
        return permits == null ? 0 : Math.max(0, ingressBufferCapacity - permits.availablePermits());
    }

    /**
     * How many admitted units the most recent {@link #barrier(long)} ended.
     *
     * <p>Package-private evidence, not surface: it exists so the half-open interval can be asserted as
     * an exact accounting -- every accepted arrival either completed or was ended, never neither and
     * never both -- instead of being inferred from the two counts that would have to agree by
     * accident.</p>
     *
     * @return the count the last barrier ended, or zero when no barrier has run.
     */
    long endedByLastBarrier() {
        lock.lock();
        try {
            return endedByLastBarrier;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adopts the generation an authority named and clears any barrier that governed the old one.
     *
     * <p>The clear is the point: a barrier ends work, and none of the operations that call this one
     * does. Leaving the previous barrier's generation standing would refuse an arrival admitted at the
     * new generation for a barrier that never applied to it. Caller holds {@link #lock}.</p>
     */
    private void adoptGeneration(long deploymentGeneration) {
        ingressGeneration = deploymentGeneration;
        barrierStanding = false;
    }

    /**
     * The generation this start runs at: the one an authority named, or the next local one.
     *
     * <p>The local counter is the fallback for an embedded process with no durable authority, and it
     * is honest about what it is -- it restarts at zero with the process and fences nothing across
     * one. A deployment an authority drives never reaches it.</p>
     */
    private long nextIngressGeneration() {
        lock.lock();
        try {
            Long adopted = pendingGeneration;
            pendingGeneration = null;
            ingressGeneration = adopted != null ? adopted : ingressGeneration + 1;
            // Set here rather than where the start settles, because startSources hands every source
            // its context -- and therefore its fence -- while this start is still running.
            activationGeneration = ingressGeneration;
            return ingressGeneration;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Undoes one admission: the generation assignment and the permit, in that order.
     *
     * <p>Taken under {@link #lock} so a barrier's snapshot sees the unit either still awaiting dispatch
     * or gone, never admitted with its dispatch state already cleared -- which the barrier would read
     * as a dispatched traversal and count as ended.</p>
     */
    private void releaseAdmission(UUID traversalId, Semaphore permits) {
        lock.lock();
        try {
            awaitingDispatch.remove(traversalId);
            admitted.remove(traversalId);
        } finally {
            lock.unlock();
        }
        permits.release();
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
                    identitySource, runnerShutdownStepBound, graphExecutionLimits);
            // Sources are discovered and started here -- while this graph's nodes are being spawned,
            // never earlier -- and only after the runner itself is built, so a source's start failure
            // rolls back a fully-formed runner rather than a half-built one.
            generation = nextIngressGeneration();
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
            // A start opens admission at the generation this attempt adopted, and no barrier governs
            // it: whatever a previous run held is gone with the domain that held it.
            this.admissionFenced = false;
            this.draining = false;
            this.drained = false;
            this.barrierStanding = false;
            this.admitted.clear();
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
            BehaviorRegistry.SourceRegistration sourceAuthority = behaviors.registerSourceAuthority(
                    context, packageId, id, node.id(), generation, security);
            InboundSource source = null;
            try {
                source = capableFactory.get().createSource(node, context);
                if (source == null) {
                    throw new IllegalStateException("Behavior '" + node.behavior()
                            + "' returned no inbound source for node '" + node.id() + "'");
                }
                sourceAuthority.activate();
                joinSourceStart(source.start(context));
                // From this point the source owns live resources. Record it before managed route
                // activation so an acquisition failure rolls back this source and retires any lease
                // it obtained before completing exceptionally, not only earlier siblings.
                started.add(new SourceHandle(node.id(), source, owner, sourceAuthority));
                if (source instanceof ManagedIngressSource ingressSource) {
                    IngressRouteAuthority authority = context.ingressRoutes().orElseThrow(() ->
                            new IllegalStateException("managed ingress is unavailable for source"));
                    joinSourceStart(ingressSource.activateManagedIngress(authority));
                }
            } catch (RuntimeException | Error failure) {
                sourceAuthority.close();
                if (source != null) stopSourceBounded(source::rollback);
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
            handle.sourceAuthority().close();
            stopSourceBounded(handle.source()::rollback);
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
            this.admitted.clear();
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
            handle.sourceAuthority().close();
            stopSourceBounded(release == SourceRelease.SHUTDOWN
                    ? handle.source()::shutdown
                    : handle.source()::stop);
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
            this.admitted.clear();
            this.draining = false;
            this.drained = false;
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

    private static void stopSourceBounded(java.util.function.Supplier<CompletionStage<Void>> stop) {
        try {
            joinSourceStopBounded(stop.get());
        } catch (RuntimeException ignored) {
            // A source is already being retired; one synchronous cleanup failure must not strand
            // sibling sources or replace the startup failure that selected this cleanup path.
        }
    }

    /** One node's inbound source, paired with the node id for diagnostics and individual stop/rollback. */
    private void retireIngress(SourceHandle handle) {
        if (managedIngress != null && handle.owner() != null) managedIngress.retire(handle.owner());
    }

    private record SourceHandle(String nodeId, InboundSource source, IngressRouteOwner owner,
                                BehaviorRegistry.SourceRegistration sourceAuthority) {
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
                    ? activationGeneration : ingressOwner.graphGeneration());
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
        boolean fenced;
        lock.lock();
        try {
            coordinator = requestReplyCoordinator;
            current = status.state();
            // The activation, not admission: a source view belongs to the run that created it, and a
            // pause or a barrier does not end that run. See activationGeneration.
            currentGeneration = activationGeneration;
            fenced = admissionFenced;
        } finally {
            lock.unlock();
        }
        if (requiredGeneration != null && requiredGeneration.longValue() != currentGeneration) {
            return new RequestReplyGate(null, RequestReplyRefusal.ADMISSION_CLOSED);
        }
        if (current == DeploymentState.STOPPING || current == DeploymentState.STOPPED) {
            return new RequestReplyGate(null, RequestReplyRefusal.ADMISSION_CLOSED);
        }
        // A lifecycle authority holding this deployment closes every admitting surface it has, not
        // only the trusted ingress: a pause that still admitted request/reply would be a pause an
        // operator could not rely on.
        if (fenced) {
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
        // Then the manifest, and only then the acceptance -- the same ordering, for the same reason,
        // as the submission path. A deployment-hosted traversal is an accepted execution like any
        // other, and one accepted without a manifest would be refused by every recovery path that
        // verifies one.
        recordExecutionManifest(key);
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
                                executionContextDeploymentId, traversalId.toString(), security.requestId()))
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
     * Pins what this traversal's process instance was resolved against.
     *
     * <p>Reached only from {@link #openTraversalRecorder}, which returns before this when no execution
     * store is composed, so a deployment carrying a manifest service and no execution store records
     * nothing -- correctly, because there is no acceptance to protect.</p>
     *
     * <p>Takes the {@code key} rather than a {@link SecurityContext}: the tenant is already inside
     * the key its caller built, and a second parameter carrying the same tenant would be a second
     * place for the two to disagree.</p>
     *
     * <p>{@link ai.ravenroot.api.application.ExecutionPolicy#STANDARD} is what is pinned because it
     * is what a deployment-hosted traversal runs under: this deployment's runner is built without a
     * policy parameter and takes that default. Recording the policy the traversal actually runs
     * under, rather than a placeholder, is what lets a later recovery compare like with like.</p>
     */
    private void recordExecutionManifest(ai.ravenroot.api.persistence.ExecutionKey key) {
        if (executionManifests == null) {
            return;
        }
        var contentId = new ai.ravenroot.api.persistence.GraphContentId(graphVersion);
        executionManifests.pin(key, contentId,
                ai.ravenroot.api.persistence.GraphDefinitionIdentity.forSubmission(contentId),
                ai.ravenroot.api.application.ExecutionPolicy.STANDARD,
                manager.definition().nodes().stream()
                        .map(ai.ravenroot.core.graph.GraphNode::behavior)
                        .filter(java.util.Objects::nonNull).toList());
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
        var executionOperationalPolicy = executionManifests == null ? null
                : executionManifests.resolvePolicyForNodes(key,
                        ai.ravenroot.api.application.ExecutionPolicy.STANDARD,
                        manager.definition().nodes());
        AutoCloseable budgetBinding = null;
        AutoCloseable humanTaskBinding = null;
        try {
            budgetBinding = agentBudgets == null || recorder == null
                    ? null : agentBudgets.bindLive(key, recorder);
            humanTaskBinding = humanTasks == null || recorder == null
                    ? null : humanTasks.bindLive(key, recorder, activeRunner::continuationBudget);
            CompletionStage<GraphExecutionResult> execution = activeRunner.execute(security,
                    processInstanceId, traversalId, payload, graphVersion, executionContextDeploymentId,
                    traversalId.toString(), recorder, executionOperationalPolicy,
                    executionOperationalPolicy == null ? graphExecutionLimits
                            : ai.ravenroot.core.manifest.ExecutionManifestResolver.graphExecutionLimits(
                                    executionOperationalPolicy));
            AutoCloseable finalBudgetBinding = budgetBinding;
            AutoCloseable finalHumanTaskBinding = humanTaskBinding;
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
                    closeQuietly(finalHumanTaskBinding);
                    closeQuietly(recorder);
                }
            });
        } catch (RuntimeException | Error failure) {
            closeQuietly(budgetBinding);
            closeQuietly(humanTaskBinding);
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
            GraphRunner activeRunner;
            Semaphore permits;
            UUID processInstanceId;
            UUID traversalId;
            long generation;
            lock.lock();
            try {
                DeploymentState currentState = status.state();
                activeRunner = runner;
                permits = ingressPermits;
                if (currentState == DeploymentState.STOPPING || currentState == DeploymentState.STOPPED) {
                    return IngressDisposition.REJECTED_ADMISSION_CLOSED;
                }
                // A lifecycle authority that closed admission is refused here rather than through the
                // state machine, because Pause -- and the first half of Drain, Stop and Undeploy --
                // deliberately retains the activation and therefore stays READY. Reading the state
                // alone would admit into a deployment an operator is holding.
                if (admissionFenced) {
                    return IngressDisposition.REJECTED_ADMISSION_CLOSED;
                }
                // DEGRADED admits too: it means "serving with reduced capability... work is still being
                // accepted". A source can enter
                // DEGRADED through reportDegraded, so rejecting ingress here would contradict that state.
                boolean admitting = currentState == DeploymentState.READY
                        || currentState == DeploymentState.DEGRADED;
                if (!admitting || activeRunner == null || permits == null) {
                    return IngressDisposition.REJECTED_NOT_READY;
                }
                if (!permits.tryAcquire()) {
                    return IngressDisposition.REJECTED_BUFFER_FULL;
                }
                processInstanceId = identitySource.nextProcessInstanceId();
                traversalId = identitySource.nextTraversalId();
                // The admission decision and the generation it is assigned are one step, taken under
                // the lock a barrier also takes. Reading the generation afterwards would leave an
                // instant in which an arrival is admitted and unassigned, and an arrival a barrier
                // neither ended nor admitted is exactly the one ADR 0038 D6 forbids.
                generation = ingressGeneration;
                admitted.put(traversalId, generation);
                awaitingDispatch.add(traversalId);
            } finally {
                lock.unlock();
            }
            ExecutionRecorder recorder;
            try {
                recorder = openTraversalRecorder(security, processInstanceId, traversalId);
            } catch (ExecutionInstanceBusyException busy) {
                // Fail closed. See IngressDisposition.REJECTED_INSTANCE_BUSY for why this is
                // unreachable today and must not be deleted as dead code.
                releaseAdmission(traversalId, permits);
                return IngressDisposition.REJECTED_INSTANCE_BUSY;
            } catch (RuntimeException | Error recordFailure) {
                releaseAdmission(traversalId, permits);
                throw recordFailure;
            }
            try {
                // ADR 0021 D5. deploymentId is this deployment's own identity;
                // workloadId is the traversal's -- the "whole item" ADR 0021 D3's sharding key places
                // as a unit, which in the current scope (no cluster, no multi-attempt redelivery) is exactly
                // what one accepted ingress event's traversal already is. Both are fixed for this one
                // execute() call, so every event it produces carries the same pair.
                //
                // Dispatched under the lock, and only if no barrier closed this arrival while it was
                // awaiting dispatch: the durable commit above runs outside the lock because it is I/O,
                // and a barrier that ran during it took this arrival out of awaitingDispatch. Handing
                // the runner a traversal afterwards would start work on the closing side of a barrier
                // that had finished ending everything it could see -- even if a later pause and resume
                // have since stood that barrier down, which is why this reads the barrier's own mark
                // rather than whether a barrier still governs.
                //
                // The cost was weighed rather than overlooked: this widens the critical section to
                // cover the dispatch. Everything inside it is a map write and one message to an
                // already-spawned actor -- execute() never waits for the traversal, which is what
                // makes offer() return a disposition rather than a result -- so the section stays
                // bounded by work this thread was going to do anyway. The alternative, dispatching
                // outside and letting the barrier re-sweep for units that appeared behind it, trades a
                // short lock for a convergence loop with no natural end.
                lock.lock();
                try {
                    if (!awaitingDispatch.remove(traversalId)) {
                        closeQuietly(recorder);
                        releaseAdmission(traversalId, permits);
                        return IngressDisposition.REJECTED_ADMISSION_CLOSED;
                    }
                    executeHosted(activeRunner, security, processInstanceId, traversalId, payload, recorder)
                            .whenComplete((ignoredResult, ignoredError) -> {
                                // Closed on TRAVERSAL completion, never on deployment stop: a
                                // deployment that runs for days would otherwise hold every instance it
                                // ever touched, and each held lease is an instance a recovery sweep is
                                // correctly forbidden from reclaiming.
                                admitted.remove(traversalId);
                                permits.release();
                            });
                } finally {
                    lock.unlock();
                }
            } catch (RuntimeException | Error dispatchFailure) {
                releaseAdmission(traversalId, permits);
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
            GraphRunner activeRunner;
            Semaphore permits;
            long generation;
            lock.lock();
            try {
                DeploymentState currentState = status.state();
                activeRunner = runner;
                permits = ingressPermits;
                if (currentState == DeploymentState.STOPPING || currentState == DeploymentState.STOPPED) {
                    return new ai.ravenroot.api.deployment.IngressReceipt.Refused("admission closed");
                }
                // Same fence, same reason, same instant as the volatile path: a held deployment is
                // still READY, so its state cannot be what decides this.
                if (admissionFenced) {
                    return new ai.ravenroot.api.deployment.IngressReceipt.Refused("admission closed");
                }
                boolean admitting = currentState == DeploymentState.READY
                        || currentState == DeploymentState.DEGRADED;
                if (!admitting || activeRunner == null || permits == null) {
                    return new ai.ravenroot.api.deployment.IngressReceipt.Refused("not ready");
                }
                if (!permits.tryAcquire()) {
                    return new ai.ravenroot.api.deployment.IngressReceipt.Refused("buffer full");
                }
                generation = ingressGeneration;
            } finally {
                lock.unlock();
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
                // Registered and dispatched under one lock, against the generation this arrival was
                // admitted at. The durable commit above is I/O and cannot be held under the lock, so a
                // barrier may have closed that generation while it ran; this is where that is decided,
                // by the exact equality ADR 0038 D6 requires. The durable record stands either way and
                // a redelivery of the same key is recognised as a Duplicate -- which is why refusing
                // here loses nothing the source cannot re-offer.
                lock.lock();
                try {
                    if (barrierStanding && generation != barrierGeneration) {
                        closeQuietly(recorder);
                        permits.release();
                        return new ai.ravenroot.api.deployment.IngressReceipt.Refused("admission closed");
                    }
                    admitted.put(traversalId, generation);
                    executeHosted(activeRunner, security, processInstanceId, traversalId, payload, recorder)
                            .whenComplete((ignoredResult, ignoredError) -> {
                                admitted.remove(traversalId);
                                permits.release();
                            });
                } finally {
                    lock.unlock();
                }
            } catch (RuntimeException | Error dispatchFailure) {
                releaseAdmission(traversalId, permits);
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
