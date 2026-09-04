package ai.ravenroot.server.approval;

import ai.ravenroot.core.recovery.ExecutionRecoveryService;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Single-threaded production driver for bounded durable approval recovery sweeps. */
public final class ToolApprovalRecoveryDriver implements AutoCloseable {
    private final ExecutionRecoveryService recovery;
    private final ScheduledExecutorService executor;
    private final Duration interval;

    public ToolApprovalRecoveryDriver(ExecutionRecoveryService recovery, Duration interval) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "ravenroot-tool-approval-recovery");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::sweepAllQuietly, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void sweepTenant(String tenantId) {
        executor.execute(() -> sweepQuietly(tenantId));
    }

    private void sweepAllQuietly() {
        try { recovery.sweepOnce(); } catch (RuntimeException unavailable) { /* retry next bounded tick */ }
    }

    private void sweepQuietly(String tenantId) {
        try { recovery.sweepOnce(tenantId); } catch (RuntimeException unavailable) { /* periodic retry */ }
    }

    @Override public void close() {
        executor.shutdownNow();
    }
}
