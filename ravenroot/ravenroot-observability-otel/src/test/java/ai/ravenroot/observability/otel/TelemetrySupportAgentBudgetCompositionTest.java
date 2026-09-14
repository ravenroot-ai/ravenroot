package ai.ravenroot.observability.otel;

import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.security.nodepackage.AgentBudgetTelemetry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetrySupportAgentBudgetCompositionTest {
    @Test
    void runnerRelayUsesTheSameDisabledEnabledAndCloseLifecycle() throws Exception {
        var relay = new ai.ravenroot.core.runner.RunnerTelemetry.Relay();
        var staleCalls = new AtomicLong();
        var stale = new ai.ravenroot.core.runner.RunnerTelemetry() {
            public void increment(Counter counter) { staleCalls.incrementAndGet(); }
            public void activeJobs(int count) { staleCalls.incrementAndGet(); }
        };
        relay.install(stale);
        assertTrue(TelemetrySupport.install(TelemetryConfiguration.disabled(), new ExecutionMonitor(), null, relay).isEmpty());
        relay.activeJobs(1);
        relay.increment(ai.ravenroot.core.runner.RunnerTelemetry.Counter.RECOVERY_CONFLICT);
        assertEquals(0, staleCalls.get());
        relay.install(stale);
        var configuration = TelemetryConfiguration.fromEnvironment(Map.of(
                TelemetryConfiguration.ENABLED_VARIABLE, "true", TelemetryConfiguration.EXPORTER_VARIABLE, "logging"));
        var installed = TelemetrySupport.install(configuration, new ExecutionMonitor(), null, relay).orElseThrow();
        relay.activeJobs(4);
        relay.increment(ai.ravenroot.core.runner.RunnerTelemetry.Counter.WORKER_FAILURE);
        assertEquals(0, staleCalls.get());
        installed.close();
        relay.activeJobs(0);
        relay.increment(ai.ravenroot.core.runner.RunnerTelemetry.Counter.UNKNOWN_OBSERVED);
        assertEquals(0, staleCalls.get());
    }

    @Test
    void disabledTelemetryRestoresTheProductionRelayToDiscarding() {
        var relay = new AgentBudgetTelemetry.Relay();
        var staleSinkCalls = new AtomicLong();
        relay.install((dimension, outcome, amount) -> staleSinkCalls.incrementAndGet());

        assertTrue(TelemetrySupport.install(TelemetryConfiguration.disabled(),
                new ExecutionMonitor(), relay).isEmpty());
        relay.record(AgentBudgetTelemetry.Dimension.TEAM_ACTIVE,
                AgentBudgetTelemetry.Outcome.RESERVED, 1);

        assertEquals(0, staleSinkCalls.get(),
                "disabled composition must leave the production relay on its discard sink");
    }

    @Test
    void enabledTelemetryInstallsTheBridgeAndCloseRestoresDiscarding() throws Exception {
        var relay = new AgentBudgetTelemetry.Relay();
        var staleSinkCalls = new AtomicLong();
        relay.install((dimension, outcome, amount) -> staleSinkCalls.incrementAndGet());
        var configuration = TelemetryConfiguration.fromEnvironment(Map.of(
                TelemetryConfiguration.ENABLED_VARIABLE, "true",
                TelemetryConfiguration.EXPORTER_VARIABLE, "logging"));

        var installed = TelemetrySupport.install(configuration, new ExecutionMonitor(), relay);
        assertTrue(installed.isPresent());
        relay.record(AgentBudgetTelemetry.Dimension.TEAM_CUMULATIVE,
                AgentBudgetTelemetry.Outcome.USED, 1);
        assertEquals(0, staleSinkCalls.get(),
                "enabled composition must replace the prior sink with the OpenTelemetry bridge");

        installed.orElseThrow().close();
        relay.record(AgentBudgetTelemetry.Dimension.TEAM_ACTIVE,
                AgentBudgetTelemetry.Outcome.RELEASED, 1);
        assertEquals(0, staleSinkCalls.get(),
                "close must restore discard behavior rather than an earlier or closed sink");
    }
}
