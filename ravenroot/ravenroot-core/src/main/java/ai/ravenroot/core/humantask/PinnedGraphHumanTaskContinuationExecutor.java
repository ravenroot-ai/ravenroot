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
import ai.ravenroot.core.approval.ToolApprovalService;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphVersionKey;
import ai.ravenroot.core.graph.GraphVersionSnapshot;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.core.runtime.GraphRunner;
import ai.ravenroot.core.security.nodepackage.DurableToolApprovalSuspension;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;

/** Re-enters from a settled human task against the immutable graph bytes pinned at registration. */
public final class PinnedGraphHumanTaskContinuationExecutor implements HumanTaskContinuationExecutor {
    private static final java.util.concurrent.Executor CLEANUP_EXECUTOR = ForkJoinPool.commonPool();
    private final GraphDefinitionStore definitions;
    private final ExecutionStore executions;
    private final HumanTaskService tasks;
    private final ToolApprovalService approvals;
    private final ExecutionEngine engine;
    private final BehaviorRegistry behaviors;
    private final ExecutionMonitor monitor;
    private final ExecutionIdentitySource identities;
    private final String workerId;
    private final Duration leaseTtl;
    private final ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets;
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
        this(definitions, executions, tasks, null, engine, behaviors, monitor, identities,
                workerId, leaseTtl, null);
    }

    /**
     * Creates a continuation executor that can hand off to either durable decision service.
     *
     * <p>The tool-approval service is additive so embedders using the earlier constructor retain
     * source compatibility. Production supplies both services and therefore permits a resumed
     * human task to suspend safely on either another human task or a tool approval.</p>
     */
    public PinnedGraphHumanTaskContinuationExecutor(GraphDefinitionStore definitions,
                                                    ExecutionStore executions,
                                                    HumanTaskService tasks,
                                                    ToolApprovalService approvals,
                                                    ExecutionEngine engine,
                                                    BehaviorRegistry behaviors,
                                                    ExecutionMonitor monitor,
                                                    ExecutionIdentitySource identities,
                                                    String workerId, Duration leaseTtl) {
        this(definitions, executions, tasks, approvals, engine, behaviors, monitor, identities,
                workerId, leaseTtl, null);
    }

    /**
     * Creates a continuation executor with durable decisions and finite agent resources.
     * @param definitions pinned graph-definition store
     * @param executions durable execution store
     * @param tasks durable human-task service
     * @param approvals durable tool-approval service, or {@code null} when unavailable
     * @param engine execution engine used for resumed traversal work
     * @param behaviors trusted behavior registry
     * @param monitor execution event monitor
     * @param identities trusted execution identity source
     * @param workerId recovery worker identity
     * @param leaseTtl claimed execution lease duration
     * @param agentBudgets finite agent authority mediator, or {@code null} when unavailable
     */
    public PinnedGraphHumanTaskContinuationExecutor(GraphDefinitionStore definitions,
                                                    ExecutionStore executions,
                                                    HumanTaskService tasks,
                                                    ToolApprovalService approvals,
                                                    ExecutionEngine engine,
                                                    BehaviorRegistry behaviors,
                                                    ExecutionMonitor monitor,
                                                    ExecutionIdentitySource identities,
                                                    String workerId, Duration leaseTtl,
                                                    ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService
                                                            agentBudgets) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.approvals = approvals;
        this.engine = Objects.requireNonNull(engine, "engine");
        this.behaviors = Objects.requireNonNull(behaviors, "behaviors");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
        this.agentBudgets = agentBudgets;
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
                binding = bindLive(task.key(), recorder);
            } catch (RuntimeException failure) {
                runner.close();
                recorder.detachForAcknowledgement();
                manager.close();
                throw failure;
            }
            CompletionStage<Void> result = runner.executeAfterHumanTask(task.request().requester(),
                    task.key().processInstanceId(), claim.traversalId(), task.request().nodeId(),
                    task.request().graphVersionPin().reference(), recorder, result(task, handler));
            CompletionStage<Void> handoff = result.handle((ignored, failure) -> {
                Throwable cause = unwrap(failure);
                if (agentBudgets != null && (cause == null
                        || !(cause instanceof DurableHumanTaskSuspension
                        || cause instanceof DurableToolApprovalSuspension))) {
                    agentBudgets.finishProcess(task.key(), failure == null);
                }
                if (cause == null || cause instanceof DurableHumanTaskSuspension
                        || cause instanceof DurableToolApprovalSuspension) return null;
                throw new CompletionException(cause);
            });
            // Pekko may complete on the node's actor-dispatcher thread. Runner shutdown waits for
            // that node, so cleanup must move off the completion thread to avoid waiting on itself.
            return handoff.whenCompleteAsync((ignored, failure) -> {
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
            }, CLEANUP_EXECUTOR);
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

    private AutoCloseable bindLive(ExecutionKey key, ExecutionRecorder recorder) {
        AutoCloseable taskBinding = tasks.bindLive(key, recorder);
        AutoCloseable approvalBinding = null;
        AutoCloseable budgetBinding = null;
        try {
            approvalBinding = approvals == null ? null : approvals.bindLive(key, recorder);
            budgetBinding = agentBudgets == null ? null : agentBudgets.bindLive(key, recorder);
            AutoCloseable finalApprovalBinding = approvalBinding;
            AutoCloseable finalBudgetBinding = budgetBinding;
            return () -> {
                try {
                    if (finalBudgetBinding != null) close(finalBudgetBinding);
                } finally {
                    try {
                        if (finalApprovalBinding != null) close(finalApprovalBinding);
                    } finally {
                        close(taskBinding);
                    }
                }
            };
        } catch (RuntimeException failure) {
            try {
                if (budgetBinding != null) close(budgetBinding);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                if (approvalBinding != null) close(approvalBinding);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try { close(taskBinding); } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record Prepared(GraphManager manager, GraphVersionSnapshot snapshot) { }
}
