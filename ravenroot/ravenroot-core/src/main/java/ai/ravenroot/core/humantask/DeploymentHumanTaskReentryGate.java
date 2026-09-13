package ai.ravenroot.core.humantask;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.persistence.DurableHumanTask;
import ai.ravenroot.api.persistence.ExecutionStore;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Uses the shared durable deployment authority to gate Human Task continuation dispatch. */
public final class DeploymentHumanTaskReentryGate implements HumanTaskReentryGate {
    private final ExecutionStore executions;
    private final DeploymentRegistry deployments;

    public DeploymentHumanTaskReentryGate(ExecutionStore executions, DeploymentRegistry deployments) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
    }

    @Override
    public boolean admits(DurableHumanTask task) {
        Objects.requireNonNull(task, "task");
        var process = await(executions.findProcessInstance(task.key())).orElse(null);
        if (process == null) return false;
        String deploymentId = process.deploymentId().orElse(null);
        if (deploymentId == null) return true;
        var deployment = await(deployments.get(task.key().tenantId(), DeploymentId.of(deploymentId)))
                .orElse(null);
        if (deployment == null || deployment.tombstone() != null) return false;
        return switch (deployment.desired().kind()) {
            case RUNNING, DRAINED -> true;
            case PAUSED, STOPPED, REMOVED -> false;
        };
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
