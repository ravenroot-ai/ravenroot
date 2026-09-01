package ai.ravenroot.server.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The derivation that guarantees a tenant cannot occupy the whole execution ceiling.
 *
 * <p>The per-tenant share has no environment variable of its own, and the tests below are the reason:
 * a separate setting would be one an operator could raise to the ceiling, at which point the guarantee
 * would be gone without anything failing. Derivation makes it hold for every ceiling that can be
 * configured, which is a claim worth checking across the range rather than at one convenient value.</p>
 */
class ExecutionQuotaConfigurationTest {
    @Test
    void aTenantShareIsAlwaysBelowTheCeilingAboveAceilingOfOne() {
        for (int ceiling = 2; ceiling <= 4096; ceiling++) {
            var limits = withGlobalCeiling(ceiling);
            assertTrue(limits.tenantActiveExecutions() < ceiling,
                    "ceiling " + ceiling + " let one tenant hold all of it");
            assertTrue(limits.tenantActiveExecutions() >= 1,
                    "ceiling " + ceiling + " left a tenant unable to run anything");
            assertEquals(ceiling - limits.reservedExecutionHeadroom(), limits.tenantActiveExecutions());
        }
    }

    /**
     * The one case where the guarantee cannot hold, stated rather than hidden.
     *
     * <p>With a ceiling of one there is a single slot and whoever holds it holds all of them. Rounding
     * the share to zero would be worse: no tenant could ever run anything.</p>
     */
    @Test
    void aCeilingOfOneCannotReserveHeadroomAndSaysSo() {
        var limits = withGlobalCeiling(1);

        assertEquals(1, limits.reservedExecutionHeadroom());
        assertEquals(1, limits.tenantActiveExecutions());
    }

    @Test
    void theDerivationAtTheShippingDefaults() {
        var defaults = RateLimitConfiguration.DEFAULTS;

        assertEquals(64, defaults.globalActiveExecutions());
        assertEquals(8, defaults.reservedExecutionHeadroom());
        assertEquals(56, defaults.tenantActiveExecutions());
        assertEquals(Duration.ofHours(1), defaults.executionMaxAge());
        assertEquals(256, defaults.maxTrackedExecutions());
    }

    @Test
    void theTrackedExecutionCeilingScalesWithTheGlobalCeilingAndNeverFallsBelowItsFloor() {
        assertEquals(256, withGlobalCeiling(1).maxTrackedExecutions());
        assertEquals(256, withGlobalCeiling(64).maxTrackedExecutions());
        assertEquals(4_000, withGlobalCeiling(1_000).maxTrackedExecutions());
        assertTrue(withGlobalCeiling(Integer.MAX_VALUE).maxTrackedExecutions() > 0,
                "the tracked-execution ceiling overflowed at the largest configurable global ceiling");
    }

    @Test
    void theExecutionAgeOutIsReadFromTheEnvironmentAndBounded() {
        assertEquals(Duration.ofSeconds(120), RateLimitConfiguration.fromEnvironment(
                Map.of("RAVENROOT_RATELIMIT_EXECUTION_MAX_AGE_SECONDS", "120")).executionMaxAge());
        assertEquals(RateLimitConfiguration.DEFAULTS.executionMaxAge(),
                RateLimitConfiguration.fromEnvironment(Map.of()).executionMaxAge());

        for (String rejected : java.util.List.of("0", "-1", "86401")) {
            assertThrows(IllegalArgumentException.class, () -> RateLimitConfiguration.fromEnvironment(
                            Map.of("RAVENROOT_RATELIMIT_EXECUTION_MAX_AGE_SECONDS", rejected)),
                    rejected + " was accepted as an execution age-out");
        }
    }

    private static RateLimitConfiguration withGlobalCeiling(int ceiling) {
        var defaults = RateLimitConfiguration.DEFAULTS;
        return new RateLimitConfiguration(
                defaults.addressRequestsPerSecond(), defaults.addressBurst(),
                defaults.tenantRequestsPerSecond(), defaults.tenantBurst(),
                defaults.principalRequestsPerSecond(), defaults.principalBurst(),
                defaults.submissionsPerSecond(), defaults.submissionBurst(),
                defaults.tenantConcurrentSubmissions(), ceiling,
                defaults.tenantConcurrentStreams(), defaults.principalConcurrentStreams(),
                defaults.streamQueueCapacity(),
                defaults.maxQueryBytes(), defaults.maxQueryParameters(),
                defaults.maxHeaderCount(), defaults.maxHeaderBytes(), defaults.maxHeaderValueBytes(),
                defaults.maxTrackedClients(), defaults.maxTrackedTenants(),
                defaults.maxTrackedPrincipals(), defaults.idleEntryTtl(), defaults.executionMaxAge());
    }
}
