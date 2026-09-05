package ai.ravenroot.core.humantask;

import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.DurableHumanTask;
import ai.ravenroot.api.persistence.PendingWork;

import java.util.concurrent.CompletionStage;

/** Executes the trusted fresh traversal created by a terminal human-task decision. */
public interface HumanTaskContinuationExecutor {
    boolean supports(DurableHumanTask task);

    CompletionStage<Void> execute(DurableHumanTask task, DurableHandler handler,
                                  PendingWork.HandlerTrigger claim);

    default void afterAcknowledged(PendingWork.HandlerTrigger claim) { }

    /** Fail-closed continuation used when a host has no pinned-graph re-entry runtime. */
    HumanTaskContinuationExecutor NONE = new HumanTaskContinuationExecutor() {
        @Override public boolean supports(DurableHumanTask task) { return false; }

        @Override public CompletionStage<Void> execute(DurableHumanTask task, DurableHandler handler,
                                                       PendingWork.HandlerTrigger claim) {
            throw new IllegalStateException("human-task continuation execution is unavailable");
        }
    };
}
