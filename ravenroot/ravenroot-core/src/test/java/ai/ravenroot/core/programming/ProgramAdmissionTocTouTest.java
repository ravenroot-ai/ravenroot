package ai.ravenroot.core.programming;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.programming.ArtifactProvenanceVerifier;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-12 and SEC-25. The three gates on {@link ProgramAdmission#redeem()}, each proved
 * on its own, plus the revocation cancellation that redemption cannot provide.
 *
 * <p><b>Deterministic by construction — there is no clock in this file.</b> The defect being closed
 * is a race, but reproducing it does not require running one: the whole point of the admission is
 * that the authoritative read happens at redemption, so a test can simply retire the artifact
 * <em>between</em> obtaining the admission and redeeming it and observe the refusal. That interleaving
 * is a sequence of ordinary method calls. Five load-sensitive tests have required repairs in this
 * repository, so a new security test that depends on a race would be indefensible.
 *
 * <p><b>Mutation-tested, each gate separately</b> (applied to a scratch copy of
 * {@code InMemoryArtifactRegistry.RegistryAdmission#redeem} and reverted):
 * <ol>
 *   <li>tenant comparison removed → {@link #refusesAnArtifactOwnedByAnotherTenant} and
 *       {@link #refusesAnArtifactWithNoRecordedOwner} FAIL;</li>
 *   <li>revision check removed → {@link #refusesAnArtifactWhoseRevisionMovedAfterAdmission} FAILS;</li>
 *   <li>ACTIVE check removed → {@link #refusesAnArtifactRetiredAfterAdmissionButBeforeRedemption} and
 *       {@link #refusesAnArtifactThatWasNeverActivated} FAIL.</li>
 * </ol>
 * Each mutation leaves the other tests green, which is what makes them three gates rather than one
 * assertion wearing three hats.
 */
class ProgramAdmissionTocTouTest {
    /**
     * Stated rather than defaulted. The registry now refuses to release any source
     * unless a provenance verifier is configured, so a test about the admission GATES has to say
     * explicitly that provenance is not what it is testing — otherwise every refusal below could be
     * the verifier's and the gate under test would prove nothing.
     */
    private static final ArtifactProvenanceVerifier VERIFIED = artifact -> { };

    private static final String OWNER = AuthorizedRavenrootApplication.OWNER_TENANT_METADATA;
    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";

    /**
     * The defect itself. The admission is taken while the artifact is ACTIVE — exactly as the node
     * behavior takes it — and the artifact is retired before the source is redeemed. A check that
     * trusts only the admission-time {@code ACTIVE} snapshot executes the source despite retirement.
     */
    @Test
    void refusesAnArtifactRetiredAfterAdmissionButBeforeRedemption() {
        var registry = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact artifact = active(registry, TENANT_A);

        ProgramAdmission admission = registry.admitForExecution(TENANT_A, artifact.id());
        // Admissible at this instant: proving the refusal below is caused by the retirement and not
        // by the admission having been unusable all along.
        assertEquals(ArtifactState.ACTIVE, admission.redeem().state());

        registry.transition(artifact.id(), ArtifactState.ACTIVE, ArtifactState.RETIRED, Map.of());

        var refused = assertThrows(SecurityException.class, admission::redeem,
                "a retired artifact must not be redeemable; this is the exact window in which a "
                        + "sandbox was still starting while the retirement completed");
        assertTrue(refused.getMessage().contains("not ACTIVE"), refused.getMessage());
    }

    /** SEC-25: the cross-tenant hole, reachable from graph content before this gate existed. */
    @Test
    void refusesAnArtifactOwnedByAnotherTenant() {
        var registry = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact artifact = active(registry, TENANT_B);

        ProgramAdmission admission = registry.admitForExecution(TENANT_A, artifact.id());

        var refused = assertThrows(SecurityException.class, admission::redeem,
                "tenant-a named tenant-b's ACTIVE artifact and executed it; the artifact being ACTIVE "
                        + "is precisely why the state gate alone never caught this");
        assertTrue(refused.getMessage().contains("not owned by tenant"), refused.getMessage());
    }

    /** An artifact owned by nobody must be executable by nobody, not by everybody. */
    @Test
    void refusesAnArtifactWithNoRecordedOwner() {
        var registry = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact artifact = activate(registry, registry.create("javascript", "() => 1", Map.of()));

        var refused = assertThrows(SecurityException.class,
                () -> registry.admitForExecution(TENANT_A, artifact.id()).redeem(),
                "an artifact with no owner metadata must fail closed");
        assertTrue(refused.getMessage().contains("not owned by tenant"), refused.getMessage());
    }

    /**
     * Anti-replay, and the one case that isolates the revision gate from the other two.
     *
     * <p>Getting this scenario right took a second attempt worth recording. The obvious fixture —
     * admit while ACTIVE, then retire, then redeem — cannot isolate anything, because
     * {@code ACTIVE -> RETIRED} is the only legal move out of {@code ACTIVE} and {@code RETIRED} is
     * terminal, so the state gate fires on the same fact. Under that fixture, deleting the revision
     * check leaves every test green: it would have been a check that cannot fail, of which this run
     * has already catalogued thirty.
     *
     * <p>The reachable case runs the other way. The admission is taken while the artifact is
     * {@code APPROVED} and the artifact is activated before redemption. At redemption the tenant
     * matches and the state <em>is</em> {@code ACTIVE}, so neither of the other gates has anything to
     * say — only the revision moved. An admission authorizes one revision; executing a different one
     * than the caller was admitted against is the substitution this gate exists to refuse.
     */
    @Test
    void refusesAnArtifactWhoseRevisionMovedAfterAdmission() {
        var registry = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact artifact = registry.create("javascript", "() => 1", Map.of(OWNER, TENANT_A));
        registry.transition(artifact.id(), ArtifactState.GENERATED, ArtifactState.VALIDATED);
        registry.transition(artifact.id(), ArtifactState.VALIDATED, ArtifactState.TESTED);
        GeneratedArtifact approved = registry.transition(artifact.id(), ArtifactState.TESTED,
                ArtifactState.APPROVED);

        ProgramAdmission admission = registry.admitForExecution(TENANT_A, artifact.id());
        GeneratedArtifact activated = registry.transition(artifact.id(), ArtifactState.APPROVED,
                ArtifactState.ACTIVE);

        assertEquals(ArtifactState.ACTIVE, activated.state(), "the state gate must have nothing to say");
        assertTrue(activated.revision() > approved.revision(), "the fixture must actually move the revision");

        var refused = assertThrows(SecurityException.class, admission::redeem,
                "an admission authorizes the revision it was taken against, not whichever revision "
                        + "happens to be ACTIVE by the time it is redeemed");
        assertTrue(refused.getMessage().contains("changed between admission and execution"),
                refused.getMessage());
    }

    /** The state gate, from the other side: never activated at all. */
    @Test
    void refusesAnArtifactThatWasNeverActivated() {
        var registry = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact artifact = registry.create("javascript", "() => 1", Map.of(OWNER, TENANT_A));

        var refused = assertThrows(SecurityException.class,
                () -> registry.admitForExecution(TENANT_A, artifact.id()).redeem());
        assertTrue(refused.getMessage().contains("not ACTIVE"), refused.getMessage());
    }

    /**
     * The half redemption cannot provide. An execution admitted before a retirement is legitimately
     * admitted and its source may already be inside a worker; the only remaining remedy is to cancel
     * it. Without this, admission narrows the hole and leaves the tail of it open.
     */
    @Test
    void retiringAnArtifactCancelsAnAdmissionThatIsAlreadyInFlight() {
        var registry = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact artifact = active(registry, TENANT_A);
        var cancelled = new AtomicBoolean();

        ProgramAdmission admission = registry.admitForExecution(TENANT_A, artifact.id());
        admission.onRevoked(() -> cancelled.set(true));
        assertFalse(cancelled.get(), "nothing has been revoked yet");

        registry.transition(artifact.id(), ArtifactState.ACTIVE, ArtifactState.RETIRED, Map.of());

        assertTrue(cancelled.get(), "retiring an artifact must cancel executions already admitted for "
                + "it -- redemption cannot stop a source that is already inside a worker");
    }

    /** A released admission must not keep receiving revocations, or the registry leaks per execution. */
    @Test
    void aClosedAdmissionIsNoLongerCancelled() {
        var registry = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact artifact = active(registry, TENANT_A);
        var cancelled = new AtomicBoolean();

        ProgramAdmission admission = registry.admitForExecution(TENANT_A, artifact.id());
        admission.onRevoked(() -> cancelled.set(true));
        admission.close();

        registry.transition(artifact.id(), ArtifactState.ACTIVE, ArtifactState.RETIRED, Map.of());

        assertFalse(cancelled.get(), "a completed execution must not be cancelled retroactively");
    }

    /** Admitting an unknown artifact is a caller error, not a security refusal. */
    @Test
    void admittingAnUnknownArtifactIsRejectedUpFront() {
        var registry = new InMemoryArtifactRegistry(VERIFIED);
        assertThrows(IllegalArgumentException.class,
                () -> registry.admitForExecution(TENANT_A, "no-such-artifact"));
    }

    /** A blank tenant is not a wildcard. */
    @Test
    void admissionRequiresATenant() {
        var registry = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact artifact = active(registry, TENANT_A);
        assertThrows(IllegalArgumentException.class, () -> registry.admitForExecution("", artifact.id()));
        assertThrows(IllegalArgumentException.class, () -> registry.admitForExecution(null, artifact.id()));
    }

    /** The happy path, so every refusal above is known to be a refusal and not a broken fixture. */
    @Test
    void admitsAnActiveArtifactForItsOwningTenant() {
        var registry = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact artifact = active(registry, TENANT_A);

        GeneratedArtifact redeemed = registry.admitForExecution(TENANT_A, artifact.id()).redeem();

        assertNotNull(redeemed);
        assertEquals(ArtifactState.ACTIVE, redeemed.state());
        assertEquals(artifact.id(), redeemed.id());
        assertEquals(artifact.sha256(), redeemed.sha256());
    }

    private static GeneratedArtifact active(InMemoryArtifactRegistry registry, String owner) {
        return activate(registry, registry.create("javascript", "() => 1", Map.of(OWNER, owner)));
    }

    private static GeneratedArtifact activate(InMemoryArtifactRegistry registry, GeneratedArtifact artifact) {
        registry.transition(artifact.id(), ArtifactState.GENERATED, ArtifactState.VALIDATED);
        registry.transition(artifact.id(), ArtifactState.VALIDATED, ArtifactState.TESTED);
        registry.transition(artifact.id(), ArtifactState.TESTED, ArtifactState.APPROVED);
        return registry.transition(artifact.id(), ArtifactState.APPROVED, ArtifactState.ACTIVE);
    }
}
