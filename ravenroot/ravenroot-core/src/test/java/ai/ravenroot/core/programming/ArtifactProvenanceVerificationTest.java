package ai.ravenroot.core.programming;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.programming.ArtifactProvenanceVerifier;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-12, signature half. Provenance verification happens <b>inside</b>
 * {@code ProgramAdmission#redeem()}, and its absence refuses rather than permits.
 *
 * <p><b>Mutation-tested</b> (applied to a scratch copy of {@code InMemoryArtifactRegistry} and
 * reverted): removing the {@code verifier.verify(current)} call from {@code redeem()} turns
 * {@link #refusesWhenNoVerifierIsConfigured}, {@link #refusesWhatTheVerifierRejects} and
 * {@link #verifiesTheRedeemedRevisionNotTheAdmittedSnapshot} red while every other test in the module
 * stays green.
 */
class ArtifactProvenanceVerificationTest {
    private static final String OWNER = AuthorizedRavenrootApplication.OWNER_TENANT_METADATA;
    private static final String TENANT = "tenant-a";
    private static final ArtifactProvenanceVerifier ACCEPTING = artifact -> { };

    /**
     * The default. A deployment that never configured a verifier has established nothing about what
     * it is running, and executing anyway would make the whole admission path decorative.
     */
    @Test
    void refusesWhenNoVerifierIsConfigured() {
        var registry = new InMemoryArtifactRegistry();
        GeneratedArtifact artifact = active(registry, TENANT);

        var refused = assertThrows(SecurityException.class,
                () -> registry.admitForExecution(TENANT, artifact.id()).redeem());

        assertTrue(refused.getMessage().contains("No artifact provenance verifier is configured"),
                refused.getMessage());
    }

    /** A configured verifier's refusal is authoritative: redemption fails and no source is released. */
    @Test
    void refusesWhatTheVerifierRejects() {
        var registry = new InMemoryArtifactRegistry(artifact -> {
            throw new SecurityException("provenance signature does not bind " + artifact.id());
        });
        GeneratedArtifact artifact = active(registry, TENANT);

        var refused = assertThrows(SecurityException.class,
                () -> registry.admitForExecution(TENANT, artifact.id()).redeem());

        assertTrue(refused.getMessage().contains("does not bind"), refused.getMessage());
    }

    /**
     * The placement, asserted rather than described. The verifier must see the artifact redemption is
     * about to return — the authoritative record at redemption time — not the stale snapshot captured
     * when the admission was created. If it saw the snapshot, a signature could be checked against a
     * revision other than the one executed, which is the substitution the revision gate refuses and
     * would be a strange thing for the signature check to reintroduce.
     */
    @Test
    void verifiesTheRedeemedRevisionNotTheAdmittedSnapshot() {
        var seen = new AtomicReference<GeneratedArtifact>();
        var registry = new InMemoryArtifactRegistry(seen::set);
        GeneratedArtifact artifact = active(registry, TENANT);

        var admission = registry.admitForExecution(TENANT, artifact.id());
        GeneratedArtifact redeemed = admission.redeem();

        assertEquals(redeemed.revision(), seen.get().revision(),
                "the verifier must be given the record redemption returns");
        assertEquals(artifact.id(), seen.get().id());
        assertEquals(ArtifactState.ACTIVE, seen.get().state());
    }

    /**
     * Verification runs after the ownership, state and revision gates, not before. Spending a key
     * operation on a record that is about to be refused anyway is wasteful, and — more importantly —
     * it would hand an unverified caller's artifact to the integrator's key material.
     */
    @Test
    void doesNotConsultTheVerifierForAnArtifactTheGatesAlreadyRefuse() {
        var calls = new AtomicInteger();
        var registry = new InMemoryArtifactRegistry(artifact -> calls.incrementAndGet());
        GeneratedArtifact foreign = active(registry, "tenant-b");

        assertThrows(SecurityException.class,
                () -> registry.admitForExecution(TENANT, foreign.id()).redeem());

        assertEquals(0, calls.get(),
                "a cross-tenant admission must be refused before the verifier is consulted");
    }

    /** Null is not a way to opt out; refusing() is, and it says so at the call site. */
    @Test
    void aRegistryCannotBeBuiltWithoutStatingAVerifier() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryArtifactRegistry(null));
    }

    /** The happy path, so every refusal above is a refusal and not a broken fixture. */
    @Test
    void releasesTheSourceWhenProvenanceVerifies() {
        var registry = new InMemoryArtifactRegistry(ACCEPTING);
        GeneratedArtifact artifact = active(registry, TENANT);

        GeneratedArtifact redeemed = registry.admitForExecution(TENANT, artifact.id()).redeem();

        assertEquals(artifact.id(), redeemed.id());
        assertEquals(ArtifactState.ACTIVE, redeemed.state());
    }

    private static GeneratedArtifact active(InMemoryArtifactRegistry registry, String owner) {
        GeneratedArtifact artifact = registry.create("javascript", "() => 1", Map.of(OWNER, owner));
        registry.transition(artifact.id(), ArtifactState.GENERATED, ArtifactState.VALIDATED);
        registry.transition(artifact.id(), ArtifactState.VALIDATED, ArtifactState.TESTED);
        registry.transition(artifact.id(), ArtifactState.TESTED, ArtifactState.APPROVED);
        return registry.transition(artifact.id(), ArtifactState.APPROVED, ArtifactState.ACTIVE);
    }
}
