package ai.ravenroot.cli;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The fail-closed mode is reachable from the CLI's embedded path, not only from the server.
 *
 * <p>Running a graph from the CLI is the shortest way anyone tries this product — shorter than
 * starting a server — so a mode that existed only behind {@code RavenrootServerMain} would be a mode
 * a first-time user never finds.</p>
 *
 * <p>The policy is built here <strong>the way {@code RavenrootCliMain} builds it</strong>:
 * {@link UnknownBehaviorPolicy#fromEnvironment} over a map, not {@link UnknownBehaviorPolicy#refuse()}
 * handed straight in. That distinction is the whole point. The first draft of the server's equivalent
 * test did the latter, and it stayed green with the variable-to-policy wiring severed — it proved the
 * half that already worked. Severing that wiring must red this test, or this test is decoration.</p>
 */
class EmbeddedUnknownBehaviorRefusalTest {

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

    /**
     * Composes through {@link RavenrootCliMain#embeddedApplication} — production's own wiring, driven
     * by an environment map — rather than building an application here. Building one here would prove
     * that {@code DefaultRavenrootApplication} honours a policy, while leaving the CLI root free to
     * stop passing one without a single test noticing.
     */
    private static CliBackend embeddedBackendUnder(Map<String, String> environmentVariables,
                                                   PekkoExecutionEngine engine) {
        var application = RavenrootCliMain.embeddedApplication(engine, new ExecutionMonitor(),
                environmentVariables);
        var authorized = new AuthorizedRavenrootApplication(application,
                new DefaultAuthorizationService(event -> { }), event -> { }, true);
        var context = new RequestContext("embedded-331-request", "embedded-caller",
                PrincipalType.USER, "urn:ravenroot:embedded-331", "local", Set.of(Role.PLATFORM_ADMIN),
                Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                        .map(AuthorizationAction::requiredScope).collect(Collectors.toUnmodifiableSet()));
        return new EmbeddedBackend(authorized, context);
    }

    private static CliBackend.ResultView runAndRead(CliBackend backend) throws Exception {
        var submission = backend.run(GRAPH_WITH_ABSENT_BEHAVIOR.getBytes(StandardCharsets.UTF_8), "probe");
        for (int attempt = 1; attempt <= 12; attempt++) {
            var view = backend.result(submission.executionId());
            if (!"RUNNING".equals(view.status())) {
                return view;
            }
            if (attempt == 12) {
                fail("execution never reached a terminal status; last was " + view.status());
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("unreachable");
    }

    @Test
    void refusalSelectedThroughTheVariableFailsARunFromTheEmbeddedCliPath() throws Exception {
        var selected = Map.of(UnknownBehaviorPolicy.ENVIRONMENT_VARIABLE, "refuse");
        try (var engine = new PekkoExecutionEngine("cli-unknown-behavior-refuse-" + UUID.randomUUID())) {

            var view = runAndRead(embeddedBackendUnder(selected, engine));

            assertEquals("FAILED", view.status(),
                    () -> "refusal selected through " + UnknownBehaviorPolicy.ENVIRONMENT_VARIABLE
                            + ", yet the embedded CLI run did not fail: " + view);
            assertFalse(view.degraded(),
                    () -> "a refused run is failed, not degraded: " + view);
            assertTrue(view.defaultedNodes().isEmpty(),
                    () -> "a refused node must never be reported as defaulted: " + view);
        }
    }

    @Test
    void theEmbeddedDefaultStillCompletesAndReportsItselfDegraded() throws Exception {
        try (var engine = new PekkoExecutionEngine("cli-unknown-behavior-default-" + UUID.randomUUID())) {

            var view = runAndRead(embeddedBackendUnder(Map.of(), engine));

            assertEquals("COMPLETED", view.status(), () -> view.toString());
            assertEquals(java.util.List.of("does-nothing"), view.defaultedNodes(),
                    () -> "the node that did nothing must be named: " + view);
            assertTrue(view.degraded(),
                    () -> "completing while skipping work must not read as a clean run: " + view);
        }
    }

    /** Both composition roots read one variable through one parser; this pins that they agree. */
    @Test
    void theCliAndTheServerReadTheSameSettingThroughTheSameParser() {
        assertEquals("RAVENROOT_UNKNOWN_BEHAVIOR", UnknownBehaviorPolicy.ENVIRONMENT_VARIABLE);
        assertTrue(UnknownBehaviorPolicy.refusalSelected(
                Map.of(UnknownBehaviorPolicy.ENVIRONMENT_VARIABLE, " Refuse ")));
        assertFalse(UnknownBehaviorPolicy.refusalSelected(
                Map.of(UnknownBehaviorPolicy.ENVIRONMENT_VARIABLE, "yes")));
        assertFalse(UnknownBehaviorPolicy.refusalSelected(Map.of()));
        assertEquals("refuse", UnknownBehaviorPolicy.describe(
                Map.of(UnknownBehaviorPolicy.ENVIRONMENT_VARIABLE, "refuse")));
        assertEquals("pass-through", UnknownBehaviorPolicy.describe(Map.of()));
    }
}
