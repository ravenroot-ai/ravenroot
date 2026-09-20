package ai.ravenroot.core.runner;

import ai.ravenroot.api.runner.*;
import ai.ravenroot.api.security.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

/** In-process capability held only by a trusted host's supervised worker; no socket or bearer. */
public final class LocalRunnerClient implements RunnerControlClient {
    private final AuthorizedRunnerControl control;
    private final RequestContext actor;
    private final UUID session = UUID.randomUUID();
    private final RunnerWorkerConfiguration configuration;

    public LocalRunnerClient(AuthorizedRunnerControl control, RequestContext actor, RunnerWorkerConfiguration configuration) {
        this.control = Objects.requireNonNull(control);
        this.actor = Objects.requireNonNull(actor);
        if (actor.principalType() != PrincipalType.WORKLOAD) throw new IllegalArgumentException("local worker requires a structural WORKLOAD identity");
        this.configuration = Objects.requireNonNull(configuration);
    }

    @Override public void register(RunnerRegistration registration) { control.register(actor, registration); }
    @Override public void availability(int capacity, int active, Set<String> profiles, Duration ttl) {
        control.availability(actor, session, capacity, active, profiles, ttl);
    }
    @Override public Map<String, Object> assignments(String cursor) {
        var page = control.assignments(actor, cursor);
        var result = new LinkedHashMap<String, Object>();
        result.put("items", page.items()); result.put("nextCursor", page.nextCursor());
        return result;
    }
    @Override public RunnerAssignment assignment(UUID process, UUID job) { return control.assignment(actor, process, job); }
    @Override public void workspaceStopped(RunnerAssignment assignment) {
        var process = process(assignment);
        control.workspaceStopped(actor, process, assignment.workspace().nodeId(), assignment.workspaceId(), control.workerRevision(actor, process));
    }
    @Override public RunnerWorkspaceRelease release(UUID process, String node) throws IOException {
        return node == null ? control.release(actor, process) : control.releaseWorkspace(actor, process, node);
    }
    @Override public void workspaceReleased(UUID process, String node, RunnerWorkspaceRelease release) {
        if (node != null) control.workspaceReleased(actor, process, node, release.workspaceId(), control.workerRevision(actor, process));
    }
    @Override public RunnerAssignment claim(UUID process, UUID job, boolean reconcile) throws IOException {
        try {
            return control.operate(actor, process, reconcile
                    ? new RunnerJobOperation.ReconcileReport(job, actor.subject(), configuration.leaseTtl())
                    : new RunnerJobOperation.Claim(job, actor.subject(), configuration.leaseTtl(), session));
        } catch (RuntimeException conflict) {
            // Same optimistic-admission refusal as the remote transport; not an execution permit.
            var storeFailure = ai.ravenroot.api.persistence.ExecutionStoreException.unwrap(conflict);
            if (conflict instanceof IllegalStateException || storeFailure != null
                    && (storeFailure.failure() instanceof ai.ravenroot.api.persistence.ExecutionStoreFailure.InvalidRequest
                        || storeFailure.failure() instanceof ai.ravenroot.api.persistence.ExecutionStoreFailure.ConcurrencyConflict
                        || storeFailure.failure() instanceof ai.ravenroot.api.persistence.ExecutionStoreFailure.LeaseHeldByAnother))
                throw new RemoteRunnerClient.ProtocolRefusal(409);
            throw conflict;
        }
    }
    @Override public RunnerAssignment heartbeat(RunnerAssignment assignment) throws IOException {
        return control.operate(actor, process(assignment), new RunnerJobOperation.Heartbeat(assignment.job().identity().runnerJobId(),
                actor.subject(), assignment.job().fence(), configuration.leaseTtl(), session,
                assignment.workspace() == null ? null : assignment.workspace().kubernetes()));
    }
    @Override public RunnerAssignment complete(RunnerAssignment assignment, RunnerResult result) throws IOException {
        return control.operate(actor, process(assignment), new RunnerJobOperation.Complete(assignment.job().identity().runnerJobId(),
                actor.subject(), assignment.job().fence(), result));
    }
    @Override public RunnerArtifact upload(RunnerAssignment assignment, RunnerArtifact.Kind kind, byte[] bytes) throws IOException {
        return control.upload(actor, process(assignment), assignment.job().identity().runnerJobId(), assignment.job().fence(),
                kind, new ByteArrayInputStream(bytes));
    }
    private static UUID process(RunnerAssignment assignment) { return assignment.job().identity().execution().processInstanceId(); }
}
