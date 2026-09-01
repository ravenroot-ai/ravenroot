package ai.ravenroot.core.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link UnknownBehaviorPolicy#mode()} reports {@link UnknownBehaviorPolicy#UNKNOWN_VALUE} for a
 * policy it cannot characterize with certainty, rather than guessing.
 *
 * <p>Neither shipped policy can exercise this path: {@link UnknownBehaviorPolicy#passThrough()} and
 * {@link UnknownBehaviorPolicy#refuse()} are both constant functions, so {@code mode()}'s two witnesses
 * always agree on them. Reaching the {@code UNKNOWN_VALUE} branch requires a policy whose
 * {@link UnknownBehaviorPolicy#admits(String)} genuinely answers {@link UnknownBehaviorPolicy#PROBE_BEHAVIOR}
 * and {@link UnknownBehaviorPolicy#PROBE_BEHAVIOR_ALTERNATE} differently — so this test defines one, as a
 * real class implementing the interface (a lambda cannot override {@code mode()}, and this test does not
 * need to: the default is exactly what is under test).</p>
 */
class UnknownBehaviorPolicyUnknownModeTest {

    /**
     * Admits exactly one witness and refuses the other. Not a mock and not a test double standing in
     * for behaviour that does not exist elsewhere: a policy an integrator could ship that admits some
     * unknown behaviors and refuses others would answer {@code admits(String)} the same way — by
     * discriminating on the argument — and would trip this same default.
     */
    private static final class SelectiveUnknownBehaviorPolicy implements UnknownBehaviorPolicy {
        @Override
        public boolean admits(String behavior) {
            return UnknownBehaviorPolicy.PROBE_BEHAVIOR.equals(behavior);
        }
    }

    /**
     * A policy that discriminates, but not between the two witnesses {@code mode()} uses: it agrees on
     * both of them and differs only on a third name neither probe asks about. This is the honest
     * residual limitation the interface's Javadoc discloses — two witnesses can prove non-constancy
     * when they disagree, but agreement is evidence, not proof. Pinned here so the boundary of what the
     * mechanism can and cannot detect is a tested fact, not only a documented claim.
     *
     * <p>The boundary moved without disappearing. This policy still escapes, and must: it is the
     * shape the limitation is about. What no longer escapes is the far more common shape below.</p>
     */
    private static final class PartlySelectiveButAgreesOnBothProbesPolicy implements UnknownBehaviorPolicy {
        @Override
        public boolean admits(String behavior) {
            return !"a-third-behavior-name-neither-probe-uses".equals(behavior);
        }
    }

    /**
     * A realistic discriminating policy admits by namespace prefix, which is how an
     * integrator would plausibly write "trust anything under our own namespace, refuse the rest".
     *
     * <p>Previously this escaped detection entirely — the second witness was the first plus a suffix,
     * so a prefix test classified both identically and {@code mode()} reported a confident, wrong
     * answer. It is not a contrived counter-example: it is the case the mechanism was supposed to
     * catch all along and did not.</p>
     */
    private static final class NamespacePrefixPolicy implements UnknownBehaviorPolicy {
        @Override
        public boolean admits(String behavior) {
            return behavior != null && behavior.startsWith("ravenroot.");
        }
    }

    @Test
    void aPolicyThatAnswersTheTwoWitnessesDifferentlyReportsUnknownRatherThanGuessing() {
        UnknownBehaviorPolicy policy = new SelectiveUnknownBehaviorPolicy();

        // The disagreement this test relies on is real, not assumed.
        assertFalse(policy.admits(UnknownBehaviorPolicy.PROBE_BEHAVIOR)
                == policy.admits(UnknownBehaviorPolicy.PROBE_BEHAVIOR_ALTERNATE));

        assertEquals(UnknownBehaviorPolicy.UNKNOWN_VALUE, policy.mode());
        assertEquals("unknown-behavior:unknown", policy.capability());
    }

    /**
     * <b>The unknown token actually reaches {@code ApplicationStatus.capabilities()}.</b>
     *
     * <p>Mirrors {@code UnknownBehaviorCapabilityTest#theStanceReachesTheApplicationStatus}, which
     * proves the same wire for the two shipped constant policies; this proves it for a policy the probe
     * cannot characterize. Both matter separately: this one reds if {@code DefaultRavenrootApplication}
     * ever special-cased the two known tokens instead of publishing whatever {@code capability()}
     * returns.</p>
     */
    @Test
    void theUnknownTokenReachesTheApplicationStatus() throws Exception {
        var engine = new JoinTestEngine();
        var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), null, 1,
                new SelectiveUnknownBehaviorPolicy());
        try {
            var capabilities = application.status().capabilities();
            assertEquals(true, capabilities.contains("unknown-behavior:unknown"),
                    () -> "a policy mode() cannot characterize must publish the unknown token, not a "
                            + "guessed one: " + capabilities);
        } finally {
            application.close();
            engine.close();
        }
    }

    /**
     * <b>The two witnesses are lexically dissimilar, and that is what buys the detection.</b>
     *
     * <p>Two assertions, deliberately separate. The first pins the property directly: neither constant
     * is a prefix of the other. It reds if anyone re-derives one witness from the other, which is the
     * exact regression this test prevents and the kind that reads as harmless in a diff.</p>
     *
     * <p>The second pins what the property is <em>for</em>. A namespace-prefix policy is now proven
     * non-constant by observation, where before it returned a confident wrong answer. Asserting the
     * separation without asserting the consequence would pin the mechanism and not its purpose.</p>
     */
    @Test
    void aNamespacePrefixPolicyIsDetectedBecauseTheWitnessesShareNoPrefix() {
        assertFalse(UnknownBehaviorPolicy.PROBE_BEHAVIOR_ALTERNATE
                        .startsWith(UnknownBehaviorPolicy.PROBE_BEHAVIOR),
                "the second witness must not be the first plus a suffix: a prefix-based predicate "
                        + "would classify both identically and escape mode() entirely");
        assertFalse(UnknownBehaviorPolicy.PROBE_BEHAVIOR
                        .startsWith(UnknownBehaviorPolicy.PROBE_BEHAVIOR_ALTERNATE),
                "neither witness may be a prefix of the other, in either direction");

        UnknownBehaviorPolicy policy = new NamespacePrefixPolicy();

        assertFalse(policy.admits(UnknownBehaviorPolicy.PROBE_BEHAVIOR)
                        == policy.admits(UnknownBehaviorPolicy.PROBE_BEHAVIOR_ALTERNATE),
                "the witnesses must fall on opposite sides of a plausible namespace predicate");
        assertEquals(UnknownBehaviorPolicy.UNKNOWN_VALUE, policy.mode(),
                "a policy admitting by namespace prefix is not constant, and mode() must now say so "
                        + "rather than reporting the stance of whichever side both probes landed on");
    }

    @Test
    void agreementAcrossBothWitnessesIsEvidenceNotProofOfConstancy() {
        UnknownBehaviorPolicy policy = new PartlySelectiveButAgreesOnBothProbesPolicy();

        assertEquals(policy.admits(UnknownBehaviorPolicy.PROBE_BEHAVIOR),
                policy.admits(UnknownBehaviorPolicy.PROBE_BEHAVIOR_ALTERNATE));
        // Both witnesses say "admit"; mode() reports pass-through even though this policy is not
        // actually constant. This is the disclosed limitation, not a defect: mode() proves non-constancy
        // on disagreement and cannot prove constancy on agreement, because that would require evaluating
        // admits(String) over every possible input.
        assertEquals(UnknownBehaviorPolicy.PASS_THROUGH_VALUE, policy.mode());
    }
}
