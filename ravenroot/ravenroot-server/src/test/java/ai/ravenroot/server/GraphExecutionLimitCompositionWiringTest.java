package ai.ravenroot.server;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the operator-owned graph limits into both live and recovered production execution. */
class GraphExecutionLimitCompositionWiringTest {
    private static final Path MAIN =
            Path.of("src/main/java/ai/ravenroot/server/RavenrootServerMain.java")
                    .toAbsolutePath().normalize();

    @Test
    void shippedCompositionUsesOneConfigurationForLiveReentryAndRecoveryDeliveryLimits()
            throws Exception {
        String source = Files.readString(MAIN);

        assertTrue(source.contains("GraphExecutionLimits.fromEnvironment(System.getenv())"),
                () -> MAIN + " must read operator graph limits");
        assertEquals(2, source.split(
                        "recoveryConfiguration\\.leaseTtl\\(\\), graphExecutionLimits, agentBudgets,",
                        -1).length - 1,
                () -> MAIN + " must give tool and human re-entry the same limits and agent-budget "
                        + "lifecycle as live execution");
        // The same pinning, one argument further along: re-entry must also verify against the same
        // manifest service the acceptance path pinned with. A second resolver built beside it would
        // agree until the day one of the two composition sites was updated and the other was not.
        assertEquals(2, source.split(
                        "graphExecutionLimits, agentBudgets,\\s*\\n\\s*executionManifests\\)", -1)
                        .length - 1,
                () -> MAIN + " must give tool and human re-entry the application's own manifest "
                        + "verification service");
        assertTrue(source.contains("application.executionManifests()"),
                () -> MAIN + " must take that service from the composed application rather than "
                        + "building a second resolver beside it");
        assertTrue(source.contains("graphExecutionLimits.maxRecoveryDeliveriesPerAttempt())"),
                () -> MAIN + " must bound production recovery delivery attempts from operator configuration");
    }
}
