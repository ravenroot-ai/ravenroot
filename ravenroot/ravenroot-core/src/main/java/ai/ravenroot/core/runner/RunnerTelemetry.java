package ai.ravenroot.core.runner;

/** Bounded numeric runner telemetry: no tenant, job, runner, graph, credential or content labels. */
public interface RunnerTelemetry {
    /** Fixed process-local observation counters, not unique durable job totals. */
    enum Counter { RECOVERY_OBSERVED, UNKNOWN_OBSERVED, RECOVERY_CONFLICT, WORKER_FAILURE,
        JOB_COMPLETED, JOB_CANCELLED, JOB_DEADLINE_EXCEEDED, WORKSPACE_CHECKPOINTED, WORKSPACE_STOPPED, WORKSPACE_RELEASED }
    /** Last bounded recovery page, not a fleet total; names cannot contain user-defined identities. */
    enum PageGauge { QUEUED_JOBS, ACTIVE_WORKSPACES, RETAINED_WORKSPACES, PER_INVOCATION_WORKSPACES,
        PER_WORKSPACE_WORKSPACES, RESERVED_STORAGE_BYTES, RETAINED_ARTIFACT_BYTES, CANCELLING_JOBS }
    void increment(Counter counter);
    void activeJobs(int count);
    default void workerCapacity(int configured, int available) { }
    default void recoveryPage(java.util.Map<PageGauge, Long> values) { }

    /** Composition relay keeps the core independent of the telemetry SDK. */
    final class Relay implements RunnerTelemetry {
        private static final RunnerTelemetry DISCARD = new RunnerTelemetry() {
            public void increment(Counter counter) { }
            public void activeJobs(int count) { }
        };
        private volatile RunnerTelemetry delegate = DISCARD;
        public void install(RunnerTelemetry value) { delegate = java.util.Objects.requireNonNull(value); }
        public void clear() { delegate = DISCARD; }
        public void increment(Counter counter) { delegate.increment(counter); }
        public void activeJobs(int count) {
            if (count < 0) throw new IllegalArgumentException("invalid runner worker count");
            delegate.activeJobs(count);
        }
        public void workerCapacity(int configured, int available) {
            if (configured < 1 || available < 0 || available > configured) throw new IllegalArgumentException("invalid worker capacity observation");
            delegate.workerCapacity(configured, available);
        }
        public void recoveryPage(java.util.Map<PageGauge, Long> values) {
            if (values.values().stream().anyMatch(value -> value == null || value < 0))
                throw new IllegalArgumentException("invalid recovery page observation");
            delegate.recoveryPage(java.util.Map.copyOf(values));
        }
    }
}
