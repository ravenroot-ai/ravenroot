package ai.ravenroot.server.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RateLimitConfigurationTest {
    @Test
    void absentAndBlankValuesUseTheShippedDefaults() {
        Map<String, String> blanks = settingNames()
                .collect(Collectors.toUnmodifiableMap(Function.identity(), ignored -> " \t "));

        assertEquals(RateLimitConfiguration.DEFAULTS, RateLimitConfiguration.fromEnvironment(Map.of()));
        assertEquals(RateLimitConfiguration.DEFAULTS, RateLimitConfiguration.fromEnvironment(blanks));
    }

    @Test
    void nonblankValuesRetainTheExistingAsciiTrimContract() {
        assertEquals(17, RateLimitConfiguration.fromEnvironment(
                Map.of("RAVENROOT_RATELIMIT_ADDRESS_RPS", " 17 ")).addressRequestsPerSecond());
        assertInvalidInteger("RAVENROOT_RATELIMIT_ADDRESS_RPS", "\u00a017\u00a0");
    }

    @ParameterizedTest
    @MethodSource("settingNames")
    void malformedAndOverflowingValuesHaveCauseFreeSettingOnlyDiagnostics(String name) {
        assertInvalidInteger(name, "operator-secret-not-a-number");
        assertInvalidInteger(name, "2147483648");
    }

    @ParameterizedTest
    @MethodSource("settingNames")
    void everyRateLimitRefusesZeroAndNegativeValues(String name) {
        assertNull(assertThrows(IllegalArgumentException.class,
                () -> RateLimitConfiguration.fromEnvironment(Map.of(name, "0"))).getCause());
        assertNull(assertThrows(IllegalArgumentException.class,
                () -> RateLimitConfiguration.fromEnvironment(Map.of(name, "-1"))).getCause());
    }

    @Test
    void documentedBoundariesRemainAccepted() {
        RateLimitConfiguration configuration = RateLimitConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_RATELIMIT_ADDRESS_RPS", "1",
                "RAVENROOT_RATELIMIT_ADDRESS_BURST", "1000000",
                "RAVENROOT_RATELIMIT_IDLE_TTL_SECONDS", "1",
                "RAVENROOT_RATELIMIT_EXECUTION_MAX_AGE_SECONDS", "86400"));

        assertEquals(1, configuration.addressRequestsPerSecond());
        assertEquals(1_000_000, configuration.addressBurst());
        assertEquals(1, configuration.idleEntryTtl().toSeconds());
        assertEquals(86_400, configuration.executionMaxAge().toSeconds());
    }

    @Test
    void existingBurstAndCrossFieldConstraintsRemainFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> RateLimitConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_RATELIMIT_ADDRESS_BURST", "19")));
        assertThrows(IllegalArgumentException.class, () -> RateLimitConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_RATELIMIT_ADDRESS_BURST", "1000001")));
        assertThrows(IllegalArgumentException.class, () -> RateLimitConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_RATELIMIT_TENANT_STREAMS", "4",
                "RAVENROOT_RATELIMIT_PRINCIPAL_STREAMS", "5")));
        assertThrows(IllegalArgumentException.class, () -> RateLimitConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_RATELIMIT_MAX_HEADER_BYTES", "8",
                "RAVENROOT_RATELIMIT_MAX_HEADER_VALUE_BYTES", "9")));
        assertThrows(IllegalArgumentException.class, () -> RateLimitConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_RATELIMIT_IDLE_TTL_SECONDS", "3601")));
        assertThrows(IllegalArgumentException.class, () -> RateLimitConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_RATELIMIT_EXECUTION_MAX_AGE_SECONDS", "86401")));
    }

    private static void assertInvalidInteger(String name, String raw) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RateLimitConfiguration.fromEnvironment(Map.of(name, raw)));
        assertEquals(name + " must be an integer", failure.getMessage());
        assertNull(failure.getCause());
    }

    private static Stream<String> settingNames() {
        return Stream.of(
                "RAVENROOT_RATELIMIT_ADDRESS_RPS",
                "RAVENROOT_RATELIMIT_ADDRESS_BURST",
                "RAVENROOT_RATELIMIT_TENANT_RPS",
                "RAVENROOT_RATELIMIT_TENANT_BURST",
                "RAVENROOT_RATELIMIT_PRINCIPAL_RPS",
                "RAVENROOT_RATELIMIT_PRINCIPAL_BURST",
                "RAVENROOT_RATELIMIT_SUBMISSION_RPS",
                "RAVENROOT_RATELIMIT_SUBMISSION_BURST",
                "RAVENROOT_RATELIMIT_TENANT_CONCURRENT_SUBMISSIONS",
                "RAVENROOT_RATELIMIT_GLOBAL_ACTIVE_EXECUTIONS",
                "RAVENROOT_RATELIMIT_TENANT_STREAMS",
                "RAVENROOT_RATELIMIT_PRINCIPAL_STREAMS",
                "RAVENROOT_SSE_QUEUE_CAPACITY",
                "RAVENROOT_RATELIMIT_MAX_QUERY_BYTES",
                "RAVENROOT_RATELIMIT_MAX_QUERY_PARAMETERS",
                "RAVENROOT_RATELIMIT_MAX_HEADER_COUNT",
                "RAVENROOT_RATELIMIT_MAX_HEADER_BYTES",
                "RAVENROOT_RATELIMIT_MAX_HEADER_VALUE_BYTES",
                "RAVENROOT_RATELIMIT_MAX_TRACKED_CLIENTS",
                "RAVENROOT_RATELIMIT_MAX_TRACKED_TENANTS",
                "RAVENROOT_RATELIMIT_MAX_TRACKED_PRINCIPALS",
                "RAVENROOT_RATELIMIT_IDLE_TTL_SECONDS",
                "RAVENROOT_RATELIMIT_EXECUTION_MAX_AGE_SECONDS");
    }
}
