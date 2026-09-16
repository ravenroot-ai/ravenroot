package ai.ravenroot.core.runner;

import ai.ravenroot.api.runner.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Operator-sized worker admission; durable control-plane fences authorize every execution. */
public final class RunnerWorker implements AutoCloseable {
    private final RemoteRunnerClient client;
    private final RunnerDriver driver;
    private final RunnerWorkerConfiguration configuration;
    private final ScheduledExecutorService polling;
    private final ConcurrentMap<UUID, Slot> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong failures = new AtomicLong();
    private final RunnerTelemetry.Relay telemetry = new RunnerTelemetry.Relay();
    public RunnerTelemetry.Relay telemetry() { return telemetry; }
    private void failure() { failures.incrementAndGet(); telemetry.increment(RunnerTelemetry.Counter.WORKER_FAILURE); }
    private synchronized void observeActive() {
        telemetry.activeJobs(active.size()); telemetry.workerCapacity(capacity(), availableJobs());
    }
    private String cursor;
    public RunnerWorker(RemoteRunnerClient client, RunnerDriver driver) {
        this(client, driver, RunnerWorkerConfiguration.defaults());
    }
    public RunnerWorker(RemoteRunnerClient client, RunnerDriver driver, RunnerWorkerConfiguration configuration) {
        this.client = Objects.requireNonNull(client); this.driver = Objects.requireNonNull(driver);
        this.configuration = Objects.requireNonNull(configuration);
        polling = Executors.newScheduledThreadPool(Math.addExact(configuration.maxConcurrentJobs(), 1),
                Thread.ofPlatform().daemon(true).name("runner-poll-", 0).factory());
    }
    public void start() throws Exception {
        client.register(driver.registration());
        advertise();
        observeActive();
        polling.scheduleWithFixedDelay(this::tick, 0, configuration.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        polling.scheduleWithFixedDelay(() -> { try { advertise(); } catch (Exception unavailable) {
            failure(); active.values().forEach(slot -> driver.cancel(slot.assignment));
        } }, configuration.heartbeatInterval().toMillis(), configuration.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
    }
    private void advertise() throws Exception {
        if (!driver.runtimeProfiles().isEmpty()) client.availability(capacity(), activeJobs(), driver.runtimeProfiles(), configuration.availabilityTtl());
    }
    public synchronized void tick() {
        if (closed.get()) return;
        try {
            var page = client.assignments(cursor);
            cursor = (String) page.get("nextCursor");
            if (!(page.get("items") instanceof List<?> items)) throw new IllegalArgumentException("invalid runner inventory");
            for (var raw : items) {
                if (closed.get()) return;
                var item = RunnerJson.map(raw);
                UUID process = UUID.fromString(RunnerJson.text(item, "processInstanceId"));
                if (Boolean.TRUE.equals(item.get("workspaceStop"))) {
                    var stop = client.assignment(process, UUID.fromString(RunnerJson.text(item, "runnerJobId")));
                    driver.stopWorkspace(stop).toCompletableFuture().get(configuration.cleanupTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    client.workspaceStopped(stop); continue;
                }
                if (Boolean.TRUE.equals(item.get("cleanup"))) {
                    String node = (String) item.get("workspaceNodeId");
                    var release = client.release(process, node);
                    driver.release(release).toCompletableFuture()
                            .get(configuration.cleanupTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    client.workspaceReleased(process, node, release);
                    continue;
                }
                if (active.size() >= configuration.maxConcurrentJobs()) continue;
                UUID id = UUID.fromString(RunnerJson.text(item, "runnerJobId"));
                if (active.containsKey(id)) continue;
                var assignment = client.assignment(process, id);
                var state = assignment.job().state();
                try {
                    if (state == RunnerJob.State.QUEUED) assignment = client.claim(process, id, false);
                    else if (state == RunnerJob.State.UNKNOWN) assignment = client.claim(process, id, true);
                    else if (state != RunnerJob.State.RECONCILING && state != RunnerJob.State.CANCELLING) continue;
                } catch (RemoteRunnerClient.ProtocolRefusal refusal) {
                    // Another workspace may be runnable even when this FIFO head awaits readers,
                    // a process resume, or another coordinator's winning claim. Never bypass its fence.
                    if (refusal.status() == 409) continue;
                    throw refusal;
                }
                var slot = new Slot(assignment);
                active.put(id, slot);
                observeActive();
                // A separate heartbeat task keeps admission and cleanup from starving each live job.
                slot.heartbeat = polling.scheduleWithFixedDelay(() -> renew(id, slot),
                        configuration.heartbeatInterval().toMillis(), configuration.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
                boolean execute = state == RunnerJob.State.QUEUED;
                var accepted = assignment;
                try {
                    var future = execute ? driver.execute(accepted)
                            : accepted.job().stopReason() != RunnerJob.StopReason.NONE
                                ? driver.cancel(accepted).thenCompose(ignored -> driver.reconcile(accepted))
                                : driver.reconcile(accepted);
                    future.whenComplete((result, failure) -> {
                        try {
                            synchronized (slot) {
                                if (failure != null) { failure(); return; }
                                client.complete(slot.assignment, result);
                            }
                        } catch (Exception lostAcknowledgement) { failure(); }
                        finally { slot.heartbeat.cancel(false); active.remove(id, slot); observeActive(); }
                    });
                } catch (RuntimeException refused) {
                    slot.heartbeat.cancel(false); active.remove(id, slot); observeActive(); throw refused;
                }
            }
        } catch (Exception unavailable) {
            failure();
        }
    }
    private void renew(UUID id, Slot slot) {
        synchronized (slot) {
            if (closed.get() || active.get(id) != slot) return;
            try {
                slot.assignment = client.heartbeat(slot.assignment);
                if (slot.assignment.job().stopReason() != RunnerJob.StopReason.NONE) driver.cancel(slot.assignment);
            } catch (Exception unavailable) {
                failure();
                driver.cancel(slot.assignment);
            }
        }
    }
    public long protocolFailures() { return failures.get(); }
    public int activeJobs() { return active.size(); }
    public int capacity() { return configuration.maxConcurrentJobs(); }
    public int availableJobs() { return Math.max(0, capacity() - activeJobs()); }
    @Override public void close() {
        closed.set(true); polling.shutdownNow();
        var stops = new ArrayList<CompletableFuture<Void>>();
        for (var slot : active.values()) try { stops.add(driver.cancel(slot.assignment).toCompletableFuture()); }
        catch (RuntimeException refused) { failure(); }
        try { CompletableFuture.allOf(stops.toArray(CompletableFuture[]::new)).get(configuration.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS); }
        catch (Exception unconfirmed) { failure(); }
        driver.close();
    }
    private static final class Slot {
        private volatile RunnerAssignment assignment;
        private ScheduledFuture<?> heartbeat;
        private Slot(RunnerAssignment assignment) { this.assignment = assignment; }
    }
}
