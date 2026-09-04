package ai.ravenroot.core.approval;

import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.DurableToolApproval;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.HandlerStatus;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.ToolApprovalStatus;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.recovery.RecoveryDispatcher;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Dispatches only reserved durable tool-approval handler triggers. */
public final class ToolApprovalHandlerDispatcher implements RecoveryDispatcher {
    private final ExecutionStore store;
    private final ToolApprovalService approvals;
    private final ToolPolicy currentPolicy;
    private final ToolApprovalContinuationExecutor executor;

    public ToolApprovalHandlerDispatcher(ExecutionStore store, ToolApprovalService approvals,
                                         ToolPolicy currentPolicy,
                                         ToolApprovalContinuationExecutor executor) {
        this.store = Objects.requireNonNull(store, "store");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.currentPolicy = Objects.requireNonNull(currentPolicy, "currentPolicy");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public boolean canDispatch(PendingWork item) {
        if (item instanceof PendingWork.TimerDue timer) return approvals.ownsTimer(timer);
        if (!(item instanceof PendingWork.HandlerTrigger trigger)
                || !ToolApprovalService.HANDLER_NAME.equals(trigger.handlerName())) return false;
        DurableToolApproval approval = await(store.loadToolApproval(trigger.key(), trigger.workItemId()))
                .orElse(null);
        return approval != null && executor.supports(approval);
    }

    @Override
    public void dispatch(PendingWork item, String idempotencyKey) {
        if (item instanceof PendingWork.TimerDue timer) {
            if (!approvals.expireClaimedTimer(timer, idempotencyKey)) {
                throw new IllegalStateException("tool approval timer was not due or did not match");
            }
            return;
        }
        if (!(item instanceof PendingWork.HandlerTrigger trigger)
                || !ToolApprovalService.HANDLER_NAME.equals(trigger.handlerName())) {
            throw new IllegalArgumentException("not a tool approval handler trigger");
        }
        DurableToolApproval approval = await(store.loadToolApproval(trigger.key(), trigger.workItemId()))
                .orElseThrow(() -> new IllegalStateException("tool approval is absent"));
        DurableHandler handler = await(store.loadHandler(trigger.key(), trigger.workItemId()))
                .orElseThrow(() -> new IllegalStateException("tool approval handler is absent"));
        if (!Objects.equals(handler.resumeTraversalId(), trigger.traversalId())
                || handler.status() == HandlerStatus.WAITING
                || handler.status() == HandlerStatus.ESCALATED) {
            throw new IllegalStateException("tool approval trigger does not match its settled handler");
        }

        ToolApprovalStatus decision = approval.status();
        if (decision == ToolApprovalStatus.CONSUMED) {
            approvals.markConsumedIndeterminate(trigger.key(), idempotencyKey);
            return;
        }
        if (decision == ToolApprovalStatus.APPROVED) {
            ToolApprovalResult redeemed = approvals.redeemStoredFenced(approval, currentPolicy,
                    idempotencyKey, trigger.fencingToken());
            if (redeemed.code() != ToolApprovalResult.Code.CONSUMED) return;
            approval = redeemed.approval();
        } else if (decision == ToolApprovalStatus.SUCCEEDED
                || decision == ToolApprovalStatus.FAILED
                || decision == ToolApprovalStatus.INDETERMINATE) {
            return;
        }

        var request = approval.request();
        var continuation = new ToolApprovalContinuation(request.approvalId(),
                approval.key().processInstanceId(), request.traversalId(), request.invocationId(),
                request.attemptId(), trigger.traversalId(), request.requester(),
                request.graphVersionPin(), request.nodeId(), request.tool(),
                request.canonicalArguments(), request.argumentsDigest(), decision,
                request.continuationVersion(), request.continuation(), request.continuationDigest());
        DurableToolApproval consumed = approval;
        try {
            Boolean succeeded = await(Objects.requireNonNull(executor.execute(continuation, trigger),
                    "continuation execution"));
            DurableToolApproval afterExecution = await(store.loadToolApproval(
                    consumed.key(), consumed.request().approvalId())).orElse(consumed);
            if (afterExecution.status() == ToolApprovalStatus.CONSUMED) {
                approvals.complete(consumed.key(), consumed.request().approvalId(),
                        Boolean.TRUE.equals(succeeded), idempotencyKey);
            }
        } catch (RuntimeException failedBeforeReturn) {
            DurableToolApproval afterFailure = await(store.loadToolApproval(
                    consumed.key(), consumed.request().approvalId())).orElse(consumed);
            if (afterFailure.status() == ToolApprovalStatus.CONSUMED) {
                approvals.complete(consumed.key(), consumed.request().approvalId(), false, idempotencyKey);
            }
            throw failedBeforeReturn;
        }
    }

    @Override
    public void afterAcknowledged(PendingWork item) {
        if (item instanceof PendingWork.HandlerTrigger trigger
                && ToolApprovalService.HANDLER_NAME.equals(trigger.handlerName())) {
            executor.afterAcknowledged(trigger);
        }
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException runtime) throw runtime;
            throw wrapped;
        }
    }
}
