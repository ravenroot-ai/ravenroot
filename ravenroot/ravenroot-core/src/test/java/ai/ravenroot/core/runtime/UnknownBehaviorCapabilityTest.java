package ai.ravenroot.core.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unknown-behaviour admission stance is published, and says the right thing.
 *
 * <p>The token must have a direct assertion on {@code ApplicationStatus}; an otherwise green suite
 * does not prove the capability is emitted.
 * A capability nobody checks is a capability that can quietly stop being emitted.</p>
 */
class UnknownBehaviorCapabilityTest {

    /**
     * <b>Both stances are distinguishable in the published token.</b>
     *
     * <p>Asserted for each policy rather than for one, because a {@code capability()} that returned a
     * constant would satisfy a single-value test perfectly while telling every reader the same thing
     * regardless of how the deployment is configured.</p>
     */
    @Test
    void eachPolicyPublishesItsOwnStance() {
        assertEquals("unknown-behavior:pass-through", UnknownBehaviorPolicy.passThrough().capability());
        assertEquals("unknown-behavior:refuse", UnknownBehaviorPolicy.refuse().capability());
        assertEquals(UnknownBehaviorPolicy.PASS_THROUGH_VALUE, UnknownBehaviorPolicy.passThrough().mode());
        assertEquals(UnknownBehaviorPolicy.REFUSE_VALUE, UnknownBehaviorPolicy.refuse().mode());
    }

    /**
     * The published stance agrees with what {@link UnknownBehaviorPolicy#describe} says about the same
     * environment, so the status surface and a startup log line cannot disagree about one deployment.
     */
    @Test
    void thePublishedStanceAgreesWithTheEnvironmentItWasBuiltFrom() {
        for (Map<String, String> environment : java.util.List.of(
                Map.<String, String>of(),
                Map.of(UnknownBehaviorPolicy.ENVIRONMENT_VARIABLE, "refuse"),
                Map.of(UnknownBehaviorPolicy.ENVIRONMENT_VARIABLE, "REFUSE"),
                Map.of(UnknownBehaviorPolicy.ENVIRONMENT_VARIABLE, "anything-unrecognised"))) {
            assertEquals("unknown-behavior:" + UnknownBehaviorPolicy.describe(environment),
                    UnknownBehaviorPolicy.fromEnvironment(environment).capability(),
                    () -> "the status surface and the startup line must describe the same deployment "
                            + "the same way: " + environment);
        }
    }

    /**
     * <b>The token actually reaches {@code ApplicationStatus.capabilities()}.</b>
     *
     * <p>The capability provides observability on a running server, so the assertion is on the
     * status object rather than on the policy alone — the previous two tests would pass with the line
     * in {@code DefaultRavenrootApplication#status} deleted.</p>
     *
     * <p><b>Mutation proof.</b> Remove the {@code capabilities.add(unknownBehaviors.capability())} line
     * and this test reds; the other two stay green, which is exactly why it is separate.</p>
     */
    @Test
    void theStanceReachesTheApplicationStatus() throws Exception {
        // A core test uses a core test engine: ravenroot-core must not reach for an engine
        // implementation, which is the engine-neutral boundary this module exists to hold.
        var engine = new JoinTestEngine();
        var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), null, 1);
        try {
            var capabilities = application.status().capabilities();
            assertTrue(capabilities.contains("unknown-behavior:pass-through"),
                    () -> "the shipped default's stance must be observable on a running server: "
                            + capabilities);
        } finally {
            application.close();
            engine.close();
        }
    }
}
