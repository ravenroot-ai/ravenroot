package ai.ravenroot.api.programming;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every field the commitment claims to bind is proved bound, one field at a time.
 *
 * <p><b>Why field by field.</b> A digest test that only checks "same artifact, same digest" passes
 * against an implementation that hashes the id and ignores everything else — it would be a control
 * that cannot fail, of which this run has catalogued thirty-one. Each test below changes exactly one
 * field and requires the digest to move, so dropping any single line from
 * {@code ArtifactEvidence.canonicalForm} turns exactly one of them red.
 *
 * <p><b>Mutation-tested</b> (applied to a scratch copy and reverted): removing any one of the
 * {@code id}, {@code language}, {@code sha256}, {@code revision}, {@code state}, {@code createdAt},
 * {@code updatedAt} or metadata lines from the canonical form turns its corresponding test below red
 * and leaves the others green.
 */
class ArtifactEvidenceTest {
    /** See {@link #pinsTheEncodingToAGoldenVector()} before changing this. */
    private static final String GOLDEN =
            "4f3c09bb629d34d0fe1f98a190f3f919f0c6be2a079c576b4baeef20691843e4";

    private static final Instant CREATED = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void theSameArtifactAlwaysProducesTheSameCommitment() {
        assertEquals(ArtifactEvidence.of(artifact()).hex(), ArtifactEvidence.of(artifact()).hex());
        assertTrue(ArtifactEvidence.of(artifact()).matches(artifact()));
    }

    @Test
    void bindsTheIdentity() {
        assertBound(with(a -> new GeneratedArtifact("other-id", a.language(), a.sha256(), a.source(),
                a.state(), a.revision(), a.createdAt(), a.updatedAt(), a.metadata())));
    }

    @Test
    void bindsTheLanguage() {
        assertBound(with(a -> new GeneratedArtifact(a.id(), "python", a.sha256(), a.source(),
                a.state(), a.revision(), a.createdAt(), a.updatedAt(), a.metadata())));
    }

    @Test
    void bindsTheSourceHash() {
        assertBound(with(a -> new GeneratedArtifact(a.id(), a.language(), "ff".repeat(32), a.source(),
                a.state(), a.revision(), a.createdAt(), a.updatedAt(), a.metadata())));
    }

    /**
     * The field {@code sha256} cannot supply. A transition copies the hash verbatim, so every revision
     * of an artifact shares one — without this, the commitment could not say which revision it was
     * made for.
     */
    @Test
    void bindsTheRevision() {
        assertBound(with(a -> new GeneratedArtifact(a.id(), a.language(), a.sha256(), a.source(),
                a.state(), a.revision() + 1, a.createdAt(), a.updatedAt(), a.metadata())));
    }

    @Test
    void bindsTheState() {
        assertBound(with(a -> new GeneratedArtifact(a.id(), a.language(), a.sha256(), a.source(),
                ArtifactState.RETIRED, a.revision(), a.createdAt(), a.updatedAt(), a.metadata())));
    }

    @Test
    void bindsBothTimestamps() {
        assertBound(with(a -> new GeneratedArtifact(a.id(), a.language(), a.sha256(), a.source(),
                a.state(), a.revision(), CREATED.plusSeconds(1), a.updatedAt(), a.metadata())));
        assertBound(with(a -> new GeneratedArtifact(a.id(), a.language(), a.sha256(), a.source(),
                a.state(), a.revision(), a.createdAt(), UPDATED.plusSeconds(1), a.metadata())));
    }

    /** The whole point: who approved it, and why, are covered. */
    @Test
    void bindsTheApproverAndEveryOtherEvidenceEntry() {
        assertBound(with(a -> copyWithMetadata(a, "evidence.approved.approver", "issuer|USER|mallory")));
        assertBound(with(a -> copyWithMetadata(a, "evidence.approved.reason", "rubber stamped")));
        assertBound(with(a -> copyWithMetadata(a, "evidence.tested.result", "not actually run")));
        assertBound(with(a -> copyWithMetadata(a, "ravenroot.security.ownerTenant", "tenant-b")));
    }

    /** An added key is a change even when no existing value moved. */
    @Test
    void bindsTheAbsenceOfMetadataToo() {
        assertBound(with(a -> copyWithMetadata(a, "evidence.approved.extra", "smuggled")));
    }

    /**
     * <b>The golden vector, and the only test that can see the metadata ordering.</b>
     *
     * <p>An order-independence test cannot: {@code GeneratedArtifact} stores metadata as
     * {@code Map.copyOf}, so two maps with the same entries are already normalised to one iteration
     * order before {@code ArtifactEvidence} ever runs. The first version of this test compared a
     * forward and a reverse {@code LinkedHashMap} and passed with the sorting deleted — it proved
     * nothing, and the mutation run is what found that.
     *
     * <p>What the sorting actually protects is <em>cross-process</em> stability. {@code Map.copyOf}'s
     * order is unspecified and salted per JVM: identical within one process, free to differ in the
     * next. Pinning the digest to a constant is therefore the real assertion — it fixes the encoding
     * against a value computed in sorted order, so dropping the sort changes it. This is the property
     * a durable registry will depend on, since a commitment recorded by one process must verify
     * against an artifact read back by another.
     *
     * <p>If this value ever changes, the encoding changed, and every evidence digest already written
     * to an audit trail has been invalidated. That is a breaking change to a security record and
     * wants a migration, not an updated constant.
     */
    @Test
    void pinsTheEncodingToAGoldenVector() {
        assertEquals(GOLDEN, ArtifactEvidence.of(goldenArtifact()).hex(),
                "the canonical encoding changed. Every evidence digest previously written to an audit "
                        + "trail now fails to verify against the artifact it was recorded for");
    }

    /**
     * Field boundaries are length-prefixed, so no value can impersonate the next field by containing
     * the delimiter.
     *
     * <p><b>This test was vacuous twice, and the second time was self-inflicted.</b> Originally the
     * encoding also wrote a separator byte after every value, and this pair -- {@code {"ab": "c"}}
     * against {@code {"a": "bc"}} -- passed with the length prefix deleted, because the separator
     * alone still distinguished them. The fix was to embed the separator inside a value, the only
     * shape that could forge a boundary while one existed.
     *
     * <p>That exposed the separator as dead code, and removing it was also correct. But the two
     * corrections together restored the defect: against a purely length-prefixed encoding the
     * embedded-separator pair no longer collides, because the two encodings differ as
     * {@code a,1f,b,c} against {@code a,b,1f,c}. The discarded pair became the right one.
     *
     * <p>The lesson outlasts the test: each change was validated against the code as it stood when
     * it was made, rather than against the code as it ended up, so the interaction survived a
     * mutation run that examined both fixes individually.
     *
     * <p>With the prefix deleted the pair below encodes to {@code a,b,c} both ways and collides;
     * with it, the lengths (2, 1) and (1, 2) keep them apart. Deleting the prefix turns this red.
     */
    @Test
    void cannotBeForgedByShiftingTextAcrossFieldBoundaries() {
        // Without the length prefix both of these encode to the bytes a,b,c and collide, while
        // their metadata says different things. With it they differ: lengths (2,1) against (1,2).
        var left = artifactWith(Map.of("ab", "c"));
        var right = artifactWith(Map.of("a", "bc"));
        assertNotEquals(ArtifactEvidence.of(left).hex(), ArtifactEvidence.of(right).hex(),
                "text shifted across a field boundary produced the same commitment: these two "
                        + "artifacts claim different metadata yet are indistinguishable to the digest");
    }

    @Test
    void matchesRefusesNull() {
        assertFalse(ArtifactEvidence.of(artifact()).matches(null));
        assertThrows(IllegalArgumentException.class, () -> ArtifactEvidence.of(null));
    }

    private static void assertBound(GeneratedArtifact altered) {
        var original = ArtifactEvidence.of(artifact());
        assertNotEquals(original.hex(), ArtifactEvidence.of(altered).hex(),
                "changing this field left the commitment unchanged, so the field is not bound and an "
                        + "alteration to it would go undetected");
        assertFalse(original.matches(altered),
                "matches() must reject an artifact that no longer says what it said");
    }

    private static GeneratedArtifact with(java.util.function.UnaryOperator<GeneratedArtifact> change) {
        return change.apply(artifact());
    }

    private static GeneratedArtifact copyWithMetadata(GeneratedArtifact artifact, String key, String value) {
        var metadata = new LinkedHashMap<>(artifact.metadata());
        metadata.put(key, value);
        return new GeneratedArtifact(artifact.id(), artifact.language(), artifact.sha256(), artifact.source(),
                artifact.state(), artifact.revision(), artifact.createdAt(), artifact.updatedAt(), metadata);
    }

    /**
     * Deliberately WIDE. With three metadata keys the golden vector could not discriminate:
     * {@code Map.copyOf}'s iteration order happened to equal sorted order, so deleting the sort left
     * the digest unchanged and the mutation survived. With thirty-two keys the two orders coincide
     * only by an accident of probability far smaller than anything this suite need account for, so
     * dropping the sort moves the digest — and, because sorted order does not depend on the JVM's
     * per-process salt, the pinned value stays stable across processes.
     */
    private static GeneratedArtifact goldenArtifact() {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("evidence.approved.approver", "issuer|USER|bob");
        metadata.put("evidence.approved.reason", "peer approval");
        metadata.put("ravenroot.security.ownerTenant", "tenant-a");
        for (int i = 0; i < 29; i++) {
            metadata.put("evidence.filler." + i, "value-" + i);
        }
        return artifactWith(metadata);
    }

    private static GeneratedArtifact artifact() {
        return artifactWith(Map.of("evidence.approved.approver", "issuer|USER|bob",
                "evidence.approved.reason", "peer approval",
                "ravenroot.security.ownerTenant", "tenant-a"));
    }

    private static GeneratedArtifact artifactWith(Map<String, String> metadata) {
        return new GeneratedArtifact("artifact-1", "javascript", "ab".repeat(32), "() => 1",
                ArtifactState.ACTIVE, 5, CREATED, UPDATED, metadata);
    }
}
