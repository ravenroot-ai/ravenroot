package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ApplicationStatus;
import ai.ravenroot.api.application.DurableExecutionEvent;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.application.GraphSummary;
import ai.ravenroot.api.application.LiveExecution;
import ai.ravenroot.api.application.LocalDeploymentException;
import ai.ravenroot.api.application.LocalDeploymentState;
import ai.ravenroot.api.application.LocalDeploymentStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.application.RuntimeSnapshot;
import ai.ravenroot.api.application.SourceSessionException;
import ai.ravenroot.api.application.SourceSessionState;
import ai.ravenroot.api.application.SourceSessionStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.deployment.DeploymentAdmissionException;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentState;
import ai.ravenroot.api.deployment.DeploymentStatus;
import ai.ravenroot.api.deployment.GraphDeployment;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeRuntimeNatureProperty;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.ExecutionOrigin;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.programming.ArtifactRegistry;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.ArtifactTestResult;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class DefaultRavenrootApplication implements RavenrootApplication {
    private volatile ai.ravenroot.api.ingress.ManagedIngress managedIngress;
    /** Composition-root hook installed before deployment activation. */
    public void installManagedIngress(ai.ravenroot.api.ingress.ManagedIngress managedIngress) {
        if (!deployments.isEmpty()) throw new IllegalStateException("ingress must be installed before deployments");
        this.managedIngress = java.util.Objects.requireNonNull(managedIngress, "managedIngress");
    }

    /** Composition-root policy installed before the listener accepts graph work. */
    public void configureArtifactDualControl(boolean dualControl) {
        if (!activeExecutions.isEmpty() || !deployments.isEmpty()) {
            throw new IllegalStateException("artifact dual-control policy must be configured before graph work");
        }
        this.artifactDualControl = dualControl;
    }
    private final ExecutionEngine engine;
    private final ExecutionMonitor monitor;
    private final BehaviorRegistry behaviors;
    private final ArtifactRegistry artifacts;
    private final ProgramRuntime programRuntime;
    private volatile boolean artifactDualControl;
    private final ConcurrentHashMap<String, BuildLock> programBuildLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Thread> programBuildTasks = new ConcurrentHashMap<>();
    private final ExecutionIdentitySource identitySource;

    /**
     * Whether a graph naming a behavior the trusted catalog lacks may run (SEC-09).
     *
     * <p>Passed inward from a composition root rather than looked up here. Core still has no
     * configuration channel and still must not grow one for this: {@code ravenroot-server} reads
     * {@code RAVENROOT_UNKNOWN_BEHAVIOR} and hands the decision down, so where platform-wide
     * configuration lives stays an open question rather than one this field answers by accident.
     * Defaults to {@link UnknownBehaviorPolicy#passThrough()} for every constructor without an
     * explicit policy, so an embedder that never heard of the switch keeps exactly today's behaviour.</p>
     */
    private final UnknownBehaviorPolicy unknownBehaviors;
    /**
     * Optional engine-neutral execution store (PERS-02). Core holds only the port type; no adapter
     * type is referenced here and none may be. When absent, the application behaves exactly as
     * before — PERS-02 does not make core durable, it makes core depend on the port.
     */
    private final ExecutionStore executionStore;
    /**
     * The durable authority for the graph an accepted execution replays, or {@code null} when no
     * definition store is composed and acceptance keeps its earlier behaviour of pinning an
     * identifier whose bytes nothing retains.
     */
    private final ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore;

    /** Identifies this process to the store, so an operator reading leases() can tell who holds one. */
    private final String workerId = "ravenroot-" + java.util.UUID.randomUUID();

    /**
     * How long a traversal's lease runs before it must be renewed. Comfortably longer than a
     * renewal period, and short enough that a crashed worker's instances become recoverable promptly.
     */
    private final java.time.Duration executionLeaseTtl = java.time.Duration.ofSeconds(30);
    private final ConcurrentHashMap<UUID, ActiveExecution> activeExecutions = new ConcurrentHashMap<>();

    /**
     * Where a finished execution's result survives long enough to be read.
     *
     * <p>A separate map from {@link #activeExecutions} for a structural reason, not a stylistic one:
     * that map's entries are <em>removed</em> on completion, which is correct for it — it exists to
     * bound live runners and tear them down — but it means a lookup against it answers absent for a
     * completed execution and for a nonexistent one alike. Previously nothing else held the
     * result, so the engine computed a payload, visited-node set and defaulted-node set on every run
     * and discarded all three. This registry is what makes {@code POST /v1/executions}'s 202 useful
     * rather than terminal.</p>
     */
    private final ExecutionResultRegistry executionResults = new ExecutionResultRegistry();

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Long-lived deployments hosted alongside the one-shot submissions above (ADR 0021 D2).
     * A separate registry, deliberately: {@link #activeExecutions} is one ephemeral runner per
     * traversal, torn down on completion, while an entry here survives across many traversals until
     * its own {@link GraphDeployment#stop()}. Shaped for a durable backend (D6) -- an
     * in-process map keyed by the same {@link DeploymentId} a durable registry would use -- rather
     * than for anything Phase A specifically needs.
     */
    private final ConcurrentHashMap<DeploymentId, GraphDeployment> deployments = new ConcurrentHashMap<>();
    /**
     * The one tenant-scoped, process-local deployment registry, which source sessions are built on.
     *
     * <h2>Why there is exactly one of these</h2>
     * <p>Source sessions previously had their own {@code sourceSessions} map. Graphs without a SOURCE
     * need the same lifecycle, and adding a second map beside the first would have given the editor two
     * incompatible meanings of "this graph is running locally". The map was therefore widened rather
     * than duplicated: a source session <em>is</em> a local deployment
     * whose graph happens to have at least one effective SOURCE, and
     * {@link #startSourceSession}/{@link #sourceSession}/{@link #stopSourceSession} are now
     * projections of this registry rather than a parallel mechanism. The source-session wire shape is unchanged.
     *
     * <h2>Tenant isolation is the key, not a filter</h2>
     * <p>The tenant is half of {@link LocalDeploymentKey}, so an equal id in two tenants names two
     * siblings and no lookup can reach across. The engine-level {@link DeploymentId} is derived from
     * the same pair, so two tenants' identically named deployments never share an execution domain.
     *
     * <h2>What this is not</h2>
     * <p>Not {@code DeploymentRegistry}/{@code InMemoryDeploymentRegistry} (ADR 0023). Those model
     * durable CAS, leases, fencing and desired/observed reconciliation, none of which this process-local
     * lifecycle provides; they remain wired to nothing here. This local registry and that durable model
     * must not be treated as two equivalent lifecycles.
     */
    private final ConcurrentHashMap<LocalDeploymentKey, LocalDeploymentRecord> localDeployments =
            new ConcurrentHashMap<>();
    private final Object localDeploymentLock = new Object();
    /**
     * Serializes the admission decision against concurrent activations. A {@link ConcurrentHashMap}
     * alone makes membership atomic, not the count-then-admit decision the cap requires: two
     * activations racing the same free slot could each see "one below the cap" and both proceed,
     * which is exactly the boundary the deployment-admission contract requires to fail closed rather than admit both.
     */
    private final Object deploymentAdmissionLock = new Object();
    /**
     * The per-pod cap on active deployments. A plain {@code int}: this class
     * never reads an environment variable, and {@code ravenroot-server}'s
     * {@code DeploymentCapConfiguration} is the only place that does -- see that class's Javadoc for
     * why the split is deliberate. Every constructor without an explicit cap defaults this to {@code 0}, which is
     * itself a documented legitimate cap ({@code DeploymentCapConfiguration}: "a pod that must run
     * zero long-lived deployments... every activation is rejected") rather than a duplicate of
     * the process-boundary design's placeholder default: a caller that never configured a cap gets deployments
     * fail-closed rather than this class inventing a number the process-boundary design already owns.
     */
    private final int maxActiveDeployments;

    public DefaultRavenrootApplication(ExecutionEngine engine, ExecutionMonitor monitor) {
        this(engine, monitor, BehaviorEnvironment.safeDefaults());
    }

    public DefaultRavenrootApplication(ExecutionEngine engine, ExecutionMonitor monitor,
                                       ExecutionIdentitySource identitySource) {
        this(engine, monitor, BehaviorEnvironment.safeDefaults(), identitySource);
    }

    public DefaultRavenrootApplication(ExecutionEngine engine, ExecutionMonitor monitor,
                                       BehaviorEnvironment environment) {
        this(engine, monitor, environment, ExecutionIdentitySource.randomUuids());
    }

    public DefaultRavenrootApplication(ExecutionEngine engine, ExecutionMonitor monitor,
                                       BehaviorEnvironment environment, ExecutionIdentitySource identitySource) {
        this(engine, monitor, BehaviorRegistry.standard(environment), environment.artifacts(),
                environment.programRuntime(), identitySource);
    }

    public DefaultRavenrootApplication(ExecutionEngine engine, ExecutionMonitor monitor, BehaviorRegistry behaviors) {
        this(engine, monitor, behaviors, new InMemoryArtifactRegistry(), new DisabledProgramRuntime());
    }

    public DefaultRavenrootApplication(ExecutionEngine engine, ExecutionMonitor monitor, BehaviorRegistry behaviors,
                                       ArtifactRegistry artifacts, ProgramRuntime programRuntime) {
        this(engine, monitor, behaviors, artifacts, programRuntime, ExecutionIdentitySource.randomUuids());
    }

    public DefaultRavenrootApplication(ExecutionEngine engine, ExecutionMonitor monitor, BehaviorRegistry behaviors,
                                       ArtifactRegistry artifacts, ProgramRuntime programRuntime,
                                       ExecutionIdentitySource identitySource) {
        this(engine, monitor, behaviors, artifacts, programRuntime, identitySource, null);
    }

    /**
     * Composes the application against an {@link ExecutionStore}. Passing {@code null} keeps the
     * pre-PERS-02 behaviour.
     *
     * <p>The capability requirement is checked here rather than at first write: core relies on batch
     * atomicity, so a store that does not declare {@link StoreCapability#TRANSACTIONAL_BATCH} must
     * refuse to start rather than fail later on a half-applied write.</p>
     *
     * <p>There is no {@code tenantId} parameter. Until SEC-07 this class held one tenant for its whole
     * lifetime and stamped it on every {@link ExecutionKey}, which meant every tenant's instances
     * landed in one partition and the store's tenant scoping — real since PERS-10 — was being fed a
     * constant. Tenancy is a property of the <em>caller</em>, not of the application instance, so it
     * now arrives per submission on {@link SecurityContext}.</p>
     */
    public DefaultRavenrootApplication(ExecutionEngine engine, ExecutionMonitor monitor, BehaviorRegistry behaviors,
                                       ArtifactRegistry artifacts, ProgramRuntime programRuntime,
                                       ExecutionIdentitySource identitySource, ExecutionStore executionStore) {
        this(engine, monitor, behaviors, artifacts, programRuntime, identitySource, executionStore, 0);
    }

    /**
     * Composes the application with long-lived deployments enabled up to {@code maxActiveDeployments}.
     * The overload every other constructor delegates to, defaulting this to
     * {@code 0} -- see the field's own Javadoc for why that default is deliberate rather than borrowed.
     * {@code ravenroot-server} is expected to call this overload directly, passing
     * {@code DeploymentCapConfiguration.fromEnvironment(...).maxActiveDeployments()}.
     */
    public DefaultRavenrootApplication(ExecutionEngine engine, ExecutionMonitor monitor, BehaviorRegistry behaviors,
                                       ArtifactRegistry artifacts, ProgramRuntime programRuntime,
                                       ExecutionIdentitySource identitySource, ExecutionStore executionStore,
                                       int maxActiveDeployments) {
        this(engine, monitor, behaviors, artifacts, programRuntime, identitySource, executionStore,
                maxActiveDeployments, UnknownBehaviorPolicy.passThrough());
    }

    /**
     * The terminal constructor, and the only one that assigns state.
     *
     * <p>{@code ravenroot-server} calls this overload directly, passing
     * {@code UnknownBehaviorConfiguration.fromEnvironment(...).policy()}. Every overload above passes
     * {@link UnknownBehaviorPolicy#passThrough()}, which is the shipped default -- see that method for
     * why the default is a product capability rather than an oversight.</p>
     */
    public DefaultRavenrootApplication(ExecutionEngine engine, ExecutionMonitor monitor, BehaviorRegistry behaviors,
                                       ArtifactRegistry artifacts, ProgramRuntime programRuntime,
                                       ExecutionIdentitySource identitySource, ExecutionStore executionStore,
                                       int maxActiveDeployments, UnknownBehaviorPolicy unknownBehaviors) {
        this(engine, monitor, behaviors, artifacts, programRuntime, identitySource, executionStore,
                maxActiveDeployments, unknownBehaviors, null);
    }

    /**
     * The terminal constructor, and the only one that assigns state.
     *
     * <p>{@code graphDefinitionStore} is what makes an accepted execution recoverable. When one is
     * composed, the canonical document is committed to it <em>before</em> the acceptance write that
     * pins it, so acceptance can never succeed while the definition it names is absent. Passing
     * {@code null} keeps the earlier behaviour, in which a pin identifies a document nothing
     * retains; that mode is still supported and is still the only mode an embedded caller composing
     * no persistence at all can have.</p>
     *
     * @param engine execution engine the application dispatches through.
     * @param monitor monitor execution events are published to.
     * @param behaviors behavior registry node kinds are resolved against.
     * @param artifacts registry holding generated program artifacts.
     * @param programRuntime runtime that executes generated program artifacts.
     * @param identitySource source of process-instance identifiers.
     * @param executionStore durable execution state, or {@code null} for no durable acceptance.
     * @param maxActiveDeployments per-process cap on active long-lived deployments.
     * @param unknownBehaviors admission stance for a node kind no behavior claims.
     * @param graphDefinitionStore durable graph definitions, or {@code null} to retain no document.
     */
    public DefaultRavenrootApplication(ExecutionEngine engine, ExecutionMonitor monitor, BehaviorRegistry behaviors,
                                       ArtifactRegistry artifacts, ProgramRuntime programRuntime,
                                       ExecutionIdentitySource identitySource, ExecutionStore executionStore,
                                       int maxActiveDeployments, UnknownBehaviorPolicy unknownBehaviors,
                                       ai.ravenroot.api.persistence.GraphDefinitionStore graphDefinitionStore) {
        this.unknownBehaviors = java.util.Objects.requireNonNull(unknownBehaviors, "unknownBehaviors");
        this.graphDefinitionStore = graphDefinitionStore;
        this.engine = engine;
        this.monitor = monitor;
        this.behaviors = behaviors;
        this.artifacts = artifacts;
        this.programRuntime = programRuntime;
        this.identitySource = java.util.Objects.requireNonNull(identitySource, "identitySource");
        if (executionStore != null && !executionStore.supports(StoreCapability.TRANSACTIONAL_BATCH)) {
            throw new IllegalArgumentException(
                    "ExecutionStore must declare TRANSACTIONAL_BATCH; this one declares "
                            + executionStore.capabilities());
        }
        this.executionStore = executionStore;
        if (maxActiveDeployments < 0) {
            throw new IllegalArgumentException(
                    "maxActiveDeployments cannot be negative, got " + maxActiveDeployments);
        }
        this.maxActiveDeployments = maxActiveDeployments;
        artifacts.listIncompleteBuilds().forEach(this::scheduleProgramBuild);
    }

    /**
     * PLAT-02 requires {@code state()} to derive from {@link ExecutionEngine#state()} rather than
     * return the literal {@code "UP"} unconditionally. A constant value would be as decorative as
     * the static {@code /health} response it is meant to be more informative than. This method reports the
     * real {@link ai.ravenroot.api.execution.EngineState} name (RUNNING/DRAINING/CLOSED), which is
     * what makes an external draining signal possible at all: {@code RavenrootServer}'s {@code /ready}
     * route reads this same value through the undecorated, unauthenticated {@code application.status()}
     * call, never through {@code AuthorizedRavenrootApplication}, because a readiness probe must not
     * need credentials.
     */
    /**
     * The declared capabilities now include the unknown-behavior admission stance.
     *
     * <p>Added because the stance was previously observable only from the starting process's
     * environment — invisible to an operator looking at a running server, and invisible to the
     * authoring assistant answering "why did this graph run when I expected a refusal?". It is a
     * namespaced token ({@code unknown-behavior:refuse} / {@code unknown-behavior:pass-through}) rather
     * than a boolean, so the set stays a set of strings and a third mode would not need a new field.</p>
     */
    @Override
    public ApplicationStatus status() {
        var capabilities = engine.capabilities().stream()
                .map(Enum::name).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        capabilities.add(unknownBehaviors.capability());
        return new ApplicationStatus(engine.state().name(), engine.id(), capabilities);
    }

    @Override
    public RuntimeSnapshot runtimeSnapshot() {
        return monitor.snapshot();
    }

    @Override
    public List<ai.ravenroot.api.catalog.NodeTypeDescriptor> nodeTypes() {
        return behaviors.descriptors();
    }

    @Override
    public Map<String, ai.ravenroot.api.catalog.NodeCatalogSource> nodeTypeSources() {
        return behaviors.catalogSources();
    }

    @Override
    public List<GeneratedArtifact> programArtifacts() {
        return artifacts.list();
    }

    /** Reads straight off the composed runtime; this class does not maintain its own list. */
    @Override
    public List<ai.ravenroot.api.programming.ProgramLanguageDescriptor> supportedProgramLanguages() {
        return programRuntime.supportedLanguages();
    }

    @Override
    public GeneratedArtifact createProgramArtifact(String language, String source, Map<String, String> metadata) {
        if (language == null || language.isBlank()) throw new IllegalArgumentException("Language cannot be blank");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Program source cannot be blank");
        return artifacts.create(language, source, metadata);
    }

    @Override
    public java.util.concurrent.CompletionStage<GeneratedArtifact> validateProgramArtifact(String id) {
        var reservation = artifacts.reserve(id, ArtifactState.GENERATED, ArtifactState.VALIDATED);
        try {
            return programRuntime.validate(reservation.artifact()).thenApply(ignored -> artifacts.complete(reservation,
                    Map.of("runtime", programRuntime.id(), "result", "source parsed and contract verified")))
                    .whenComplete((ignored, error) -> {
                        if (error != null) artifacts.cancel(reservation);
                    });
        } catch (RuntimeException error) {
            artifacts.cancel(reservation);
            throw error;
        }
    }

    @Override
    public java.util.concurrent.CompletionStage<ArtifactTestResult> testProgramArtifact(String id, Object payload) {
        var reservation = artifacts.reserve(id, ArtifactState.VALIDATED, ArtifactState.TESTED);
        var request = new ProgramRequest(UUID.randomUUID(), "artifact-test", payload, Map.of("mode", "test"));
        try {
            return programRuntime.test(reservation.artifact(), request).thenApply(output -> {
                var tested = artifacts.complete(reservation,
                        Map.of("runtime", programRuntime.id(), "result", "worker execution succeeded"));
                return new ArtifactTestResult(tested, output);
            }).whenComplete((ignored, error) -> {
                if (error != null) artifacts.cancel(reservation);
            });
        } catch (RuntimeException error) {
            artifacts.cancel(reservation);
            throw error;
        }
    }

    @Override
    public GeneratedArtifact approveProgramArtifact(String id, Map<String, String> trustedEvidence) {
        String reason = trustedEvidence == null ? null : trustedEvidence.get("reason");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Approval reason cannot be blank");
        }
        artifactInState(id, ArtifactState.TESTED);
        return artifacts.transition(id, ArtifactState.TESTED, ArtifactState.APPROVED,
                Map.copyOf(trustedEvidence));
    }

    @Override
    public GeneratedArtifact activateProgramArtifact(String id, Map<String, String> trustedEvidence) {
        artifactInState(id, ArtifactState.APPROVED);
        GeneratedArtifact active = artifacts.transition(id, ArtifactState.APPROVED, ArtifactState.ACTIVE,
                merged(trustedEvidence, Map.of("runtime", programRuntime.id())));
        String fingerprint = active.metadata().getOrDefault("evidence.validated.compatibilityFingerprint",
                programRuntime.compatibilityFingerprint());
        String payloadDigest = active.metadata().getOrDefault("evidence.tested.payloadDigest", "");
        if (payloadDigest.isBlank()) return active;
        return artifacts.recordEvidence(active.id(), active.revision(), Map.of(
                "ravenroot.program.compatibilityFingerprint", fingerprint,
                "ravenroot.program.payloadDigest", payloadDigest));
    }

    @Override
    public GeneratedArtifact retireProgramArtifact(String id, Map<String, String> trustedEvidence) {
        GeneratedArtifact artifact = artifacts.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown artifact: " + id));
        if (artifact.state() == ArtifactState.RETIRED) {
            throw new IllegalStateException("Artifact is already retired");
        }
        return artifacts.transition(id, artifact.state(), ArtifactState.RETIRED,
                trustedEvidence == null ? Map.of() : Map.copyOf(trustedEvidence));
    }

    @Override
    public CompletionStage<ai.ravenroot.api.programming.ProgramBuildResult> buildProgramArtifact(
            String nodeId, String tenantId, String language, String source, Object testPayload,
            boolean dualControl, Map<String, String> trustedMetadata) {
        var result = new java.util.concurrent.CompletableFuture<ai.ravenroot.api.programming.ProgramBuildResult>();
        Thread.startVirtualThread(() -> {
            try {
                result.complete(buildProgramArtifactBlocking(nodeId, tenantId, language, source, testPayload,
                        dualControl, trustedMetadata));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    @Override
    public CompletionStage<ai.ravenroot.api.programming.ProgramBuildSnapshot> startProgramBuild(
            String tenantId, List<ai.ravenroot.api.programming.ProgramBuildRequest> programs,
            boolean dualControl, Map<String, String> trustedMetadata) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenant is required");
        if (programs == null || programs.isEmpty() || programs.size() > 256
                || programs.stream().map(ai.ravenroot.api.programming.ProgramBuildRequest::nodeId)
                .distinct().count() != programs.size()) {
            throw new IllegalArgumentException("one to 256 uniquely identified programs are required");
        }
        var plans = programs.stream().map(program -> {
            var payload = ai.ravenroot.api.payload.PayloadValue.fromJava(program.testPayload(),
                    ai.ravenroot.api.payload.PayloadLimits.DEFAULTS);
            String payloadJson = ai.ravenroot.api.payload.PayloadJson.write(payload);
            return new ai.ravenroot.api.programming.ProgramBuildNodePlan(
                    program.nodeId(), program.language(), program.source(),
                    ai.ravenroot.api.programming.ProgramArtifactIdentity.sha256(
                            program.language(), program.source()), payloadJson,
                    ai.ravenroot.api.programming.ProgramTestPayload.sha256(payload.toJava()));
        }).toList();
        String requestDigest = programBuildRequestDigest(plans, dualControl);
        var snapshot = artifacts.startOrFindBuild(tenantId, requestDigest, dualControl,
                trustedMetadata == null ? Map.of() : Map.copyOf(trustedMetadata), plans);
        scheduleProgramBuild(snapshot);
        return java.util.concurrent.CompletableFuture.completedFuture(snapshot);
    }

    @Override
    public java.util.Optional<ai.ravenroot.api.programming.ProgramBuildSnapshot> observeProgramBuild(
            String tenantId, String buildId) {
        var snapshot = artifacts.findBuild(tenantId, buildId);
        snapshot.filter(build -> !build.terminal()).ifPresent(this::scheduleProgramBuild);
        return snapshot;
    }

    private void scheduleProgramBuild(ai.ravenroot.api.programming.ProgramBuildSnapshot snapshot) {
        if (snapshot.terminal() || closed.get()) return;
        Thread task = Thread.ofVirtual().name("ravenroot-program-build-" + snapshot.id()).unstarted(() -> {
            try {
                runProgramBuild(snapshot.tenantId(), snapshot.id());
            } finally {
                programBuildTasks.remove(snapshot.id(), Thread.currentThread());
            }
        });
        if (programBuildTasks.putIfAbsent(snapshot.id(), task) == null) task.start();
    }

    private void runProgramBuild(String tenantId, String buildId) {
        var build = artifacts.findBuild(tenantId, buildId).orElse(null);
        if (build == null || build.terminal()) return;
        for (var original : build.nodes()) {
            if (Thread.currentThread().isInterrupted() || closed.get()) return;
            var current = currentBuildNode(tenantId, buildId, original.plan().nodeId());
            if (current.terminal()) continue;
            runProgramBuildNode(tenantId, buildId, current.plan().nodeId());
        }
    }

    private void runProgramBuildNode(String tenantId, String buildId, String nodeId) {
        var first = currentBuildNode(tenantId, buildId, nodeId);
        String key = tenantId + '\0' + first.plan().sourceDigest();
        BuildLock buildLock = programBuildLocks.compute(key, (ignored, existing) -> {
            BuildLock acquired = existing == null ? new BuildLock() : existing;
            acquired.references.incrementAndGet();
            return acquired;
        });
        try {
            synchronized (buildLock.monitor) {
                for (int transitions = 0; transitions < 8; transitions++) {
                    if (Thread.currentThread().isInterrupted() || closed.get()) return;
                    var node = currentBuildNode(tenantId, buildId, nodeId);
                    if (node.terminal() || approvalStillRequired(node)) return;
                    try {
                        runProgramBuildPhase(artifacts.findBuild(tenantId, buildId).orElseThrow(), node);
                    } catch (RuntimeException failure) {
                        var latest = currentBuildNode(tenantId, buildId, nodeId);
                        Throwable cause = failure instanceof java.util.concurrent.CompletionException
                                && failure.getCause() != null ? failure.getCause() : failure;
                        String diagnostic = cause instanceof ai.ravenroot.api.programming.ProgramSourceRejectedException rejected
                                ? rejected.diagnostic()
                                : cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
                        artifacts.recordBuildNode(tenantId, buildId, nodeId, latest.revision(), latest.artifactId(),
                                ai.ravenroot.api.programming.ProgramBuildPhase.FAILED, true, false,
                                latest.reused(), diagnostic, latest.smokeOutputJson());
                        return;
                    }
                }
                throw new IllegalStateException("program build exceeded its finite phase sequence");
            }
        } finally {
            programBuildLocks.computeIfPresent(key, (ignored, current) -> current != buildLock
                    ? current : current.references.decrementAndGet() == 0 ? null : current);
        }
    }

    private void runProgramBuildPhase(
            ai.ravenroot.api.programming.ProgramBuildSnapshot build,
            ai.ravenroot.api.programming.ProgramBuildNodeSnapshot node) {
        Object payload = ai.ravenroot.api.payload.PayloadJson.read(
                node.plan().payloadJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).toJava();
        String compatibility = programRuntime.compatibilityFingerprint();
        GeneratedArtifact artifact = node.artifactId().isBlank() ? null : artifacts.find(node.artifactId()).orElse(null);
        if (artifact == null) {
            artifact = artifacts.findByTenantAndDigest(build.tenantId(), node.plan().sourceDigest()).orElse(null);
        }
        switch (node.phase()) {
            case REGISTER -> {
                boolean reused = artifact != null;
                if (artifact == null) {
                    var metadata = new java.util.LinkedHashMap<>(build.trustedMetadata());
                    metadata.put(ai.ravenroot.api.application.AuthorizedRavenrootApplication.OWNER_TENANT_METADATA,
                            build.tenantId());
                    metadata.put("ravenroot.program.digestFormat",
                            Integer.toString(ai.ravenroot.api.programming.ProgramArtifactIdentity.FORMAT_VERSION));
                    artifact = artifacts.create(node.plan().language(), node.plan().source(), metadata);
                }
                if (artifact.state() == ArtifactState.RETIRED) {
                    advanceBuildNode(node, artifact.id(), ai.ravenroot.api.programming.ProgramBuildPhase.RETIRED,
                            true, false, reused, "retired content cannot be rebuilt or resurrected", "");
                    return;
                }
                if (artifact.state() == ArtifactState.ACTIVE) {
                    String recordedCompatibility = artifact.metadata()
                            .getOrDefault("ravenroot.program.compatibilityFingerprint", "");
                    String recordedPayload = artifact.metadata().getOrDefault("ravenroot.program.payloadDigest", "");
                    if (constantTimeText(recordedCompatibility, compatibility)
                            && constantTimeText(recordedPayload, node.plan().payloadDigest())) {
                        advanceBuildNode(node, artifact.id(), ai.ravenroot.api.programming.ProgramBuildPhase.READY,
                                true, true, true, "", "");
                    } else {
                        var next = constantTimeText(recordedCompatibility, compatibility)
                                ? ai.ravenroot.api.programming.ProgramBuildPhase.SMOKE_TEST
                                : ai.ravenroot.api.programming.ProgramBuildPhase.VALIDATE;
                        if (next == ai.ravenroot.api.programming.ProgramBuildPhase.VALIDATE) {
                            artifact = artifacts.recordEvidence(artifact.id(), artifact.revision(), Map.of(
                                    "ravenroot.program.compatibilityFingerprint",
                                    "ravenroot:requalification-required"));
                        }
                        advanceBuildNode(node, artifact.id(), next, false, false, true, "", "");
                    }
                    return;
                }
                var next = switch (artifact.state()) {
                    case GENERATED -> ai.ravenroot.api.programming.ProgramBuildPhase.VALIDATE;
                    case VALIDATED -> ai.ravenroot.api.programming.ProgramBuildPhase.SMOKE_TEST;
                    case TESTED -> ai.ravenroot.api.programming.ProgramBuildPhase.APPROVE_BY_POLICY;
                    case APPROVED -> ai.ravenroot.api.programming.ProgramBuildPhase.ACTIVATE;
                    case ACTIVE -> ai.ravenroot.api.programming.ProgramBuildPhase.READY;
                    case RETIRED -> ai.ravenroot.api.programming.ProgramBuildPhase.RETIRED;
                };
                advanceBuildNode(node, artifact.id(), next, next == ai.ravenroot.api.programming.ProgramBuildPhase.READY,
                        next == ai.ravenroot.api.programming.ProgramBuildPhase.READY, reused, "", "");
            }
            case VALIDATE -> {
                artifact = requiredBuildArtifact(artifact, node);
                if (artifact.state() == ArtifactState.GENERATED) {
                    artifact = validateBuild(artifact, compatibility);
                } else if (artifact.state() == ArtifactState.ACTIVE) {
                    try {
                        programRuntime.validate(qualificationView(artifact, ArtifactState.GENERATED))
                                .toCompletableFuture().get();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.util.concurrent.CompletionException(interrupted);
                    } catch (java.util.concurrent.ExecutionException failure) {
                        throw new java.util.concurrent.CompletionException(failure.getCause());
                    }
                } else if (artifact.state().ordinal() < ArtifactState.VALIDATED.ordinal()) {
                    throw new IllegalStateException("artifact cannot resume validation from " + artifact.state());
                }
                advanceBuildNode(node, artifact.id(), ai.ravenroot.api.programming.ProgramBuildPhase.SMOKE_TEST,
                        false, false, node.reused(), "", node.smokeOutputJson());
            }
            case SMOKE_TEST -> {
                artifact = requiredBuildArtifact(artifact, node);
                String outputJson = node.smokeOutputJson();
                if (artifact.state() == ArtifactState.VALIDATED) {
                    var tested = smokeBuild(artifact, payload, node.plan().payloadDigest());
                    artifact = tested.artifact();
                    outputJson = boundedOutput(tested.output());
                } else if (artifact.state() == ArtifactState.ACTIVE) {
                    var request = new ai.ravenroot.api.programming.ProgramRequest(UUID.randomUUID(),
                            "artifact-requalification-smoke", payload, Map.of("mode", "smoke-test"));
                    Object output;
                    try {
                        output = programRuntime.test(qualificationView(artifact, ArtifactState.VALIDATED), request)
                                .toCompletableFuture().get();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.util.concurrent.CompletionException(interrupted);
                    } catch (java.util.concurrent.ExecutionException failure) {
                        throw new java.util.concurrent.CompletionException(failure.getCause());
                    }
                    outputJson = boundedOutput(output);
                    artifact = artifacts.recordEvidence(artifact.id(), artifact.revision(), Map.of(
                            "ravenroot.program.compatibilityFingerprint", compatibility,
                            "ravenroot.program.payloadDigest", node.plan().payloadDigest(),
                            "evidence.tested.payloadDigest", node.plan().payloadDigest(),
                            "evidence.tested.output", outputJson,
                            "evidence.tested.runtime", programRuntime.id(),
                            "evidence.tested.recordedAt", Instant.now().toString()));
                } else if (artifact.state() == ArtifactState.TESTED && outputJson.isBlank()) {
                    outputJson = artifact.metadata().getOrDefault("evidence.tested.output", "null");
                }
                advanceBuildNode(node, artifact.id(),
                        ai.ravenroot.api.programming.ProgramBuildPhase.APPROVE_BY_POLICY,
                        false, false, node.reused(), "", outputJson);
            }
            case APPROVE_BY_POLICY -> {
                artifact = requiredBuildArtifact(artifact, node);
                if (artifact.state() == ArtifactState.TESTED && build.dualControl()) {
                    advanceBuildNode(node, artifact.id(),
                            ai.ravenroot.api.programming.ProgramBuildPhase.APPROVAL_REQUIRED,
                            false, false, node.reused(), "independent graph-level approval is required",
                            node.smokeOutputJson());
                    return;
                }
                if (artifact.state() == ArtifactState.TESTED) {
                    artifact = artifacts.transition(artifact.id(), ArtifactState.TESTED, ArtifactState.APPROVED,
                            Map.of("reason", "automatic policy approval", "approver", "ravenroot:policy"));
                }
                advanceBuildNode(node, artifact.id(), ai.ravenroot.api.programming.ProgramBuildPhase.ACTIVATE,
                        false, false, node.reused(), "", node.smokeOutputJson());
            }
            case ACTIVATE -> {
                artifact = requiredBuildArtifact(artifact, node);
                if (artifact.state() == ArtifactState.APPROVED) {
                    artifact = activateProgramArtifact(artifact.id(),
                            Map.of("activatedBy", "ravenroot:policy", "reason", "build pipeline"));
                }
                if (artifact.state() != ArtifactState.ACTIVE) {
                    throw new IllegalStateException("artifact cannot activate from " + artifact.state());
                }
                advanceBuildNode(node, artifact.id(), ai.ravenroot.api.programming.ProgramBuildPhase.READY,
                        true, true, node.reused(), "", node.smokeOutputJson());
            }
            case APPROVAL_REQUIRED -> {
                artifact = requiredBuildArtifact(artifact, node);
                if (artifact.state() == ArtifactState.APPROVED || artifact.state() == ArtifactState.ACTIVE) {
                    advanceBuildNode(node, artifact.id(), ai.ravenroot.api.programming.ProgramBuildPhase.ACTIVATE,
                            false, false, node.reused(), "", node.smokeOutputJson());
                }
            }
            case READY, FAILED, RETIRED -> { }
        }
    }

    private boolean approvalStillRequired(ai.ravenroot.api.programming.ProgramBuildNodeSnapshot node) {
        if (node.phase() != ai.ravenroot.api.programming.ProgramBuildPhase.APPROVAL_REQUIRED) return false;
        return node.artifactId().isBlank() || artifacts.find(node.artifactId())
                .map(artifact -> artifact.state() == ArtifactState.TESTED).orElse(true);
    }

    @Override
    public void beginProgramBuildActivation(String tenantId, String artifactId) {
        for (var build : artifacts.listIncompleteBuilds()) {
            if (!build.tenantId().equals(tenantId)) continue;
            for (var node : build.nodes()) {
                if (node.artifactId().equals(artifactId)
                        && node.phase() == ai.ravenroot.api.programming.ProgramBuildPhase.APPROVAL_REQUIRED) {
                    advanceBuildNode(node, artifactId, ai.ravenroot.api.programming.ProgramBuildPhase.ACTIVATE,
                            false, false, node.reused(), "", node.smokeOutputJson());
                }
            }
        }
    }

    @Override
    public void completeProgramBuildActivation(String tenantId, String artifactId) {
        for (var build : artifacts.listIncompleteBuilds()) {
            if (!build.tenantId().equals(tenantId)) continue;
            for (var node : build.nodes()) {
                if (node.artifactId().equals(artifactId)
                        && node.phase() == ai.ravenroot.api.programming.ProgramBuildPhase.ACTIVATE) {
                    advanceBuildNode(node, artifactId, ai.ravenroot.api.programming.ProgramBuildPhase.READY,
                            true, true, node.reused(), "", node.smokeOutputJson());
                }
            }
        }
    }

    private ai.ravenroot.api.programming.ProgramBuildNodeSnapshot currentBuildNode(
            String tenantId, String buildId, String nodeId) {
        return artifacts.findBuild(tenantId, buildId).orElseThrow().nodes().stream()
                .filter(node -> node.plan().nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private void advanceBuildNode(
            ai.ravenroot.api.programming.ProgramBuildNodeSnapshot node, String artifactId,
            ai.ravenroot.api.programming.ProgramBuildPhase phase, boolean terminal, boolean ready,
            boolean reused, String diagnostic, String smokeOutputJson) {
        artifacts.recordBuildNode(node.tenantId(), node.buildId(), node.plan().nodeId(), node.revision(),
                artifactId, phase, terminal, ready, reused, diagnostic, smokeOutputJson);
    }

    private static GeneratedArtifact requiredBuildArtifact(
            GeneratedArtifact artifact, ai.ravenroot.api.programming.ProgramBuildNodeSnapshot node) {
        if (artifact == null) throw new IllegalStateException("build artifact binding is missing for " + node.plan().nodeId());
        return artifact;
    }

    private static String programBuildRequestDigest(
            List<ai.ravenroot.api.programming.ProgramBuildNodePlan> plans, boolean dualControl) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("ravenroot-program-build\0v1\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) (dualControl ? 1 : 0));
            for (var plan : plans) {
                digestField(digest, plan.nodeId());
                digestField(digest, plan.sourceDigest());
                digestField(digest, plan.payloadDigest());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void digestField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private ai.ravenroot.api.programming.ProgramBuildResult buildProgramArtifactBlocking(
            String nodeId, String tenantId, String language, String source, Object testPayload,
            boolean dualControl, Map<String, String> trustedMetadata) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenant is required");
        String sourceDigest = ai.ravenroot.api.programming.ProgramArtifactIdentity.sha256(language, source);
        Object boundedPayload = ai.ravenroot.api.payload.PayloadValue.fromJava(testPayload,
                ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).toJava();
        String payloadDigest = ai.ravenroot.api.programming.ProgramTestPayload.sha256(boundedPayload);
        String compatibility = programRuntime.compatibilityFingerprint();
        String key = tenantId + '\0' + sourceDigest;
        BuildLock buildLock = programBuildLocks.compute(key, (ignored, existing) -> {
            BuildLock acquired = existing == null ? new BuildLock() : existing;
            acquired.references.incrementAndGet();
            return acquired;
        });
        try {
            synchronized (buildLock.monitor) {
                try {
                GeneratedArtifact artifact = artifacts.findByTenantAndDigest(tenantId, sourceDigest).orElse(null);
                boolean reused = artifact != null;
                if (artifact == null) {
                    var metadata = new java.util.LinkedHashMap<String, String>();
                    if (trustedMetadata != null) metadata.putAll(trustedMetadata);
                    metadata.put(ai.ravenroot.api.application.AuthorizedRavenrootApplication.OWNER_TENANT_METADATA,
                            tenantId);
                    metadata.put("ravenroot.program.digestFormat",
                            Integer.toString(ai.ravenroot.api.programming.ProgramArtifactIdentity.FORMAT_VERSION));
                    artifact = artifacts.create(language, source, metadata);
                }
                if (artifact.state() == ArtifactState.RETIRED) {
                    return buildResult(nodeId, artifact, sourceDigest, payloadDigest,
                            ai.ravenroot.api.programming.ProgramBuildPhase.RETIRED, false, reused, null,
                            "retired content cannot be rebuilt or resurrected");
                }
                if (artifact.state() == ArtifactState.ACTIVE) {
                    String recordedCompatibility = artifact.metadata()
                            .getOrDefault("ravenroot.program.compatibilityFingerprint", "");
                    String recordedPayload = artifact.metadata().getOrDefault("ravenroot.program.payloadDigest", "");
                    if (constantTimeText(recordedCompatibility, compatibility)
                            && constantTimeText(recordedPayload, payloadDigest)) {
                        return buildResult(nodeId, artifact, sourceDigest, payloadDigest,
                                ai.ravenroot.api.programming.ProgramBuildPhase.READY, true, true, null, "");
                    }
                    boolean compatibilityChanged = !constantTimeText(recordedCompatibility, compatibility);
                    if (compatibilityChanged) {
                        artifact = artifacts.recordEvidence(artifact.id(), artifact.revision(), Map.of(
                                "ravenroot.program.compatibilityFingerprint",
                                "ravenroot:requalification-required"));
                    }
                    Object output = requalifyActive(artifact, boundedPayload, compatibilityChanged);
                    artifact = artifacts.recordEvidence(artifact.id(), artifact.revision(), Map.of(
                            "ravenroot.program.compatibilityFingerprint", compatibility,
                            "ravenroot.program.payloadDigest", payloadDigest,
                            "evidence.tested.payloadDigest", payloadDigest,
                            "evidence.tested.output", boundedOutput(output),
                            "evidence.tested.runtime", programRuntime.id(),
                            "evidence.tested.recordedAt", Instant.now().toString()));
                    return buildResult(nodeId, artifact, sourceDigest, payloadDigest,
                            ai.ravenroot.api.programming.ProgramBuildPhase.READY, true, true, output, "");
                }
                if (artifact.state() == ArtifactState.GENERATED) artifact = validateBuild(artifact, compatibility);
                Object output = null;
                if (artifact.state() == ArtifactState.VALIDATED) {
                    var tested = smokeBuild(artifact, boundedPayload, payloadDigest);
                    artifact = tested.artifact();
                    output = tested.output();
                }
                if (artifact.state() == ArtifactState.TESTED) {
                    if (dualControl) {
                        return buildResult(nodeId, artifact, sourceDigest, payloadDigest,
                                ai.ravenroot.api.programming.ProgramBuildPhase.APPROVAL_REQUIRED,
                                false, reused, output, "independent graph-level approval is required");
                    }
                    artifact = artifacts.transition(artifact.id(), ArtifactState.TESTED, ArtifactState.APPROVED,
                            Map.of("reason", "automatic policy approval", "approver", "ravenroot:policy"));
                }
                if (artifact.state() == ArtifactState.APPROVED) {
                    artifact = activateProgramArtifact(artifact.id(),
                            Map.of("activatedBy", "ravenroot:policy", "reason", "build pipeline"));
                }
                return buildResult(nodeId, artifact, sourceDigest, payloadDigest,
                        ai.ravenroot.api.programming.ProgramBuildPhase.READY,
                        artifact.state() == ArtifactState.ACTIVE, reused, output, "");
                } catch (RuntimeException failure) {
                    GeneratedArtifact current = artifacts.findByTenantAndDigest(tenantId, sourceDigest).orElseThrow();
                    Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                            ? failure.getCause() : failure;
                    String diagnostic = cause instanceof ai.ravenroot.api.programming.ProgramSourceRejectedException rejected
                            ? rejected.diagnostic() : cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
                    if (diagnostic.length() > 4096) diagnostic = diagnostic.substring(0, 4096);
                    return buildResult(nodeId, current, sourceDigest, payloadDigest,
                            ai.ravenroot.api.programming.ProgramBuildPhase.FAILED, false, false, null, diagnostic);
                }
            }
        } finally {
            programBuildLocks.computeIfPresent(key, (ignored, current) -> current != buildLock
                    ? current : current.references.decrementAndGet() == 0 ? null : current);
        }
    }

    private static final class BuildLock {
        private final Object monitor = new Object();
        private final java.util.concurrent.atomic.AtomicInteger references = new java.util.concurrent.atomic.AtomicInteger();
    }

    private GeneratedArtifact validateBuild(GeneratedArtifact artifact, String compatibility) {
        var reservation = artifacts.reserve(artifact.id(), ArtifactState.GENERATED, ArtifactState.VALIDATED);
        try {
            programRuntime.validate(reservation.artifact()).toCompletableFuture().join();
            return artifacts.complete(reservation, Map.of("runtime", programRuntime.id(),
                    "compatibilityFingerprint", compatibility,
                    "result", "source parsed and handler contract verified"));
        } catch (RuntimeException failure) {
            artifacts.cancel(reservation);
            throw failure;
        }
    }

    private ai.ravenroot.api.programming.ArtifactTestResult smokeBuild(
            GeneratedArtifact artifact, Object payload, String payloadDigest) {
        var reservation = artifacts.reserve(artifact.id(), ArtifactState.VALIDATED, ArtifactState.TESTED);
        var request = new ai.ravenroot.api.programming.ProgramRequest(UUID.randomUUID(), "artifact-build-smoke",
                payload, Map.of("mode", "smoke-test"));
        try {
            Object output = programRuntime.test(reservation.artifact(), request).toCompletableFuture().join();
            GeneratedArtifact tested = artifacts.complete(reservation, Map.of(
                    "runtime", programRuntime.id(), "payloadDigest", payloadDigest,
                    "output", boundedOutput(output), "result", "bounded sandbox smoke execution succeeded"));
            return new ai.ravenroot.api.programming.ArtifactTestResult(tested, output);
        } catch (RuntimeException failure) {
            artifacts.cancel(reservation);
            throw failure;
        }
    }

    private Object requalifyActive(GeneratedArtifact artifact, Object payload, boolean validateCompatibility) {
        if (validateCompatibility) {
            programRuntime.validate(qualificationView(artifact, ArtifactState.GENERATED)).toCompletableFuture().join();
        }
        var request = new ai.ravenroot.api.programming.ProgramRequest(UUID.randomUUID(), "artifact-requalification-smoke",
                payload, Map.of("mode", "smoke-test"));
        Object output = programRuntime.test(qualificationView(artifact, ArtifactState.VALIDATED), request)
                .toCompletableFuture().join();
        boundedOutput(output);
        return output;
    }

    private static GeneratedArtifact qualificationView(GeneratedArtifact artifact, ArtifactState state) {
        return new GeneratedArtifact(artifact.id(), artifact.language(), artifact.sha256(), artifact.source(), state,
                artifact.revision(), artifact.createdAt(), artifact.updatedAt(), artifact.metadata());
    }

    private static String boundedOutput(Object output) {
        return ai.ravenroot.api.payload.PayloadJson.write(ai.ravenroot.api.payload.PayloadValue.fromJava(output,
                ai.ravenroot.api.payload.PayloadLimits.DEFAULTS));
    }

    private static boolean constantTimeText(String left, String right) {
        return MessageDigest.isEqual((left == null ? "" : left).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                (right == null ? "" : right).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static ai.ravenroot.api.programming.ProgramBuildResult buildResult(
            String nodeId, GeneratedArtifact artifact, String sourceDigest, String payloadDigest,
            ai.ravenroot.api.programming.ProgramBuildPhase phase, boolean ready, boolean reused,
            Object output, String diagnostic) {
        return new ai.ravenroot.api.programming.ProgramBuildResult(nodeId, artifact, sourceDigest, payloadDigest,
                phase, ready, reused, output, diagnostic);
    }

    /**
     * Counting {@code START}/{@code END} vertices by raw property was the whole answer before
     * this operation, and it is not the same question as "is this a valid graph" -- an unknown node kind
     * or a surplus error terminal both left the counts looking exactly like a sound graph's (measured:
     * {@code startNodes=1, endNodes=1} either way, the plausible shape a caller reading only the
     * counts would take for a valid one). A dangling edge is not a counter-example here: it is refused
     * by {@link GraphManager#readGraphMl} itself, before this method ever gets a graph to count, so it
     * never produces counts for comparison.
     * {@link GraphManager#semanticViolations()} runs the same rule {@code GraphManager.definition()}
     * would, without raising it, so the counts below and the verdict come from one inspection rather
     * than two that could disagree.
     *
     * <p><strong>Cost:</strong> every call now also builds a complete
     * {@link ai.ravenroot.core.graph.GraphDefinition} to obtain {@code semanticViolations()}, on top of
     * the raw property counts it already computed -- work this method did not do before. Accepted
     * because this is an inspection endpoint reading a caller-supplied document, not a hot path,
     * but it is real work an authenticated caller can now trigger on every request that was not
     * there before semantic validation was included.</p>
     */
    @Override
    public GraphSummary inspectGraphMl(InputStream graphMl) {
        try (var manager = GraphManager.readGraphMl(graphMl)) {
            long starts = manager.query(g -> g.V().has(GraphManager.KIND, NodeKind.START.name()).count().next());
            long ends = manager.query(g -> g.V().has(GraphManager.KIND, NodeKind.END.name()).count().next());
            return new GraphSummary(Math.toIntExact(manager.nodeCount()), Math.toIntExact(manager.edgeCount()),
                    Math.toIntExact(starts), Math.toIntExact(ends), manager.semanticViolations());
        }
    }

    @Override
    public ExecutionSubmission startGraphMl(SecurityContext security, UUID executionId, InputStream graphMl,
                                            Object payload) {
        return startGraphMl(security, executionId, graphMl, payload, ExecutionPolicy.STANDARD);
    }

    @Override
    public ExecutionSubmission startGraphMl(SecurityContext security, UUID executionId, InputStream graphMl,
                                            Object payload, ExecutionPolicy policy) {
        if (closed.get()) {
            throw new IllegalStateException("Ravenroot application is closed");
        }
        // SEC-04 requires failing before any work starts if the reserved identifier is absent,
        // so this precedes the SEC-08 secure read that consumes the stream. SEC-07 adds the identity
        // to the same fail-first group: a submission that cannot say whose it is must not consume the
        // stream, reserve an identifier or reach the store.
        java.util.Objects.requireNonNull(security, "security");
        java.util.Objects.requireNonNull(executionId, "executionId");
        java.util.Objects.requireNonNull(policy, "policy");
        var document = GraphManager.readGraphMlDocument(graphMl);
        byte[] graphBytes = document.bytes();
        String graphVersion = sha256(graphBytes);
        var manager = document.manager();
        GraphRunner runner;
        try {
            runner = new GraphRunner(manager, engine, behaviors, monitor, identitySource,
                    unknownBehaviors, policy);
        } catch (RuntimeException error) {
            manager.close();
            throw error;
        }

        // SEC-04 reserves ownership of exactly this executionId before calling, and execution events
        // are published under the traversal id (ExecutionEvent.executionId() == traversalId). The
        // traversal id must therefore BE the caller-supplied identifier, or events would be
        // unattributable to the reserved owner and fail closed. The process-instance id is PERS-01
        // identity and stays application-generated.
        UUID processInstanceId = identitySource.nextProcessInstanceId();
        UUID traversalId = executionId;
        // Tenant, process-instance id, graph version and start time are captured here, at the
        // one place every path into activeExecutions already passes through, so GET
        // /v1/executions/live can list this traversal from the same map cancelTraversal mutates --
        // never a separate, potentially-stale projection of who owns what.
        var active = new ActiveExecution(manager, runner, security.tenantId(), processInstanceId,
                graphVersion, Instant.now());
        if (activeExecutions.putIfAbsent(traversalId, active) != null) {
            var collision = new IllegalStateException("Execution identifier is already active");
            try {
                active.close();
            } catch (RuntimeException | Error cleanupFailure) {
                collision.addSuppressed(cleanupFailure);
            }
            throw collision;
        }
        // Registered as RUNNING before the graph starts, so an id returned by a 202 is readable
        // for the whole life of the run rather than only after it ends.
        var resultKey = new ExecutionResultRegistry.Key(security.tenantId(), traversalId);
        executionResults.started(resultKey, processInstanceId);
        java.util.concurrent.CompletionStage<GraphExecutionResult> execution;
        try {
            // The definition is made durable BEFORE the acceptance that pins it. The ordering is not
            // interchangeable: a definition committed for an acceptance that then fails is an
            // unreferenced blob that retention reclaims, while an acceptance committed for a
            // definition that was never written is an execution that can never be recovered. Only
            // one of the two orderings can reach the second state.
            recordGraphDefinition(security, graphBytes);
            // Recorded before the graph starts so a rejected write cannot leave an unrecorded
            // execution running; the surrounding catch already owns cleanup.
            long revision = recordAcceptedExecution(security, processInstanceId, traversalId,
                    manager.start().id(), graphVersion);
            // The lease is taken *after* the instance exists — a lease on a nonexistent
            // instance is not stale, it is impossible — and *before* any invocation or attempt row
            // exists, so there is no window in which a recovery sweep could see claimable work on an
            // instance this engine is about to execute.
            ExecutionRecorder recorder = openRecorder(security, processInstanceId, revision);
            execution = java.util.Objects.requireNonNull(
                    runner.execute(security, processInstanceId, traversalId, payload, graphVersion,
                            null, null, recorder),
                    "execution result");
            execution.whenComplete((result, error) -> {
                // This is the seam where the result used to be dropped. `result` was already
                // in scope and simply unused -- the engine had computed the payload, the visited
                // nodes and the defaulted nodes, and the lambda ignored all three. Capturing it
                // first, before any teardown, so a cleanup failure below cannot cost the caller the
                // answer it is about to ask for.
                if (error != null || result == null) {
                    executionResults.failed(resultKey, processInstanceId);
                } else {
                    executionResults.completed(resultKey, result);
                }
                if (recorder != null) {
                    // Orderly shutdown of this traversal's lease: hands the instance back at once
                    // rather than leaving it locked for a whole TTL. Best-effort, because a crash
                    // does neither and must reach the same state by expiry (ADR 0010 section 13.1).
                    recorder.close();
                }
                activeExecutions.remove(traversalId, active);
                // Completion normally runs on the actor dispatcher. Node teardown waits for actor
                // acknowledgements and must therefore never block that dispatcher.
                Thread.startVirtualThread(active::close);
            });
        } catch (RuntimeException | Error startupFailure) {
            activeExecutions.remove(traversalId, active);
            // A submission that never started is recorded FAILED rather than erased. The caller
            // is told the start failed by this throw, but a second caller holding the same id -- a
            // retry, an operator, the UI -- must not be told the id never existed.
            executionResults.failed(resultKey, processInstanceId);
            try {
                active.close();
            } catch (RuntimeException | Error cleanupFailure) {
                startupFailure.addSuppressed(cleanupFailure);
            }
            throw startupFailure;
        }
        return new ExecutionSubmission(processInstanceId, traversalId, graphVersion);
    }

    /**
     * API-02. Atomic removal is what makes this safe under a concurrent natural completion:
     * {@code startGraphMl}'s {@code whenComplete} callback removes the same entry via the conditional
     * {@code activeExecutions.remove(traversalId, active)} -- only one of the two removals can ever
     * observe a non-null/matching value, so a cancel racing a traversal's own completion never double
     * -closes it and never reports a stop it did not actually win.
     *
     * <p>{@code active.close()} runs on a fresh virtual thread rather than the calling thread, exactly
     * like the natural-completion path a few lines below in {@link #startGraphMl} -- this may be an HTTP
     * or CLI request thread, not the actor dispatcher, and {@code GraphRunner.close()}'s stop-then-cancel
     * escalation is bounded by up to {@code GraphRunner.DEFAULT_SHUTDOWN_BOUND} (10s), which a control
     * endpoint must not block on to report its result: the atomic map removal above is already the
     * moment cancellation was accepted, and that is what the caller's result reports.</p>
     *
     * <p>The same rule applies one step further in. A paused
     * traversal has a hop waiting on a gate, and {@code GraphRunner} used to complete that gate on
     * this thread, so a dependent registered before the completion ran here: on this path the
     * failure propagation, and on {@link #resumeTraversal}'s the next hop's prologue with its journal
     * write under the recorder's lease. Both now run on a virtual thread of the gate's own. What the
     * results report is unchanged, because what decides them stays here: the removal above, and
     * {@code GraphRunner.cancelTraversal}'s publication of the refusal, both of which complete before
     * this method returns.</p>
     */
    @Override
    public boolean cancelTraversal(UUID traversalId) {
        java.util.Objects.requireNonNull(traversalId, "traversalId");
        ActiveExecution active = activeExecutions.remove(traversalId);
        if (active == null) {
            return false;
        }
        // The cooperative refusal is set HERE, on the calling thread, before the teardown below
        // is handed to a virtual thread.
        //
        // What that placement buys, stated precisely. It is NOT that the alternatives leave a window in
        // which hops keep being dispatched. Probed on the three-node self-loop under
        // TEST_PASSTHROUGH, three runs each, counting the hops that landed after cancelTraversal had
        // returned, with the loop turning at 540-700 hops/s. These are one machine's numbers -- the
        // shape reproduced on a second machine, the absolutes did not, differing on two of the three
        // rows -- so read the rows against each other and never as constants:
        //
        //   refusal on this line, as here ......................  0, 1, 1   (0, 0, 0 elsewhere)
        //   refusal moved below startVirtualThread .............  1, 1, 1   (1, 1, 1 elsewhere)
        //   refusal removed from this method, left to close() ..  1, 1, 1   (1, 2, 1 elsewhere)
        //
        // Every arrangement leaves at most one or two hops -- the one already inside engine.send when
        // the refusal landed, which is exactly the concession cancel has always declared. At ~600
        // hops/s a real window would show tens of events. There is no window to find.
        //
        // What the placement does buy is that `true` is a statement rather than a race. When this
        // method returns, the refusal is already published, so no hop that has not already started
        // can start -- by construction rather than by scheduling. Left to close() alone, that becomes
        // true only once a virtual thread this method does not schedule gets to run; the probe says
        // that is quick on an idle machine, and an idle machine is not a guarantee. So this line is
        // kept for what can be read off it, never for a difference anyone can time -- recorded here
        // so it is not deleted later on the correct observation that removing it changes no
        // measurement.
        //
        // That guarantee used to rest on a precondition this method does not check, and inside
        // the startup window the precondition was false. GraphRunner.cancelTraversal tested its
        // coordinator map before publishing anything; registration happens inside runner.execute, and
        // the line above puts the entry into activeExecutions BEFORE that -- with a durable
        // ProcessCreated/ProcessTransitioned pair and a recorder lease in between. Inside that window
        // the traversal is already listed by liveExecutions and therefore cancellable by someone who
        // did not submit it, the runner answered false without publishing a refusal, and this method
        // answered true anyway: listed live, cancel answered CANCELLED, and the graph then ran a real
        // node effect.
        //
        // The refusal is now published against the traversal id rather than against membership of
        // that map, so the window has nothing left to expose: whichever side of runner.execute this
        // call lands on, the mark is set before the hop that reads it. CancelInStartupWindowTest
        // holds the store's first write open and asserts both halves of the old measurement at once
        // -- the answer is still true, and the effect count is zero.
        //
        // The runner's own answer is deliberately not this method's answer. It reports whether THIS
        // call published the refusal -- a
        // question about the runner's own idempotency -- while whether there was anything to stop is
        // decided by the atomic removal above, which is the bookkeeping liveExecutions reads from.
        // Returning the runner's boolean instead would be incorrect: it would
        // answer false for a traversal that is genuinely about to start, trading a false success for
        // a false failure. CancelInStartupWindowTest reddens on that trade as well as on the defect.
        //
        // The teardown alone was not a stop. It stops the actors this runner is responsible for at
        // the instant it snapshots them, and a traversal that keeps moving spawns its next worker
        // after the snapshot; a graph that loops therefore survived a cancel that reported success.
        // See GraphRunner.cancelTraversal for the measurement.
        //
        // Both are kept, because they cover different failures. The refusal ends a traversal that is
        // still moving; the teardown ends one that has stalled inside a behaviour and will never
        // reach another hop to read the refusal -- which is the case /v1/executions/live exists to
        // make findable.
        // Paused-hop unwinding creates an interleaving that did not exist here before, and the reason it is safe
        // lives in another class, so it is written down rather than left to be rediscovered.
        //
        // Before, a hop parked on the pause gate unwound INLINE inside the call above, and only then
        // was the teardown below started. Now the two run concurrently: the gate's own virtual thread
        // carries the released hop into release() -> JoinCoordinator.terminate(), while the virtual
        // thread started below carries GraphRunner.close() -> releaseTraversals() into
        // JoinCoordinator.terminate() on the same coordinator.
        //
        // Two calls, one termination. That is not a coincidence and it is not this class's doing:
        // JoinCoordinator.terminate() is idempotent and hands EVERY caller the same single stage,
        // assigned inside its own monitor -- the FIX-17 invariant, whose javadoc says why a
        // second caller must not be given a fresh completed future. Whichever thread arrives first
        // drives the termination; the other composes on the same stage. close()'s bounded wait in
        // releaseTraversals() is a wait on that stage, so the join store is not closed underneath a
        // termination still in flight.
        //
        // If that invariant is ever weakened -- a second caller handed its own stage, or termination
        // made non-idempotent -- this call site is one of the places that breaks, and nothing here
        // would say so.
        active.runner.cancelTraversal(traversalId);
        Thread.startVirtualThread(active::close);
        return true;
    }

    /**
     * Reads {@link #activeExecutions} without removing: a paused traversal is still live, still
     * listed by {@link #liveExecutions} and still cancellable, which is exactly what distinguishes
     * pausing from stopping. Nothing is torn down here — no actor is stopped, no graph manager is
     * closed — because everything a paused execution holds is what makes it inspectable.
     *
     * <p>This read is the <em>whole</em> of the "is there anything to hold" decision, just as
     * {@link #cancelTraversal}'s removal is. {@code GraphRunner.pauseTraversal}
     * used to ask the same question over its coordinator map and get a different answer inside the
     * startup window — the entry goes into {@code activeExecutions} on the line above
     * {@code recordAcceptedExecution}, and the coordinator is registered inside {@code runner.execute}
     * two durable writes and a lease later — so a traversal this map listed as live was refused by the
     * runner and reported {@code ALREADY_PAUSED} with nothing holding it. The runner no longer asks;
     * the map that decides is this one, which is also the map {@link #liveExecutions} reads, so the
     * boolean and the liveness that {@code AuthorizedRavenrootApplication} reads it against can no
     * longer come from two places that disagree.</p>
     */
    @Override
    public boolean pauseTraversal(UUID traversalId) {
        java.util.Objects.requireNonNull(traversalId, "traversalId");
        ActiveExecution active = activeExecutions.get(traversalId);
        return active != null && active.runner.pauseTraversal(traversalId);
    }

    /** The counterpart of {@link #pauseTraversal}, and equally non-destructive. */
    @Override
    public boolean resumeTraversal(UUID traversalId) {
        java.util.Objects.requireNonNull(traversalId, "traversalId");
        ActiveExecution active = activeExecutions.get(traversalId);
        return active != null && active.runner.resumeTraversal(traversalId);
    }

    /**
     * Reads live executions straight out of {@link #activeExecutions} -- the same map
     * {@link #cancelTraversal} mutates and {@link #startGraphMl} populates -- rather than from any
     * projection of published events. A traversal whose behavior has deadlocked stops publishing
     * events the moment it stalls, but it stays in this map until it is cancelled or completes, so it
     * stays listed here for exactly as long as it stays cancellable.
     *
     * <p>Filtering happens here, against each entry's own recorded {@code tenantId}, rather than by
     * fetching every tenant's entries and narrowing the result afterward: an entry belonging to
     * another tenant is never added to the returned list in the first place, so there is no separate
     * exclusion step that could be forgotten.</p>
     */
    @Override
    public List<LiveExecution> liveExecutions(String tenantId) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        var result = new ArrayList<LiveExecution>();
        activeExecutions.forEach((traversalId, active) -> {
            if (tenantId.equals(active.tenantId)) {
                result.add(new LiveExecution(active.processInstanceId, traversalId, active.graphVersion,
                        active.startedAt));
            }
        });
        // Deterministic order for callers and tests: earliest-started first, traversal id as the
        // tie-break for two executions accepted within the same clock tick.
        result.sort(java.util.Comparator.comparing(LiveExecution::startedAt)
                .thenComparing(execution -> execution.traversalId().toString()));
        return List.copyOf(result);
    }

    /**
     * API-02: ADR 0012's engine-wide {@link ExecutionEngine#drain()}, exposed as a bounded,
     * on-demand operator command rather than only through the process-shutdown path
     * ({@code GracefulShutdown}, ravenroot-server). Deliberately does not escalate to
     * {@link ExecutionEngine#cancel} or {@link ExecutionEngine#close()} on timeout: those remain the
     * separate, explicit acts {@code GracefulShutdown} composes for an actual shutdown, and this method
     * exists to expose drain, not to invent a second shutdown path under a different name.
     */
    @Override
    public boolean drain(java.time.Duration bound) {
        java.util.Objects.requireNonNull(bound, "bound");
        CompletionStage<Void> draining = engine.drain();
        try {
            draining.toCompletableFuture().get(bound.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException timedOut) {
            return false;
        } catch (java.util.concurrent.ExecutionException wrapped) {
            throw new IllegalStateException("drain failed",
                    wrapped.getCause() == null ? wrapped : wrapped.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("drain interrupted", interrupted);
        }
    }

    @Override
    public List<ExecutionEvent> executionEventsAfter(long sequence) {
        return monitor.eventsAfter(sequence);
    }

    /**
     * Reports {@link ExecutionMonitor}'s own ring floor, so this implementation does not fall back to
     * the interface's "floor unknown" default while actually knowing the answer.
     */
    @Override
    public java.util.OptionalLong oldestRetainedEventSequence() {
        return monitor.oldestRetainedSequence();
    }

    @Override
    public AutoCloseable subscribeToExecutionEvents(Consumer<ExecutionEvent> listener) {
        return monitor.subscribe(listener);
    }

    @Override
    public boolean durableEventJournalAvailable() {
        return executionStore != null && executionStore.supports(StoreCapability.EVENT_JOURNAL);
    }

    /**
     * Issue 154: the durable inventory is available exactly when a store is composed and declares
     * {@link StoreCapability#PROCESS_INVENTORY} — the same "declared capability, not implicit
     * feature-sniffing" rule {@link #durableEventJournalAvailable()} already follows for the journal.
     */
    @Override
    public boolean processInventoryAvailable() {
        return executionStore != null && executionStore.supports(StoreCapability.PROCESS_INVENTORY);
    }

    private void requireProcessInventory() {
        if (!processInventoryAvailable()) {
            throw new IllegalStateException(
                    "no durable, inventory-capable execution store is configured (issue 154 durable "
                            + "process inventory is unavailable; the caller must choose its own "
                            + "fallback rather than discover this by catching an exception)");
        }
    }

    @Override
    public ai.ravenroot.api.persistence.ProcessInventoryPage processInventory(
            String tenantId, ai.ravenroot.api.persistence.ProcessInventoryQuery query) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(query, "query");
        requireProcessInventory();
        return await(executionStore.listProcessInstances(tenantId, query));
    }

    @Override
    public java.util.Optional<ai.ravenroot.api.persistence.ProcessInventoryEntry> processInstance(
            String tenantId, UUID processInstanceId) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(processInstanceId, "processInstanceId");
        requireProcessInventory();
        return await(executionStore.findProcessInstance(new ExecutionKey(tenantId, processInstanceId)));
    }

    @Override
    public List<ai.ravenroot.api.persistence.TraversalInventoryEntry> processInstanceTraversals(
            String tenantId, UUID processInstanceId) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(processInstanceId, "processInstanceId");
        requireProcessInventory();
        return await(executionStore.listTraversals(new ExecutionKey(tenantId, processInstanceId)));
    }

    @Override
    public java.time.Instant processInventoryRetainedFrom(String tenantId) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        if (executionStore == null) {
            return java.time.Instant.MIN;
        }
        return await(executionStore.inventoryRetainedFrom(tenantId));
    }

    /** Always true: this implementation always retains results, bounded by count. */
    @Override
    public boolean executionResultsRetained() {
        return true;
    }

    @Override
    public ai.ravenroot.api.application.ExecutionLookup executionResult(String tenantId, UUID executionId) {
        return executionResults.lookup(new ExecutionResultRegistry.Key(tenantId, executionId));
    }

    @Override
    public List<DurableExecutionEvent> durableEventsAfter(String tenantId, long afterOffset, int limit) {
        if (!durableEventJournalAvailable()) {
            throw new IllegalStateException(
                    "no durable, journal-capable execution store is configured (API-03 durable "
                            + "replay is unavailable; the in-memory-only degradation must be chosen "
                            + "by the caller, not discovered by catching this)");
        }
        List<JournalRecord> page = await(executionStore.readJournal(tenantId, afterOffset, limit));
        // Scoped to this one page rather than kept as instance state: bounded by `limit`, so the
        // cost is bounded too, and a cache that outlived a single call would need its own staleness
        // policy for no benefit -- a page's own records rarely span more than a handful of instances.
        var nodeNamesByInstance = new HashMap<ExecutionKey, Map<UUID, String>>();
        var events = new ArrayList<DurableExecutionEvent>(page.size());
        for (JournalRecord record : page) {
            EventEnvelope envelope = record.envelope();
            String nodeId = envelope.invocation()
                    .map(invocationId -> nodeNamesByInstance
                            .computeIfAbsent(record.key(), this::loadInvocationNodeNames)
                            .get(invocationId))
                    .orElse(null);
            events.add(new DurableExecutionEvent(envelope.eventId(), record.journalOffset(),
                    record.streamSequence(), envelope.tenantId(), envelope.eventType(),
                    envelope.processInstanceId(), envelope.traversalId(), envelope.invocationId(),
                    envelope.attemptId(), envelope.causationId(), envelope.correlationId(),
                    envelope.graphVersion(), envelope.occurredAt(), nodeId,
                    ExecutionEventType.EDGE_TRAVERSED.name().equals(envelope.eventType())
                            ? ai.ravenroot.api.persistence.EdgeTraversalEventData.edgeId(envelope.payload())
                                    .orElse(null)
                            : null));
        }
        return List.copyOf(events);
    }

    /**
     * The invocation-to-node binding {@code InvocationAdded} recorded as structure, in the same
     * transaction as the events themselves. The envelope deliberately carries no node id; see
     * {@link EventEnvelope}'s own Javadoc for why the binding is joined here rather than added to the
     * payload schema. A best-effort join, not a second source of truth: an instance whose current state is no
     * longer loadable (its own retention is independent of the journal's) resolves every one of its
     * node ids to {@code null} rather than failing the whole page over one unresolved instance.
     */
    private Map<UUID, String> loadInvocationNodeNames(ExecutionKey key) {
        StoredProcessInstance stored;
        try {
            stored = await(executionStore.load(key));
        } catch (ExecutionStoreException notFound) {
            if (notFound.failure() instanceof ExecutionStoreFailure.NotFound) {
                return Map.of();
            }
            throw notFound;
        }
        var bindings = new HashMap<UUID, String>();
        for (Traversal traversal : stored.state().traversals().values()) {
            for (NodeInvocation invocation : traversal.invocations().values()) {
                bindings.put(invocation.invocationId(), invocation.nodeId());
            }
        }
        return bindings;
    }

    /**
     * Activates a long-lived deployment for {@code graphMl}, admitting it against the per-pod cap
     * before anything else happens.
     *
     * <h2>Fail-closed, at the boundary</h2>
     * <p>The admission count is taken and checked under {@link #deploymentAdmissionLock} before a
     * domain is opened, a node spawned or {@code id} is even registered for anything but that count --
     * a rejection here leaves no trace behind for {@code id} to have accumulated. Re-activating an
     * {@code id} that is already active (a restart of a deployment this application already admitted)
     * does not count against itself: it already holds its slot, so this is not a second admission.</p>
     *
     * <p>Admission governs registration, not every {@code start()}/{@code restart()} an application
     * caller later performs directly on the returned {@link GraphDeployment}: the cap counts how many
     * distinct deployments this pod has admitted, which is what the deployment-admission contract's shutdown-budget
     * definition is about, not how many times any one of them has been asked to (re)start.</p>
     *
     * @throws DeploymentAdmissionException fail-closed, carrying the active count and the configured
     *                                       cap, if admitting {@code id} would exceed
     *                                       {@link #maxActiveDeployments}
     */
    public CompletionStage<DeploymentStatus> activateDeployment(SecurityContext security, DeploymentId id,
                                                                InputStream graphMl) {
        java.util.Objects.requireNonNull(security, "security");
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(graphMl, "graphMl");
        if (closed.get()) {
            throw new IllegalStateException("Ravenroot application is closed");
        }
        byte[] graphMlBytes = readFully(graphMl);
        // start() must run INSIDE this critical section, not after it. A freshly
        // registered deployment is COLD until start() flips it, and COLD does not count as active
        // (countsAsActive's own contract) -- so releasing the lock between registration and start()
        // left a real window where a second racer's count missed the first racer's just-admitted
        // entry entirely, because it had not yet become "active" by any observable measure. Measured:
        // a 16-way concurrent-activation test that must admit exactly one against a cap of one
        // admitted two once contention was real (a fixed thread pool + a barrier), not merely under a
        // hand-picked interleaving. start()'s own synchronous portion only flips its internal state and
        // returns a CompletionStage; the async work behind that stage still runs off this thread, so
        // holding the lock this far costs nothing that was not already serialized.
        synchronized (deploymentAdmissionLock) {
            requireAdmissionSlot(id, deployments.get(id));
            return registerDeployment(id, graphMlBytes).start(security);
        }
    }

    /**
     * the deployment admission decision, taken under {@link #deploymentAdmissionLock} by every caller
     * that is about to start something.
     *
     * <p>Extracted so {@code startLocalDeployment} is admitted on exactly the terms
     * {@link #activateDeployment} already applies; a divergence here would quietly change when a
     * source session is refused.</p>
     *
     * @throws DeploymentAdmissionException fail-closed, carrying the active count and the cap
     */
    private void requireAdmissionSlot(DeploymentId id, GraphDeployment existing) {
        long active = deployments.values().stream().filter(DefaultRavenrootApplication::countsAsActive).count();
        if (existing != null && countsAsActive(existing)) {
            // Already holding its own slot; do not penalize it for re-activating itself.
            active--;
        }
        if (active >= maxActiveDeployments) {
            throw new DeploymentAdmissionException(id, (int) active, maxActiveDeployments);
        }
    }

    /**
     * Constructs and registers a deployment for {@code id} without starting it.
     *
     * <p>Extracted from {@link #activateDeployment}, whose behaviour is unchanged: it still checks
     * admission before calling this and still starts inside the same critical section. What this
     * separation buys is the register-without-start lifecycle — a caller that wants
     * to reserve an id and a graph version now, and decide later when it runs.</p>
     *
     * <p>A freshly constructed deployment is {@code COLD}, which
     * {@link #countsAsActive(GraphDeployment)} deliberately does not count: registering reserves an
     * identity, it does not consume the shutdown budget the deployment-admission contract's cap is about.</p>
     */
    private GraphDeployment registerDeployment(DeploymentId id, byte[] graphMlBytes) {
        return deployments.computeIfAbsent(id, key -> {
            // The definition store is threaded through, but this composition supplies no execution
            // store, so a hosted traversal here is not durably recorded and is therefore not durably
            // bound to a definition. That is the pre-existing shape of deployment registration and
            // this change does not alter it; giving deployments durable execution state is separate
            // work. Passing the store now is what keeps the two from having to be threaded twice --
            // see DefaultGraphDeployment's constructor that takes both, which is where the binding
            // actually happens.
            var created = new DefaultGraphDeployment(key, engine, behaviors, monitor, identitySource, graphMlBytes,
                    DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, graphDefinitionStore);
            if (managedIngress != null) created.installManagedIngress(managedIngress);
            return created;
        });
    }

    /** The registered deployment for {@code id}, or empty if none was ever activated. */
    public java.util.Optional<GraphDeployment> deployment(DeploymentId id) {
        return java.util.Optional.ofNullable(deployments.get(java.util.Objects.requireNonNull(id, "id")));
    }

    @Override
    public LocalDeploymentStatus registerLocalDeployment(SecurityContext security, String deploymentId,
                                                         InputStream graphMl) {
        java.util.Objects.requireNonNull(security, "security");
        java.util.Objects.requireNonNull(graphMl, "graphMl");
        var key = new LocalDeploymentKey(requireTenant(security.tenantId()), requireLocalDeploymentId(deploymentId));
        byte[] graphBytes = readFully(graphMl);
        // Validated before anything is reserved: a graph naming a SOURCE the trusted catalog cannot
        // bind is refused here rather than at start, where the caller would already believe it owns a
        // working registration. A count of zero is not an error on this surface, which admits
        // source-less graphs; it is only an error for a source session.
        int sourceCount = inspectEffectiveSources(graphBytes);
        return localDeploymentStatus(key.deploymentId(), register(key, graphBytes, sourceCount).record());
    }

    @Override
    public java.util.List<LocalDeploymentStatus> localDeployments(String tenantId) {
        String tenant = requireTenant(tenantId);
        return localDeployments.entrySet().stream()
                .filter(entry -> entry.getKey().tenantId().equals(tenant))
                .sorted(java.util.Comparator.comparing(entry -> entry.getKey().deploymentId()))
                .map(entry -> localDeploymentStatus(entry.getKey().deploymentId(), entry.getValue()))
                .toList();
    }

    @Override
    public java.util.Optional<LocalDeploymentStatus> localDeployment(String tenantId, String deploymentId) {
        var key = new LocalDeploymentKey(requireTenant(tenantId), requireLocalDeploymentId(deploymentId));
        return java.util.Optional.ofNullable(localDeployments.get(key))
                .map(record -> localDeploymentStatus(key.deploymentId(), record));
    }

    @Override
    public CompletionStage<java.util.Optional<LocalDeploymentStatus>> startLocalDeployment(
            SecurityContext security, String deploymentId) {
        java.util.Objects.requireNonNull(security, "security");
        var key = new LocalDeploymentKey(requireTenant(security.tenantId()), requireLocalDeploymentId(deploymentId));
        return command(key, deployment -> {
            // The count and the start share one critical section, or two racers each see a
            // free slot and both take it.
            synchronized (deploymentAdmissionLock) {
                requireAdmissionSlot(deployment.id(), deployment);
                return deployment.start(security);
            }
        });
    }

    @Override
    public CompletionStage<java.util.Optional<LocalDeploymentStatus>> stopLocalDeployment(
            String tenantId, String deploymentId) {
        var key = new LocalDeploymentKey(requireTenant(tenantId), requireLocalDeploymentId(deploymentId));
        return command(key, GraphDeployment::stop);
    }

    @Override
    public CompletionStage<java.util.Optional<LocalDeploymentStatus>> restartLocalDeployment(
            SecurityContext security, String deploymentId) {
        java.util.Objects.requireNonNull(security, "security");
        var key = new LocalDeploymentKey(requireTenant(security.tenantId()), requireLocalDeploymentId(deploymentId));
        // GraphDeployment.restart is a stop that completes before the start begins, never the two
        // overlapping -- which is what makes "no duplicated source subscriptions" structural here
        // rather than a promise this layer would have to keep on its own.
        return command(key, deployment -> deployment.restart(security));
    }

    @Override
    public CompletionStage<java.util.Optional<LocalDeploymentStatus>> undeployLocalDeployment(
            String tenantId, String deploymentId) {
        var key = new LocalDeploymentKey(requireTenant(tenantId), requireLocalDeploymentId(deploymentId));
        LocalDeploymentRecord record;
        synchronized (localDeploymentLock) {
            record = localDeployments.get(key);
        }
        if (record == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
        }
        GraphDeployment deployment = deployments.get(record.engineId());
        // shutdown(), not stop(): after deregistration this deployment can never restart in this
        // process, so the condition InboundSource#shutdown() describes -- release what you would
        // otherwise keep across a restart of your own deployment -- is exactly the one that holds.
        // Using stop() here would strand a source's process-wide resource with no remaining reference
        // able to release it, because close() iterates the registry this call is about to remove from.
        CompletionStage<DeploymentStatus> stopped = deployment == null
                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : deployment instanceof DefaultGraphDeployment owned ? owned.shutdown() : deployment.stop();
        // thenApply, not handle: a stop that failed has not stopped, and "stop first, then deregister"
        // is the whole distinction between undeploy and stop. A failure propagates and the
        // registration stays, rather than the caller being told STOPPED over a domain still running.
        return stopped.thenApply(ignored -> {
            // Value-conditional, under the same lock register() holds, and the engine-level removal is
            // gated on it: a registration that arrived under this key while the stop was in flight is
            // a different record with the same derived engine id, and an unconditional removal would
            // delete the deployment that fresh registration had just constructed.
            synchronized (localDeploymentLock) {
                if (localDeployments.remove(key, record)) {
                    deployments.remove(record.engineId());
                }
            }
            return java.util.Optional.of(LocalDeploymentStatus.of(
                    key.deploymentId(), LocalDeploymentState.STOPPED, record.sourceCount()));
        });
    }

    /**
     * The one shape every lifecycle command takes: resolve inside the tenant's own key space, refuse
     * to disclose anything when that resolution is empty, then project the deployment's own truthful
     * status once the command settles.
     *
     * <p>{@code handle} rather than {@code thenApply} for start and restart on purpose: a start that
     * fails has still produced a real, truthful outcome — {@link LocalDeploymentState#FAILED} with a
     * rolled-back deployment — and reporting that is more useful to an operator than converting it
     * into a transport-level error that says only "something went wrong".</p>
     */
    private CompletionStage<java.util.Optional<LocalDeploymentStatus>> command(
            LocalDeploymentKey key, java.util.function.Function<GraphDeployment, CompletionStage<DeploymentStatus>> command) {
        LocalDeploymentRecord record = localDeployments.get(key);
        if (record == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
        }
        GraphDeployment deployment = deployments.get(record.engineId());
        if (deployment == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
        }
        return command.apply(deployment)
                .handle((ignored, failure) -> java.util.Optional.of(
                        localDeploymentStatus(key.deploymentId(), record)));
    }

    /**
     * Registers {@code key} idempotently, and says whether this call is what created it.
     *
     * <p>The {@code created} flag is not decoration: {@link #startSourceSession} must start a
     * registration it just made and must <em>not</em> restart one it merely rejoined. Re-posting a
     * stopped session observes it stopped rather than
     * silently bringing it back.</p>
     */
    private Registration register(LocalDeploymentKey key, byte[] graphBytes, int sourceCount) {
        if (closed.get()) {
            throw new IllegalStateException("Ravenroot application is closed");
        }
        String graphHash = sha256(graphBytes);
        synchronized (localDeploymentLock) {
            LocalDeploymentRecord existing = localDeployments.get(key);
            if (existing != null) {
                // A registered graph version is immutable. Replacing it silently would change what a
                // running deployment is without anyone having asked for a restart.
                if (!existing.graphHash().equals(graphHash)) {
                    throw new LocalDeploymentException(LocalDeploymentException.Reason.GRAPH_CONFLICT);
                }
                return new Registration(existing, false);
            }
            DeploymentId engineId = localDeploymentId(key);
            // No cap is applied here, deliberately. The deployment admission cap counts deployments a graceful
            // shutdown would owe time to, and a cold registration owes it none -- which is also why
            // countsAsActive() excludes COLD. Capping registrations instead would narrow source-session
            // behavior: its start path refuses on the *active* count, so a tenant whose sessions are all stopped
            // could always start another, and a per-record cap would have started answering 429 there.
            // A published route's limits are not something to tighten as a side effect.
            var created = new LocalDeploymentRecord(graphHash, engineId, sourceCount);
            registerDeployment(engineId, graphBytes);
            localDeployments.put(key, created);
            return new Registration(created, true);
        }
    }

    private LocalDeploymentStatus localDeploymentStatus(String deploymentId, LocalDeploymentRecord record) {
        GraphDeployment deployment = deployments.get(record.engineId());
        if (deployment == null) {
            return LocalDeploymentStatus.of(deploymentId, LocalDeploymentState.STOPPED, record.sourceCount());
        }
        // The diagnostics are fixed strings chosen here, not the engine's own DeploymentStatus.cause().
        // That cause is sanitized for an operator log, and the degraded one originates in an inbound
        // adapter; neither has been reviewed for a client-facing wire surface, and widening this
        // response to carry adapter-authored text is a decision for whoever needs it, not a side
        // effect of adding the route.
        return switch (deployment.status().state()) {
            case COLD -> LocalDeploymentStatus.of(
                    deploymentId, LocalDeploymentState.REGISTERED, record.sourceCount());
            case STARTING -> LocalDeploymentStatus.of(
                    deploymentId, LocalDeploymentState.STARTING, record.sourceCount());
            case READY -> LocalDeploymentStatus.of(
                    deploymentId, LocalDeploymentState.READY, record.sourceCount());
            case DEGRADED -> LocalDeploymentStatus.of(deploymentId, LocalDeploymentState.DEGRADED,
                    record.sourceCount(), "one or more inbound sources reported degraded health");
            case FAILED -> LocalDeploymentStatus.of(deploymentId, LocalDeploymentState.FAILED,
                    record.sourceCount(), "deployment startup failed in this process");
            case STOPPING -> LocalDeploymentStatus.of(
                    deploymentId, LocalDeploymentState.STOPPING, record.sourceCount());
            case STOPPED -> LocalDeploymentStatus.of(
                    deploymentId, LocalDeploymentState.STOPPED, record.sourceCount());
        };
    }

    /**
     * Source-session start, as a projection of the one local-deployment registry.
     *
     * <p>Every observable behaviour this method had is preserved deliberately: the >= 1 effective
     * SOURCE requirement (which the deployment surface does <em>not</em> impose), the
     * {@link SourceSessionException} taxonomy, the idempotent rejoin that observes a stopped session
     * rather than restarting it, and the immediate return in {@code STARTING} while source
     * construction continues asynchronously. What changed is underneath: there is no second registry
     * for the editor to disagree with.</p>
     */
    @Override
    public SourceSessionStatus startSourceSession(SecurityContext security, String sessionId, InputStream graphMl) {
        java.util.Objects.requireNonNull(security, "security");
        java.util.Objects.requireNonNull(graphMl, "graphMl");
        String normalizedId = requireSourceSessionId(sessionId);
        byte[] graphBytes = readFully(graphMl);
        int sourceCount;
        try {
            sourceCount = inspectEffectiveSources(graphBytes);
        } catch (LocalDeploymentException refusal) {
            throw asSourceSessionRefusal(refusal);
        }
        // Checked before anything is reserved, so a graph that is not a source session leaves no
        // registration behind to roll back -- and so this refusal cannot reach a deployment another
        // route legitimately registered under the same id.
        if (sourceCount < 1) {
            throw new SourceSessionException(SourceSessionException.Reason.NO_EFFECTIVE_SOURCE);
        }
        var key = new LocalDeploymentKey(requireTenant(security.tenantId()), normalizedId);
        Registration registration;
        try {
            registration = register(key, graphBytes, sourceCount);
        } catch (LocalDeploymentException refusal) {
            throw asSourceSessionRefusal(refusal);
        }
        if (!registration.created()) {
            return sourceSessionStatus(normalizedId, registration.record());
        }
        try {
            // start() flips the deployment to STARTING synchronously and performs all source
            // construction asynchronously. The HTTP caller can therefore observe Starting
            // immediately, while failure remains visible through the same session record.
            GraphDeployment deployment = deployments.get(registration.record().engineId());
            synchronized (deploymentAdmissionLock) {
                requireAdmissionSlot(deployment.id(), deployment);
                deployment.start(security)
                        .whenComplete((ignored, failure) -> { /* status is held by the deployment */ });
            }
            return sourceSessionStatus(normalizedId, registration.record());
        } catch (RuntimeException | Error refusedBeforeStart) {
            synchronized (localDeploymentLock) {
                if (localDeployments.remove(key, registration.record())) {
                    deployments.remove(registration.record().engineId());
                }
            }
            throw refusedBeforeStart;
        }
    }

    @Override
    public java.util.Optional<SourceSessionStatus> sourceSession(String tenantId, String sessionId) {
        var key = new LocalDeploymentKey(requireTenant(tenantId), requireSourceSessionId(sessionId));
        return sourceSessionRecord(key).map(record -> sourceSessionStatus(key.deploymentId(), record));
    }

    @Override
    public CompletionStage<java.util.Optional<SourceSessionStatus>> stopSourceSession(
            String tenantId, String sessionId) {
        var key = new LocalDeploymentKey(requireTenant(tenantId), requireSourceSessionId(sessionId));
        java.util.Optional<LocalDeploymentRecord> found = sourceSessionRecord(key);
        if (found.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
        }
        LocalDeploymentRecord record = found.orElseThrow();
        GraphDeployment deployment = deployments.get(record.engineId());
        if (deployment == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
        }
        return deployment.stop().thenApply(ignored ->
                java.util.Optional.of(sourceSessionStatus(key.deploymentId(), record)));
    }

    /**
     * The record behind {@code key}, but only if it really is a source session.
     *
     * <p>The registry is shared with {@link #registerLocalDeployment}, which admits graphs with no
     * effective SOURCE. Such a deployment is not a source session and must not be observable as one:
     * projecting it would have to invent a {@code sourceCount} of zero that
     * {@link SourceSessionStatus} rejects by construction, and answering "this is not a source
     * session" with anything other than the same empty result an unknown id produces would leak the
     * fact that the id is taken. So it is empty, exactly as a sibling tenant's id is.</p>
     */
    private java.util.Optional<LocalDeploymentRecord> sourceSessionRecord(LocalDeploymentKey key) {
        return java.util.Optional.ofNullable(localDeployments.get(key))
                .filter(record -> record.sourceCount() >= 1);
    }

    /**
     * Validates the graph and derives SOURCE only from the sanitized trusted-catalog taxonomy.
     *
     * <p>Returns the count and judges nothing about it. Whether zero is acceptable belongs to the
     * caller: it is fatal for a source session and legitimate for a deployment.</p>
     */
    private int inspectEffectiveSources(byte[] graphBytes) {
        try (GraphManager manager = GraphManager.readGraphMl(new java.io.ByteArrayInputStream(graphBytes))) {
            var definition = manager.definition();
            new BehaviorPropertySchema(behaviors).validate(definition);
            new NodeRuntimeNatureValidator(behaviors).validate(definition);
            int count = 0;
            for (var node : definition.nodes()) {
                var descriptor = node.kind() == NodeKind.BEHAVIOR
                        ? behaviors.descriptor(node.behavior()).orElse(null) : null;
                if (NodeRuntimeNatureProperty.effectiveNature(descriptor, node.properties())
                        != NodeRuntimeNature.SOURCE) {
                    continue;
                }
                if (node.kind() != NodeKind.BEHAVIOR
                        || behaviors.sourceCapableFactory(node.behavior()).isEmpty()) {
                    throw new LocalDeploymentException(
                            LocalDeploymentException.Reason.SOURCE_CAPABILITY_MISMATCH,
                            java.util.Map.of("nodeId", node.id()));
                }
                count++;
            }
            return count;
        }
    }

    /** Keeps the source session's published refusal taxonomy over the shared registration path's own. */
    private static SourceSessionException asSourceSessionRefusal(LocalDeploymentException refusal) {
        return switch (refusal.reason()) {
            case GRAPH_CONFLICT -> new SourceSessionException(SourceSessionException.Reason.GRAPH_CONFLICT);
            case SOURCE_CAPABILITY_MISMATCH -> new SourceSessionException(
                    SourceSessionException.Reason.SOURCE_CAPABILITY_MISMATCH, refusal.diagnosticDetail());
            case DEPLOYMENT_ID_INVALID -> new SourceSessionException(
                    SourceSessionException.Reason.SESSION_ID_INVALID);
        };
    }

    private SourceSessionStatus sourceSessionStatus(String sessionId, LocalDeploymentRecord record) {
        DeploymentStatus deployment = deployments.get(record.engineId()).status();
        return switch (deployment.state()) {
            // COLD is unreachable through the source-session path, which starts what it registers before it
            // ever projects. It is reachable now that a graph can be registered by /v1/deployments and
            // observed here, and for that deployment STOPPED is the truthful answer: nothing is
            // listening. Reporting STARTING, as this projection did while COLD was unreachable, would
            // claim a startup nobody has asked for.
            case COLD, STOPPED -> SourceSessionStatus.of(
                    sessionId, SourceSessionState.STOPPED, record.sourceCount());
            case STARTING -> SourceSessionStatus.of(
                    sessionId, SourceSessionState.STARTING, record.sourceCount());
            case READY -> SourceSessionStatus.of(
                    sessionId, SourceSessionState.LISTENING, record.sourceCount());
            case DEGRADED -> SourceSessionStatus.of(sessionId, SourceSessionState.DEGRADED,
                    record.sourceCount(), "one or more inbound sources reported degraded health");
            case FAILED -> SourceSessionStatus.of(sessionId, SourceSessionState.FAILED,
                    record.sourceCount(), "source session startup failed in this process");
            case STOPPING -> SourceSessionStatus.of(
                    sessionId, SourceSessionState.STOPPING, record.sourceCount());
        };
    }

    /**
     * The engine-level identity for one tenant's deployment id.
     *
     * <p>Derived rather than caller-supplied because {@link #deployments} is a flat map and
     * {@code ExecutionEngine.openDomain} takes its value: two tenants that both register "editor-1"
     * must not meet in one execution domain. The domain-separation prefix inside the digest is what
     * keeps this space disjoint from any other derivation that may later share the map.</p>
     */
    private static DeploymentId localDeploymentId(LocalDeploymentKey key) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(
                    ("local-deployment\u0000" + key.tenantId() + "\u0000" + key.deploymentId())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        return DeploymentId.of("local-" + java.util.HexFormat.of().formatHex(digest));
    }

    private static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        return tenantId;
    }

    /**
     * The accepted id shape, shared by source sessions and deployments because they are one id space.
     * Unreserved URI characters only (RFC 3986 section 2.3), bounded at 128: an id travels in a path
     * segment and is echoed in responses, so anything needing escaping is refused rather than encoded.
     */
    private static final java.util.regex.Pattern LOCAL_ID =
            java.util.regex.Pattern.compile("[A-Za-z0-9._~-]{1,128}");

    private static String requireSourceSessionId(String sessionId) {
        if (sessionId == null || !LOCAL_ID.matcher(sessionId).matches()) {
            throw new SourceSessionException(SourceSessionException.Reason.SESSION_ID_INVALID);
        }
        return sessionId;
    }

    private static String requireLocalDeploymentId(String deploymentId) {
        if (deploymentId == null || !LOCAL_ID.matcher(deploymentId).matches()) {
            throw new LocalDeploymentException(LocalDeploymentException.Reason.DEPLOYMENT_ID_INVALID);
        }
        return deploymentId;
    }

    /** The tenant is half the identity, so no lookup can reach across tenants by construction. */
    private record LocalDeploymentKey(String tenantId, String deploymentId) {
        private LocalDeploymentKey {
            requireTenant(tenantId);
            java.util.Objects.requireNonNull(deploymentId, "deploymentId");
        }
    }

    private record LocalDeploymentRecord(String graphHash, DeploymentId engineId, int sourceCount) { }

    /** A registration plus whether this call is the one that created it. */
    private record Registration(LocalDeploymentRecord record, boolean created) { }

    /**
     * States the deployment-admission contract's cap counts: work a graceful shutdown would actually have to drain. COLD
     * (never started), STOPPED (already drained) and FAILED (rolled back, nothing retained) hold
     * nothing that shutdown owes time to, so none of the three occupies a slot.
     */
    private static boolean countsAsActive(GraphDeployment deployment) {
        DeploymentState state = deployment.status().state();
        return state == DeploymentState.STARTING || state == DeploymentState.READY
                || state == DeploymentState.DEGRADED || state == DeploymentState.STOPPING;
    }

    private static byte[] readFully(InputStream input) {
        try {
            return input.readAllBytes();
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Cannot read GraphML for deployment activation", error);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        activeExecutions.values().forEach(ActiveExecution::close);
        activeExecutions.clear();
        programBuildTasks.values().forEach(Thread::interrupt);
        programBuildTasks.values().forEach(task -> {
            try { task.join(java.time.Duration.ofSeconds(5)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        programBuildTasks.clear();
        // Best-effort, bounded by each deployment's own stop(): a deployment stuck mid-close must not
        // strand the rest of an orderly application shutdown.
        deployments.values().forEach(deployment -> {
            try {
                // shutdown(), not stop(): this is the application ending, which is the one condition
                // InboundSource#shutdown() exists to signal and which nothing has ever signalled
                // previously. A source that keeps a process-wide resource across restarts of its own
                // deployment releases it here and nowhere else. Every other caller -- an operator
                // stopping one deployment, a restart, a rollback -- still gets stop().
                CompletionStage<DeploymentStatus> ending = deployment instanceof DefaultGraphDeployment owned
                        ? owned.shutdown()
                        : deployment.stop();
                ending.toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (RuntimeException | java.util.concurrent.ExecutionException
                    | java.util.concurrent.TimeoutException ignored) {
                // The underlying engine's own close() (invoked separately by the composition root)
                // remains the final backstop for anything left running.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        deployments.clear();
        localDeployments.clear();
        if (artifacts instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot close program artifact authority", failure);
            }
        }
    }

    /**
     * Records the accepted execution through the {@link ExecutionStore} port: instance creation
     * followed by one state transition.
     *
     * <p>Two batches rather than one, deliberately. The first asserts {@link RevisionExpectation.NotPresent},
     * which is what makes creation exactly-once and turns a duplicated process-instance identifier
     * into {@code AlreadyExists} instead of a silent overwrite. The second asserts
     * {@link RevisionExpectation.Exactly} against the revision the first returned, so the optimistic
     * concurrency token is genuinely exercised rather than merely declared.</p>
     *
     * <p>This does <em>not</em> make core durable: nothing is read back, no recovery path exists and
     * {@code GraphRunner} still holds its own in-memory state. Routing runtime writes through the
     * store is PERS-04/CORE-03.</p>
     *
     * <p>Blocking on the stage is acceptable here and only here: submission runs on the caller's HTTP
     * or CLI thread, not on actor dispatch, and the instance must be recorded before the submission
     * is acknowledged. PERS-04 restructures this path when writes move onto the dispatcher.</p>
     *
     * <p>The {@link ExecutionKey} is built from {@code security.tenantId()} — the tenant the request
     * authenticated as — which is what makes PERS-10's tenant scoping load-bearing rather than
     * decorative (SEC-07).</p>
     */
    /**
     * Opens the lease this traversal executes under, or {@code null} when no store is composed —
     * which keeps the in-memory-only behaviour exactly as it was.
     */
    private ExecutionRecorder openRecorder(SecurityContext security, UUID processInstanceId, long revision) {
        if (executionStore == null) {
            return null;
        }
        return ExecutionRecorder.open(executionStore, new ExecutionKey(security.tenantId(), processInstanceId),
                workerId, executionLeaseTtl, revision);
    }

    /**
     * Commits the canonical document this submission was accepted against, so the pin written next
     * addresses bytes the store actually holds.
     *
     * <p>The content address the definition store files the document under is byte-identical to the
     * graph version reference the pin carries, which is why an execution pinned before any
     * definition store existed still names a definition once one is composed.</p>
     *
     * <p>A failure here propagates and the submission is refused. That is the entire point: the
     * alternative is an accepted execution whose graph nothing retains, which is exactly the state
     * this ordering exists to make unreachable.</p>
     */
    private void recordGraphDefinition(SecurityContext security, byte[] graphBytes) {
        if (graphDefinitionStore == null) {
            return;
        }
        var canonical = ai.ravenroot.api.persistence.CanonicalGraphMl.of(graphBytes);
        awaitDefinition(graphDefinitionStore.put(security.tenantId(),
                ai.ravenroot.api.persistence.GraphDefinitionIdentity.forSubmission(canonical.contentId()),
                canonical));
    }

    /**
     * Joins a definition-store stage and unwraps the completion wrapper, so callers observe the
     * sealed classification rather than a wrapper that hides it.
     */
    private static <T> T awaitDefinition(java.util.concurrent.CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException wrapped) {
            var failure = ai.ravenroot.api.persistence.GraphDefinitionStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }

    /** @return the revision the instance is at after these writes, or {@code -1} with no store */
    private long recordAcceptedExecution(SecurityContext security, UUID processInstanceId, UUID traversalId,
                                         String ingressNodeId, String graphVersion) {
        if (executionStore == null) {
            return -1L;
        }
        var key = new ExecutionKey(security.tenantId(), processInstanceId);
        var traversal = new Traversal(traversalId, ingressNodeId, TraversalStatus.ACCEPTED, Map.of());
        var accepted = new ProcessInstance(processInstanceId, ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, traversal));

        // Issue 154: a transient submission opens no deployment domain and models no workload, so
        // only the caller's own correlation identity is knowable here -- deploymentId and workloadId
        // stay absent rather than being invented, which is exactly what keeps a transient execution's
        // inventory row from being conflated with a deployment or a graph version (acceptance
        // criterion 2). requestId is SecurityContext's own "ingress request correlation identifier",
        // the same value every interior boundary already carries for this submission.
        StoredProcessInstance created = await(executionStore.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin(graphVersion)))
                .recordOrigin(ExecutionOrigin.of(null, null, security.requestId()))
                .build()));

        return await(executionStore.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .build())).revision();
    }

    /**
     * Joins a store stage and unwraps the {@link java.util.concurrent.CompletionException} so callers
     * observe the sealed {@link ai.ravenroot.api.persistence.ExecutionStoreFailure} classification
     * rather than a wrapper that hides it.
     */
    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException wrapped) {
            ExecutionStoreException failure = ExecutionStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }

    private GeneratedArtifact artifactInState(String id, ArtifactState expected) {
        var artifact = artifacts.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown artifact: " + id));
        if (artifact.state() != expected) {
            throw new IllegalStateException("Artifact " + id + " is " + artifact.state() + ", not " + expected);
        }
        return artifact;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static Map<String, String> merged(Map<String, String> first, Map<String, String> second) {
        var result = new java.util.LinkedHashMap<String, String>();
        if (first != null) result.putAll(first);
        result.putAll(second);
        return Map.copyOf(result);
    }

    private static final class ActiveExecution implements AutoCloseable {
        private final GraphManager manager;
        private final GraphRunner runner;
        private final String tenantId;
        private final UUID processInstanceId;
        private final String graphVersion;
        private final Instant startedAt;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ActiveExecution(GraphManager manager, GraphRunner runner, String tenantId,
                                UUID processInstanceId, String graphVersion, Instant startedAt) {
            this.manager = manager;
            this.runner = runner;
            this.tenantId = tenantId;
            this.processInstanceId = processInstanceId;
            this.graphVersion = graphVersion;
            this.startedAt = startedAt;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                runner.close();
            } finally {
                manager.close();
            }
        }
    }
}
