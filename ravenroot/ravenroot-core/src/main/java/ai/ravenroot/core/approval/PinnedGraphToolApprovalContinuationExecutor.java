package ai.ravenroot.core.approval;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.ToolCallContinuationInput;
import ai.ravenroot.api.persistence.DurableToolApproval;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionKey;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.StoredGraphDefinition;
import ai.ravenroot.api.persistence.ToolApprovalStatus;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphVersionKey;
import ai.ravenroot.core.graph.GraphVersionSnapshot;
import ai.ravenroot.core.humantask.DurableHumanTaskSuspension;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.core.runtime.GraphRunner;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;

/** Production trusted re-entry against the immutable graph bytes pinned by the approval. */
public final class PinnedGraphToolApprovalContinuationExecutor
        implements ToolApprovalContinuationExecutor {
    private static final java.util.concurrent.Executor CLEANUP_EXECUTOR = ForkJoinPool.commonPool();
    private final GraphDefinitionStore definitions;
    private final ExecutionStore executions;
    private final ToolApprovalService approvals;
    private final HumanTaskService humanTasks;
    private final ExecutionEngine engine;
    private final BehaviorRegistry behaviors;
    private final ExecutionMonitor monitor;
    private final ExecutionIdentitySource identities;
    private final String workerId;
    private final Duration leaseTtl;
    private final Map<PendingWork.HandlerTrigger, ExecutionRecorder> awaitingAcknowledgement
            = new ConcurrentHashMap<>();

    public PinnedGraphToolApprovalContinuationExecutor(GraphDefinitionStore definitions,
                                                       ExecutionStore executions,
                                                       ToolApprovalService approvals,
                                                       ExecutionEngine engine,
                                                       BehaviorRegistry behaviors,
                                                       ExecutionMonitor monitor,
                                                       ExecutionIdentitySource identities,
                                                       String workerId, Duration leaseTtl) {
        this(definitions, executions, approvals, null, engine, behaviors, monitor, identities,
                workerId, leaseTtl);
    }

    /**
     * Creates a continuation executor that can hand off to either durable decision service.
     *
     * <p>The human-task service is additive so embedders using the earlier constructor retain
     * source compatibility.</p>
     */
    public PinnedGraphToolApprovalContinuationExecutor(GraphDefinitionStore definitions,
                                                       ExecutionStore executions,
                                                       ToolApprovalService approvals,
                                                       HumanTaskService humanTasks,
                                                       ExecutionEngine engine,
                                                       BehaviorRegistry behaviors,
                                                       ExecutionMonitor monitor,
                                                       ExecutionIdentitySource identities,
                                                       String workerId, Duration leaseTtl) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.humanTasks = humanTasks;
        this.engine = Objects.requireNonNull(engine, "engine");
        this.behaviors = Objects.requireNonNull(behaviors, "behaviors");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
    }

    @Override
    public boolean supports(DurableToolApproval approval) {
        GraphManager manager = null;
        try {
            Prepared prepared = prepare(approval.request().requester().tenantId(),
                    approval.request().graphVersionPin().reference(), approval.request().nodeId());
            manager = prepared.manager();
            var request = approval.request();
            NodeMessage original = new NodeMessage(request.requester(), approval.key().processInstanceId(),
                    request.traversalId(), request.invocationId(), request.attemptId(), Set.of(),
                    request.nodeId(), null, Map.of(), NodeCommand.PROCESS);
            prepared.action().validate(new ToolCallContinuationInput(original, request.approvalId(),
                    request.traversalId(), request.invocationId(), request.attemptId(), request.tool(),
                    request.canonicalArguments(), request.argumentsDigest(),
                    decision(approval.status()), request.continuationVersion(),
                    request.continuation(), request.continuationDigest()));
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        } finally {
            if (manager != null) manager.close();
        }
    }

    @Override
    public CompletionStage<Boolean> execute(ToolApprovalContinuation continuation,
                                            PendingWork.HandlerTrigger claim) {
        if (!claim.key().processInstanceId().equals(continuation.processInstanceId())
                || !claim.traversalId().equals(continuation.resumeTraversalId())) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("continuation claim scope mismatch"));
        }
        try {
            Prepared prepared = prepare(continuation.requester().tenantId(),
                    continuation.graphVersionPin().reference(), continuation.nodeId());
            GraphManager manager = prepared.manager();
            long revision = executions.load(claim.key()).toCompletableFuture().join().revision();
            DurableToolApproval storedApproval = executions.loadToolApproval(
                    claim.key(), continuation.approvalId()).toCompletableFuture().join()
                    .filter(candidate -> candidate.status() == continuation.decision())
                    .orElseThrow(() -> new IllegalStateException(
                            "tool approval decision changed before claimed re-entry"));
            boolean approvedEffect = storedApproval.status() == ToolApprovalStatus.CONSUMED;
            ExecutionRecorder recorder = ExecutionRecorder.resumeClaimed(
                    executions, claim, workerId, leaseTtl, revision);
            var runner = new GraphRunner(manager, prepared.snapshot(), engine, behaviors, monitor, identities,
                    GraphRunner.DEFAULT_SHUTDOWN_BOUND);
            AutoCloseable approvalBinding;
            try {
                approvalBinding = bindLive(new ExecutionKey(
                        continuation.requester().tenantId(), continuation.processInstanceId()), recorder);
            } catch (RuntimeException bindingFailure) {
                runner.close();
                recorder.detachForAcknowledgement();
                manager.close();
                throw bindingFailure;
            }
            CompletionStage<Boolean> result = runner.executeFrom(continuation.requester(),
                    continuation.processInstanceId(), continuation.resumeTraversalId(),
                    continuation.nodeId(), continuation.graphVersionPin().reference(), recorder,
                    message -> new ToolCallContinuationInput(message, continuation.approvalId(),
                            continuation.originalTraversalId(), continuation.originalInvocationId(),
                            continuation.originalAttemptId(), continuation.tool(),
                            continuation.canonicalArguments(), continuation.argumentsDigest(),
                            decision(continuation.decision()), continuation.version(),
                            continuation.checkpoint(), continuation.checkpointDigest()),
                    succeeded -> {
                        if (approvedEffect) {
                            approvals.completeFenced(recorder, storedApproval, succeeded,
                                    continuation.approvalId().toString());
                        }
                    }, prepared.action());
            CompletionStage<Boolean> effectResult = result.handle((succeeded, failure) -> {
                DurableToolApproval current = executions.loadToolApproval(
                        claim.key(), continuation.approvalId()).toCompletableFuture().join()
                        .orElse(storedApproval);
                if (failure != null && isDurableSuspension(unwrap(failure))) {
                    if (current.status() == ToolApprovalStatus.SUCCEEDED) return true;
                    if (current.status() == ToolApprovalStatus.FAILED) return false;
                    return false;
                }
                if (current.status() == ToolApprovalStatus.SUCCEEDED) return true;
                if (current.status() == ToolApprovalStatus.FAILED) return false;
                if (failure != null) throw new CompletionException(unwrap(failure));
                return approvedEffect ? succeeded : false;
            });
            // Pekko may complete on the node's actor-dispatcher thread. Runner shutdown waits for
            // that node, so cleanup must move off the completion thread to avoid waiting on itself.
            return effectResult.whenCompleteAsync((ignored, failure) -> {
                try {
                    closeBinding(approvalBinding);
                } finally {
                    runner.close();
                    if (failure == null) {
                        recorder.detachForAcknowledgement();
                        ExecutionRecorder existing = awaitingAcknowledgement.putIfAbsent(claim, recorder);
                        if (existing != null) {
                            recorder.close();
                            throw new IllegalStateException(
                                    "duplicate continuation claim awaiting acknowledgement");
                        }
                    } else {
                        recorder.detachForAcknowledgement();
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

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void closeBinding(AutoCloseable binding) {
        try {
            binding.close();
        } catch (Exception failure) {
            throw new IllegalStateException("failed to release approval continuation binding", failure);
        }
    }

    private AutoCloseable bindLive(ExecutionKey key, ExecutionRecorder recorder) {
        AutoCloseable approvalBinding = approvals.bindLive(key, recorder);
        if (humanTasks == null) return approvalBinding;
        try {
            AutoCloseable taskBinding = humanTasks.bindLive(key, recorder);
            return () -> {
                try {
                    closeBinding(taskBinding);
                } finally {
                    closeBinding(approvalBinding);
                }
            };
        } catch (RuntimeException failure) {
            try {
                closeBinding(approvalBinding);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static boolean isDurableSuspension(Throwable failure) {
        return failure instanceof DurableHumanTaskSuspension
                || failure instanceof ai.ravenroot.core.security.nodepackage.DurableToolApprovalSuspension;
    }

    private Prepared prepare(String tenantId, String pin, String nodeId) {
        StoredGraphDefinition stored = definitions.load(new GraphDefinitionKey(
                tenantId, new GraphContentId(pin))).toCompletableFuture().join();
        GraphManager manager = GraphManager.readGraphMl(
                new ByteArrayInputStream(stored.canonical().bytes()));
        try {
            GraphVersionSnapshot snapshot = GraphVersionSnapshot.create(
                    new GraphVersionKey(stored.identity().graphId(), stored.identity().versionId()),
                    manager.definition());
            var action = behaviors.createToolCallContinuation(manager.definition().node(nodeId))
                    .orElseThrow(() -> new IllegalStateException(
                            "trusted continuation action is unavailable"));
            return new Prepared(manager, snapshot, action);
        } catch (RuntimeException failure) {
            manager.close();
            throw failure;
        }
    }

    private record Prepared(GraphManager manager, GraphVersionSnapshot snapshot,
                            ai.ravenroot.api.node.ToolCallContinuationAction action) { }

    private static ToolCallContinuationInput.Decision decision(ToolApprovalStatus status) {
        return switch (status) {
            case APPROVED, CONSUMED -> ToolCallContinuationInput.Decision.APPROVED;
            case DENIED -> ToolCallContinuationInput.Decision.DENIED;
            case EXPIRED -> ToolCallContinuationInput.Decision.EXPIRED;
            case CANCELLED -> ToolCallContinuationInput.Decision.CANCELLED;
            default -> throw new IllegalArgumentException("status cannot resume a continuation");
        };
    }
}
