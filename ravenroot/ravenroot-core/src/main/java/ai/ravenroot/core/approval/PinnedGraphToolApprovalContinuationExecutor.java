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
import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphVersionKey;
import ai.ravenroot.core.graph.GraphVersionSnapshot;
import ai.ravenroot.core.humantask.DurableHumanTaskSuspension;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.core.runtime.GraphRunner;
import ai.ravenroot.core.runtime.GraphExecutionLimits;
import ai.ravenroot.core.runtime.GraphExecutionContinuationCheckpoint;

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
    private final GraphExecutionLimits executionLimits;
    private final ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets;
    /**
     * Verifies that this runtime resolves what the execution was accepted against, or {@code null}
     * when no manifest store is composed and this executor behaves exactly as it did before
     * manifests existed.
     *
     * <p>Composed rather than built here, so this executor and the admission path that pinned the
     * manifest compare against one description of the runtime rather than two.</p>
     */
    private final ai.ravenroot.core.manifest.ExecutionManifestService manifests;

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
                workerId, leaseTtl, GraphExecutionLimits.DEFAULTS, null);
    }

    /** Additive composition with both durable decision services and default graph limits. */
    public PinnedGraphToolApprovalContinuationExecutor(GraphDefinitionStore definitions,
                                                       ExecutionStore executions,
                                                       ToolApprovalService approvals,
                                                       HumanTaskService humanTasks,
                                                       ExecutionEngine engine,
                                                       BehaviorRegistry behaviors,
                                                       ExecutionMonitor monitor,
                                                       ExecutionIdentitySource identities,
                                                       String workerId, Duration leaseTtl) {
        this(definitions, executions, approvals, humanTasks, engine, behaviors, monitor, identities,
                workerId, leaseTtl, GraphExecutionLimits.DEFAULTS, null);
    }

    /** Additive operator limits for hosts that do not compose durable human tasks. */
    public PinnedGraphToolApprovalContinuationExecutor(GraphDefinitionStore definitions,
                                                       ExecutionStore executions,
                                                       ToolApprovalService approvals,
                                                       ExecutionEngine engine,
                                                       BehaviorRegistry behaviors,
                                                       ExecutionMonitor monitor,
                                                       ExecutionIdentitySource identities,
                                                       String workerId, Duration leaseTtl,
                                                       GraphExecutionLimits executionLimits) {
        this(definitions, executions, approvals, null, engine, behaviors, monitor, identities,
                workerId, leaseTtl, executionLimits, null);
    }

    /** Full durable-decision composition with explicit graph limits. */
    public PinnedGraphToolApprovalContinuationExecutor(GraphDefinitionStore definitions,
                                                       ExecutionStore executions,
                                                       ToolApprovalService approvals,
                                                       HumanTaskService humanTasks,
                                                       ExecutionEngine engine,
                                                       BehaviorRegistry behaviors,
                                                       ExecutionMonitor monitor,
                                                       ExecutionIdentitySource identities,
                                                       String workerId, Duration leaseTtl,
                                                       GraphExecutionLimits executionLimits) {
        this(definitions, executions, approvals, humanTasks, engine, behaviors, monitor, identities,
                workerId, leaseTtl, executionLimits, null);
    }

    /** Additive finite agent-resource composition with default graph limits. */
    public PinnedGraphToolApprovalContinuationExecutor(GraphDefinitionStore definitions,
                                                       ExecutionStore executions,
                                                       ToolApprovalService approvals,
                                                       ExecutionEngine engine,
                                                       BehaviorRegistry behaviors,
                                                       ExecutionMonitor monitor,
                                                       ExecutionIdentitySource identities,
                                                       String workerId, Duration leaseTtl,
                                                       ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets) {
        this(definitions, executions, approvals, null, engine, behaviors, monitor, identities,
                workerId, leaseTtl, GraphExecutionLimits.DEFAULTS, agentBudgets);
    }

    /** Creates a continuation executor with both durable decision and resource services. */
    public PinnedGraphToolApprovalContinuationExecutor(GraphDefinitionStore definitions,
                                                       ExecutionStore executions,
                                                       ToolApprovalService approvals,
                                                       HumanTaskService humanTasks,
                                                       ExecutionEngine engine,
                                                       BehaviorRegistry behaviors,
                                                       ExecutionMonitor monitor,
                                                       ExecutionIdentitySource identities,
                                                       String workerId, Duration leaseTtl,
                                                       ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets) {
        this(definitions, executions, approvals, humanTasks, engine, behaviors, monitor, identities,
                workerId, leaseTtl, GraphExecutionLimits.DEFAULTS, agentBudgets);
    }

    /** Full production composition with durable decisions, graph limits, and finite agent resources. */
    public PinnedGraphToolApprovalContinuationExecutor(GraphDefinitionStore definitions,
                                                       ExecutionStore executions,
                                                       ToolApprovalService approvals,
                                                       HumanTaskService humanTasks,
                                                       ExecutionEngine engine,
                                                       BehaviorRegistry behaviors,
                                                       ExecutionMonitor monitor,
                                                       ExecutionIdentitySource identities,
                                                       String workerId, Duration leaseTtl,
                                                       GraphExecutionLimits executionLimits,
                                                       ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets) {
        this(definitions, executions, approvals, humanTasks, engine, behaviors, monitor, identities,
                workerId, leaseTtl, executionLimits, agentBudgets, null);
    }

    /**
     * Full production composition that also refuses to resume an execution this runtime cannot
     * reproduce.
     *
     * @param definitions durable graph definitions the pinned document is read from.
     * @param executions durable execution state.
     * @param approvals durable tool-approval coordinator.
     * @param humanTasks durable human-task coordinator, or {@code null}.
     * @param engine execution engine the rebuilt runner dispatches through.
     * @param behaviors trusted behavior catalog.
     * @param monitor execution monitor that observes the resumed traversal.
     * @param identities source of identifiers for the resumed traversal.
     * @param workerId identity this executor claims leases under.
     * @param leaseTtl how long a claimed lease lives.
     * @param executionLimits operator-owned admission and traversal limits.
     * @param agentBudgets agent authority budget service, or {@code null}.
     * @param manifests manifest verification service, or {@code null} to verify nothing.
     */
    public PinnedGraphToolApprovalContinuationExecutor(GraphDefinitionStore definitions,
                                                       ExecutionStore executions,
                                                       ToolApprovalService approvals,
                                                       HumanTaskService humanTasks,
                                                       ExecutionEngine engine,
                                                       BehaviorRegistry behaviors,
                                                       ExecutionMonitor monitor,
                                                       ExecutionIdentitySource identities,
                                                       String workerId, Duration leaseTtl,
                                                       GraphExecutionLimits executionLimits,
                                                       ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetService agentBudgets,
                                                       ai.ravenroot.core.manifest.ExecutionManifestService manifests) {
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
        this.executionLimits = Objects.requireNonNull(executionLimits, "executionLimits");
        this.agentBudgets = agentBudgets;
        this.manifests = manifests;
    }

    /**
     * Refuses to rebuild a graph for an execution this runtime cannot reproduce.
     *
     * <p>Runs before the definition is loaded and before any lease or runner exists, so a refusal
     * costs nothing and claims nothing. Both refusals are typed: an absent, unreadable or
     * digest-mismatched manifest arrives as
     * {@link ai.ravenroot.api.persistence.ExecutionManifestStoreException}, and a runtime that
     * resolves something different arrives as
     * {@link ai.ravenroot.core.manifest.ExecutionManifestIncompatibleException} naming each differing
     * dimension. Either way the work is not dispatched, not acknowledged and not lost: the recovery
     * loop leaves it claimable, which is what fail-closed means on this path.</p>
     *
     * <p>{@link ai.ravenroot.api.application.ExecutionPolicy#STANDARD} is the policy compared
     * against because it is the policy this executor actually rebuilds the runner under. An execution
     * accepted under a different one is therefore refused here rather than silently resumed as a
     * standard run — which is the behaviour a pin exists to produce.</p>
     */
    private void verifyManifest(ai.ravenroot.api.persistence.ExecutionKey key) {
        if (manifests != null) {
            manifests.verify(key, ai.ravenroot.api.application.ExecutionPolicy.STANDARD);
        }
    }

    @Override
    public boolean supports(DurableToolApproval approval) {
        GraphManager manager = null;
        try {
            verifyManifest(approval.key());
            Prepared prepared = prepare(approval.request().requester().tenantId(),
                    approval.request().graphVersionPin().reference(), approval.request().nodeId());
            manager = prepared.manager();
            var request = approval.request();
            GraphExecutionContinuationCheckpoint.Decoded checkpoint =
                    GraphExecutionContinuationCheckpoint.read(request.continuationVersion(),
                            request.continuation());
            NodeMessage original = new NodeMessage(request.requester(), approval.key().processInstanceId(),
                    request.traversalId(), request.invocationId(), request.attemptId(), Set.of(),
                    request.nodeId(), null, Map.of(), NodeCommand.PROCESS);
            prepared.action().validate(new ToolCallContinuationInput(original, request.approvalId(),
                    request.traversalId(), request.invocationId(), request.attemptId(), request.tool(),
                    request.canonicalArguments(), request.argumentsDigest(),
                    decision(approval.status()), checkpoint.innerVersion(), checkpoint.inner(),
                    ToolApprovalRegistration.digest(checkpoint.inner())));
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
            GraphExecutionContinuationCheckpoint.Decoded checkpoint =
                    GraphExecutionContinuationCheckpoint.read(continuation.version(), continuation.checkpoint());
            verifyManifest(claim.key());
            Prepared prepared = prepare(continuation.requester().tenantId(),
                    continuation.graphVersionPin().reference(), continuation.nodeId());
            GraphManager manager = prepared.manager();
            long revision;
            DurableToolApproval storedApproval;
            try {
                revision = executions.load(claim.key()).toCompletableFuture().join().revision();
                storedApproval = executions.loadToolApproval(
                        claim.key(), continuation.approvalId()).toCompletableFuture().join()
                        .filter(candidate -> candidate.status() == continuation.decision())
                        .orElseThrow(() -> new IllegalStateException(
                                "tool approval decision changed before claimed re-entry"));
            } catch (RuntimeException setupFailure) {
                setupFailure = cleanup(setupFailure, manager::close);
                throw setupFailure;
            }
            boolean approvedEffect = storedApproval.status() == ToolApprovalStatus.CONSUMED;
            ExecutionRecorder recorder;
            try {
                recorder = ExecutionRecorder.resumeClaimed(
                        executions, claim, workerId, leaseTtl, revision);
            } catch (RuntimeException setupFailure) {
                setupFailure = cleanup(setupFailure, manager::close);
                throw setupFailure;
            }
            GraphRunner runner;
            try {
                runner = new GraphRunner(manager, prepared.snapshot(), engine, behaviors, monitor, identities,
                        GraphRunner.DEFAULT_SHUTDOWN_BOUND, executionLimits);
            } catch (RuntimeException setupFailure) {
                setupFailure = cleanup(setupFailure, recorder::detachForAcknowledgement);
                setupFailure = cleanup(setupFailure, manager::close);
                throw setupFailure;
            }
            AutoCloseable approvalBinding = null;
            AutoCloseable budgetBinding = null;
            try {
                approvalBinding = bindLive(new ExecutionKey(
                        continuation.requester().tenantId(), continuation.processInstanceId()), recorder,
                        runner::continuationBudget);
                budgetBinding = agentBudgets == null ? null : agentBudgets.bindLive(new ExecutionKey(
                        continuation.requester().tenantId(), continuation.processInstanceId()), recorder);
            } catch (RuntimeException bindingFailure) {
                if (approvalBinding != null) {
                    AutoCloseable failedBinding = approvalBinding;
                    bindingFailure = cleanup(bindingFailure, () -> closeBinding(failedBinding));
                }
                bindingFailure = cleanup(bindingFailure, runner::close);
                bindingFailure = cleanup(bindingFailure, recorder::detachForAcknowledgement);
                bindingFailure = cleanup(bindingFailure, manager::close);
                throw bindingFailure;
            }
            AutoCloseable approvalResourceBinding = approvalBinding;
            AutoCloseable budgetResourceBinding = budgetBinding;
            ExecutionKey executionKey = new ExecutionKey(
                    continuation.requester().tenantId(), continuation.processInstanceId());
            CompletionStage<Boolean> result;
            try {
                result = runner.executeFrom(continuation.requester(),
                        continuation.processInstanceId(), continuation.resumeTraversalId(),
                        continuation.nodeId(), continuation.graphVersionPin().reference(), recorder,
                        message -> new ToolCallContinuationInput(message, continuation.approvalId(),
                                continuation.originalTraversalId(), continuation.originalInvocationId(),
                                continuation.originalAttemptId(), continuation.tool(),
                                continuation.canonicalArguments(), continuation.argumentsDigest(),
                                decision(continuation.decision()), checkpoint.innerVersion(),
                                checkpoint.inner(), ToolApprovalRegistration.digest(checkpoint.inner())),
                        succeeded -> {
                            if (approvedEffect) {
                                approvals.completeFenced(recorder, storedApproval, succeeded,
                                        continuation.approvalId().toString());
                            }
                        }, prepared.action(), checkpoint.budget());
            } catch (RuntimeException setupFailure) {
                setupFailure = cleanup(setupFailure,
                        () -> closeBinding(approvalResourceBinding));
                if (budgetResourceBinding != null) {
                    setupFailure = cleanup(setupFailure,
                            () -> closeBinding(budgetResourceBinding));
                }
                setupFailure = cleanup(setupFailure, runner::close);
                setupFailure = cleanup(setupFailure, recorder::detachForAcknowledgement);
                setupFailure = cleanup(setupFailure, manager::close);
                throw setupFailure;
            }
            CompletionStage<Boolean> effectResult = result.handle((succeeded, failure) -> {
                Throwable cause = failure == null ? null : unwrap(failure);
                if (agentBudgets != null && (cause == null || !isDurableSuspension(cause))) {
                    agentBudgets.finishProcess(executionKey, failure == null);
                }
                DurableToolApproval current = executions.loadToolApproval(
                        claim.key(), continuation.approvalId()).toCompletableFuture().join()
                        .orElse(storedApproval);
                if (failure != null && isDurableSuspension(cause)) {
                    if (current.status() == ToolApprovalStatus.SUCCEEDED) return true;
                    if (current.status() == ToolApprovalStatus.FAILED) return false;
                    return false;
                }
                if (current.status() == ToolApprovalStatus.SUCCEEDED) return true;
                if (current.status() == ToolApprovalStatus.FAILED) return false;
                if (failure != null) throw new CompletionException(cause);
                return approvedEffect ? succeeded : false;
            });
            // Pekko may complete on the node's actor-dispatcher thread. Runner shutdown waits for
            // that node, so cleanup must move off the completion thread to avoid waiting on itself.
            return effectResult.whenCompleteAsync((ignored, failure) -> {
                RuntimeException cleanupFailure = null;
                cleanupFailure = cleanup(cleanupFailure,
                        () -> closeBinding(approvalResourceBinding));
                if (budgetResourceBinding != null) {
                    cleanupFailure = cleanup(cleanupFailure,
                            () -> closeBinding(budgetResourceBinding));
                }
                cleanupFailure = cleanup(cleanupFailure, runner::close);
                cleanupFailure = cleanup(cleanupFailure, recorder::detachForAcknowledgement);
                cleanupFailure = cleanup(cleanupFailure, manager::close);
                if (failure == null && cleanupFailure == null) {
                    try {
                        ExecutionRecorder existing = awaitingAcknowledgement.putIfAbsent(claim, recorder);
                        if (existing != null) {
                            recorder.close();
                            throw new IllegalStateException(
                                    "duplicate continuation claim awaiting acknowledgement");
                        }
                    } catch (RuntimeException mapFailure) {
                        cleanupFailure = combine(cleanupFailure, mapFailure);
                    }
                } else {
                    cleanupFailure = cleanup(cleanupFailure, recorder::close);
                }
                if (cleanupFailure != null) throw cleanupFailure;
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

    private static RuntimeException cleanup(RuntimeException first, Runnable action) {
        try {
            action.run();
            return first;
        } catch (RuntimeException failure) {
            return combine(first, failure);
        }
    }

    private static RuntimeException combine(RuntimeException first, RuntimeException next) {
        if (first == null) return next;
        if (next != first) first.addSuppressed(next);
        return first;
    }

    private AutoCloseable bindLive(ExecutionKey key, ExecutionRecorder recorder,
                                   java.util.function.Function<NodeMessage,
                                           ai.ravenroot.core.runtime.GraphExecutionBudgetSnapshot> budget) {
        AutoCloseable approvalBinding = approvals.bindLive(key, recorder, budget);
        if (humanTasks == null) return approvalBinding;
        try {
            AutoCloseable taskBinding = humanTasks.bindLive(key, recorder, budget);
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
                new ByteArrayInputStream(stored.canonical().bytes()), executionLimits.graphMl());
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
