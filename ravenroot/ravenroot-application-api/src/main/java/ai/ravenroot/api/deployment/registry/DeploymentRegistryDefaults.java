package ai.ravenroot.api.deployment.registry;

import java.time.Duration;

/** Named default policy for deployment-registry adapters. */
public final class DeploymentRegistryDefaults {
    private static final int MAXIMUM_PAGE_SIZE = 100;
    private static final Duration MAXIMUM_LEASE_TTL = Duration.ofMinutes(5);
    private static final Duration DURABLE_CLOCK_SKEW = Duration.ofSeconds(5);
    private static final Duration DURABLE_COMMAND_RETENTION = Duration.ofDays(7);

    private DeploymentRegistryDefaults() {
    }

    /**
     * Returns defaults for an adapter whose authority and caller share one process and clock.
     *
     * @return a fresh immutable limits value with no clock-skew allowance.
     */
    public static DeploymentRegistry.Limits inProcessLimits() {
        return new DeploymentRegistry.Limits(
                MAXIMUM_PAGE_SIZE, MAXIMUM_LEASE_TTL, Duration.ZERO);
    }

    /**
     * Returns defaults for an adapter used across independently clocked processes or hosts.
     *
     * @return a fresh immutable limits value with the durable clock-skew allowance.
     */
    public static DeploymentRegistry.Limits durableLimits() {
        return new DeploymentRegistry.Limits(
                MAXIMUM_PAGE_SIZE, MAXIMUM_LEASE_TTL, DURABLE_CLOCK_SKEW);
    }

    /**
     * Returns the default period after which a durable command-ledger row may be purged.
     *
     * @return seven-day command-ledger retention.
     */
    public static Duration durableCommandRetention() {
        return DURABLE_COMMAND_RETENTION;
    }
}
