package ai.ravenroot.core.runner;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.runner.*;
import ai.ravenroot.api.security.*;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tenant-exact reference monitor shared by HTTP and embedded control-plane hosts. */
public final class AuthorizedRunnerControl {
    private final RunnerJobService jobs;
    private final AuthorizationService authorization;
    private final String runnerIssuer;
    private final RunnerArtifactStore artifacts;
    private final Clock clock;

    public AuthorizedRunnerControl(RunnerJobService jobs, AuthorizationService authorization,
                                   String runnerIssuer, RunnerArtifactStore artifacts, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs); this.authorization = Objects.requireNonNull(authorization);
        if (runnerIssuer == null || runnerIssuer.isBlank()) throw new IllegalArgumentException("runner issuer is required");
        this.runnerIssuer = runnerIssuer; this.artifacts = Objects.requireNonNull(artifacts); this.clock = Objects.requireNonNull(clock);
    }

    public List<GovernedRunnerResource> resources(RequestContext actor) {
        authorize(actor, AuthorizationAction.RUNNER_READ, "catalog");
        return jobs.store().runnerResources(actor.tenantId()).toCompletableFuture().join();
    }

    public GovernedRunnerResource save(RequestContext actor, GovernedRunnerResource resource, long expectedRevision) {
        authorize(actor, AuthorizationAction.RUNNER_ADMIN, "catalog");
        if (actor.principalType() != PrincipalType.USER || !actor.tenantId().equals(resource.tenantId())) {
            throw new AuthorizationDeniedException("runner governance requires a same-tenant operator");
        }
        var accepted = new GovernedRunnerResource(resource.kind(), actor.tenantId(), resource.name(), resource.version(),
                resource.approved(), resource.document(), 0, SecurityContext.of(actor).qualifiedIdentity(), clock.instant());
        if (resource.kind() == GovernedRunnerResource.Kind.AGENT_DEFINITION
                && RunnerCodec.definition(resource.document().bytes()).workspaceRetention()
                    .compareTo(jobs.store().terminalRetention()) >= 0) {
            throw new IllegalArgumentException("workspace retention must leave a cleanup window before process retention");
        }
        return jobs.store().saveRunnerResource(accepted, expectedRevision).toCompletableFuture().join();
    }

    /** A workload advertisement is always a draft; it cannot grant itself an approved profile. */
    public GovernedRunnerResource register(RequestContext actor, RunnerRegistration registration) {
        requireRunner(actor, registration.runnerId());
        if (!actor.tenantId().equals(registration.tenantId())) throw new AuthorizationDeniedException("runner tenant mismatch");
        var resource = new GovernedRunnerResource(GovernedRunnerResource.Kind.RUNNER, actor.tenantId(),
                registration.runnerId(), 1, false,
                ai.ravenroot.api.persistence.OpaquePayload.of(RunnerCodec.registration(registration),
                        "application/vnd.ravenroot.runner-registration.v1"), 0,
                SecurityContext.of(actor).qualifiedIdentity(), clock.instant());
        var existing = jobs.store().runnerResources(actor.tenantId()).toCompletableFuture().join().stream()
                .filter(value -> value.key().equals(resource.key())).findFirst().orElse(null);
        if (existing != null) {
            if (!existing.document().equals(resource.document())) throw new IllegalArgumentException("runner registration is immutable");
            return existing;
        }
        return jobs.store().saveRunnerResource(resource, 0).toCompletableFuture().join();
    }

    public RunnerWorkspaceState workspace(RequestContext actor, UUID processId) {
        authorize(actor, AuthorizationAction.RUNNER_READ, processId.toString());
        return load(actor, processId);
    }

    public MapPage assignments(RequestContext actor, String cursor) {
        requireRunner(actor, actor.subject());
        var query = ai.ravenroot.api.persistence.ProcessInventoryQuery.everything(16).after(cursor);
        var page = jobs.store().listProcessInstances(actor.tenantId(), query).toCompletableFuture().join();
        var items = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var process : page.items()) {
            var workspace = jobs.store().loadRunnerWorkspace(process.key()).toCompletableFuture().join().orElse(null);
            if (workspace == null || !workspace.runnerId().equals(actor.subject())) continue;
            if (process.status().terminal() && workspace.jobs().values().stream().allMatch(entry -> entry.job().state().terminal())) {
                var retention = workspace.jobs().values().stream().map(entry -> entry.job().definition().workspaceRetention())
                        .max(java.time.Duration::compareTo).orElseThrow();
                if (!clock.instant().isBefore(process.updatedAt().plus(retention))) {
                    items.add(java.util.Map.of("cleanup", true, "processInstanceId", process.key().processInstanceId().toString()));
                }
                continue;
            }
            for (var entry : workspace.jobs().values()) if (entry.job().retainsWorkspace()) items.add(RunnerJson.job(entry.job()));
        }
        return new MapPage(List.copyOf(items), page.nextCursor().orElse(null));
    }
    public record MapPage(List<java.util.Map<String, Object>> items, String nextCursor) { }

    /** A bounded inventory page, not a misleading unbounded/global health aggregate. */
    public java.util.Map<String, Object> health(RequestContext actor, String cursor) {
        authorize(actor, AuthorizationAction.RUNNER_READ, "health");
        var page = jobs.store().listProcessInstances(actor.tenantId(),
                ai.ravenroot.api.persistence.ProcessInventoryQuery.outstanding(16).after(cursor)).toCompletableFuture().join();
        var counts = new java.util.TreeMap<String, Long>();
        var items = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var process : page.items()) {
            var workspace = jobs.store().loadRunnerWorkspace(process.key()).toCompletableFuture().join().orElse(null);
            if (workspace == null) continue;
            for (var entry : workspace.jobs().values()) if (!entry.job().state().terminal()) {
                var job = entry.job();
                String state = job.leaseUntil() != null && !clock.instant().isBefore(job.leaseUntil())
                        ? "LEASE_EXPIRED" : job.state().name();
                counts.merge(state, 1L, Long::sum);
                items.add(java.util.Map.of("runnerId", workspace.runnerId(), "runnerJobId", job.identity().runnerJobId().toString(),
                        "processInstanceId", process.key().processInstanceId().toString(), "health", state,
                        "lastAcceptedAt", job.updatedAt().toString()));
            }
        }
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("items", items); result.put("pageMetrics", counts); result.put("nextCursor", page.nextCursor().orElse(null));
        result.put("observedAt", clock.instant().toString());
        return result;
    }

    public java.util.Map<String, Object> audit(RequestContext actor, long after) {
        authorize(actor, AuthorizationAction.RUNNER_READ, "audit");
        if (after < 0) throw new IllegalArgumentException("invalid runner audit offset");
        var page = jobs.store().readJournal(actor.tenantId(), after, 64).toCompletableFuture().join();
        var items = page.stream().filter(record -> record.envelope().eventType().startsWith("RUNNER_JOB_"))
                .map(record -> {
                    var envelope = record.envelope();
                    return java.util.Map.of("offset", record.journalOffset(), "revision", record.committedAtRevision(),
                            "eventId", envelope.eventId().toString(), "type", envelope.eventType(),
                            "processInstanceId", envelope.processInstanceId().toString(), "recordedAt", record.recordedAt().toString(),
                            "detail", RunnerJson.read(envelope.payload().bytes()));
                }).toList();
        return java.util.Map.of("items", items, "nextOffset", page.isEmpty() ? after : page.getLast().journalOffset());
    }

    public RunnerWorkspaceRelease release(RequestContext actor, UUID processId) throws IOException {
        requireRunner(actor, actor.subject());
        var workspace = load(actor, processId);
        requireRunner(actor, workspace.runnerId());
        var process = jobs.store().load(workspace.execution()).toCompletableFuture().join();
        if (!process.state().status().terminal() || workspace.jobs().values().stream().anyMatch(entry -> !entry.job().state().terminal() || entry.continuationUncertain())) {
            throw new IllegalStateException("workspace cleanup requires a terminal quiescent process");
        }
        var retention = workspace.jobs().values().stream().map(entry -> entry.job().definition().workspaceRetention())
                .max(java.time.Duration::compareTo).orElseThrow();
        var notBefore = process.updatedAt().plus(retention);
        if (clock.instant().isBefore(notBefore)) throw new IllegalStateException("workspace retention has not elapsed");
        for (var entry : workspace.jobs().values()) artifacts.removeRetained(entry.job());
        return new RunnerWorkspaceRelease(1, workspace.execution(), workspace.workspaceId(), workspace.runnerId(),
                workspace.jobs().keySet(), notBefore);
    }

    public RunnerAssignment assignment(RequestContext actor, UUID processId, UUID jobId) {
        var workspace = load(actor, processId);
        var job = requireJob(workspace, jobId);
        requireRunner(actor, job.runner().runnerId());
        return new RunnerAssignment(1, workspace.workspaceId(), job);
    }

    public RunnerAssignment operate(RequestContext actor, UUID processId, RunnerJobOperation operation) throws IOException {
        var workspace = load(actor, processId); var job = requireJob(workspace, operation.jobId());
        if (operation instanceof RunnerJobOperation.Submit || operation instanceof RunnerJobOperation.ContinuationUncertain) {
            throw new AuthorizationDeniedException("runner cannot change graph admission or delivery state");
        }
        if (operation instanceof RunnerJobOperation.Cancel || operation instanceof RunnerJobOperation.Reconcile) {
            authorize(actor, AuthorizationAction.RUNNER_CONTROL, processId.toString());
            if (actor.principalType() != PrincipalType.USER) throw new AuthorizationDeniedException("operator identity required");
        } else {
            requireRunner(actor, job.runner().runnerId());
            String claimedRunner = switch (operation) {
                case RunnerJobOperation.Claim value -> value.runnerId();
                case RunnerJobOperation.Heartbeat value -> value.runnerId();
                case RunnerJobOperation.ReconcileReport value -> value.runnerId();
                case RunnerJobOperation.Complete value -> value.runnerId();
                default -> throw new AuthorizationDeniedException("invalid runner operation");
            };
            if (!actor.subject().equals(claimedRunner)) throw new AuthorizationDeniedException("runner identity mismatch");
            if (operation instanceof RunnerJobOperation.Claim && jobs.runners(actor.tenantId()).stream()
                    .noneMatch(value -> value.equals(job.runner()))) throw new AuthorizationDeniedException("runner approval retired");
            if (operation instanceof RunnerJobOperation.Complete report) {
                for (var artifact : report.result().artifacts()) artifacts.verify(job, artifact);
            }
        }
        var result = jobs.mutate(SecurityContext.of(actor), new ExecutionKey(actor.tenantId(), processId), operation);
        return new RunnerAssignment(1, result.workspaceId(), requireJob(result, operation.jobId()));
    }

    public RunnerArtifact upload(RequestContext actor, UUID processId, UUID jobId, long fence,
                                 RunnerArtifact.Kind kind, InputStream bytes) throws IOException {
        var job = requireJob(load(actor, processId), jobId);
        requireRunner(actor, job.runner().runnerId());
        if (job.fence() != fence || job.leaseUntil() == null || !clock.instant().isBefore(job.leaseUntil())
                || (job.state() != RunnerJob.State.CLAIMED && job.state() != RunnerJob.State.RECONCILING)) {
            throw new IllegalStateException("runner artifact upload requires a live fenced claim");
        }
        return artifacts.put(job, kind, bytes);
    }

    public InputStream artifact(RequestContext actor, UUID processId, UUID jobId, UUID artifactId) throws IOException {
        var workspace = workspace(actor, processId); var job = requireJob(workspace, jobId);
        if (job.result() == null) throw new IllegalStateException("runner artifact is not sealed");
        var artifact = job.result().artifacts().stream().filter(value -> value.artifactId().equals(artifactId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("runner artifact not found"));
        if (!clock.instant().isBefore(job.updatedAt().plus(job.definition().workspaceRetention()))) {
            throw new IllegalArgumentException("runner artifact retention expired");
        }
        return artifacts.open(job, artifact);
    }

    private RunnerWorkspaceState load(RequestContext actor, UUID processId) {
        return jobs.store().loadRunnerWorkspace(new ExecutionKey(actor.tenantId(), processId)).toCompletableFuture()
                .join().orElseThrow(() -> new IllegalArgumentException("runner workspace not found"));
    }
    private static RunnerJob requireJob(RunnerWorkspaceState workspace, UUID id) {
        var entry = workspace.jobs().get(id);
        if (entry == null) throw new IllegalArgumentException("runner job not found");
        return entry.job();
    }
    private void requireRunner(RequestContext actor, String runnerId) {
        authorize(actor, AuthorizationAction.RUNNER_DISPATCH, runnerId);
        if (actor.principalType() != PrincipalType.WORKLOAD || !actor.issuer().equals(runnerIssuer)
                || !actor.subject().equals(runnerId)) throw new AuthorizationDeniedException("designated runner identity required");
    }
    private void authorize(RequestContext actor, AuthorizationAction action, String id) {
        authorization.requireAllowed(actor, action, ProtectedResource.owned("governed-runner", id, actor.tenantId()));
    }
}
