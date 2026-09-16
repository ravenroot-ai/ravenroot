package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.lifecycle.DeploymentLifecycleTarget;
import ai.ravenroot.core.humantask.HumanTaskService;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Adds durable Human Task closure to a deployment cancellation barrier. */
public final class HumanTaskCancellingLifecycleTarget implements DeploymentLifecycleTarget {
    private final String tenantId;
    private final DeploymentId deploymentId;
    private final DeploymentLifecycleTarget delegate;
    private final HumanTaskService humanTasks;

    public HumanTaskCancellingLifecycleTarget(String tenantId, DeploymentId deploymentId,
                                              DeploymentLifecycleTarget delegate,
                                              HumanTaskService humanTasks) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.humanTasks = Objects.requireNonNull(humanTasks, "humanTasks");
    }

    @Override public CompletionStage<Void> start(long version, long generation) {
        return delegate.start(version, generation);
    }

    @Override public CompletionStage<Void> closeAdmission(long generation) {
        return delegate.closeAdmission(generation);
    }

    @Override public CompletionStage<Void> openAdmission(long generation) {
        return delegate.openAdmission(generation);
    }

    @Override public CompletionStage<Void> barrier(long generation) {
        return delegate.barrier(generation).thenRun(() -> humanTasks.cancelDeploymentTasks(
                tenantId, deploymentId,
                "deployment-cancel:" + deploymentId.value() + ":" + generation));
    }

    @Override public CompletionStage<Boolean> drain(Duration bound, long generation) {
        return delegate.drain(bound, generation);
    }

    @Override public CompletionStage<Void> terminateDomain(long generation) {
        return delegate.terminateDomain(generation);
    }

    @Override public CompletionStage<Reading> observe() {
        return delegate.observe();
    }
}
