package ai.ravenroot.core.process;

import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import ai.ravenroot.api.persistence.IdempotencyWrite;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.core.humantask.HumanTaskService;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable, process-scoped lifecycle command authority over every contained traversal. */
public final class ProcessLifecycleService {
    private static final String CONTENT_TYPE = "application/vnd.ravenroot.process-lifecycle+json";
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(7);

    public enum Command { PAUSE, RESUME, CANCEL, DRAIN, STOP }
    public enum State { RUNNING, PAUSED, CANCELLED, DRAINING, STOPPED, RECOVERY_REQUIRED }
    public enum Code { APPLIED, REPLAYED, STALE_GENERATION, NOT_FOUND, TERMINAL, IDEMPOTENCY_CONFLICT,
        PARTIALLY_SETTLED }
    public record TraversalOutcome(UUID traversalId, String outcome) { }
    public record Result(Code code, UUID processInstanceId, long generation, State state,
                         List<TraversalOutcome> traversals, String reason) {
        public Result { traversals = List.copyOf(traversals); reason = reason == null ? "" : reason; }
    }

    private final ExecutionStore store;
    private final RavenrootApplication application;
    private final HumanTaskService humanTasks;
    private final Clock clock;
    private volatile java.util.function.BiConsumer<ExecutionKey, UUID> runnerDelivery = (key, job) -> { };

    /** Composes the same durable authority with runner delivery before serving process commands. */
    public void installRunnerDelivery(ExecutionStore runnerStore, java.util.function.BiConsumer<ExecutionKey, UUID> delivery) {
        if (runnerStore != store) throw new IllegalArgumentException("runner and lifecycle stores must be identical");
        runnerDelivery = Objects.requireNonNull(delivery, "delivery");
    }

    public ProcessLifecycleService(ExecutionStore store, RavenrootApplication application,
                                   HumanTaskService humanTasks, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.application = Objects.requireNonNull(application, "application");
        this.humanTasks = humanTasks;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result command(String tenantId, UUID processInstanceId, Command command,
                          long expectedGeneration, String idempotencyKey, String reason) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        Objects.requireNonNull(command, "command");
        if (expectedGeneration < 1) throw new IllegalArgumentException("expected generation must be positive");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("a bounded idempotency key is required");
        }
        reason = reason == null ? "" : reason.strip();
        if (reason.length() > 500) throw new IllegalArgumentException("reason is too long");
        var key = new ExecutionKey(tenantId, processInstanceId);
        var inventory = await(store.findProcessInstance(key)).orElse(null);
        if (inventory == null) return new Result(Code.NOT_FOUND, processInstanceId, 0,
                State.RUNNING, List.of(), reason);

        byte[] requestBytes = (processInstanceId + "|" + command + "|" + expectedGeneration + "|" + reason)
                .getBytes(StandardCharsets.UTF_8);
        OpaquePayload fingerprint = OpaquePayload.of(
                ai.ravenroot.api.persistence.ToolApprovalRegistration.digest(requestBytes)
                        .getBytes(StandardCharsets.UTF_8), "text/plain");
        OpaquePayload outcome = payload(command, expectedGeneration + 1);
        UUID eventTraversalId = await(store.load(key)).state().traversals().keySet().stream()
                .findFirst().orElse(processInstanceId);
        boolean replay = await(store.lookupIdempotency(tenantId, idempotencyKey, clock.instant())).isPresent();
        if (!replay && inventory.status().terminal()) return new Result(Code.TERMINAL, processInstanceId,
                inventory.revision(), currentState(key), List.of(), reason);
        if (!replay && inventory.revision() != expectedGeneration) {
            return new Result(Code.STALE_GENERATION, processInstanceId, inventory.revision(),
                    currentState(key), List.of(), reason);
        }
        try {
            var builder = ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(expectedGeneration))
                    .apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessControlChanged(
                            ai.ravenroot.api.application.ProcessControlState.valueOf(state(command).name())))
                    .recordIdempotency(new IdempotencyWrite(idempotencyKey, fingerprint, outcome,
                            IDEMPOTENCY_RETENTION, clock.instant()))
                    .publish(EventEnvelope.of(UUID.randomUUID(), tenantId, "PROCESS_" + command.name(),
                            processInstanceId, eventTraversalId, null, null, null, idempotencyKey,
                            inventory.graphVersionPin().reference(), clock.instant(), outcome));
            if (command == Command.CANCEL && !replay) {
                var aggregate = await(store.load(key));
                // The process revision arbitrates completion/cancel races. Every terminal
                // transition, runner stop request and lifecycle event commits together.
                for (var traversal : aggregate.state().traversals().values()) {
                    if (traversal.status().terminal()) continue;
                    for (var invocation : traversal.invocations().values()) {
                        if (invocation.status().terminal()) continue;
                        for (var attempt : invocation.attempts()) if (!attempt.status().terminal()) {
                            builder.apply(new ai.ravenroot.api.persistence.ExecutionTransition.AttemptTransitioned(
                                    traversal.traversalId(), invocation.invocationId(), attempt.attemptId(),
                                    ai.ravenroot.api.application.NodeAttemptStatus.FAILED));
                        }
                        builder.apply(new ai.ravenroot.api.persistence.ExecutionTransition.InvocationTransitioned(
                                traversal.traversalId(), invocation.invocationId(), ai.ravenroot.api.application.NodeInvocationStatus.FAILED));
                    }
                    builder.apply(new ai.ravenroot.api.persistence.ExecutionTransition.TraversalTransitioned(
                            traversal.traversalId(), ai.ravenroot.api.application.TraversalStatus.FAILED,
                            ai.ravenroot.api.application.ExecutionTerminationReason.CANCELLED));
                }
                builder.apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessTransitioned(
                        ai.ravenroot.api.application.ProcessInstanceStatus.FAILED,
                        ai.ravenroot.api.application.ExecutionTerminationReason.CANCELLED));
                if (store.supports(ai.ravenroot.api.persistence.StoreCapability.RUNNER_JOBS)) {
                    var workspace = await(store.loadRunnerWorkspace(key)).orElse(null);
                    if (workspace != null) for (var entry : workspace.jobs().values()) {
                        var id = entry.job().identity();
                        builder.publish(EventEnvelope.of(UUID.randomUUID(), tenantId, "RUNNER_JOB_PROCESS_CANCELLED",
                                processInstanceId, id.traversalId(), id.invocationId(), id.attemptId(), null,
                                idempotencyKey, inventory.graphVersionPin().reference(), clock.instant(),
                                OpaquePayload.of(ai.ravenroot.core.runner.RunnerJson.write(java.util.Map.of(
                                        "runnerJobId", id.runnerJobId().toString(), "fence", entry.job().fence(),
                                        "actor", "process-lifecycle", "reason", reason)),
                                        "application/vnd.ravenroot.runner-event.v1+json")));
                    }
                }
            }
            if (command == Command.STOP && !replay && store.supports(ai.ravenroot.api.persistence.StoreCapability.RUNNER_JOBS)) {
                var workspace = await(store.loadRunnerWorkspace(key)).orElse(null);
                if (workspace != null) for (String node : workspace.workspaces().keySet()) {
                    builder.runner(new ai.ravenroot.api.runner.RunnerJobOperation.WorkspaceStop(UUID.randomUUID(), node));
                }
            }
            var stored = await(store.apply(builder.build()));
            State effective = currentState(key);
            if (effective != state(command)) return new Result(Code.REPLAYED, processInstanceId,
                    await(store.load(key)).revision(), effective, List.of(), reason);
            var traversalOutcomes = settle(tenantId, processInstanceId, command, idempotencyKey);
            boolean partial = traversalOutcomes.stream().anyMatch(value -> value.outcome().startsWith("NOT_"));
            return new Result(partial ? Code.PARTIALLY_SETTLED : replay ? Code.REPLAYED : Code.APPLIED,
                    processInstanceId, stored.revision(), state(command), traversalOutcomes, reason);
        } catch (ExecutionStoreException failure) {
            if (failure.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict) {
                var current = await(store.findProcessInstance(key)).orElse(inventory);
                return new Result(Code.STALE_GENERATION, processInstanceId, current.revision(),
                        currentState(key), List.of(), reason);
            }
            if (failure.failure() instanceof ExecutionStoreFailure.IdempotencyConflict) {
                return new Result(Code.IDEMPOTENCY_CONFLICT, processInstanceId, inventory.revision(),
                        currentState(key), List.of(), reason);
            }
            throw failure;
        }
    }

    /** Durable admission reading used by asynchronous process re-entry. */
    public boolean admitsReentry(String tenantId, UUID processInstanceId) {
        var key = new ExecutionKey(tenantId, processInstanceId);
        var process = await(store.findProcessInstance(key)).orElse(null);
        if (process == null || process.status().terminal()) return false;
        State state = currentState(key);
        return state == State.RUNNING || state == State.DRAINING;
    }

    /** Runner re-entry reads aggregate authority and commits through the same observed revision. */
    public static boolean admitsRunnerDelivery(ExecutionStore store, ExecutionKey key, long revision) {
        var process = await(store.load(key));
        if (process.revision() != revision || process.state().status().terminal()) return false;
        State state = State.valueOf(process.state().controlState().name());
        return (state == State.RUNNING || state == State.DRAINING)
                && await(store.load(key)).revision() == revision;
    }

    public State currentState(ExecutionKey key) {
        return currentState(store, key);
    }

    private static State currentState(ExecutionStore store, ExecutionKey key) {
        return State.valueOf(await(store.load(key)).state().controlState().name());
    }

    private List<TraversalOutcome> settle(String tenantId, UUID processInstanceId, Command command,
                                          String correlationId) {
        var stored = await(store.load(new ExecutionKey(tenantId, processInstanceId)));
        var outcomes = new ArrayList<TraversalOutcome>();
        if (command == Command.CANCEL && humanTasks != null) {
            humanTasks.cancelProcessTasks(tenantId, processInstanceId, correlationId);
            stored = await(store.load(new ExecutionKey(tenantId, processInstanceId)));
        }
        var workspace = store.supports(ai.ravenroot.api.persistence.StoreCapability.RUNNER_JOBS)
                ? await(store.loadRunnerWorkspace(new ExecutionKey(tenantId, processInstanceId))).orElse(null) : null;
        for (var traversal : stored.state().traversals().values()) {
            if (command == Command.CANCEL) {
                application.cancelTraversal(tenantId, traversal.traversalId());
                outcomes.add(new TraversalOutcome(traversal.traversalId(), "CANCEL"));
                continue;
            }
            if (traversal.status().terminal()) continue;
            var parked = workspace == null ? List.<ai.ravenroot.api.runner.RunnerWorkspaceState.Entry>of()
                    : new ArrayList<>(workspace.jobs().values()).reversed().stream().filter(entry -> entry.job().identity().traversalId().equals(traversal.traversalId())
                            && traversal.status() == ai.ravenroot.api.application.TraversalStatus.WAITING).toList();
            if (!parked.isEmpty()) {
                if (command == Command.RESUME || command == Command.DRAIN) {
                    for (var entry : parked) if (entry.job().state().terminal() && !entry.continuationUncertain()) {
                        runnerDelivery.accept(workspace.execution(), entry.job().identity().runnerJobId());
                    }
                }
                outcomes.add(new TraversalOutcome(traversal.traversalId(), command.name()));
                continue;
            }
            boolean changed = switch (command) {
                case PAUSE -> application.pauseTraversal(traversal.traversalId());
                case RESUME -> application.resumeTraversal(tenantId, traversal.traversalId());
                case CANCEL -> application.cancelTraversal(tenantId, traversal.traversalId());
                case DRAIN, STOP -> true;
            };
            String value = changed ? command.name() : switch (command) {
                case PAUSE -> application.executionPaused(tenantId, traversal.traversalId())
                        ? "ALREADY_PAUSED" : "NOT_ACTIVE";
                case RESUME -> "NOT_PAUSED";
                case CANCEL -> traversal.status().terminal() ? "ALREADY_TERMINAL" : "NOT_ACTIVE";
                case DRAIN, STOP -> command.name();
            };
            outcomes.add(new TraversalOutcome(traversal.traversalId(), value));
        }
        if (command == Command.STOP) application.stopProcessInvocations(tenantId, processInstanceId);
        return outcomes;
    }

    private static State state(Command command) {
        return switch (command) {
            case PAUSE -> State.PAUSED;
            case RESUME -> State.RUNNING;
            case CANCEL -> State.CANCELLED;
            case DRAIN -> State.DRAINING;
            case STOP -> State.STOPPED;
        };
    }

    private static OpaquePayload payload(Command command, long generation) {
        return OpaquePayload.of(("{\"generation\":" + generation + ",\"state\":\"" + state(command)
                + "\"}").getBytes(StandardCharsets.UTF_8), CONTENT_TYPE);
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        try { return stage.toCompletableFuture().join(); }
        catch (java.util.concurrent.CompletionException failed) {
            if (failed.getCause() instanceof ExecutionStoreException storeFailure) throw storeFailure;
            throw failed;
        }
    }
}
