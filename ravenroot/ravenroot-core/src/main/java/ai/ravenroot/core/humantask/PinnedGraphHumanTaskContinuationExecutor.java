package ai.ravenroot.core.humantask;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.DurableHumanTask;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionKey;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.StoredGraphDefinition;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphVersionKey;
import ai.ravenroot.core.graph.GraphVersionSnapshot;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.core.runtime.GraphRunner;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Re-enters from a settled human task against the immutable graph bytes pinned at registration. */
public final class PinnedGraphHumanTaskContinuationExecutor implements HumanTaskContinuationExecutor {
    private final GraphDefinitionStore definitions;
    private final ExecutionStore executions;
    private final HumanTaskService tasks;
    private final ExecutionEngine engine;
    private final BehaviorRegistry behaviors;
    private final ExecutionMonitor monitor;
    private final ExecutionIdentitySource identities;
    private final String workerId;
    private final Duration leaseTtl;
    private final Map<PendingWork.HandlerTrigger, ExecutionRecorder> awaitingAcknowledgement
            = new ConcurrentHashMap<>();

    public PinnedGraphHumanTaskContinuationExecutor(GraphDefinitionStore definitions,
                                                    ExecutionStore executions,
                                                    HumanTaskService tasks,
                                                    ExecutionEngine engine,
                                                    BehaviorRegistry behaviors,
                                                    ExecutionMonitor monitor,
                                                    ExecutionIdentitySource identities,
                                                    String workerId, Duration leaseTtl) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.behaviors = Objects.requireNonNull(behaviors, "behaviors");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
    }

    @Override
    public boolean supports(DurableHumanTask task) {
        GraphManager manager = null;
        try {
            manager = prepare(task).manager();
            return task.status().terminal();
        } catch (RuntimeException unavailable) {
            return false;
        } finally {
            if (manager != null) manager.close();
        }
    }

    @Override
    public CompletionStage<Void> execute(DurableHumanTask task, DurableHandler handler,
                                         PendingWork.HandlerTrigger claim) {
        if (!task.key().equals(claim.key()) || !task.request().taskId().equals(claim.workItemId())
                || !Objects.equals(handler.resumeTraversalId(), claim.traversalId())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "human-task continuation claim scope mismatch"));
        }
        try {
            Prepared prepared = prepare(task);
            GraphManager manager = prepared.manager();
            long revision = executions.load(claim.key()).toCompletableFuture().join().revision();
            ExecutionRecorder recorder = ExecutionRecorder.resumeClaimed(
                    executions, claim, workerId, leaseTtl, revision);
            var runner = new GraphRunner(manager, prepared.snapshot(), engine, behaviors, monitor, identities,
                    GraphRunner.DEFAULT_SHUTDOWN_BOUND);
            AutoCloseable binding;
            try {
                binding = tasks.bindLive(new ExecutionKey(task.key().tenantId(),
                        task.key().processInstanceId()), recorder);
            } catch (RuntimeException failure) {
                runner.close();
                recorder.detachForAcknowledgement();
                manager.close();
                throw failure;
            }
            CompletionStage<Void> result = runner.executeAfterHumanTask(task.request().requester(),
                    task.key().processInstanceId(), claim.traversalId(), task.request().nodeId(),
                    task.request().graphVersionPin().reference(), recorder, result(task, handler));
            return result.whenComplete((ignored, failure) -> {
                try {
                    close(binding);
                } finally {
                    runner.close();
                    recorder.detachForAcknowledgement();
                    if (failure == null) {
                        ExecutionRecorder existing = awaitingAcknowledgement.putIfAbsent(claim, recorder);
                        if (existing != null) {
                            recorder.close();
                            throw new IllegalStateException(
                                    "duplicate human-task continuation awaiting acknowledgement");
                        }
                    }
                    manager.close();
                }
            });
        } catch (CompletionException wrapped) {
            return CompletableFuture.failedFuture(wrapped.getCause());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public void afterAcknowledged(PendingWork.HandlerTrigger claim) {
        ExecutionRecorder recorder = awaitingAcknowledgement.remove(claim);
        if (recorder != null) recorder.close();
    }

    private NodeResult result(DurableHumanTask task, DurableHandler handler) {
        var body = new LinkedHashMap<String, Object>();
        body.put("taskId", task.request().taskId().toString());
        body.put("generation", task.generation());
        body.put("disposition", task.status().name());
        body.put("schema", task.request().responseSchema().schema());
        body.put("schemaVersion", task.request().responseSchema().schemaVersion());
        if (task.status() == HumanTaskStatus.RESOLVED) {
            PayloadEnvelope envelope = PayloadJson.readEnvelope(handler.outcomePayload().bytes(),
                    new PayloadLimits(task.request().responseSchema().maxBytes(),
                            32, 1024, 4096, 16 * 1024, 256));
            body.put("response", envelope.toJava());
        }
        return new NodeResult(task.request().reentryMapping().outcomeFor(task.status()),
                Map.copyOf(body), Map.of());
    }

    private Prepared prepare(DurableHumanTask task) {
        StoredGraphDefinition stored = definitions.load(new GraphDefinitionKey(task.key().tenantId(),
                new GraphContentId(task.request().graphVersionPin().reference())))
                .toCompletableFuture().join();
        GraphManager manager = GraphManager.readGraphMl(new ByteArrayInputStream(stored.canonical().bytes()));
        try {
            GraphVersionSnapshot snapshot = GraphVersionSnapshot.create(
                    new GraphVersionKey(stored.identity().graphId(), stored.identity().versionId()),
                    manager.definition());
            manager.definition().node(task.request().nodeId());
            return new Prepared(manager, snapshot);
        } catch (RuntimeException failure) {
            manager.close();
            throw failure;
        }
    }

    private static void close(AutoCloseable value) {
        try {
            value.close();
        } catch (Exception failure) {
            throw new IllegalStateException("failed to release human-task continuation binding", failure);
        }
    }

    private record Prepared(GraphManager manager, GraphVersionSnapshot snapshot) { }
}
