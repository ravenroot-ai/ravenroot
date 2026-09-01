package ai.ravenroot.server;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral verification for {@code RAVENROOT_ARTIFACT_PROVENANCE}.
 *
 * <p>The opt-in makes several checkable claims about behaviour -- the default is unchanged, an
 * unknown value is refused at
 * startup rather than falling back, and the opt-in must be typed. Each test below makes one of those
 * claims falsifiable.
 *
 * <p>Two levels deliberately. The mapping tests pin the {@code mode -> verifier} decision; the two
 * redemption tests drive the verifier through the real {@link InMemoryArtifactRegistry} to
 * {@code redeem()}, which is where {@code ArtifactProvenanceVerifier} is actually consulted. Only the
 * second kind would notice if the seam kept returning the right verifier while the wiring around it
 * stopped using it.
 */
class ArtifactProvenanceConfigurationTest {
    private static final String OWNER = AuthorizedRavenrootApplication.OWNER_TENANT_METADATA;
    private static final String TENANT = "tenant-a";
    private static final String VARIABLE = "RAVENROOT_ARTIFACT_PROVENANCE";

    /**
     * An environment that never mentions the variable must refuse all the way through redemption,
     * not merely select a verifier that
     * looks refusing.
     */
    @Test
    void anEnvironmentWithoutTheVariableStillRefusesEveryArtifact() {
        var provenance = RavenrootServerMain.artifactProvenance(Map.of());

        assertEquals("refusing", provenance.mode());
        assertFalse(provenance.unverified());

        var registry = new InMemoryArtifactRegistry(provenance.verifier());
        GeneratedArtifact artifact = active(registry);

        var refused = assertThrows(SecurityException.class,
                () -> registry.admitForExecution(TENANT, artifact.id()).redeem());
        assertTrue(refused.getMessage().contains("No artifact provenance verifier is configured"),
                refused.getMessage());
    }

    /**
     * The same default, reached the two other ways a real deployment reaches it: named explicitly,
     * and -- the case that actually occurs -- present but empty, because Compose declares optional
     * settings as {@code NAME: ${NAME:-}} and an unset one arrives as an empty string, not as an
     * absent key. A {@code getOrDefault} alone does not cover that, so it is asserted rather than
     * assumed.
     */
    @Test
    void namingTheDefaultAndLeavingItEmptyBothRefuse() {
        for (String value : new String[] {"refusing", "", "   "}) {
            var provenance = RavenrootServerMain.artifactProvenance(Map.of(VARIABLE, value));

            assertEquals("refusing", provenance.mode(), "value: '" + value + "'");
            assertThrows(SecurityException.class, () -> provenance.verifier().verify(artifact()),
                    "value: '" + value + "'");
        }
    }

    /**
     * The opt-in does what it says: accepts everything, checks nothing, and releases the source
     * through the real admission path. Without the opt-in, no environment may take this path.
     */
    @Test
    void theOptInAdmitsAnArtifactWithoutVerifyingAnything() {
        var provenance = RavenrootServerMain.artifactProvenance(Map.of(VARIABLE, "unverified"));

        assertEquals("unverified", provenance.mode());
        assertTrue(provenance.unverified());

        var registry = new InMemoryArtifactRegistry(provenance.verifier());
        GeneratedArtifact artifact = active(registry);

        GeneratedArtifact redeemed = registry.admitForExecution(TENANT, artifact.id()).redeem();

        assertEquals(artifact.id(), redeemed.id());
        assertEquals(ArtifactState.ACTIVE, redeemed.state());
    }

    /** Surrounding whitespace is a typo, not a different mode: {@code trim()} is asserted, not implied. */
    @Test
    void surroundingWhitespaceDoesNotChangeTheMode() {
        assertEquals("unverified", RavenrootServerMain.artifactProvenance(
                Map.of(VARIABLE, "  unverified  ")).mode());
        assertEquals("refusing", RavenrootServerMain.artifactProvenance(
                Map.of(VARIABLE, "\trefusing\n")).mode());
    }

    /**
     * An unknown value fails <b>at startup</b> and is not quietly read as the
     * default. The distinction that matters is where the operator learns about it -- a fallback would
     * start a server that looks configured and refuses hours later, at a user's first node.
     *
     * <p>The case variants are the point, not padding. {@code unverified} is a security opt-out, so
     * near-misses must refuse rather than be helpfully normalised: a mode this consequential should
     * never be reachable by a spelling the operator did not write.
     */
    @Test
    void anUnknownValueIsRefusedInsteadOfFallingBackToTheDefault() {
        for (String value : new String[] {
                "Unverified", "UNVERIFIED", "Refusing", "none", "true", "off", "disabled", "verify"}) {
            var refused = assertThrows(IllegalArgumentException.class,
                    () -> RavenrootServerMain.artifactProvenance(Map.of(VARIABLE, value)),
                    "value '" + value + "' must not be accepted");
            assertTrue(refused.getMessage().contains(VARIABLE), refused.getMessage());
            assertTrue(refused.getMessage().contains("refusing") && refused.getMessage().contains("unverified"),
                    "the refusal must name both legal values: " + refused.getMessage());
        }
    }

    /**
     * The startup log declares the posture. The reason goes in the log at the moment the condition
     * holds, using the same shape as the
     * {@code unknown-behavior-policy} event emitted a few lines further down the same method.
     *
     * <p>Both modes are asserted. A line that only appeared when something was wrong would leave an
     * operator who believes they are fail-closed with nothing to confirm it against.
     */
    @Test
    void startupDeclaresWhichPostureIsInEffect() {
        String refusing = captureStdout(() -> RavenrootServerMain.artifactProvenance(Map.of()));
        assertTrue(refusing.contains("\"event\":\"artifact-provenance\""), refusing);
        assertTrue(refusing.contains("\"mode\":\"refusing\""), refusing);
        assertFalse(refusing.contains("unverified"), refusing);

        String unverified = captureStdout(
                () -> RavenrootServerMain.artifactProvenance(Map.of(VARIABLE, "unverified")));
        assertTrue(unverified.contains("\"event\":\"artifact-provenance\""), unverified);
        assertTrue(unverified.contains("\"mode\":\"unverified\""), unverified);
        assertTrue(unverified.contains("NOT verified"), unverified);
    }

    /**
     * Captures the real stream rather than inspecting the returned text, and that is the whole point
     * of this helper: a test that asserted on {@code startupEvent()} alone would stay green if the
     * line were never actually written. This one turns red for a deleted {@code println} as well as
     * for a wrong one.
     */
    private static String captureStdout(Runnable action) {
        java.io.PrintStream original = System.out;
        var buffer = new java.io.ByteArrayOutputStream();
        try (var captured = new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setOut(captured);
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static GeneratedArtifact active(InMemoryArtifactRegistry registry) {
        GeneratedArtifact artifact = registry.create("javascript", "() => 1", Map.of(OWNER, TENANT));
        registry.transition(artifact.id(), ArtifactState.GENERATED, ArtifactState.VALIDATED);
        registry.transition(artifact.id(), ArtifactState.VALIDATED, ArtifactState.TESTED);
        registry.transition(artifact.id(), ArtifactState.TESTED, ArtifactState.APPROVED);
        return registry.transition(artifact.id(), ArtifactState.APPROVED, ArtifactState.ACTIVE);
    }

    private static GeneratedArtifact artifact() {
        return new GeneratedArtifact("artifact-1", "javascript", "0".repeat(64), "() => 1",
                ArtifactState.ACTIVE, 1L, null, null, Map.of(OWNER, TENANT));
    }
}
