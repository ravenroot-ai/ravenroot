package ai.ravenroot.core.runner;

/** Bounded numeric runner telemetry: no tenant, job, runner, graph, credential or content labels. */
public interface RunnerTelemetry {
    /** Fixed process-local observation counters, not unique durable job totals. */
    enum Counter { RECOVERY_OBSERVED, UNKNOWN_OBSERVED, RECOVERY_CONFLICT, WORKER_FAILURE }
    void increment(Counter counter);
    void activeJobs(int count);

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
            if (count < 0 || count > 4) throw new IllegalArgumentException("invalid bounded runner worker count");
            delegate.activeJobs(count);
        }
    }
}
