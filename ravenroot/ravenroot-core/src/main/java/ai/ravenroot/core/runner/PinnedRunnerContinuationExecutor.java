package ai.ravenroot.core.runner;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionKey;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.runner.RunnerJob;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphVersionKey;
import ai.ravenroot.core.graph.GraphVersionSnapshot;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.core.runtime.GraphExecutionContinuationCheckpoint;
import ai.ravenroot.core.runtime.GraphExecutionLimits;
import ai.ravenroot.core.runtime.GraphRunner;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Rebuilds a parked runner continuation from its immutable graph pin and fenced terminal result. */
public final class PinnedRunnerContinuationExecutor implements AutoCloseable {
    private final RunnerJobService jobs;
    private final GraphDefinitionStore definitions;
    private final ExecutionEngine engine;
    private final BehaviorRegistry behaviors;
    private final ExecutionMonitor monitor;
    private final GraphExecutionLimits limits;
    private final ai.ravenroot.core.manifest.ExecutionManifestService manifests;
    private final ai.ravenroot.core.humantask.HumanTaskService humanTasks;
    private final ai.ravenroot.core.approval.ToolApprovalService approvals;
    private final ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets;
    private final java.util.concurrent.ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(
            4, 4, 0, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16),
            Thread.ofPlatform().daemon(true).name("runner-continuation-", 0).factory(),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final java.util.Set<ExecutionKey> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PinnedRunnerContinuationExecutor(RunnerJobService jobs, GraphDefinitionStore definitions,
                                            ExecutionEngine engine, BehaviorRegistry behaviors,
                                            ExecutionMonitor monitor, GraphExecutionLimits limits,
                                            ai.ravenroot.core.manifest.ExecutionManifestService manifests) {
        this(jobs, definitions, engine, behaviors, monitor, limits, manifests, null, null, null);
    }
    public PinnedRunnerContinuationExecutor(RunnerJobService jobs, GraphDefinitionStore definitions,
                                            ExecutionEngine engine, BehaviorRegistry behaviors,
                                            ExecutionMonitor monitor, GraphExecutionLimits limits,
                                            ai.ravenroot.core.manifest.ExecutionManifestService manifests,
                                            ai.ravenroot.core.humantask.HumanTaskService humanTasks,
                                            ai.ravenroot.core.approval.ToolApprovalService approvals,
                                            ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets) {
        this.jobs = jobs; this.definitions = definitions; this.engine = engine; this.behaviors = behaviors;
        this.monitor = monitor; this.limits = limits; this.manifests = manifests;
        this.humanTasks = humanTasks; this.approvals = approvals; this.agentBudgets = agentBudgets;
    }

    public CompletionStage<Void> resume(ExecutionKey key, UUID jobId) {
        if (!pending.add(key)) return CompletableFuture.completedFuture(null);
        try {
            return CompletableFuture.runAsync(() -> resumeOwned(key, jobId), executor)
                    .whenComplete((ignored, failure) -> pending.remove(key));
        } catch (RuntimeException refused) { pending.remove(key); return CompletableFuture.failedFuture(refused); }
    }

    private void resumeOwned(ExecutionKey key, UUID jobId) {
        var store = jobs.store();
        var stored = store.load(key).toCompletableFuture().join();
        try (var recorder = ExecutionRecorder.open(store, key, "runner-continuation-" + UUID.randomUUID(),
                Duration.ofSeconds(30), stored.revision())) {
            var workspace = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow();
            var entry = workspace.jobs().get(jobId);
            if (entry == null || !entry.job().state().terminal()) throw new IllegalStateException("runner result is not terminal");
            if (entry.continuationUncertain()) throw new IllegalStateException("runner successor delivery requires operator recovery");
            RunnerJob job = entry.job();
            var current = recorder.storedState().traversals().get(job.identity().traversalId());
            var invocation = current.invocations().get(job.identity().invocationId());
            var attempt = invocation.attempts().stream().filter(value -> value.attemptId().equals(job.identity().attemptId()))
                    .findFirst().orElseThrow();
            if (attempt.status().terminal()) {
                if (current.status().terminal()) return;
            }
            if (attempt.status() != NodeAttemptStatus.WAITING && attempt.status() != NodeAttemptStatus.RUNNING
                    && attempt.status() != NodeAttemptStatus.COMPLETED) {
                throw new IllegalStateException("runner continuation delivery is uncertain; reconcile graph recovery");
            }
            var checkpoint = GraphExecutionContinuationCheckpoint.read(GraphExecutionContinuationCheckpoint.VERSION,
                    entry.continuation().bytes());
            if (checkpoint.innerVersion() != 1) throw new IllegalArgumentException("unsupported runner continuation version");
            var context = RunnerContinuation.decode(checkpoint.inner());
            if (!context.security().tenantId().equals(key.tenantId())) throw new IllegalArgumentException("runner continuation scope mismatch");
            var graph = definitions.load(new GraphDefinitionKey(key.tenantId(),
                    new GraphContentId(stored.graphVersionPin().reference()))).toCompletableFuture().join();
            var policy = manifests == null ? null : manifests.graphPolicyForParsing(key,
                    ai.ravenroot.api.application.ExecutionPolicy.STANDARD);
            var pinnedLimits = policy == null ? limits : ai.ravenroot.core.manifest.ExecutionManifestResolver.graphExecutionLimits(policy);
            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(graph.canonical().bytes()), pinnedLimits.graphMl())) {
                var children = current.invocations().values().stream()
                        .filter(value -> value.parentInvocationIds().contains(invocation.invocationId()))
                        .map(ai.ravenroot.api.application.NodeInvocation::nodeId).collect(java.util.stream.Collectors.toSet());
                if (!children.isEmpty()) {
                    String outcome = job.state() == RunnerJob.State.COMPLETED ? job.result().outcome() : "blocked";
                    var edges = manager.definition().nextEdges(invocation.nodeId(), outcome);
                    if (edges.isEmpty() && !outcome.equals("continue")) edges = manager.definition().nextEdges(invocation.nodeId(), "continue");
                    var expected = edges.stream().map(ai.ravenroot.core.graph.GraphEdge::target).collect(java.util.stream.Collectors.toSet());
                    if (children.equals(expected)) return;
                    var id = job.identity();
                    var event = ai.ravenroot.api.persistence.EventEnvelope.of(UUID.randomUUID(), key.tenantId(),
                            "RUNNER_JOB_CONTINUATION_UNCERTAIN", key.processInstanceId(), id.traversalId(), id.invocationId(),
                            id.attemptId(), terminalEventId(job), "runner-recovery", stored.graphVersionPin().reference(), job.updatedAt(),
                            ai.ravenroot.api.persistence.OpaquePayload.of(RunnerJson.write(java.util.Map.of(
                                    "runnerJobId", jobId.toString(), "fence", job.fence(), "actor", "runner-recovery",
                                    "reason", "PARTIAL_SUCCESSOR_DISPATCH")), "application/vnd.ravenroot.runner-event.v1+json"));
                    recorder.applyRunner(new ai.ravenroot.api.runner.RunnerJobOperation.ContinuationUncertain(jobId), event);
                    throw new IllegalStateException("partial runner successor dispatch is parked for operator recovery");
                }
                var snapshot = GraphVersionSnapshot.create(new GraphVersionKey(graph.identity().graphId(), graph.identity().versionId()),
                        manager.definition());
                var operational = manifests == null ? null : manifests.resolvePolicyForNodes(key,
                        ai.ravenroot.api.application.ExecutionPolicy.STANDARD, manager.definition().nodes());
                try (var runner = new GraphRunner(manager, snapshot, engine, behaviors, monitor,
                        ExecutionIdentitySource.randomUuids(), Duration.ofSeconds(10), pinnedLimits,
                        invocation.nodeId(), operational)) {
                    Object payload = job.result() == null ? null : PayloadJson.read(job.result().payload().bytes(), PayloadLimits.DEFAULTS).toJava();
                    String outcome = job.state() == RunnerJob.State.COMPLETED ? job.result().outcome() : "blocked";
                    Runnable release = bindLive(key, recorder, runner);
                    try {
                        runner.executeAfterRunnerJob(context.security(), invocation.nodeId(), stored.graphVersionPin().reference(),
                                recorder, job, new NodeResult(outcome, payload, context.attributes()), checkpoint,
                                terminalEventId(job)).toCompletableFuture().join();
                    } catch (java.util.concurrent.CompletionException suspended) {
                        if (!(suspended.getCause() instanceof RunnerJobSuspension)
                                && !(suspended.getCause() instanceof ai.ravenroot.core.humantask.DurableHumanTaskSuspension)
                                && !(suspended.getCause() instanceof ai.ravenroot.core.security.nodepackage.DurableToolApprovalSuspension)) throw suspended;
                    } finally { release.run(); }
                }
            }
        }
    }

    static UUID terminalEventId(RunnerJob job) {
        return job.result() == null ? null : RunnerJobService.terminalEventId(job.identity().runnerJobId(), job.fence());
    }
    private Runnable bindLive(ExecutionKey key, ExecutionRecorder recorder, GraphRunner runner) {
        var bindings = new java.util.ArrayList<AutoCloseable>();
        Runnable release = () -> {
            RuntimeException failure = null;
            for (var binding : bindings.reversed()) try { binding.close(); }
            catch (Exception refused) {
                if (failure == null) failure = new IllegalStateException("runner continuation binding did not close", refused);
                else failure.addSuppressed(refused);
            }
            if (failure != null) throw failure;
        };
        try {
            if (humanTasks != null) bindings.add(humanTasks.bindLive(key, recorder, runner));
            if (approvals != null) bindings.add(approvals.bindLive(key, recorder, runner::continuationBudget));
            if (agentBudgets != null) bindings.add(agentBudgets.bindLive(key, recorder));
            return release;
        } catch (RuntimeException failure) { release.run(); throw failure; }
    }
    @Override public void close() { executor.shutdownNow(); }
}
