package ai.ravenroot.api.deployment.registry;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable operational policy for a durable deployment registry.
 *
 * <p>The three registry adapters used to repeat the same page and lease values, while each durable
 * adapter repeated the command-ledger retention as well. Keeping those values here gives an
 * embedder one typed decision to pass to whichever durable adapter it selects. It does not make the
 * registry policy an execution-store policy: deployment leases, deployment pages and deployment
 * command replay are separate contracts and remain independently configurable.</p>
 *
 * @param commandRetention how long a recorded idempotent deployment command remains replayable
 * @param limits the page and lease bounds published by the registry
 */
public record DeploymentRegistryPolicy(Duration commandRetention,
                                       DeploymentRegistry.Limits limits) {

    /** The durable-adapter policy shipped by Ravenroot. */
    public static final DeploymentRegistryPolicy DEFAULTS = new DeploymentRegistryPolicy(
            Duration.ofDays(7),
            new DeploymentRegistry.Limits(100, Duration.ofMinutes(5), Duration.ofSeconds(5)));

    public DeploymentRegistryPolicy {
        Objects.requireNonNull(commandRetention, "commandRetention");
        Objects.requireNonNull(limits, "limits");
        if (commandRetention.isZero() || commandRetention.isNegative()) {
            throw new IllegalArgumentException("commandRetention must be positive");
        }
    }

    /** A copy with an explicitly selected durable command-retention window. */
    public DeploymentRegistryPolicy withCommandRetention(Duration retention) {
        return new DeploymentRegistryPolicy(retention, limits);
    }

    /**
     * Reference-adapter limits. Its clock is the caller's clock, so its correct skew allowance is
     * zero while the page and lease bounds remain the shared defaults.
     */
    public static DeploymentRegistry.Limits inMemoryLimits() {
        return new DeploymentRegistry.Limits(DEFAULTS.limits().maximumPageSize(),
                DEFAULTS.limits().maximumLeaseTtl(), Duration.ZERO);
    }
}
