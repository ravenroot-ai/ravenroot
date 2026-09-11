package ai.ravenroot.api.deployment.registry;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentRegistryPolicyTest {
    @Test
    void defaultsAreOneTypedDurableRegistryDecision() {
        assertEquals(Duration.ofDays(7), DeploymentRegistryPolicy.DEFAULTS.commandRetention());
        assertEquals(100, DeploymentRegistryPolicy.DEFAULTS.limits().maximumPageSize());
        assertEquals(Duration.ofMinutes(5), DeploymentRegistryPolicy.DEFAULTS.limits().maximumLeaseTtl());
        assertEquals(Duration.ofSeconds(5), DeploymentRegistryPolicy.DEFAULTS.limits().maxClockSkew());
    }

    @Test
    void invalidRetentionAndOverflowingLookaheadAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DeploymentRegistryPolicy(
                Duration.ZERO, DeploymentRegistryPolicy.DEFAULTS.limits()));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentRegistry.Limits(
                Integer.MAX_VALUE, Duration.ofMinutes(5), Duration.ofSeconds(5)));
    }

    @Test
    void referenceRegistryUsesTheSameBoundsWithNoClockSkew() {
        var limits = DeploymentRegistryPolicy.inMemoryLimits();
        assertEquals(DeploymentRegistryPolicy.DEFAULTS.limits().maximumPageSize(), limits.maximumPageSize());
        assertEquals(DeploymentRegistryPolicy.DEFAULTS.limits().maximumLeaseTtl(), limits.maximumLeaseTtl());
        assertEquals(Duration.ZERO, limits.maxClockSkew());
    }
}
