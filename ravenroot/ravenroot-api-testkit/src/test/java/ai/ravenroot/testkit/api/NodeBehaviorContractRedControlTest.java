package ai.ravenroot.testkit.api;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Proves a package that runs without one declared adapter cannot pass the reusable contract. */
class NodeBehaviorContractRedControlTest {
    private static final int TOTAL_CONTRACT_TESTS = 10;

    @Test
    void noncompliantAdapterBindingFailsOnlyTheBlankBindingContract() {
        var results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(NonCompliantBlankAdapterBindingContract.class))
                .execute();

        results.testEvents().assertStatistics(statistics ->
                statistics.started(TOTAL_CONTRACT_TESTS).failed(1).succeeded(TOTAL_CONTRACT_TESTS - 1));
        var failures = results.testEvents().failed().list();
        assertEquals(1, failures.size());
        assertEquals("everyBlankAdapterBindingRefusesWithoutProducingAResult()",
                failures.getFirst().getTestDescriptor().getDisplayName());

        Throwable failure = failures.getFirst().getRequiredPayload(TestExecutionResult.class)
                .getThrowable().orElseThrow();
        assertTrue(failure.getMessage().contains("partial-adapter-guard"), failure.getMessage());
        assertTrue(failure.getMessage().contains("operatorChannel"), failure.getMessage());
        assertTrue(failure.getMessage().contains("produced a NodeResult"), failure.getMessage());
    }
}
