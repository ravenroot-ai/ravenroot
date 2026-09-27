package ai.ravenroot.api.runner;

import java.util.*;

/** Pure fleet constraint fold. Adapters must hold their global write/admission lock until commit. */
public final class RunnerFleetAdmission {
    private RunnerFleetAdmission() { }
    private record Group(RunnerFleetLimits.Scope scope, List<String> key) { }
    private static final class Usage {
        long claimed, queued, storage;
        final Map<UUID, Long> workspaces = new HashMap<>();
        void workspace(UUID id, long bytes) {
            Long previous = workspaces.putIfAbsent(id, bytes);
            if (previous == null) storage = Math.addExact(storage, bytes);
            else if (previous != bytes) throw new IllegalStateException("shared Workspace quota is pinned");
        }
    }
    /**
     * Refuses a proposed complete fleet snapshot exceeding any applicable approved ceiling.
     * @param states all retained runner aggregates, including the proposed mutation
     * @throws IllegalStateException when capacity or exclusive named ownership would be violated
     */
    public static void validate(Collection<RunnerWorkspaceState> states) {
        var usage = new HashMap<Group, Usage>();
        var policies = new LinkedHashMap<Group, List<RunnerFleetLimits.Ceiling>>();
        var namedOwners = new HashMap<UUID, ai.ravenroot.api.persistence.ExecutionKey>();
        var namedProfiles = new HashMap<UUID, WorkspaceProfile>();
        var generations = new HashMap<UUID, Map<Long, ai.ravenroot.api.persistence.ExecutionKey>>();
        var latest = new HashMap<UUID, Long>();
        for (var state : states) for (var resource : state.workspaces().values()) {
            if (resource.profile().workspaceScope() != WorkspaceProfile.Scope.NAMED) continue;
            if (!resource.workspaceId().equals(WorkspaceResource.namedWorkspaceId(state.execution().tenantId(), resource.profile().reference().name())))
                throw new IllegalStateException("named Workspace identity must be tenant scoped");
            var owner = generations.computeIfAbsent(resource.workspaceId(), ignored -> new HashMap<>())
                    .putIfAbsent(resource.generation(), state.execution());
            if (owner != null && !owner.equals(state.execution())) throw new IllegalStateException("named Workspace ownership generation was concurrently claimed");
            latest.merge(resource.workspaceId(), resource.generation(), Math::max);
        }
        for (var state : states) {
            for (var resource : state.workspaces().values()) {
                if (resource.state() == WorkspaceResource.State.RELEASED) continue;
                if (resource.profile().workspaceScope() == WorkspaceProfile.Scope.NAMED) {
                    var previous = namedProfiles.putIfAbsent(resource.workspaceId(), resource.profile());
                    if (previous != null && !previous.equals(resource.profile())) throw new IllegalStateException("named Workspace profile requires explicit migration");
                    if (!resource.terminal() || resource.state() == WorkspaceResource.State.RELEASING
                            && resource.generation() == latest.get(resource.workspaceId())) {
                        var owner = namedOwners.putIfAbsent(resource.workspaceId(), state.execution());
                        if (owner != null && !owner.equals(state.execution())) throw new IllegalStateException("named Workspace is owned by another process");
                    }
                }
                for (var scope : RunnerFleetLimits.Scope.values()) {
                    Group group = group(scope, state, resource);
                    usage.computeIfAbsent(group, ignored -> new Usage()).workspace(resource.workspaceId(), resource.profile().policy().limits().workspaceBytes());
                    policies.computeIfAbsent(group, ignored -> new ArrayList<>()).add(resource.profile().fleetLimits().scopes().get(scope));
                    if (resource.profile().workspaceScope() == WorkspaceProfile.Scope.EPHEMERAL) {
                        for (var entry : state.jobs().values()) if (resource.nodeId().equals(entry.workspaceNodeId()) && entry.lifecycleCommand() == null)
                            usage.get(group).workspace(resource.invocationWorkspaceId(entry.job().identity().runnerJobId(), null), resource.profile().policy().limits().workspaceBytes());
                    }
                }
            }
            for (var entry : state.jobs().values()) {
                if (!entry.job().retainsWorkspace()) continue;
                var resource = entry.workspaceNodeId() == null ? null : state.workspaces().get(entry.workspaceNodeId());
                for (var scope : RunnerFleetLimits.Scope.values()) {
                    if (resource == null && scope != RunnerFleetLimits.Scope.GLOBAL && scope != RunnerFleetLimits.Scope.TENANT) continue;
                    Group group = resource == null ? new Group(scope, scope == RunnerFleetLimits.Scope.GLOBAL ? List.of() : List.of(state.execution().tenantId()))
                            : group(scope, state, resource);
                    var count = usage.computeIfAbsent(group, ignored -> new Usage());
                    if (entry.job().state() == RunnerJob.State.QUEUED) count.queued++;
                    else count.claimed++; // Unknown effects and cancelling jobs retain their reservation.
                }
            }
        }
        for (var policy : policies.entrySet()) {
            var count = usage.get(policy.getKey());
            for (var ceiling : policy.getValue()) {
                if (count.claimed > ceiling.claimedJobs() || count.queued > ceiling.queuedJobs()
                        || count.workspaces.size() > ceiling.retainedWorkspaces() || count.storage > ceiling.storageBytes())
                    throw new IllegalStateException("runner " + policy.getKey().scope() + " capacity exhausted");
            }
        }
    }
    /**
     * Runs under the same fleet lock as reservation and cleanup, preventing reuse after deletion authority was issued.
     * @param states current complete fleet snapshot
     * @param operation proposed admission, ignored unless it opens a named resource
     */
    public static void verifyNamedAdmission(Collection<RunnerWorkspaceState> states, RunnerJobOperation operation) {
        if (!(operation instanceof RunnerJobOperation.Submit submit) || submit.workspace() == null
                || submit.workspace().profile().workspaceScope() != WorkspaceProfile.Scope.NAMED) return;
        var previous = states.stream().filter(value -> !value.execution().equals(submit.identity().execution()))
                .flatMap(value -> value.workspaces().values().stream())
                .filter(value -> value.workspaceId().equals(submit.workspaceId()))
                .max(Comparator.comparingLong(WorkspaceResource::generation)).orElse(null);
        if (previous != null && previous.state() == WorkspaceResource.State.RELEASING)
            throw new IllegalStateException("named Workspace physical cleanup is reserved; wait for fenced acknowledgement");
    }
    private static Group group(RunnerFleetLimits.Scope scope, RunnerWorkspaceState state, WorkspaceResource resource) {
        String tenant = state.execution().tenantId();
        return new Group(scope, switch (scope) {
            case GLOBAL -> List.of();
            case TENANT -> List.of(tenant);
            case POOL -> List.of(tenant, resource.profile().runnerPool());
            case WORKER -> List.of(tenant, resource.runnerId());
            case PROFILE -> List.of(tenant, resource.profile().reference().name(), Long.toString(resource.profile().reference().version()));
        });
    }
    /**
     * Requires the exact live worker incarnation and installed runtime for dispatch or placement.
     * @param state current process runner aggregate
     * @param operation proposed claim, heartbeat or placement
     * @param availability persisted tenant-scoped worker advertisements
     * @param now authoritative store time
     */
    public static void verifyWorkerOperation(RunnerWorkspaceState state, RunnerJobOperation operation,
                                              Collection<RunnerAvailability> availability, java.time.Instant now) {
        UUID session = operation instanceof RunnerJobOperation.Claim value ? value.workerSession()
                : operation instanceof RunnerJobOperation.Heartbeat value ? value.workerSession()
                : operation instanceof RunnerJobOperation.WorkspacePlace value ? value.workerSession() : null;
        if (operation instanceof RunnerJobOperation.WorkspacePlace placement) {
            var resource = state.workspaces().get(placement.workspaceNodeId());
            if (availability.stream().noneMatch(value -> value.tenantId().equals(state.execution().tenantId())
                    && value.runnerId().equals(placement.runner().runnerId()) && value.sessionId().equals(session)
                    && value.live(now) && value.runtimeProfiles().contains(resource.profile().runtimeProfile())))
                throw new IllegalStateException("placement requires a live compatible worker incarnation");
            return;
        }
        if (!(operation instanceof RunnerJobOperation.Claim || operation instanceof RunnerJobOperation.Heartbeat)) return;
        var entry = state.jobs().get(operation.jobId());
        if (entry == null || entry.workspaceNodeId() == null) return; // Decode/report legacy durable jobs, never admit a generic graph node.
        var worker = availability.stream().filter(value -> value.tenantId().equals(state.execution().tenantId())
                && value.runnerId().equals(entry.job().runner().runnerId())).findFirst().orElseThrow(() -> new IllegalStateException("worker is unavailable"));
        if (!worker.live(now) || !worker.sessionId().equals(session) || !worker.runtimeProfiles().contains(entry.job().definition().runtimeProfile()))
            throw new IllegalStateException("worker incarnation or runtime profile is unavailable");
    }
    /**
     * Refuses oversubscription even when concurrent coordinators observe stale worker-local counts.
     * @param fleet complete proposed fleet snapshot under the admission lock
     * @param availability configured capacities advertised by approved workers
     */
    public static void verifyWorkerCapacity(Collection<RunnerWorkspaceState> fleet, Collection<RunnerAvailability> availability) {
        for (var worker : availability) {
            long claimed = fleet.stream().filter(state -> state.execution().tenantId().equals(worker.tenantId()))
                    .flatMap(state -> state.jobs().values().stream()).filter(entry -> entry.job().runner().runnerId().equals(worker.runnerId())
                            && entry.job().retainsWorkspace() && entry.job().state() != RunnerJob.State.QUEUED).count();
            if (claimed > worker.capacity()) throw new IllegalStateException("worker configured capacity exhausted");
        }
    }
}
