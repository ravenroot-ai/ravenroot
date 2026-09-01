package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionLookup;
import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.application.ExecutionOutcome;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate that existed but nothing walked through.
 *
 * <p>The SEC-09 refusal policy for unknown behaviors is selected by the shipped runtime only when the
 * corresponding environment mode is configured. This class proves that selecting it genuinely fails
 * a run, and provides the counterpart proof that
 * the default was not quietly reversed while doing so.</p>
 *
 * <p>Driven through the production application composition rather than the public inline HTTP route:
 * that route is intentionally fixed to TEST_PASSTHROUGH and must not provide a back door to
 * operational behavior execution. The environment-selected policy still reaches the Standard
 * runner used by deployment/application work.</p>
 */
class UnknownBehaviorRefusalSelectionTest {

    /** Middle node names a behavior no deployment has. Start and end are ordinary. */
    private static final String GRAPH_WITH_ABSENT_BEHAVIOR = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="absent-behavior" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="does-nothing"><data key="kind">BEHAVIOR</data><data key="behavior">not-in-the-catalog</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="does-nothing"><data key="outcome">continue</data></edge>
                <edge id="e2" source="does-nothing" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    private static DefaultRavenrootApplication applicationWith(UnknownBehaviorPolicy policy,
                                                                PekkoExecutionEngine engine) {
        // The same environment the ordinary constructors compose, so the only difference between this
        // server and a stock one is the policy under test.
        var environment = ai.ravenroot.core.runtime.BehaviorEnvironment.safeDefaults();
        return new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                ai.ravenroot.core.runtime.BehaviorRegistry.standard(environment), environment.artifacts(),
                environment.programRuntime(),
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), null, 0, policy);
    }

    /** Starts genuine Standard work and polls the application result registry to terminal state. */
    private static ExecutionOutcome runAndReadResult(DefaultRavenrootApplication application) throws Exception {
        UUID executionId = UUID.randomUUID();
        application.startGraphMl(new SecurityContext("unknown-behavior-test", "tenant-test", "fixture",
                        PrincipalType.WORKLOAD, "urn:ravenroot:test"), executionId,
                new ByteArrayInputStream(GRAPH_WITH_ABSENT_BEHAVIOR.getBytes(StandardCharsets.UTF_8)), "probe",
                ExecutionPolicy.STANDARD);
        for (int attempt = 0; attempt < 100; attempt++) {
            if (application.executionResult("tenant-test", executionId) instanceof ExecutionLookup.Found found
                    && found.outcome().status() != ProcessInstanceStatus.RUNNING) {
                return found.outcome();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("execution did not become terminal");
    }

    /**
     * With refusal selected, a graph naming an absent behavior fails.
     *
     * <p>Also asserts the two negatives that stop a refusal from merely looking like the pass-through
     * it refuses to do: the run must not report itself degraded, and no node may be listed as
     * defaulted. A refusal that still emitted {@code NODE_DEFAULTED} would be a third outcome nobody
     * asked for.</p>
     */
    @Test
    void withRefusalSelectedAGraphNamingAnAbsentBehaviorFails() throws Exception {
        // Selected the way an operator selects it -- through the variable -- and deliberately NOT by
        // handing UnknownBehaviorPolicy.refuse() straight to the application. Build the policy the
        // way production builds it so severing the variable-to-policy wiring makes this test fail.
        var selected = UnknownBehaviorConfiguration.fromEnvironment(
                Map.of(UnknownBehaviorConfiguration.VARIABLE, "refuse"));
        try (var engine = new PekkoExecutionEngine("unknown-behavior-refuse")) {
            var application = applicationWith(selected.policy(), engine);
            ExecutionOutcome result = runAndReadResult(application);

            assertEquals(ProcessInstanceStatus.FAILED, result.status(),
                    "refusal selected, yet the run did not fail");
            assertFalse(result.defaultedNodes().contains("does-nothing"));
            assertFalse(result.degraded());
        }
    }

    /**
     * The counterpart preserves the shipped default rather than simply flipping a boolean: it is
     * unchanged, and the same graph still completes while reporting honestly that it was degraded.
     */
    @Test
    void underTheShippedDefaultTheSameGraphCompletesButReportsItselfDegraded() throws Exception {
        try (var engine = new PekkoExecutionEngine("unknown-behavior-default")) {
            var application = applicationWith(UnknownBehaviorConfiguration.fromEnvironment(Map.of()).policy(),
                    engine);
            ExecutionOutcome result = runAndReadResult(application);

            assertEquals(ProcessInstanceStatus.COMPLETED, result.status());
            assertTrue(result.defaultedNodes().contains("does-nothing"));
            assertTrue(result.degraded());
        }
    }

    @Test
    void onlyTheExactRefuseValueOptsIn() {
        assertTrue(UnknownBehaviorConfiguration.fromEnvironment(
                Map.of(UnknownBehaviorConfiguration.VARIABLE, "refuse")).refuse());
        assertTrue(UnknownBehaviorConfiguration.fromEnvironment(
                Map.of(UnknownBehaviorConfiguration.VARIABLE, "  REFUSE ")).refuse(),
                "trimmed and case-insensitive, like every other flag this server reads");

        // Absent, blank, unrecognised and a plausible near-miss all mean the default. An unrecognised
        // value must not fail startup: a typo becoming an outage is worse than a typo being ignored.
        assertFalse(UnknownBehaviorConfiguration.fromEnvironment(Map.of()).refuse());
        assertFalse(UnknownBehaviorConfiguration.fromEnvironment(
                Map.of(UnknownBehaviorConfiguration.VARIABLE, "")).refuse());
        assertFalse(UnknownBehaviorConfiguration.fromEnvironment(
                Map.of(UnknownBehaviorConfiguration.VARIABLE, "true")).refuse());
        assertFalse(UnknownBehaviorConfiguration.fromEnvironment(
                Map.of(UnknownBehaviorConfiguration.VARIABLE, "pass-through")).refuse());
        assertEquals("pass-through", UnknownBehaviorConfiguration.passThrough().describe());
        assertEquals("refuse", UnknownBehaviorConfiguration.refusing().describe());
    }
}
