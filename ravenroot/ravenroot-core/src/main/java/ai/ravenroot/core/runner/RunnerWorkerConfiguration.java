package ai.ravenroot.core.runner;

import java.time.Duration;
import java.util.Objects;

/** Operator-owned worker admission and supervision settings. Defaults are not upper bounds. */
public record RunnerWorkerConfiguration(int maxConcurrentJobs, Duration pollInterval,
                                        Duration heartbeatInterval, Duration cleanupTimeout,
                                        Duration shutdownTimeout, Duration leaseTtl, Duration availabilityTtl,
                                        Duration httpConnectTimeout, Duration httpRequestTimeout, int maxResponseBytes,
                                        Duration driverCommandTimeout, Duration driverOutputTimeout, int maxSupervisorOutputBytes) {
    public RunnerWorkerConfiguration(int maxConcurrentJobs, Duration pollInterval, Duration heartbeatInterval,
                                     Duration cleanupTimeout, Duration shutdownTimeout, Duration leaseTtl, Duration availabilityTtl,
                                     Duration httpConnectTimeout, Duration httpRequestTimeout, int maxResponseBytes) {
        this(maxConcurrentJobs, pollInterval, heartbeatInterval, cleanupTimeout, shutdownTimeout, leaseTtl, availabilityTtl,
                httpConnectTimeout, httpRequestTimeout, maxResponseBytes, Duration.ofSeconds(30), Duration.ofSeconds(10), 65_536);
    }
    public RunnerWorkerConfiguration(int maxConcurrentJobs, Duration pollInterval, Duration heartbeatInterval,
                                     Duration cleanupTimeout, Duration shutdownTimeout) {
        this(maxConcurrentJobs, pollInterval, heartbeatInterval, cleanupTimeout, shutdownTimeout,
                Duration.ofSeconds(30), heartbeatInterval.multipliedBy(3), Duration.ofSeconds(10), Duration.ofSeconds(30), 1_048_576);
    }
    public static RunnerWorkerConfiguration defaults() {
        return new RunnerWorkerConfiguration(4, Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofMinutes(1), Duration.ofSeconds(20));
    }

    public RunnerWorkerConfiguration {
        if (maxConcurrentJobs < 1) throw new IllegalArgumentException("worker capacity must be positive");
        if (maxResponseBytes < 1 || maxResponseBytes == Integer.MAX_VALUE
                || maxSupervisorOutputBytes < 1 || maxSupervisorOutputBytes == Integer.MAX_VALUE)
            throw new IllegalArgumentException("HTTP response byte ceiling must fit a Java byte array plus overflow sentinel");
        for (Duration value : new Duration[]{pollInterval, heartbeatInterval, cleanupTimeout, shutdownTimeout,
                leaseTtl, availabilityTtl, httpConnectTimeout, httpRequestTimeout, driverCommandTimeout, driverOutputTimeout}) {
            Objects.requireNonNull(value);
            if (value.isNegative() || value.isZero() || value.toMillis() < 1) {
                throw new IllegalArgumentException("worker timing must be at least one millisecond");
            }
        }
        if (leaseTtl.toSeconds() < 1 || leaseTtl.getNano() != 0 || heartbeatInterval.compareTo(leaseTtl) >= 0
                || heartbeatInterval.compareTo(availabilityTtl) >= 0)
            throw new IllegalArgumentException("whole-second lease TTL and availability TTL must exceed heartbeat interval");
    }
}
