package ai.ravenroot.core.runner;

import ai.ravenroot.api.runner.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Four bounded worker slots; the control plane alone serializes each process workspace. */
public final class RunnerWorker implements AutoCloseable {
    private final RemoteRunnerClient client;
    private final RunnerDriver driver;
    private final ScheduledExecutorService polling = Executors.newScheduledThreadPool(5,
            Thread.ofPlatform().daemon(true).name("runner-poll-", 0).factory());
    private final ConcurrentMap<UUID, Slot> active = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong failures = new AtomicLong();
    private String cursor;
    public RunnerWorker(RemoteRunnerClient client, RunnerDriver driver) { this.client = client; this.driver = driver; }
    public void start() throws Exception {
        client.register(driver.registration());
        polling.scheduleWithFixedDelay(this::tick, 0, 10, TimeUnit.SECONDS);
    }
    public synchronized void tick() {
        if (closed.get() || active.size() >= 4) return;
        try {
            var page = client.assignments(cursor);
            cursor = (String) page.get("nextCursor");
            if (!(page.get("items") instanceof List<?> items)) throw new IllegalArgumentException("invalid runner inventory");
            for (var raw : items) {
                if (closed.get() || active.size() >= 4) return;
                var item = RunnerJson.map(raw);
                UUID process = UUID.fromString(RunnerJson.text(item, "processInstanceId"));
                if (Boolean.TRUE.equals(item.get("cleanup"))) {
                    driver.release(client.release(process)).toCompletableFuture().get(60, TimeUnit.SECONDS);
                    continue;
                }
                UUID id = UUID.fromString(RunnerJson.text(item, "runnerJobId"));
                if (active.containsKey(id)) continue;
                var assignment = client.assignment(process, id);
                var state = assignment.job().state();
                if (state == RunnerJob.State.QUEUED) assignment = client.claim(process, id, false);
                else if (state == RunnerJob.State.UNKNOWN) assignment = client.claim(process, id, true);
                else if (state != RunnerJob.State.RECONCILING && state != RunnerJob.State.CANCELLING) continue;
                var slot = new Slot(assignment);
                active.put(id, slot);
                // A separate heartbeat task keeps admission and cleanup from starving each live job.
                slot.heartbeat = polling.scheduleWithFixedDelay(() -> renew(id, slot), 10, 10, TimeUnit.SECONDS);
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
                                if (failure != null) { failures.incrementAndGet(); return; }
                                client.complete(slot.assignment, result);
                            }
                        } catch (Exception lostAcknowledgement) { failures.incrementAndGet(); }
                        finally { slot.heartbeat.cancel(false); active.remove(id, slot); }
                    });
                } catch (RuntimeException refused) {
                    slot.heartbeat.cancel(false); active.remove(id, slot); throw refused;
                }
            }
        } catch (Exception unavailable) {
            failures.incrementAndGet();
        }
    }
    private void renew(UUID id, Slot slot) {
        synchronized (slot) {
            if (closed.get() || active.get(id) != slot) return;
            try {
                slot.assignment = client.heartbeat(slot.assignment);
                if (slot.assignment.job().stopReason() != RunnerJob.StopReason.NONE) driver.cancel(slot.assignment);
            } catch (Exception unavailable) {
                failures.incrementAndGet();
                driver.cancel(slot.assignment);
            }
        }
    }
    public long protocolFailures() { return failures.get(); }
    public int activeJobs() { return active.size(); }
    @Override public void close() {
        closed.set(true); polling.shutdownNow();
        var stops = new ArrayList<CompletableFuture<Void>>();
        for (var slot : active.values()) try { stops.add(driver.cancel(slot.assignment).toCompletableFuture()); }
        catch (RuntimeException refused) { failures.incrementAndGet(); }
        try { CompletableFuture.allOf(stops.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS); }
        catch (Exception unconfirmed) { failures.incrementAndGet(); }
        driver.close();
    }
    private static final class Slot {
        private volatile RunnerAssignment assignment;
        private ScheduledFuture<?> heartbeat;
        private Slot(RunnerAssignment assignment) { this.assignment = assignment; }
    }
}
