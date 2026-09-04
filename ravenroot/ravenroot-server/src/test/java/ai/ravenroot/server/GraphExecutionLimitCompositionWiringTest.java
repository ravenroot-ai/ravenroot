package ai.ravenroot.server;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
        assertTrue(source.contains("recoveryConfiguration.leaseTtl(),\n                    graphExecutionLimits)"),
                () -> MAIN + " must give approval re-entry the same limits as live execution");
        assertTrue(source.contains("approvalDispatcher, graphExecutionLimits.maxRecoveryDeliveriesPerAttempt())"),
                () -> MAIN + " must bound production recovery delivery attempts from operator configuration");
    }
}
