package ai.ravenroot.server.recovery;

import ai.ravenroot.core.recovery.ExecutionRecoveryService;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Single-threaded production driver for bounded recovery sweeps.
 *
 * <p>Named for what it drives rather than for the first caller that needed it. It began as the
 * approval-recovery driver because durable tool approvals were the only continuation a sweep could
 * dispatch; the sweep has never been approval-specific, and it is now composed wherever there is
 * durable state to recover, so a name that said otherwise would misdescribe every deployment that
 * runs it without approvals.</p>
 *
 * <p>One thread on purpose. Sweeps claim work under a lease and write under a fence, and two of them
 * running concurrently in the same process would compete for the same claims to no benefit: the
 * store hands each item to one claimant, so the second thread's only effect would be contention.</p>
 */
public final class ExecutionRecoveryDriver implements AutoCloseable {
    private final ExecutionRecoveryService recovery;
    private final ScheduledExecutorService executor;
    private final Duration interval;

    /**
     * Composes the driver over the sweep it runs.
     *
     * @param recovery the bounded sweep to run on each tick.
     * @param interval delay between the end of one sweep and the start of the next.
     */
    public ExecutionRecoveryDriver(ExecutionRecoveryService recovery, Duration interval) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "ravenroot-execution-recovery");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Begins sweeping every configured tenant on the configured cadence. */
    public void start() {
        executor.scheduleWithFixedDelay(this::sweepAllQuietly, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Queues one bounded sweep of a single tenant onto the same thread as the periodic sweep.
     *
     * @param tenantId tenant whose outstanding work should be swept now.
     */
    public void sweepTenant(String tenantId) {
        executor.execute(() -> sweepQuietly(tenantId));
    }

    private void sweepAllQuietly() {
        try { recovery.sweepOnce(); } catch (RuntimeException unavailable) { /* retry next bounded tick */ }
    }

    private void sweepQuietly(String tenantId) {
        try { recovery.sweepOnce(tenantId); } catch (RuntimeException unavailable) { /* periodic retry */ }
    }

    /** Stops the sweep thread; in-flight store calls are interrupted rather than awaited. */
    @Override public void close() {
        executor.shutdownNow();
    }
}
