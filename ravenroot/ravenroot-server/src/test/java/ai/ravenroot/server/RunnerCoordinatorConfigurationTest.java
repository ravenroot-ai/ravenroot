package ai.ravenroot.server;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunnerCoordinatorConfigurationTest {
    @Test void coordinatorCapacityIsOperatorOwnedAndIndependentOfWorkerCapacity() {
        assertEquals(RunnerCoordinatorConfiguration.DEFAULTS, RunnerCoordinatorConfiguration.fromEnvironment(Map.of()));
        for (int capacity : new int[] {2, 37}) {
            var value = RunnerCoordinatorConfiguration.fromEnvironment(Map.of("RAVENROOT_PORT", "8188",
                    "RAVENROOT_RUNNER_COORDINATOR_HTTP_THREADS", Integer.toString(capacity),
                    "RAVENROOT_RUNNER_COORDINATOR_HTTP_QUEUE", Integer.toString(capacity * 3)));
            assertEquals(8188, value.port()); assertEquals(capacity, value.httpThreads());
            assertEquals(capacity * 3, value.httpQueue());
        }
    }
    @Test void invalidCapacityAndUnrepresentablePortsFailClosed() {
        for (String name : new String[] {"RAVENROOT_PORT", "RAVENROOT_RUNNER_COORDINATOR_HTTP_THREADS", "RAVENROOT_RUNNER_COORDINATOR_HTTP_QUEUE"})
            for (String value : new String[] {"0", "-1", "unbounded", "2147483648"})
                assertThrows(IllegalArgumentException.class, () -> RunnerCoordinatorConfiguration.fromEnvironment(Map.of(name, value)));
        assertThrows(IllegalArgumentException.class, () -> RunnerCoordinatorConfiguration.fromEnvironment(Map.of("RAVENROOT_PORT", "65536")));
    }
}
