package ai.ravenroot.core.runner;

import java.time.Duration;
import java.util.Objects;

/** Operator-owned control-plane capacity; independent of worker and Workspace capacity. */
public record RunnerControlConfiguration(int continuationThreads, int continuationQueue,
                                         int recoveryPageSize, Duration recoveryInterval,
                                         Duration continuationLease, Duration nodeTimeout) {
    public static RunnerControlConfiguration defaults() {
        return new RunnerControlConfiguration(4, 16, 16, Duration.ofSeconds(2),
                Duration.ofSeconds(30), Duration.ofSeconds(10));
    }
    public RunnerControlConfiguration {
        if (continuationThreads < 1 || continuationQueue < 1 || recoveryPageSize < 1)
            throw new IllegalArgumentException("control-plane capacities must be positive");
        for (var value : new Duration[]{recoveryInterval, continuationLease, nodeTimeout}) {
            Objects.requireNonNull(value);
            if (value.isNegative() || value.isZero() || value.toMillis() < 1)
                throw new IllegalArgumentException("control-plane timing must be at least one millisecond");
        }
    }
}
