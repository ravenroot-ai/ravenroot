package ai.ravenroot.api.runner;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.OpaquePayload;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded process workspace state committed atomically with its graph aggregate. Job history remains
 * independent of journal compaction. One active job is a sequencing barrier, never a second lease.
 * @param execution owning tenant and process
 * @param workspaceId stable opaque workspace identity
 * @param runnerId immutable designated runner placement
 * @param jobs bounded insertion-ordered history keyed by runner job identity
 * @param processTerminalAt store-clock timestamp pinned atomically at process termination; null before termination
 * @param workspaces graph-declared resources keyed by same-graph node identity
 */
public record RunnerWorkspaceState(ExecutionKey execution, UUID workspaceId, String runnerId,
                                   Map<UUID, Entry> jobs, Instant processTerminalAt,
                                   Map<String, WorkspaceResource> workspaces) {
    /**
     * Process retention cannot erase physical ownership or unresolved effects.
     * @return true only after every job is terminal, delivery certain and resource physically released
     */
    public boolean retentionSafe() {
        return jobs.values().stream().allMatch(entry -> entry.job().state().terminal() && !entry.continuationUncertain())
                && workspaces.values().stream().allMatch(resource -> resource.state() == WorkspaceResource.State.RELEASED);
    }
    /**
     * Restores legacy aggregate storage without creating implicit graph resources.
     * @param execution owning tenant and process
     * @param workspaceId legacy filesystem identity
     * @param runnerId designated legacy worker
     * @param jobs retained job history
     * @param processTerminalAt pinned process termination time, or null
     */
    public RunnerWorkspaceState(ExecutionKey execution, UUID workspaceId, String runnerId,
                                Map<UUID, Entry> jobs, Instant processTerminalAt) {
        this(execution, workspaceId, runnerId, jobs, processTerminalAt, Map.of());
    }
    /** Compatibility constructor for workspaces whose process has not terminated.
     * @param execution owning process
     * @param workspaceId immutable workspace identity
     * @param runnerId immutable runner placement
     * @param jobs accepted jobs
     */
    public RunnerWorkspaceState(ExecutionKey execution, UUID workspaceId, String runnerId, Map<UUID, Entry> jobs) {
        this(execution, workspaceId, runnerId, jobs, null);
    }

    /** Pins retention once in the same transaction as the terminal graph transition.
     * @param graph authoritative post-fold process
     * @param now authoritative store clock
     * @return workspace with a stable terminal timestamp
     */
    public RunnerWorkspaceState observeProcess(ProcessInstance graph, Instant now) {
        if (!execution.processInstanceId().equals(graph.processInstanceId())) throw new IllegalArgumentException("runner process scope mismatch");
        var observed = this;
        if (graph.terminationReason() == ai.ravenroot.api.application.ExecutionTerminationReason.CANCELLED) {
            // Process cancellation and every dispatched job's sticky stop request are one
            // store transaction. Unknown effects retain ownership; terminal evidence survives.
            for (var item : jobs.entrySet()) {
                var entry = item.getValue();
                var cancelled = entry.job().cancel(now);
                if (cancelled != entry.job() || entry.continuationUncertain()) {
                    observed = observed.replace(item.getKey(), entry.withJob(cancelled, false));
                }
            }
            var resources = new LinkedHashMap<>(observed.workspaces());
            resources.replaceAll((node, resource) -> resource.terminal() ? resource : resource.request("abort", true, now));
            observed = new RunnerWorkspaceState(execution, workspaceId, runnerId, observed.jobs(), processTerminalAt, resources);
        }
        if (graph.status().terminal() && graph.terminationReason() != ai.ravenroot.api.application.ExecutionTerminationReason.CANCELLED) {
            var resources = new LinkedHashMap<>(observed.workspaces());
            for (var resource : resources.values()) {
                if (resource.terminal()) continue;
                if (resource.profile().completionPolicy() == WorkspaceProfile.CompletionPolicy.REQUIRE_CLOSED) {
                    throw new IllegalStateException("close Workspace '" + resource.nodeId() + "' before ending the process");
                }
                resources.put(resource.nodeId(), resource.request("abort", true, now));
            }
            observed = new RunnerWorkspaceState(execution, workspaceId, runnerId, observed.jobs(), processTerminalAt, resources);
        }
        return graph.status().terminal() && processTerminalAt == null
                ? new RunnerWorkspaceState(execution, workspaceId, runnerId, observed.jobs(), now, observed.workspaces()) : observed;
    }
    /** Legacy storage-v1/v2 job cardinality bound; explicit resources use approved retainedJobs instead. */
    public static final int MAX_JOBS = 256;

    /**
     * Pinned continuation travels only between trusted control-plane components.
     * @param job accepted immutable job state
     * @param continuation bounded graph continuation, never sent to the runner
     * @param continuationUncertain whether partial successor delivery prevents automatic replay
     * @param workspaceNodeId graph resource identity, null only for legacy stored jobs
     * @param lifecycleCommand resource operation, null for an Agent invocation
     */
    public record Entry(RunnerJob job, OpaquePayload continuation, boolean continuationUncertain,
                        String workspaceNodeId, String lifecycleCommand) {
        /**
         * Restores a legacy entry without asserting an explicit resource reference.
         * @param job accepted fenced job
         * @param continuation pinned trusted graph checkpoint
         * @param continuationUncertain whether graph delivery needs operator reconciliation
         */
        public Entry(RunnerJob job, OpaquePayload continuation, boolean continuationUncertain) {
            this(job, continuation, continuationUncertain, null, null);
        }
        /**
         * Advances job state without changing its pinned graph/resource correlation.
         * @param replacement newly folded job state
         * @param uncertain resulting graph-delivery uncertainty marker
         * @return entry retaining its exact continuation and resource reference
         */
        public Entry withJob(RunnerJob replacement, boolean uncertain) {
            return new Entry(replacement, continuation, uncertain, workspaceNodeId, lifecycleCommand);
        }
        /**
         * Creates a newly parked continuation without an uncertain-delivery marker.
         * @param job accepted job state
         * @param continuation trusted graph checkpoint
         */
        public Entry(RunnerJob job, OpaquePayload continuation) { this(job, continuation, false); }
        /** Validates the bounded trusted checkpoint. */
        public Entry {
            Objects.requireNonNull(job); Objects.requireNonNull(continuation);
            if (continuation.size() > 4_194_304) throw new IllegalArgumentException("runner continuation too large");
        }
    }

    /** Validates unique attempt identities, placement and exclusive workspace ownership. */
    public RunnerWorkspaceState {
        Objects.requireNonNull(execution); Objects.requireNonNull(workspaceId);
        runnerId = RunnerPolicy.identifier(runnerId);
        jobs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(jobs));
        workspaces = Map.copyOf(workspaces);
        var resourceIds = new java.util.HashSet<UUID>();
        for (var item : workspaces.entrySet()) {
            if (!item.getKey().equals(item.getValue().nodeId()) || !resourceIds.add(item.getValue().workspaceId())
                    || !execution.tenantId().equals(item.getValue().profile().reference().tenantId())) {
                throw new IllegalArgumentException("invalid graph Workspace identity");
            }
        }
        if (workspaces.isEmpty() && jobs.size() > MAX_JOBS) throw new IllegalArgumentException("process runner job quota exceeded");
        var attempts = new java.util.HashSet<UUID>();
        int active = 0;
        for (var item : jobs.entrySet()) {
            RunnerJob job = item.getValue().job();
            var resource = item.getValue().workspaceNodeId() == null ? null : workspaces.get(item.getValue().workspaceNodeId());
            if (item.getValue().workspaceNodeId() != null && resource == null) throw new IllegalArgumentException("job workspace is missing");
            if (!item.getKey().equals(job.identity().runnerJobId())
                    || !execution.equals(job.identity().execution())
                    || !(resource == null ? runnerId : resource.runnerId()).equals(job.runner().runnerId())
                    || !attempts.add(job.identity().attemptId())) {
                throw new IllegalArgumentException("invalid workspace identity chain");
            }
            if (resource == null && job.retainsWorkspace()) active++;
        }
        if (active > 1) throw new IllegalArgumentException("overlapping workspace jobs");
    }

    /**
     * Folds only validated operations, using the adapter's authoritative clock and post-fold graph.
     * @param key exact owning process scope
     * @param current prior workspace state, or null before first admission
     * @param operation accepted control-plane operation
     * @param graph post-transition graph aggregate committed in the same transaction
     * @param now authoritative store time
     * @return validated next workspace state to commit atomically
     */
    public static RunnerWorkspaceState apply(ExecutionKey key, RunnerWorkspaceState current,
                                             RunnerJobOperation operation, ProcessInstance graph, Instant now) {
        Objects.requireNonNull(operation); Objects.requireNonNull(operation.jobId());
        if (!key.processInstanceId().equals(graph.processInstanceId())
                || (current != null && !key.equals(current.execution()))) {
            throw new IllegalArgumentException("runner workspace scope mismatch");
        }
        if (operation instanceof RunnerJobOperation.WorkspacePlace placement) {
            var resource = current == null ? null : current.workspaces().get(placement.workspaceNodeId());
            if (resource == null || resource.state() != WorkspaceResource.State.OPENING || resource.runtimeId() != null || resource.stopRequested()
                    || resource.profile().workspaceScope() == WorkspaceProfile.Scope.NAMED && resource.generation() > 1
                    || !placement.runner().tenantId().equals(key.tenantId()) || !placement.runner().labels().contains(resource.profile().runnerPool()))
                throw new IllegalStateException("Workspace placement is pinned or incompatible");
            var entries = new LinkedHashMap<>(current.jobs());
            entries.replaceAll((id, entry) -> resource.nodeId().equals(entry.workspaceNodeId())
                    ? entry.withJob(entry.job().place(placement.runner(), now), entry.continuationUncertain()) : entry);
            var resources = new LinkedHashMap<>(current.workspaces());
            resources.put(resource.nodeId(), new WorkspaceResource(resource.nodeId(), resource.workspaceId(), resource.profile(), placement.runner().runnerId(),
                    resource.state(), null, resource.checkpoint(), false, now, resource.generation()));
            return new RunnerWorkspaceState(key, current.workspaceId(), current.runnerId(), entries, current.processTerminalAt(), resources);
        }
        if (operation instanceof RunnerJobOperation.WorkspaceStop stop) {
            if (current == null || !current.workspaces().containsKey(stop.workspaceNodeId())) throw new IllegalArgumentException("Workspace not found");
            var resources = new LinkedHashMap<>(current.workspaces());
            resources.compute(stop.workspaceNodeId(), (node, resource) -> resource.request("abort", true, now));
            var entries = new LinkedHashMap<>(current.jobs());
            entries.replaceAll((id, entry) -> stop.workspaceNodeId().equals(entry.workspaceNodeId())
                    ? entry.withJob(entry.job().cancel(now), false) : entry);
            return new RunnerWorkspaceState(key, current.workspaceId(), current.runnerId(), entries, current.processTerminalAt(), resources);
        }
        if (operation instanceof RunnerJobOperation.WorkspaceStopped stopped) {
            if (current == null) throw new IllegalArgumentException("Workspace not found");
            var resource = current.workspaces().get(stopped.workspaceNodeId());
            if (resource == null || !resource.workspaceId().equals(stopped.workspaceId()) || !resource.runnerId().equals(stopped.runnerId())
                    || !resource.stopRequested()) throw new IllegalStateException("Workspace stop authority mismatch");
            var resources = new LinkedHashMap<>(current.workspaces());
            resources.put(resource.nodeId(), resource.observed("abort", resource.runtimeId(), null, now));
            return new RunnerWorkspaceState(key, current.workspaceId(), current.runnerId(), current.jobs(), current.processTerminalAt(), resources);
        }
        if (operation instanceof RunnerJobOperation.WorkspaceRelease || operation instanceof RunnerJobOperation.WorkspaceReleased) {
            String node = operation instanceof RunnerJobOperation.WorkspaceRelease value ? value.workspaceNodeId()
                    : ((RunnerJobOperation.WorkspaceReleased) operation).workspaceNodeId();
            UUID id = operation instanceof RunnerJobOperation.WorkspaceRelease value ? value.workspaceId()
                    : ((RunnerJobOperation.WorkspaceReleased) operation).workspaceId();
            String worker = operation instanceof RunnerJobOperation.WorkspaceRelease value ? value.runnerId()
                    : ((RunnerJobOperation.WorkspaceReleased) operation).runnerId();
            var resource = current == null ? null : current.workspaces().get(node);
            if (resource == null || !resource.workspaceId().equals(id) || !resource.runnerId().equals(worker))
                throw new IllegalStateException("Workspace cleanup authority mismatch");
            if (resource.state() == WorkspaceResource.State.RELEASED) return current;
            var retained = current.jobs().values().stream().filter(value -> node.equals(value.workspaceNodeId())).toList();
            var retention = retained.stream().map(value -> value.job().definition().workspaceRetention())
                    .reduce(resource.profile().retention(), (a, b) -> a.compareTo(b) >= 0 ? a : b);
            if (!resource.terminal() || current.processTerminalAt() == null || now.isBefore(current.processTerminalAt().plus(retention))
                    || retained.stream().anyMatch(value -> !value.job().state().terminal() || value.continuationUncertain()))
                throw new IllegalStateException("Workspace cleanup requires retained terminal quiescence");
            boolean acknowledged = operation instanceof RunnerJobOperation.WorkspaceReleased;
            if (acknowledged && resource.state() != WorkspaceResource.State.RELEASING)
                throw new IllegalStateException("Workspace cleanup was not reserved");
            var resources = new LinkedHashMap<>(current.workspaces());
            resources.put(node, new WorkspaceResource(node, id, resource.profile(), worker,
                    acknowledged ? WorkspaceResource.State.RELEASED : WorkspaceResource.State.RELEASING,
                    resource.runtimeId(), resource.checkpoint(), resource.stopRequested(), now, resource.generation()));
            return new RunnerWorkspaceState(key, current.workspaceId(), current.runnerId(), current.jobs(), current.processTerminalAt(), resources);
        }
        if (operation instanceof RunnerJobOperation.Submit submit) {
            if (graph.status().terminal()) throw new IllegalStateException("terminal process cannot admit runner work");
            if (!key.equals(submit.identity().execution())) throw new IllegalArgumentException("runner job scope mismatch");
            var traversal = graph.traversals().get(submit.identity().traversalId());
            var invocation = traversal == null ? null : traversal.invocations().get(submit.identity().invocationId());
            var attempt = invocation == null ? null : invocation.attempts().stream()
                    .filter(value -> value.attemptId().equals(submit.identity().attemptId())).findFirst().orElse(null);
            if (attempt == null || attempt.status() != NodeAttemptStatus.WAITING
                    || !invocation.command().name().equals(submit.command())) {
                throw new IllegalArgumentException("runner admission requires the exact parked graph attempt");
            }
            if (current == null) current = new RunnerWorkspaceState(key, submit.workspaceId(),
                    submit.runner().runnerId(), Map.of());
            if (submit.workspace() == null && (!current.workspaceId().equals(submit.workspaceId())
                    || !current.runnerId().equals(submit.runner().runnerId()))) {
                throw new IllegalArgumentException("workspace placement is immutable");
            }
            if (current.jobs().containsKey(submit.jobId())) {
                throw new IllegalStateException("runner attempt already admitted; load its accepted job");
            }
            String workspaceNode = submit.workspace() == null ? null : submit.workspace().nodeId();
            var siblings = current.jobs().values().stream().filter(value -> Objects.equals(workspaceNode, value.workspaceNodeId())).toList();
            if (siblings.stream().anyMatch(value -> value.continuationUncertain()
                    || ((workspaceNode == null || submit.lifecycleCommand() != null || value.lifecycleCommand() != null) && value.job().retainsWorkspace()))) {
                throw new IllegalStateException("workspace quiescence barrier has not passed");
            }
            if (submit.workspace() != null) {
                var resources = new LinkedHashMap<>(current.workspaces());
                var resource = resources.get(workspaceNode);
                if (resource == null) {
                    if (!"open".equals(submit.lifecycleCommand()) || submit.workspace().state() != WorkspaceResource.State.UNMATERIALIZED) {
                        throw new IllegalStateException("Workspace must be opened before use");
                    }
                    resource = submit.workspace();
                } else if (!resource.workspaceId().equals(submit.workspaceId())
                        || !resource.profile().equals(submit.workspace().profile())
                        || !resource.runnerId().equals(submit.runner().runnerId())) {
                    throw new IllegalStateException("workspace profile and placement are pinned");
                }
                if (submit.lifecycleCommand() == null) {
                    if (!resource.admitsAgent()) throw new IllegalStateException("workspace is not ready or has stopped");
                    if (!resource.profile().allowedAgents().contains(submit.definition().reference().name())) {
                        throw new IllegalArgumentException("agent is not authorized for this workspace profile");
                    }
                    if (siblings.stream().filter(value -> value.job().state() == RunnerJob.State.QUEUED).count()
                            >= resource.profile().capacity().queuedJobs()) throw new IllegalStateException("Workspace queue capacity exhausted");
                    if (resource.profile().capacity().admission() == WorkspaceProfile.Admission.REJECT) {
                        requireAgentCapacity(resource, siblings, submit.definition().commands().get(submit.command()).readOnly(), true);
                    }
                } else resource = resource.request(submit.lifecycleCommand(), false, now);
                if (current.jobs().values().stream().filter(value -> Objects.equals(workspaceNode, value.workspaceNodeId())).count()
                        >= resource.profile().capacity().retainedJobs()) throw new IllegalStateException("workspace retained job capacity reached");
                resources.put(workspaceNode, resource);
                current = new RunnerWorkspaceState(key, current.workspaceId(), current.runnerId(), current.jobs(), current.processTerminalAt(), resources);
            }
            RunnerJob job = RunnerJob.accept(submit.identity(), submit.definition(), submit.command(),
                    submit.deployment(), submit.runner(), submit.input(), now, submit.deadline());
            return current.replace(submit.jobId(), new Entry(job, submit.continuation(), false, workspaceNode, submit.lifecycleCommand()));
        }
        if (current == null || !current.jobs().containsKey(operation.jobId())) {
            throw new IllegalArgumentException("runner job not found");
        }
        Entry entry = current.jobs().get(operation.jobId());
        RunnerJob job = entry.job();
        if (operation instanceof RunnerJobOperation.Claim && entry.workspaceNodeId() != null) {
            var resource = current.workspaces().get(entry.workspaceNodeId());
            if (entry.lifecycleCommand() == null && !resource.admitsAgent()) throw new IllegalStateException("Workspace is not ready for claim");
            if (resource.stopRequested() && !"abort".equals(entry.lifecycleCommand())) throw new IllegalStateException("Workspace stop is sticky");
            var siblings = current.jobs().values().stream().filter(value -> entry.workspaceNodeId().equals(value.workspaceNodeId())
                    && !value.job().identity().runnerJobId().equals(operation.jobId())).toList();
            if (siblings.stream().anyMatch(value -> value.continuationUncertain())) throw new IllegalStateException("Workspace continuation requires reconciliation");
            if (entry.lifecycleCommand() == null) {
                // A later reader cannot indefinitely starve a queued writer. Same-class readers may share capacity.
                for (var sibling : current.jobs().values()) {
                    if (sibling == entry) break;
                    if (entry.workspaceNodeId().equals(sibling.workspaceNodeId()) && sibling.job().state() == RunnerJob.State.QUEUED
                            && (!job.command().readOnly() || !sibling.job().command().readOnly()))
                        throw new IllegalStateException("Workspace FIFO admission barrier");
                }
                requireAgentCapacity(resource, siblings, job.command().readOnly(), false);
            } else if (siblings.stream().anyMatch(value -> value.job().retainsWorkspace())) {
                throw new IllegalStateException("Workspace lifecycle requires quiescence");
            }
        }
        if (operation instanceof RunnerJobOperation.Complete complete && entry.workspaceNodeId() != null) {
            var observation = complete.result().workspace();
            if (observation == null || !observation.workspaceId().equals(current.workspaces().get(entry.workspaceNodeId())
                    .invocationWorkspaceId(operation.jobId(), entry.lifecycleCommand())))
                throw new IllegalArgumentException("terminal report requires exact Workspace evidence");
        }
        if (operation instanceof RunnerJobOperation.ContinuationUncertain) {
            if (!job.state().terminal()) throw new IllegalStateException("only a terminal runner result can have uncertain graph delivery");
            return entry.continuationUncertain() ? current : current.replace(operation.jobId(), entry.withJob(job, true));
        }
        if (operation instanceof RunnerJobOperation.ResolveContinuation resolution) {
            if (!job.state().terminal() || !entry.continuationUncertain()) {
                throw new IllegalStateException("runner continuation is not awaiting resolution");
            }
            var traversal = graph.traversals().get(job.identity().traversalId());
            var observed = traversal.invocations().values().stream()
                    .filter(value -> value.parentInvocationIds().contains(job.identity().invocationId()))
                    .collect(java.util.stream.Collectors.groupingBy(ai.ravenroot.api.application.NodeInvocation::nodeId,
                            java.util.stream.Collectors.counting()));
            switch (resolution.resolution()) {
                case RESUME -> {
                    if (!observed.isEmpty() || traversal.status().terminal()) {
                        throw new IllegalStateException("resume requires an open traversal with zero recorded successors");
                    }
                }
                case ACKNOWLEDGE -> {
                    var invocation = traversal.invocations().get(job.identity().invocationId());
                    if (!observed.equals(resolution.expectedSuccessors())
                            || invocation.status() != ai.ravenroot.api.application.NodeInvocationStatus.COMPLETED) {
                        throw new IllegalStateException("acknowledgement requires the complete recorded successor multiset");
                    }
                }
                case ABANDON -> {
                    if (!traversal.status().terminal()) throw new IllegalStateException("abandoned continuation requires a terminal traversal");
                }
            }
            return current.replace(operation.jobId(), entry.withJob(job, false));
        }
        RunnerJob next = switch (operation) {
            case RunnerJobOperation.Claim value -> job.claim(value.runnerId(), now, value.ttl());
            case RunnerJobOperation.Heartbeat value -> job.heartbeat(value.runnerId(), value.fence(), now, value.ttl());
            case RunnerJobOperation.Cancel ignored -> job.cancel(now);
            case RunnerJobOperation.Reconcile ignored -> job.reconcileLiveness(now);
            case RunnerJobOperation.ReconcileReport value -> job.beginReconciliation(value.runnerId(), now, value.ttl());
            case RunnerJobOperation.Complete value -> job.complete(value.runnerId(), value.fence(), value.result(), now);
            case RunnerJobOperation.Submit ignored -> throw new IllegalStateException("admission already handled");
            case RunnerJobOperation.ContinuationUncertain ignored -> throw new IllegalStateException("uncertainty already handled");
            case RunnerJobOperation.ResolveContinuation ignored -> throw new IllegalStateException("resolution already handled");
            case RunnerJobOperation.WorkspaceStop ignored -> throw new IllegalStateException("workspace stop already handled");
            case RunnerJobOperation.WorkspaceStopped ignored -> throw new IllegalStateException("workspace stop already handled");
            case RunnerJobOperation.WorkspaceRelease ignored -> throw new IllegalStateException("workspace cleanup already handled");
            case RunnerJobOperation.WorkspaceReleased ignored -> throw new IllegalStateException("workspace cleanup already handled");
            case RunnerJobOperation.WorkspacePlace ignored -> throw new IllegalStateException("workspace placement already handled");
        };
        var updated = next == job ? current : current.replace(operation.jobId(), entry.withJob(next, entry.continuationUncertain()));
        if (entry.workspaceNodeId() != null && next != job && next.state().terminal()) {
            var resources = new LinkedHashMap<>(updated.workspaces());
            var resource = resources.get(entry.workspaceNodeId());
            String expected = entry.lifecycleCommand() == null ? null : Map.of("open", "ready", "inspect", "inspected", "checkpoint", "checkpointed", "close", "closed", "abort", "aborted")
                    .get(entry.lifecycleCommand());
            if (next.state() == RunnerJob.State.COMPLETED && next.result() != null
                    && (expected == null || expected.equals(next.result().outcome()))) {
                var observation = next.result().workspace();
                resource = resource.observed(entry.lifecycleCommand() == null ? "inspect" : entry.lifecycleCommand(),
                        observation.runtimeId(), observation.checkpoint(), now);
            } else resource = new WorkspaceResource(resource.nodeId(), resource.workspaceId(), resource.profile(), resource.runnerId(),
                    WorkspaceResource.State.RECOVERY_REQUIRED, resource.runtimeId(), resource.checkpoint(), resource.stopRequested(), now, resource.generation());
            resources.put(entry.workspaceNodeId(), resource);
            updated = new RunnerWorkspaceState(key, updated.workspaceId(), updated.runnerId(), updated.jobs(), updated.processTerminalAt(), resources);
        }
        return updated;
    }
    private static void requireAgentCapacity(WorkspaceResource resource, java.util.Collection<Entry> siblings,
                                             boolean readOnly, boolean includeQueued) {
        var active = siblings.stream().filter(value -> value.job().retainsWorkspace()
                && (includeQueued || value.job().state() != RunnerJob.State.QUEUED)).toList();
        if (active.stream().anyMatch(value -> value.lifecycleCommand() != null || value.job().command().readOnly() != readOnly))
            throw new IllegalStateException("Workspace reader/writer handoff requires persisted quiescence");
        int capacity = readOnly ? resource.profile().capacity().readOnlyUsers() : resource.profile().capacity().mutatingUsers();
        if (active.size() >= capacity) throw new IllegalStateException("Workspace invocation capacity exhausted");
    }

    private RunnerWorkspaceState replace(UUID id, Entry entry) {
        var next = new LinkedHashMap<>(jobs);
        next.put(id, entry);
        return new RunnerWorkspaceState(execution, workspaceId, runnerId, next, processTerminalAt, workspaces);
    }
}
