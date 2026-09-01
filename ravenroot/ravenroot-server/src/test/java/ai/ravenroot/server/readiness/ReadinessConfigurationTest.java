package ai.ravenroot.server.readiness;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadinessConfigurationTest {

    @Test
    void anEmptyEnvironmentProducesTheDocumentedDefaults() {
        var configuration = ReadinessConfiguration.fromEnvironment(Map.of());

        assertEquals(Duration.ofMillis(500), configuration.storeCheckTimeout());
        assertEquals(Duration.ofSeconds(6), configuration.drainGracePeriod());
        assertEquals(Duration.ofSeconds(10), configuration.httpStopDelay());
        assertEquals(configuration, ReadinessConfiguration.defaults());
    }

    @Test
    void eachVariableOverridesOnlyItsOwnField() {
        var configuration = ReadinessConfiguration.fromEnvironment(Map.of(
                ReadinessConfiguration.TIMEOUT_VARIABLE, "250",
                ReadinessConfiguration.DRAIN_GRACE_PERIOD_VARIABLE, "1500",
                ReadinessConfiguration.HTTP_STOP_DELAY_VARIABLE, "20"));

        assertEquals(Duration.ofMillis(250), configuration.storeCheckTimeout());
        assertEquals(Duration.ofMillis(1500), configuration.drainGracePeriod());
        assertEquals(Duration.ofSeconds(20), configuration.httpStopDelay());
    }

    @Test
    void drainGracePeriodMayBeZeroButNotNegative() {
        // Zero is a legitimate choice (skip the grace window entirely, e.g. for a fast local
        // dev loop); negative is not a duration at all.
        var configuration = ReadinessConfiguration.fromEnvironment(
                Map.of(ReadinessConfiguration.DRAIN_GRACE_PERIOD_VARIABLE, "0"));
        assertEquals(Duration.ZERO, configuration.drainGracePeriod());

        assertThrows(IllegalArgumentException.class,
                () -> new ReadinessConfiguration(Duration.ofMillis(500), Duration.ofMillis(-1),
                        Duration.ofSeconds(10)));
    }

    @Test
    void storeCheckTimeoutAndHttpStopDelayMustBePositiveNotJustNonNegative() {
        // Unlike the grace period, a zero timeout or a zero stop delay is not a permissive edge
        // case -- it degenerates the check/drain into never running at all, silently.
        assertThrows(IllegalArgumentException.class,
                () -> new ReadinessConfiguration(Duration.ZERO, Duration.ofSeconds(6), Duration.ofSeconds(10)));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadinessConfiguration(Duration.ofMillis(500), Duration.ofSeconds(6), Duration.ZERO));
    }

    @Test
    void httpStopDelayAboveIntSecondsIsRejectedAtConstructionNotAtShutdown() {
        // RavenrootServer#close() passes httpStopDelay to HttpServer.stop(int) as
        // (int) httpStopDelay.toSeconds(). With 3000000000, the cast wraps to -1294967296 and stop()
        // throws "IllegalArgumentException: negative
        // delay parameter" -- not here, but hours or days later, at the next graceful shutdown (a
        // restart or rollout), the most expensive moment to diagnose a mistake made at deploy time.
        // Caught here instead, at construction, so the operator sees it before the server ever starts --
        // and, held to the same standard as aNonNumericValueNamesTheOffendingVariableInTheFailure below,
        // sees which variable, which value they typed, and the limit as a number to compare it against.
        var thrown = assertThrows(IllegalArgumentException.class, () -> ReadinessConfiguration.fromEnvironment(
                Map.of(ReadinessConfiguration.HTTP_STOP_DELAY_VARIABLE, "3000000000")));
        assertEquals(true, thrown.getMessage().contains(ReadinessConfiguration.HTTP_STOP_DELAY_VARIABLE),
                () -> "must name the offending variable: " + thrown.getMessage());
        assertEquals(true, thrown.getMessage().contains("3000000000"),
                () -> "must report the value the operator typed: " + thrown.getMessage());
        assertEquals(true, thrown.getMessage().contains(String.valueOf(Integer.MAX_VALUE)),
                () -> "must report the limit as a number, not a source constant name: " + thrown.getMessage());
    }

    @Test
    void aNegativeOrZeroHttpStopDelayNamesTheOffendingVariableInTheFailure() {
        // The upper- and lower-bound failures for this variable must both name its environment key;
        // the lower bound goes through requirePositive in the constructor --
        // naming the field ("httpStopDelay"), not the variable, and printing a Duration in
        // ISO-8601 form instead of what the operator typed. "-3000000000" and "0" are the exact
        // boundary values under test.
        for (String raw : new String[] {"-3000000000", "0"}) {
            var thrown = assertThrows(IllegalArgumentException.class, () -> ReadinessConfiguration.fromEnvironment(
                    Map.of(ReadinessConfiguration.HTTP_STOP_DELAY_VARIABLE, raw)));
            assertEquals(true, thrown.getMessage().contains(ReadinessConfiguration.HTTP_STOP_DELAY_VARIABLE),
                    () -> "must name the offending variable: " + thrown.getMessage());
            assertEquals(true, thrown.getMessage().contains(raw),
                    () -> "must report the value the operator typed: " + thrown.getMessage());
            assertEquals(false, thrown.getMessage().contains("PT"),
                    () -> "must not leak a java.time.Duration ISO-8601 rendering: " + thrown.getMessage());
        }
    }

    @Test
    void aNegativeOrZeroTimeoutOrDrainGracePeriodAlsoNamesTheOffendingVariable() {
        // The same field-name-not-variable-name defect was measured on the other
        // two millisOrDefault-backed variables too (millisOrDefault never checked sign; only the
        // constructor's requirePositive/isNegative did, after the variable name was already out of
        // scope). All three are validated together; validating only httpStopDelay would leave two
        // thirds of the incoherence standing.
        var timeoutThrown = assertThrows(IllegalArgumentException.class, () -> ReadinessConfiguration.fromEnvironment(
                Map.of(ReadinessConfiguration.TIMEOUT_VARIABLE, "0")));
        assertEquals(true, timeoutThrown.getMessage().contains(ReadinessConfiguration.TIMEOUT_VARIABLE),
                () -> "must name the offending variable: " + timeoutThrown.getMessage());
        assertEquals(false, timeoutThrown.getMessage().contains("PT"),
                () -> "must not leak a java.time.Duration ISO-8601 rendering: " + timeoutThrown.getMessage());

        var drainThrown = assertThrows(IllegalArgumentException.class, () -> ReadinessConfiguration.fromEnvironment(
                Map.of(ReadinessConfiguration.DRAIN_GRACE_PERIOD_VARIABLE, "-1")));
        assertEquals(true, drainThrown.getMessage().contains(ReadinessConfiguration.DRAIN_GRACE_PERIOD_VARIABLE),
                () -> "must name the offending variable: " + drainThrown.getMessage());
        assertEquals(false, drainThrown.getMessage().contains("PT"),
                () -> "must not leak a java.time.Duration ISO-8601 rendering: " + drainThrown.getMessage());
    }

    @Test
    void aNonNumericValueNamesTheOffendingVariableInTheFailure() {
        for (String variable : new String[] {ReadinessConfiguration.TIMEOUT_VARIABLE,
                ReadinessConfiguration.DRAIN_GRACE_PERIOD_VARIABLE, ReadinessConfiguration.HTTP_STOP_DELAY_VARIABLE}) {
            var thrown = assertThrows(IllegalArgumentException.class,
                    () -> ReadinessConfiguration.fromEnvironment(Map.of(variable, "not-a-number")));
            assertEquals(true, thrown.getMessage().contains(variable), () -> variable + " must be named in: "
                    + thrown.getMessage());
        }
    }
}
