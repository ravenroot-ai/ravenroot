package ai.ravenroot.core.humantask;

import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.DurableHumanTask;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.HandlerStatus;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.core.recovery.RecoveryDispatcher;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Dispatches only durable human-task timers and reserved handler triggers. */
public final class HumanTaskHandlerDispatcher implements RecoveryDispatcher {
    private final ExecutionStore store;
    private final HumanTaskService tasks;
    private final HumanTaskContinuationExecutor executor;

    public HumanTaskHandlerDispatcher(ExecutionStore store, HumanTaskService tasks,
                                      HumanTaskContinuationExecutor executor) {
        this.store = Objects.requireNonNull(store, "store");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public boolean canDispatch(PendingWork item) {
        if (item instanceof PendingWork.TimerDue timer) return tasks.ownsTimer(timer);
        if (!(item instanceof PendingWork.HandlerTrigger trigger)
                || !HumanTaskService.HANDLER_NAME.equals(trigger.handlerName())) return false;
        DurableHumanTask task = await(store.loadHumanTask(trigger.key().tenantId(), trigger.workItemId()))
                .filter(candidate -> candidate.key().equals(trigger.key())).orElse(null);
        return task != null && task.status().terminal() && executor.supports(task);
    }

    @Override
    public void dispatch(PendingWork item, String idempotencyKey) {
        if (item instanceof PendingWork.TimerDue timer) {
            if (!tasks.applyClaimedTimer(timer, idempotencyKey)) {
                throw new IllegalStateException("human-task timer was not due or did not match");
            }
            return;
        }
        if (!(item instanceof PendingWork.HandlerTrigger trigger)
                || !HumanTaskService.HANDLER_NAME.equals(trigger.handlerName())) {
            throw new IllegalArgumentException("not a human-task handler trigger");
        }
        DurableHumanTask task = await(store.loadHumanTask(trigger.key().tenantId(), trigger.workItemId()))
                .filter(candidate -> candidate.key().equals(trigger.key()))
                .orElseThrow(() -> new IllegalStateException("human task is absent"));
        DurableHandler handler = await(store.loadHandler(trigger.key(), trigger.workItemId()))
                .orElseThrow(() -> new IllegalStateException("human-task handler is absent"));
        if (!task.status().terminal() || handler.status() == HandlerStatus.WAITING
                || handler.status() == HandlerStatus.ESCALATED
                || !Objects.equals(handler.resumeTraversalId(), trigger.traversalId())) {
            throw new IllegalStateException("human-task trigger does not match its terminal state");
        }
        await(Objects.requireNonNull(executor.execute(task, handler, trigger),
                "human-task continuation stage"));
    }

    @Override
    public void afterAcknowledged(PendingWork item) {
        if (item instanceof PendingWork.HandlerTrigger trigger
                && HumanTaskService.HANDLER_NAME.equals(trigger.handlerName())) {
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
