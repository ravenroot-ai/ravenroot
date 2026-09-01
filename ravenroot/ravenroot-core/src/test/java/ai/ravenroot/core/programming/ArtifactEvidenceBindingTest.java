package ai.ravenroot.core.programming;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.programming.ArtifactEvidence;
import ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent;
import ai.ravenroot.api.programming.ArtifactProvenanceVerifier;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commitment against a real registry: what a recorded evidence digest detects, and
 * — stated as plainly — what it does not.
 *
 * <p>This is the {@code SqliteJournalIntegrityTest} shape applied to the artifact rather than the
 * journal: commit to a record, alter it out of band, read the commitment back, and require the
 * alteration to be visible. The alteration here is a rewritten {@code evidence.approved.approver},
 * because that is the field SEC-12 exists to bind and the one that was previously covered by nothing
 * at all — the trail recorded the requesting subject, the artifact recorded the approver, and no
 * check compared them.
 */
class ArtifactEvidenceBindingTest {
    private static final String OWNER = AuthorizedRavenrootApplication.OWNER_TENANT_METADATA;
    private static final String APPROVER = AuthorizedRavenrootApplication.APPROVER_METADATA;
    private static final ArtifactProvenanceVerifier VERIFIED = artifact -> { };

    /**
     * The tamper. An approver rewritten after the commitment was recorded is detected, which is what
     * makes the audit record a binding rather than a label sitting beside an artifact free to change.
     */
    @Test
    void detectsAnApproverRewrittenAfterTheEvidenceWasRecorded() {
        var registry = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact approved = approved(registry, "issuer|USER|bob", "peer approval");

        // What a lifecycle audit record commits to at this point.
        ArtifactEvidence recorded = ArtifactEvidence.of(approved);
        assertTrue(recorded.matches(approved), "the commitment must accept the artifact it was made for");

        // Out of band: exactly the tamper the commitment exists to catch. The registry never offers a
        // way to do this -- which is the point; the attacker is assumed to have reached the store.
        GeneratedArtifact forged = withMetadata(approved, APPROVER, "issuer|USER|mallory");

        assertFalse(recorded.matches(forged),
                "an artifact whose approver was rewritten after the fact must not verify against the "
                        + "evidence recorded for it; without this, the durable trail says bob approved "
                        + "it while the artifact says mallory did, and nothing reconciles them");
        assertNotEquals(recorded.hex(), ArtifactEvidence.of(forged).hex());
    }

    /** Every lifecycle step commits separately, so the trail carries a per-revision chain of them. */
    @Test
    void eachTransitionProducesADistinctCommitment() {
        var registry = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact created = registry.create("javascript", "() => 1", Map.of(OWNER, "tenant-a"));
        String atCreate = ArtifactEvidence.of(created).hex();
        String atValidate = ArtifactEvidence.of(registry.transition(created.id(),
                ArtifactState.GENERATED, ArtifactState.VALIDATED)).hex();
        String atTest = ArtifactEvidence.of(registry.transition(created.id(),
                ArtifactState.VALIDATED, ArtifactState.TESTED, Map.of("result", "worker execution succeeded"))).hex();

        assertNotEquals(atCreate, atValidate);
        assertNotEquals(atValidate, atTest);
        assertFalse(ArtifactEvidence.of(created).matches(registry.find(created.id()).orElseThrow()),
                "a commitment made at one revision must not verify against a later one");
    }

    /**
     * The audit event carries both, so a reader of the durable trail can identify the revision and
     * verify the artifact without consulting anything else the attacker also controls.
     */
    @Test
    void theLifecycleEventCarriesTheRevisionAndTheCommitment() {
        var registry = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact approved = approved(registry, "issuer|USER|bob", "peer approval");

        var event = new ArtifactLifecycleAuditEvent(approved.updatedAt(), "req-1", "issuer|USER|bob",
                "tenant-a", "ARTIFACT_APPROVE", approved.id(), approved.sha256(), approved.state(),
                ArtifactLifecycleAuditEvent.Disposition.SUCCEEDED, approved.revision(),
                ArtifactEvidence.of(approved).hex());

        assertEquals(approved.revision(), event.revision());
        assertEquals(ArtifactEvidence.of(approved).hex(), event.evidenceDigest());
        assertFalse(event.evidenceDigest().isBlank());
    }

    /**
     * <b>The limit, asserted rather than described.</b> The digest is unkeyed, so an actor who can
     * rewrite the artifact <em>and</em> the recorded digest defeats it — recomputing over the forged
     * artifact produces a commitment that matches. This test exists so nobody reads the tamper test
     * above as proving more than it does: what protects the recorded side is the audit trail's hash
     * chain, and authentication of either side is SEC-22.
     */
    @Test
    void anAttackerWhoRewritesBothTheArtifactAndTheRecordedDigestIsNotDetected() {
        var registry = new InMemoryArtifactRegistry(VERIFIED);
        GeneratedArtifact approved = approved(registry, "issuer|USER|bob", "peer approval");

        GeneratedArtifact forged = withMetadata(approved, APPROVER, "issuer|USER|mallory");
        ArtifactEvidence recomputed = ArtifactEvidence.of(forged);

        assertTrue(recomputed.matches(forged),
                "an unkeyed digest cannot detect a forgery by an actor who also rewrites the digest; "
                        + "this is EventDigest's documented limit and it is SEC-22's to close, not "
                        + "this class's. The chained audit trail is what makes rewriting the recorded "
                        + "side expensive");
    }

    private static GeneratedArtifact approved(InMemoryArtifactRegistry registry, String approver, String reason) {
        GeneratedArtifact artifact = registry.create("javascript", "() => 1", Map.of(OWNER, "tenant-a"));
        registry.transition(artifact.id(), ArtifactState.GENERATED, ArtifactState.VALIDATED);
        registry.transition(artifact.id(), ArtifactState.VALIDATED, ArtifactState.TESTED);
        return registry.transition(artifact.id(), ArtifactState.TESTED, ArtifactState.APPROVED,
                Map.of("approver", approver, "reason", reason));
    }

    private static GeneratedArtifact withMetadata(GeneratedArtifact artifact, String key, String value) {
        var metadata = new LinkedHashMap<>(artifact.metadata());
        metadata.put(key, value);
        return new GeneratedArtifact(artifact.id(), artifact.language(), artifact.sha256(), artifact.source(),
                artifact.state(), artifact.revision(), artifact.createdAt(), artifact.updatedAt(), metadata);
    }
}
