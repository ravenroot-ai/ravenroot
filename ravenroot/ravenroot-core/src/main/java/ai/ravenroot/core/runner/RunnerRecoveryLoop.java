package ai.ravenroot.core.runner;

import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import ai.ravenroot.api.security.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/** Bounded tenant sweeps reconcile liveness and deliver already accepted terminal continuations. */
public final class RunnerRecoveryLoop implements AutoCloseable {
    private final RunnerJobService jobs;
    private final PinnedRunnerContinuationExecutor continuations;
    private final List<String> tenants;
    private final Clock clock;
    private final Map<String, String> cursors = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("runner-recovery").factory());
    private final LongAdder swept = new LongAdder(), conflicts = new LongAdder(), unknown = new LongAdder();
    public RunnerRecoveryLoop(RunnerJobService jobs, PinnedRunnerContinuationExecutor continuations,
                              Set<String> tenants, Clock clock) {
        this.jobs = jobs; this.continuations = continuations; this.tenants = List.copyOf(tenants); this.clock = clock;
        if (tenants.isEmpty()) throw new IllegalArgumentException("runner recovery tenants required");
    }
    public void start() { scheduler.scheduleWithFixedDelay(this::sweep, 0,
            jobs.controlConfiguration().recoveryInterval().toMillis(), TimeUnit.MILLISECONDS); }
    public synchronized void sweep() {
        for (String tenant : tenants) {
            try {
                var page = jobs.store().listProcessInstances(tenant,
                        ProcessInventoryQuery.everything(Math.min(jobs.controlConfiguration().recoveryPageSize(),
                                jobs.store().maxInventoryPageSize())).after(cursors.get(tenant))).toCompletableFuture().join();
                cursors.put(tenant, page.nextCursor().orElse(null));
                var measurements = new EnumMap<RunnerTelemetry.PageGauge, Long>(RunnerTelemetry.PageGauge.class);
                for (var gauge : RunnerTelemetry.PageGauge.values()) measurements.put(gauge, 0L);
                for (var process : page.items()) {
                    var workspace = jobs.store().loadRunnerWorkspace(process.key()).toCompletableFuture().join().orElse(null);
                    if (workspace == null) continue;
                    for (var resource : workspace.workspaces().values()) {
                        if (resource.state() == WorkspaceResource.State.RELEASED) continue;
                        add(measurements, RunnerTelemetry.PageGauge.RETAINED_WORKSPACES, 1);
                        if (!resource.terminal()) add(measurements, RunnerTelemetry.PageGauge.ACTIVE_WORKSPACES, 1);
                        add(measurements, resource.profile().runtimeLifecycle() == WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE
                                ? RunnerTelemetry.PageGauge.PER_WORKSPACE_WORKSPACES : RunnerTelemetry.PageGauge.PER_INVOCATION_WORKSPACES, 1);
                        add(measurements, RunnerTelemetry.PageGauge.RESERVED_STORAGE_BYTES, resource.profile().policy().limits().workspaceBytes());
                    }
                    var aggregate = jobs.store().load(process.key()).toCompletableFuture().join().state();
                    var actor = new SecurityContext("runner-recovery-" + UUID.randomUUID(), tenant, "runner-recovery",
                            PrincipalType.WORKLOAD, "ravenroot-control-plane");
                    for (var entry : new ArrayList<>(workspace.jobs().values()).reversed()) {
                        var job = entry.job(); swept.increment(); jobs.telemetry().increment(RunnerTelemetry.Counter.RECOVERY_OBSERVED);
                        if (job.state() == RunnerJob.State.QUEUED) add(measurements, RunnerTelemetry.PageGauge.QUEUED_JOBS, 1);
                        if (job.state() == RunnerJob.State.CANCELLING) add(measurements, RunnerTelemetry.PageGauge.CANCELLING_JOBS, 1);
                        if (job.result() != null) for (var artifact : job.result().artifacts())
                            add(measurements, RunnerTelemetry.PageGauge.RETAINED_ARTIFACT_BYTES, artifact.sizeBytes());
                        if (entry.continuationUncertain()) { observeUnknown(); continue; }
                        if (!job.state().terminal() && job.reconcileLiveness(clock.instant()) != job) {
                            job = jobs.mutate(actor, process.key(), new RunnerJobOperation.Reconcile(job.identity().runnerJobId()))
                                    .jobs().get(job.identity().runnerJobId()).job();
                        }
                        if (job.state() == RunnerJob.State.UNKNOWN) observeUnknown();
                        var invocation = aggregate.traversals().get(job.identity().traversalId()).invocations()
                                .get(job.identity().invocationId());
                        boolean parked = invocation.attempts().stream().anyMatch(attempt ->
                                attempt.attemptId().equals(entry.job().identity().attemptId())
                                && (attempt.status() == ai.ravenroot.api.application.NodeAttemptStatus.WAITING
                                    || attempt.status() == ai.ravenroot.api.application.NodeAttemptStatus.RUNNING
                                    || attempt.status() == ai.ravenroot.api.application.NodeAttemptStatus.COMPLETED));
                        if (job.state().terminal() && parked && !aggregate.status().terminal()) continuations.resume(process.key(), job.identity().runnerJobId())
                                .whenComplete((ignored, failure) -> { if (failure != null) observeConflict(); });
                    }
                }
                jobs.telemetry().recoveryPage(measurements);
            } catch (RuntimeException conflict) { observeConflict(); }
        }
    }
    private static void add(Map<RunnerTelemetry.PageGauge, Long> values, RunnerTelemetry.PageGauge key, long amount) {
        values.compute(key, (ignored, prior) -> amount > Long.MAX_VALUE - prior ? Long.MAX_VALUE : prior + amount);
    }
    private void observeUnknown() { unknown.increment(); jobs.telemetry().increment(RunnerTelemetry.Counter.UNKNOWN_OBSERVED); }
    private void observeConflict() { conflicts.increment(); jobs.telemetry().increment(RunnerTelemetry.Counter.RECOVERY_CONFLICT); }
    public Map<String, Long> metrics() {
        return Map.of("runnerJobsObserved", swept.sum(), "runnerRecoveryConflicts", conflicts.sum(), "runnerUnknownObserved", unknown.sum());
    }
    @Override public void close() { scheduler.shutdownNow(); continuations.close(); }
}
