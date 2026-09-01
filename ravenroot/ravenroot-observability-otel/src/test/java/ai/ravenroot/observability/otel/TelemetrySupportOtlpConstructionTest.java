package ai.ravenroot.observability.otel;

import ai.ravenroot.core.runtime.ExecutionMonitor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Permanent coverage for the previously untested real OTLP exporter path, which is exactly the path
 * the JDK-sender fix changed. An exploratory probe showed {@code install()} against a dead endpoint
 * constructs cleanly (SDK
 * construction opens no connection) and {@code close()} does not throw either (a failed flush is
 * logged internally by the OpenTelemetry SDK, not propagated as an exception). That probe was
 * discarded afterward; this is the same check kept as permanent regression coverage.
 *
 * <p>Measured, not assumed: this test takes ~5.1s, not "near-instant" as a first draft of this
 * comment claimed before being run. The loopback connection refusal itself is immediate; the
 * elapsed time is the SDK's own default shutdown/flush timeout being waited out, not network
 * latency. No real network egress occurs either way, and the bound is deterministic (it does not
 * hang indefinitely), which is the property this test actually needs.</p>
 */
class TelemetrySupportOtlpConstructionTest {

    @Test
    void installAgainstADeadEndpointConstructsAndClosesWithoutThrowingOrHangingOnNetworkIo() {
        var configuration = TelemetryConfiguration.fromEnvironment(Map.of(
                TelemetryConfiguration.ENABLED_VARIABLE, "true",
                TelemetryConfiguration.EXPORTER_VARIABLE, "otlp",
                // Loopback, port 1: no listener is possible and no real network egress occurs, but
                // close() still takes ~5.1s -- see this class's own Javadoc for why (the SDK's own
                // default shutdown timeout, not connection time).
                TelemetryConfiguration.ENDPOINT_VARIABLE, "http://127.0.0.1:1"));
        var monitor = new ExecutionMonitor();

        var telemetry = assertDoesNotThrow(() -> TelemetrySupport.install(configuration, monitor),
                "constructing the OTLP sender must not itself attempt a connection");
        assertTrue(telemetry.isPresent());
        // Bounded, not merely non-throwing: a close() that hung indefinitely against a dead
        // endpoint would be a worse defect than one that threw, and "does not throw" alone would
        // not catch it. 15s gives real margin over the ~5.1s measured, without being so loose that
        // a regression to an actually-unbounded wait would still pass.
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> telemetry.get().close(),
                "a flush attempt against an unreachable endpoint must be bounded, not hang indefinitely");
    }
}
