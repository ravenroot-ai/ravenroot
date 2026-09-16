package ai.ravenroot.core.runner;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.runner.AgentDefinition;
import ai.ravenroot.api.runner.RunnerJobIdentity;
import ai.ravenroot.api.runner.RunnerJobOperation;
import ai.ravenroot.api.runner.RunnerPolicy;
import ai.ravenroot.api.runner.RunnerRegistration;
import ai.ravenroot.api.runner.RunnerWorkspaceState;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.core.runtime.GraphRunner;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Trusted admission and lifecycle coordinator. Runners receive jobs, never graph continuations. */
public final class RunnerJobService {
    private final ExecutionStore store;
    private final Clock clock;
    private final Map<AgentDefinition.Reference, AgentDefinition> definitions;
    private final Map<String, RunnerRegistration> runners;
    private final Map<String, RunnerPolicy> policies;
    private final Map<UUID, Binding> live = new ConcurrentHashMap<>();
    private final RunnerTelemetry.Relay telemetry = new RunnerTelemetry.Relay();
    private final RunnerControlConfiguration controlConfiguration;
    public RunnerControlConfiguration controlConfiguration() { return controlConfiguration; }
    public RunnerTelemetry.Relay telemetry() { return telemetry; }

    public RunnerJobService(ExecutionStore store, Clock clock, Collection<AgentDefinition> definitions,
                            Collection<RunnerRegistration> runners, Map<String, RunnerPolicy> policies) {
        this(store, clock, definitions, runners, policies, java.util.List.of());
    }
    public RunnerJobService(ExecutionStore store, Clock clock, Collection<AgentDefinition> definitions,
                            Collection<RunnerRegistration> runners, Map<String, RunnerPolicy> policies,
                            Collection<ai.ravenroot.api.runner.WorkspaceProfile> profiles) {
        this(store, clock, definitions, runners, policies, profiles, RunnerControlConfiguration.defaults());
    }
    public RunnerJobService(ExecutionStore store, Clock clock, Collection<AgentDefinition> definitions,
                            Collection<RunnerRegistration> runners, Map<String, RunnerPolicy> policies,
                            Collection<ai.ravenroot.api.runner.WorkspaceProfile> profiles,
                            RunnerControlConfiguration controlConfiguration) {
        this.controlConfiguration = Objects.requireNonNull(controlConfiguration);
        this.store = Objects.requireNonNull(store); this.clock = Objects.requireNonNull(clock);
        if (!store.supports(StoreCapability.RUNNER_JOBS) || !store.supports(StoreCapability.EVENT_JOURNAL)) {
            throw new IllegalArgumentException("governed runners require atomic runner persistence and journal");
        }
        this.definitions = definitions.stream().collect(Collectors.toUnmodifiableMap(AgentDefinition::reference, value -> value));
        this.runners = runners.stream().collect(Collectors.toUnmodifiableMap(
                value -> runnerKey(value.tenantId(), value.runnerId()), value -> value));
        this.policies = Map.copyOf(policies);
        // Bootstrap is an explicit deployment-owned approval, never a runner self-registration.
        for (var definition : this.definitions.values()) {
            seed(new ai.ravenroot.api.runner.GovernedRunnerResource(
                    ai.ravenroot.api.runner.GovernedRunnerResource.Kind.AGENT_DEFINITION,
                    definition.reference().tenantId(), definition.reference().name(), definition.reference().version(),
                    true, OpaquePayload.of(ai.ravenroot.api.runner.RunnerCodec.definition(definition),
                    "application/vnd.ravenroot.agent-definition.v1"), 0, "deployment-bootstrap", clock.instant()));
        }
        for (var registration : this.runners.values()) {
            seed(new ai.ravenroot.api.runner.GovernedRunnerResource(
                    ai.ravenroot.api.runner.GovernedRunnerResource.Kind.RUNNER, registration.tenantId(),
                    registration.runnerId(), 1, true, OpaquePayload.of(ai.ravenroot.api.runner.RunnerCodec.registration(registration),
                    "application/vnd.ravenroot.runner-registration.v1"), 0, "deployment-bootstrap", clock.instant()));
        }
        for (var profile : profiles) seed(new ai.ravenroot.api.runner.GovernedRunnerResource(
                ai.ravenroot.api.runner.GovernedRunnerResource.Kind.WORKSPACE_PROFILE,
                profile.reference().tenantId(), profile.reference().name(), profile.reference().version(), true,
                OpaquePayload.of(ai.ravenroot.api.runner.RunnerCodec.workspaceProfile(profile), "application/vnd.ravenroot.workspace-profile.v1"),
                0, "deployment-bootstrap", clock.instant()));
    }

    private void seed(ai.ravenroot.api.runner.GovernedRunnerResource resource) {
        var existing = store.runnerResources(resource.tenantId()).toCompletableFuture().join().stream()
                .filter(value -> value.key().equals(resource.key())).findFirst().orElse(null);
        if (existing == null) {
            try { store.saveRunnerResource(resource, 0).toCompletableFuture().join(); }
            catch (RuntimeException concurrentOrUnavailable) {
                existing = store.runnerResources(resource.tenantId()).toCompletableFuture().join().stream()
                        .filter(value -> value.key().equals(resource.key())).findFirst().orElse(null);
                if (existing == null) throw concurrentOrUnavailable;
            }
        }
        boolean equivalentDefinition = existing != null
                && existing.kind() == ai.ravenroot.api.runner.GovernedRunnerResource.Kind.AGENT_DEFINITION
                && ai.ravenroot.api.runner.RunnerCodec.definition(existing.document().bytes()).equals(
                    ai.ravenroot.api.runner.RunnerCodec.definition(resource.document().bytes()));
        if (existing != null && !existing.document().equals(resource.document()) && !equivalentDefinition) {
            throw new IllegalArgumentException("deployment bootstrap conflicts with immutable runner catalog");
        }
        // Never restore approval when restarting an explicitly revoked resource.
    }

    public void bindLive(UUID attemptId, ExecutionRecorder recorder, GraphRunner runner, UUID startedEventId) {
        if (recorder == null) return;
        var binding = new Binding(recorder, runner, startedEventId);
        var previous = live.putIfAbsent(attemptId, binding);
        if (previous != null && (previous.recorder() != recorder || previous.runner() != runner)) {
            throw new IllegalStateException("runner traversal already has a live control plane");
        }
    }

    public void releaseLive(UUID traversalId, GraphRunner runner) {
        live.entrySet().removeIf(entry -> entry.getValue().runner() == runner);
    }


    /** Mutations are serialized by the existing process fence; the runner job adds its own report fence. */
    public RunnerWorkspaceState mutate(SecurityContext actor, ExecutionKey key, RunnerJobOperation operation) {
        if (!actor.tenantId().equals(key.tenantId()) || operation instanceof RunnerJobOperation.Submit) {
            throw new IllegalArgumentException("invalid runner operation scope");
        }
        var stored = store.load(key).toCompletableFuture().join();
        try (var recorder = ExecutionRecorder.open(store, key, "runner-control-" + UUID.randomUUID(),
                controlConfiguration.continuationLease(), stored.revision())) {
            if (operation instanceof RunnerJobOperation.Claim
                    && !ai.ravenroot.core.process.ProcessLifecycleService.admitsRunnerDelivery(store, key, recorder.revision())) {
                throw new IllegalStateException("process lifecycle does not admit a runner execution claim");
            }
            var workspace = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow();
            var entry = workspace.jobs().get(operation.jobId());
            if (entry == null) throw new IllegalArgumentException("runner job not found");
            if (operation instanceof RunnerJobOperation.Complete completed && entry.job().state().terminal()) {
                // Validate even duplicate reports; do not append another effect or journal entry.
                entry.job().complete(completed.runnerId(), completed.fence(), completed.result(), clock.instant());
                return workspace;
            }
            long reportFence = operation instanceof RunnerJobOperation.Claim || operation instanceof RunnerJobOperation.ReconcileReport
                    ? Math.incrementExact(entry.job().fence()) : entry.job().fence();
            recorder.applyRunner(operation, event(entry.job().identity(), actor, eventType(operation),
                    reportFence, stored.graphVersionPin().reference(), null));
            var result = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow();
            var accepted = result.jobs().get(operation.jobId()).job();
            if (!entry.job().state().terminal() && accepted.state().terminal()) telemetry.increment(switch (accepted.state()) {
                case COMPLETED -> RunnerTelemetry.Counter.JOB_COMPLETED;
                case CANCELLED -> RunnerTelemetry.Counter.JOB_CANCELLED;
                case DEADLINE_EXCEEDED -> RunnerTelemetry.Counter.JOB_DEADLINE_EXCEEDED;
                default -> throw new IllegalStateException("not a terminal runner result");
            });
            if (operation instanceof RunnerJobOperation.WorkspaceReleased) telemetry.increment(RunnerTelemetry.Counter.WORKSPACE_RELEASED);
            if (operation instanceof RunnerJobOperation.WorkspaceStopped) telemetry.increment(RunnerTelemetry.Counter.WORKSPACE_STOPPED);
            if (operation instanceof RunnerJobOperation.Complete && "checkpoint".equals(entry.lifecycleCommand()))
                telemetry.increment(RunnerTelemetry.Counter.WORKSPACE_CHECKPOINTED);
            return result;
        }
    }

    public Collection<AgentDefinition> definitions(String tenant) {
        return store.runnerResources(tenant).toCompletableFuture().join().stream()
                .filter(value -> value.approved() && value.kind() == ai.ravenroot.api.runner.GovernedRunnerResource.Kind.AGENT_DEFINITION)
                .map(value -> ai.ravenroot.api.runner.RunnerCodec.definition(value.document().bytes())).toList();
    }
    /** Composition snapshot; dispatch still checks approval in the authenticated tenant. */
    public Collection<AgentDefinition> definitions(ai.ravenroot.core.graph.GraphNode node) {
        String name = Objects.toString(node.properties().get("agentDefinition"), "");
        long version = Long.parseLong(Objects.toString(node.properties().getOrDefault("agentVersion", "1")));
        return policies.keySet().stream().flatMap(tenant -> definitions(tenant).stream())
                .filter(value -> value.reference().name().equals(name) && value.reference().version() == version).toList();
    }
    public RunnerPolicy ordinaryAuthority(AgentDefinition definition, ai.ravenroot.api.runner.AgentCommand command) {
        return policies.get(definition.reference().tenantId()).intersect(definition.policy()).intersect(command.policy());
    }
    public void requireApproved(AgentDefinition definition) {
        if (definitions(definition.reference().tenantId()).stream().noneMatch(definition::equals))
            throw new IllegalArgumentException("Agent definition is no longer approved");
    }
    public Collection<RunnerRegistration> runners(String tenant) {
        return store.runnerResources(tenant).toCompletableFuture().join().stream()
                .filter(value -> value.approved() && value.kind() == ai.ravenroot.api.runner.GovernedRunnerResource.Kind.RUNNER)
                .map(value -> ai.ravenroot.api.runner.RunnerCodec.registration(value.document().bytes())).toList();
    }
    public ExecutionStore store() { return store; }
    java.time.Instant now() { return clock.instant(); }

    /** Dynamic command declarations come from governed definitions, never a wildcard descriptor. */
    public boolean declaresCommand(String tenant, ai.ravenroot.core.graph.GraphNode node, String command) {
        if ("workspace".equals(node.behavior())) return WorkspaceBehavior.COMMANDS.contains(command);
        if (!GovernedAgent.isNamed(node)) return false;
        String name = Objects.toString(node.properties().get("agentDefinition"), "");
        long version = Long.parseLong(Objects.toString(node.properties().getOrDefault("agentVersion", "1")));
        // Graph construction has no authenticated tenant yet. It validates the named definition in
        // the deployment catalog; delivery below narrows to the exact authenticated tenant again.
        Collection<String> scopes = tenant == null ? policies.keySet() : java.util.List.of(tenant);
        for (String scope : scopes) {
            if (!policies.containsKey(scope)) continue;
            if (store.runnerResources(scope).toCompletableFuture().join().stream()
                    .filter(value -> value.kind() == ai.ravenroot.api.runner.GovernedRunnerResource.Kind.AGENT_DEFINITION
                            && value.name().equals(name) && value.version() == version)
                    .map(value -> ai.ravenroot.api.runner.RunnerCodec.definition(value.document().bytes()))
                    .anyMatch(value -> value.commands().containsKey(command))) return true;
        }
        // Approval is enforced again by suspend at atomic job admission. Retiring a definition
        // must not make its already accepted terminal result's pinned graph impossible to parse.
        return false;
    }

    private EventEnvelope event(RunnerJobIdentity id, SecurityContext actor, String type, long fence, String graph, UUID cause) {
        byte[] bytes = PayloadJson.write(PayloadValue.fromJava(Map.of("runnerJobId", id.runnerJobId().toString(),
                "actor", actor.qualifiedIdentity(), "fence", fence), PayloadLimits.DEFAULTS)).getBytes(StandardCharsets.UTF_8);
        UUID eventId = type.equals("RUNNER_JOB_TERMINAL_REPORTED")
                ? terminalEventId(id.runnerJobId(), fence) : UUID.randomUUID();
        return EventEnvelope.of(eventId, id.execution().tenantId(), type, id.execution().processInstanceId(),
                id.traversalId(), id.invocationId(), id.attemptId(), cause, actor.requestId(), graph, clock.instant(),
                OpaquePayload.of(bytes, "application/vnd.ravenroot.runner-event.v1+json"));
    }
    public static UUID terminalEventId(UUID jobId, long fence) {
        return UUID.nameUUIDFromBytes(("runner-terminal:" + jobId + ":" + fence).getBytes(StandardCharsets.UTF_8));
    }
    private static String eventType(RunnerJobOperation operation) {
        return switch (operation) {
            case RunnerJobOperation.Claim ignored -> "RUNNER_JOB_CLAIMED";
            case RunnerJobOperation.Heartbeat ignored -> "RUNNER_JOB_HEARTBEAT";
            case RunnerJobOperation.Cancel ignored -> "RUNNER_JOB_CANCEL_REQUESTED";
            case RunnerJobOperation.Reconcile ignored -> "RUNNER_JOB_RECONCILED";
            case RunnerJobOperation.ReconcileReport ignored -> "RUNNER_JOB_RECONCILIATION_CLAIMED";
            case RunnerJobOperation.Complete ignored -> "RUNNER_JOB_TERMINAL_REPORTED";
            case RunnerJobOperation.Submit ignored -> "RUNNER_JOB_SUBMITTED";
            case RunnerJobOperation.ContinuationUncertain ignored -> "RUNNER_JOB_CONTINUATION_UNCERTAIN";
            case RunnerJobOperation.ResolveContinuation ignored -> "RUNNER_JOB_CONTINUATION_RESOLVED";
            case RunnerJobOperation.WorkspaceStop ignored -> "WORKSPACE_STOP_REQUESTED";
            case RunnerJobOperation.WorkspaceStopped ignored -> "WORKSPACE_STOPPED";
            case RunnerJobOperation.WorkspaceRelease ignored -> "WORKSPACE_RELEASE_RESERVED";
            case RunnerJobOperation.WorkspaceReleased ignored -> "WORKSPACE_RELEASED";
            case RunnerJobOperation.WorkspacePlace ignored -> "WORKSPACE_PLACED";
        };
    }
    private static String runnerKey(String tenant, String runner) { return tenant.length() + ":" + tenant + runner; }

    public long controlWorkspace(SecurityContext actor, ExecutionKey key, long expectedRevision, RunnerJobOperation operation) {
        if (!actor.tenantId().equals(key.tenantId()) || !(operation instanceof RunnerJobOperation.WorkspaceStop
                || operation instanceof RunnerJobOperation.WorkspaceStopped || operation instanceof RunnerJobOperation.WorkspaceRelease
                || operation instanceof RunnerJobOperation.WorkspaceReleased || operation instanceof RunnerJobOperation.WorkspacePlace)) throw new IllegalArgumentException("invalid Workspace operation");
        try (var recorder = ExecutionRecorder.open(store, key, "workspace-control-" + UUID.randomUUID(), controlConfiguration.continuationLease(), expectedRevision)) {
            var stored = store.load(key).toCompletableFuture().join();
            String node = switch (operation) {
                case RunnerJobOperation.WorkspaceStop value -> value.workspaceNodeId();
                case RunnerJobOperation.WorkspaceStopped value -> value.workspaceNodeId();
                case RunnerJobOperation.WorkspaceRelease value -> value.workspaceNodeId();
                case RunnerJobOperation.WorkspaceReleased value -> value.workspaceNodeId();
                case RunnerJobOperation.WorkspacePlace value -> value.workspaceNodeId();
                default -> throw new IllegalArgumentException("invalid Workspace operation");
            };
            var origin = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().values().stream()
                    .filter(entry -> node.equals(entry.workspaceNodeId())).findFirst().orElseThrow().job().identity();
            recorder.applyRunner(operation, EventEnvelope.of(UUID.randomUUID(), key.tenantId(), eventType(operation), key.processInstanceId(),
                    origin.traversalId(), null, null, null, actor.requestId(), stored.graphVersionPin().reference(), clock.instant(),
                    OpaquePayload.of(RunnerJson.write(Map.of("scope", "WORKSPACE", "workspaceNodeId", node,
                            "actor", actor.qualifiedIdentity())), "application/json")));
            return recorder.revision();
        }
    }

    public void suspendAgent(NodeMessage message, ai.ravenroot.core.graph.GraphNode node) {
        var key = new ExecutionKey(message.tenantId(), message.processInstanceId());
        var state = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow(() -> new IllegalStateException("Workspace has not been opened"));
        var resource = state.workspaces().get(Objects.toString(node.properties().get("workspaceRef"), ""));
        if (resource == null || !resource.admitsAgent()) throw new IllegalStateException("referenced Workspace is unavailable or stopped");
        String name = Objects.toString(node.properties().get("agentDefinition"), "");
        long version = Long.parseLong(Objects.toString(node.properties().getOrDefault("agentVersion", "1")));
        var definition = definitions(key.tenantId()).stream().filter(value -> value.reference().name().equals(name)
                && value.reference().version() == version).findFirst().orElseThrow(() -> new IllegalArgumentException("Agent definition is not approved"));
        if (!resource.profile().allowedAgents().contains(name) || !resource.profile().runtimeProfile().equals(definition.runtimeProfile())) {
            throw new IllegalArgumentException("Agent definition is not authorized for the selected Workspace profile");
        }
        requireProfile(resource.profile());
        var registration = runners(key.tenantId()).stream().filter(value -> value.runnerId().equals(resource.runnerId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("pinned worker is not approved"));
        admit(message, definition, registration, resource, null);
    }

    public void suspendWorkspace(NodeMessage message, ai.ravenroot.core.graph.GraphNode node) {
        var key = new ExecutionKey(message.tenantId(), message.processInstanceId());
        String name = Objects.toString(node.properties().get("workspaceProfile"), "");
        long version = Long.parseLong(Objects.toString(node.properties().getOrDefault("workspaceVersion", "1")));
        var profile = store.runnerResources(key.tenantId()).toCompletableFuture().join().stream()
                .filter(value -> value.approved() && value.kind() == ai.ravenroot.api.runner.GovernedRunnerResource.Kind.WORKSPACE_PROFILE
                        && value.name().equals(name) && value.version() == version)
                .map(value -> ai.ravenroot.api.runner.RunnerCodec.workspaceProfile(value.document().bytes())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Workspace profile is not approved"));
        var declared = Map.of("workspaceScope", profile.workspaceScope().name(), "runtimeLifecycle", profile.runtimeLifecycle().name(),
                "runnerPool", profile.runnerPool(), "runtimeProfile", profile.runtimeProfile());
        declared.forEach((property, resolved) -> {
            String selected = Objects.toString(node.properties().get(property), "");
            if (!selected.isBlank() && !selected.equals(resolved)) throw new IllegalArgumentException("Workspace property disagrees with approved profile: " + property);
        });
        var state = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElse(null);
        var resource = state == null ? null : state.workspaces().get(node.id());
        RunnerRegistration registration;
        if (resource == null) {
            if (!message.command().name().equals("open")) throw new IllegalStateException("Workspace has not been opened");
            var retained = retainedWorkspaces(key.tenantId());
            UUID workspaceId = profile.workspaceScope() == ai.ravenroot.api.runner.WorkspaceProfile.Scope.NAMED
                    ? ai.ravenroot.api.runner.WorkspaceResource.namedWorkspaceId(key.tenantId(), profile.reference().name()) : UUID.randomUUID();
            var prior = retained.stream().flatMap(value -> value.workspaces().values().stream())
                    .filter(value -> value.workspaceId().equals(workspaceId))
                    .max(java.util.Comparator.comparingLong(ai.ravenroot.api.runner.WorkspaceResource::generation)).orElse(null);
            if (prior != null && !java.util.Set.of(ai.ravenroot.api.runner.WorkspaceResource.State.CLOSED,
                    ai.ravenroot.api.runner.WorkspaceResource.State.ABORTED, ai.ravenroot.api.runner.WorkspaceResource.State.RELEASED).contains(prior.state()))
                throw new IllegalStateException("named Workspace has an active or uncertain owner");
            String pinnedWorker = prior == null || prior.state() == ai.ravenroot.api.runner.WorkspaceResource.State.RELEASED ? null : prior.runnerId();
            registration = selectWorker(profile, retained, pinnedWorker).orElseGet(() -> {
                if (profile.capacity().admission() == ai.ravenroot.api.runner.WorkspaceProfile.Admission.REJECT)
                    throw new IllegalStateException("runner pool has no compatible available capacity");
                if (pinnedWorker != null) return runners(key.tenantId()).stream().filter(value -> value.runnerId().equals(pinnedWorker))
                        .findFirst().orElseThrow(() -> new IllegalStateException("named Workspace worker approval retired"));
                // No physical placement exists yet. Polling workers can atomically acquire this queued reservation.
                return new RunnerRegistration(1, key.tenantId(), "pending-" + profile.runnerPool().substring(0, Math.min(56, profile.runnerPool().length())),
                        "unassigned", java.util.Set.of(profile.runnerPool()), profile.policy());
            });
            resource = new ai.ravenroot.api.runner.WorkspaceResource(node.id(), workspaceId, profile, registration.runnerId(),
                    ai.ravenroot.api.runner.WorkspaceResource.State.UNMATERIALIZED, null, null, false, clock.instant(),
                    prior == null ? 1 : Math.addExact(prior.generation(), 1));
        } else {
            if (!profile.equals(resource.profile())) throw new IllegalStateException("Workspace profile is pinned until explicit migration");
            String runner = resource.runnerId();
            registration = runners(key.tenantId()).stream().filter(value -> value.runnerId().equals(runner)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("pinned worker is not approved"));
        }
        var commands = new java.util.LinkedHashMap<String, ai.ravenroot.api.runner.AgentCommand>();
        Map.of("open", "ready", "inspect", "inspected", "checkpoint", "checkpointed", "close", "closed", "abort", "aborted")
                .forEach((command, outcome) -> commands.put(command, new ai.ravenroot.api.runner.AgentCommand(command,
                        command.equals("inspect"), profile.policy(), java.util.Set.of(outcome, "blocked"))));
        var definition = new AgentDefinition(new AgentDefinition.Reference(key.tenantId(), "workspace-lifecycle", profile.reference().version()),
                "Apply the explicit Workspace lifecycle operation without model execution.", profile.runtimeProfile(), "none",
                commands, java.util.Set.of(), java.util.Set.of(profile.runnerPool()), profile.policy(), profile.retention(), "workspace-state");
        admit(message, definition, registration, resource, message.command().name());
    }

    private void requireProfile(ai.ravenroot.api.runner.WorkspaceProfile profile) {
        boolean approved = store.runnerResources(profile.reference().tenantId()).toCompletableFuture().join().stream().anyMatch(value ->
                value.approved() && value.kind() == ai.ravenroot.api.runner.GovernedRunnerResource.Kind.WORKSPACE_PROFILE
                        && value.name().equals(profile.reference().name()) && value.version() == profile.reference().version()
                        && profile.equals(ai.ravenroot.api.runner.RunnerCodec.workspaceProfile(value.document().bytes())));
        if (!approved) throw new IllegalArgumentException("Workspace profile approval retired");
    }
    java.util.List<RunnerWorkspaceState> retainedWorkspaces(String tenant) {
        var result = new java.util.ArrayList<RunnerWorkspaceState>();
        String cursor = null;
        do {
            var page = store.listProcessInstances(tenant, ai.ravenroot.api.persistence.ProcessInventoryQuery.everything(store.maxInventoryPageSize()).after(cursor))
                    .toCompletableFuture().join();
            for (var process : page.items()) store.loadRunnerWorkspace(process.key()).toCompletableFuture().join().ifPresent(result::add);
            cursor = page.nextCursor().orElse(null);
        } while (cursor != null);
        return java.util.List.copyOf(result);
    }
    private java.util.Optional<RunnerRegistration> selectWorker(ai.ravenroot.api.runner.WorkspaceProfile profile,
                                                               java.util.List<RunnerWorkspaceState> fleet, String pinned) {
        var availability = store.runnerAvailability(profile.reference().tenantId()).toCompletableFuture().join().stream()
                .filter(value -> value.live(clock.instant()) && value.runtimeProfiles().contains(profile.runtimeProfile()))
                .collect(Collectors.toMap(ai.ravenroot.api.runner.RunnerAvailability::runnerId, value -> value));
        java.util.function.ToDoubleFunction<RunnerRegistration> load = worker -> {
            var liveWorker = availability.get(worker.runnerId());
            long reserved = fleet.stream().flatMap(value -> value.jobs().values().stream())
                    .filter(value -> value.job().runner().runnerId().equals(worker.runnerId()) && value.job().retainsWorkspace()).count();
            return (double) Math.max(reserved, liveWorker.activeJobs()) / liveWorker.capacity();
        };
        return runners(profile.reference().tenantId()).stream().filter(value -> value.labels().contains(profile.runnerPool())
                        && value.capabilities().capabilities().contains(ai.ravenroot.api.runner.RunnerPolicy.Capability.WORKSPACE_READ)
                        && availability.containsKey(value.runnerId()) && (pinned == null || value.runnerId().equals(pinned)))
                .filter(value -> profile.capacity().admission() != ai.ravenroot.api.runner.WorkspaceProfile.Admission.REJECT || load.applyAsDouble(value) < 1)
                .min(java.util.Comparator.comparingDouble(load).thenComparing(RunnerRegistration::runnerId));
    }
    void placeOpeningWorkspaces(SecurityContext actor) {
        var fleet = retainedWorkspaces(actor.tenantId());
        for (var state : fleet) for (var resource : state.workspaces().values()) {
            if (resource.state() != ai.ravenroot.api.runner.WorkspaceResource.State.OPENING || resource.stopRequested()
                    || resource.profile().workspaceScope() == ai.ravenroot.api.runner.WorkspaceProfile.Scope.NAMED && resource.generation() > 1
                    || state.jobs().values().stream().filter(value -> resource.nodeId().equals(value.workspaceNodeId()))
                        .anyMatch(value -> value.job().state() != ai.ravenroot.api.runner.RunnerJob.State.QUEUED)) continue;
            var selected = selectWorker(resource.profile(), fleet, null).orElse(null);
            if (selected == null || !selected.runnerId().equals(actor.subject()) || selected.runnerId().equals(resource.runnerId())) continue;
            var availability = store.runnerAvailability(actor.tenantId()).toCompletableFuture().join().stream()
                    .filter(value -> value.runnerId().equals(selected.runnerId())).findFirst().orElseThrow();
            try {
                var revision = store.load(state.execution()).toCompletableFuture().join().revision();
                controlWorkspace(actor, state.execution(), revision, new RunnerJobOperation.WorkspacePlace(UUID.randomUUID(), resource.nodeId(), selected, availability.sessionId()));
            } catch (RuntimeException racedOrFull) {
                // A competing graph/worker transaction won, or a fleet ceiling is full. Durable work remains queued.
            }
        }
    }

    private void admit(NodeMessage message, AgentDefinition definition, RunnerRegistration registration,
                       ai.ravenroot.api.runner.WorkspaceResource resource, String lifecycleCommand) {
        var binding = live.get(message.attemptId());
        if (binding == null) throw new IllegalStateException("Workspace execution requires a persisted traversal");
        var key = new ExecutionKey(message.tenantId(), message.processInstanceId());
        if (!ai.ravenroot.core.process.ProcessLifecycleService.admitsRunnerDelivery(store, key, binding.recorder().revision())) {
            throw new IllegalStateException("process lifecycle does not admit Workspace work");
        }
        var ceiling = policies.get(key.tenantId());
        if (ceiling == null) throw new IllegalArgumentException("tenant has no Workspace authority");
        var policy = ceiling.intersect(resource.profile().policy());
        UUID id = UUID.nameUUIDFromBytes(("ravenroot.runner-job.v1:" + message.attemptId()).getBytes(StandardCharsets.UTF_8));
        var identity = new RunnerJobIdentity(key, message.traversalId(), message.invocationId(), message.attemptId(), id);
        byte[] input = PayloadJson.write(PayloadValue.fromJava(message.payload(), PayloadLimits.DEFAULTS)).getBytes(StandardCharsets.UTF_8);
        byte[] checkpoint = binding.runner().humanTaskContinuation(message, 1, RunnerContinuation.capture(message));
        var submit = new RunnerJobOperation.Submit(identity, definition, message.command().name(), policy, registration,
                OpaquePayload.of(input, "application/json"), clock.instant().plus(policy.limits().wallTime()), resource.workspaceId(),
                OpaquePayload.of(checkpoint, "application/vnd.ravenroot.runner-continuation.v1"), resource, lifecycleCommand);
        binding.recorder().suspendForRunner(submit, event(identity, message.security(), "RUNNER_JOB_SUBMITTED", 0,
                binding.recorder().graphVersionPin().reference(), binding.startedEventId()));
        throw new RunnerJobSuspension(id);
    }
    private record Binding(ExecutionRecorder recorder, GraphRunner runner, UUID startedEventId) { }
}
