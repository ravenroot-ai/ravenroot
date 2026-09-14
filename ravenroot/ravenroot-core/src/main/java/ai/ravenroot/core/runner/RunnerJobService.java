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
    public RunnerTelemetry.Relay telemetry() { return telemetry; }

    public RunnerJobService(ExecutionStore store, Clock clock, Collection<AgentDefinition> definitions,
                            Collection<RunnerRegistration> runners, Map<String, RunnerPolicy> policies) {
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
    }

    private void seed(ai.ravenroot.api.runner.GovernedRunnerResource resource) {
        var existing = store.runnerResources(resource.tenantId()).toCompletableFuture().join().stream()
                .filter(value -> value.key().equals(resource.key())).findFirst().orElse(null);
        if (existing == null) store.saveRunnerResource(resource, 0).toCompletableFuture().join();
        else if (!existing.document().equals(resource.document())) {
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

    /** Resolves every grant from the deployment catalog and atomically parks the exact attempt. */
    public void suspend(NodeMessage message, String name, long version, String runnerId) {
        Binding binding = live.get(message.attemptId());
        if (binding == null) throw new IllegalStateException("workspace-agent requires a persisted traversal");
        var key = new ExecutionKey(message.security().tenantId(), message.processInstanceId());
        if (!ai.ravenroot.core.process.ProcessLifecycleService.admitsRunnerDelivery(store, key, binding.recorder().revision())) {
            throw new IllegalStateException("process lifecycle does not admit runner work");
        }
        var resources = store.runnerResources(key.tenantId()).toCompletableFuture().join();
        var definition = resources.stream().filter(value -> value.approved()
                && value.kind() == ai.ravenroot.api.runner.GovernedRunnerResource.Kind.AGENT_DEFINITION
                && value.name().equals(name) && value.version() == version)
                .map(value -> ai.ravenroot.api.runner.RunnerCodec.definition(value.document().bytes())).findFirst().orElse(null);
        var registration = resources.stream().filter(value -> value.approved()
                && value.kind() == ai.ravenroot.api.runner.GovernedRunnerResource.Kind.RUNNER && value.name().equals(runnerId))
                .map(value -> ai.ravenroot.api.runner.RunnerCodec.registration(value.document().bytes())).findFirst().orElse(null);
        var policy = policies.get(key.tenantId());
        if (definition == null || registration == null || policy == null) {
            throw new IllegalArgumentException("agent definition or runner is not approved for this tenant");
        }
        var workspace = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElse(null);
        UUID workspaceId = workspace == null ? UUID.randomUUID() : workspace.workspaceId();
        UUID jobId = UUID.nameUUIDFromBytes(("ravenroot.runner-job.v1:" + message.attemptId()).getBytes(StandardCharsets.UTF_8));
        var identity = new RunnerJobIdentity(key, message.traversalId(), message.invocationId(), message.attemptId(), jobId);
        byte[] input = PayloadJson.write(PayloadValue.fromJava(message.payload(), PayloadLimits.DEFAULTS))
                .getBytes(StandardCharsets.UTF_8);
        byte[] checkpoint = binding.runner().humanTaskContinuation(message, 1, RunnerContinuation.capture(message));
        var submit = new RunnerJobOperation.Submit(identity, definition, message.command().name(), policy, registration,
                OpaquePayload.of(input, "application/json"), clock.instant().plus(policy.limits().wallTime()), workspaceId,
                OpaquePayload.of(checkpoint, "application/vnd.ravenroot.runner-continuation.v1"));
        var candidate = ai.ravenroot.api.runner.RunnerJob.accept(identity, definition, message.command().name(),
                policy, registration, submit.input(), clock.instant(), submit.deadline());
        if (ai.ravenroot.api.runner.RunnerCodec.assignment(new ai.ravenroot.api.runner.RunnerAssignment(
                1, workspaceId, candidate)).length > 1_000_000) {
            throw new IllegalArgumentException("runner assignment exceeds protocol envelope quota");
        }
        binding.recorder().suspendForRunner(submit,
                event(identity, message.security(), "RUNNER_JOB_SUBMITTED", 0,
                        binding.recorder().graphVersionPin().reference(), binding.startedEventId()));
        throw new RunnerJobSuspension(jobId);
    }

    /** Mutations are serialized by the existing process fence; the runner job adds its own report fence. */
    public RunnerWorkspaceState mutate(SecurityContext actor, ExecutionKey key, RunnerJobOperation operation) {
        if (!actor.tenantId().equals(key.tenantId()) || operation instanceof RunnerJobOperation.Submit) {
            throw new IllegalArgumentException("invalid runner operation scope");
        }
        var stored = store.load(key).toCompletableFuture().join();
        try (var recorder = ExecutionRecorder.open(store, key, "runner-control-" + UUID.randomUUID(),
                Duration.ofSeconds(30), stored.revision())) {
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
            return store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow();
        }
    }

    public Collection<AgentDefinition> definitions(String tenant) {
        return store.runnerResources(tenant).toCompletableFuture().join().stream()
                .filter(value -> value.approved() && value.kind() == ai.ravenroot.api.runner.GovernedRunnerResource.Kind.AGENT_DEFINITION)
                .map(value -> ai.ravenroot.api.runner.RunnerCodec.definition(value.document().bytes())).toList();
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
        if (!"workspace-agent".equals(node.behavior())) return false;
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
        };
    }
    private static String runnerKey(String tenant, String runner) { return tenant.length() + ":" + tenant + runner; }
    private record Binding(ExecutionRecorder recorder, GraphRunner runner, UUID startedEventId) { }
}
