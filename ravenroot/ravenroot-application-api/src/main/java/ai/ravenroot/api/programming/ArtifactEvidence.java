package ai.ravenroot.api.programming;

import ai.ravenroot.api.persistence.EventDigest;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * A commitment to everything an artifact claims about itself at one revision (SEC-12).
 *
 * <h2>The gap this closes</h2>
 * <p>An artifact's lifecycle evidence — who approved it, why, what the test run reported, which
 * runtime activated it — lives in its {@code metadata} map as {@code evidence.*} keys. The durable
 * audit trail recorded the lifecycle <em>action</em> and its <em>subject</em>, and its detail field
 * carried {@code sha256=...;state=...} and nothing else. Those were two independent claims with no
 * link between them, so an altered {@code evidence.approved.approver} contradicted the trail and
 * nothing could tell.
 *
 * <p>{@code sha256} could not close that gap, and it is worth being explicit about why, because it
 * looks as though it should. A transition copies the hash verbatim — see the artifact registry's
 * {@code changed(...)} — so <b>every revision of an artifact shares one {@code sha256}</b>. It binds
 * the source and nothing else: not the revision, not the state, and not one character of the
 * evidence.
 *
 * <p>This digest covers the identity, the source hash, the revision, the state, both timestamps and
 * the <em>entire</em> metadata map. Recording it on the tamper-evident, per-tenant, hash-chained
 * audit trail turns that trail into a binding rather than a label: given an artifact and the digest
 * the trail recorded for it, {@link #matches(GeneratedArtifact)} answers whether the artifact still
 * says what it said when the record was written.
 *
 * <h2>Integrity, deliberately not authentication</h2>
 * <p>{@link EventDigest}'s own Javadoc states the limit and it applies unchanged here: the digest is
 * unkeyed, so an actor who can rewrite the artifact <em>and</em> the audit record can recompute it.
 * What it detects is an artifact altered out from under a trail the actor could not also rewrite —
 * and the trail is chained precisely so that rewriting it is not free. Authentication needs a keyed
 * MAC or a signature and is <b>SEC-22</b>, exactly as ADRs 0011 and 0013 record for the same
 * primitive. Do not read this class as closing that.
 *
 * <h2>What "immutably bound" means today, and what it will mean</h2>
 * <p>The audit trail is durable; the artifact registry is <b>not</b>. {@code InMemoryArtifactRegistry}
 * is a map, so a restart destroys every artifact while the trail survives — the commitment outlives
 * the thing it commits to, and verification after a restart has nothing to verify against. That is
 * a known durability limit, stated here rather than papered over: today this binds an artifact to its
 * evidence <em>for the life of the process</em>, and becomes a durable binding when a durable registry exists. The
 * trail's side of it is durable now, so records written today remain verifiable against artifacts
 * that outlive their process once a durable registry exists.
 */
public final class ArtifactEvidence {
    private final EventDigest digest;

    private ArtifactEvidence(EventDigest digest) {
        this.digest = digest;
    }

/**
 * Computes the commitment for an artifact as it stands.
 * @param artifact revision whose identity, lifecycle state, timestamps, and metadata are committed.
 * @return commitment bound to the artifact's current canonical form.
 */
    public static ArtifactEvidence of(GeneratedArtifact artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("artifact is required");
        }
        return new ArtifactEvidence(EventDigest.of(sha256(canonicalForm(artifact))));
    }

/**
 * The commitment, as the trail records it.
 * @return digest recorded with the lifecycle audit event.
 */
    public EventDigest digest() {
        return digest;
    }

/**
 * Lowercase hex, the form written to the audit record's detail.
 * @return lowercase hexadecimal form of the committed digest.
 */
    public String hex() {
        return HexFormat.of().formatHex(digest.value());
    }

    /**
     * Whether the artifact still says exactly what it said when this commitment was made.
     *
     * <p>Constant-time comparison: this answers a question about integrity, and a comparison that
     * returns early would leak how much of a forged digest was correct.
 * @param artifact artifact revision to compare with this commitment.
 * @return whether its canonical form has the same constant-time digest.
     */
    public boolean matches(GeneratedArtifact artifact) {
        if (artifact == null) {
            return false;
        }
        return MessageDigest.isEqual(digest.value(), of(artifact).digest.value());
    }

    /**
     * Length-prefixed and ordered, so no two distinct artifacts share a canonical form.
     *
     * <p><b>Metadata is sorted by key, and that is required for the commitment to survive a restart.</b>
     * {@code GeneratedArtifact} stores metadata as {@code Map.copyOf}, whose iteration order is
     * unspecified and salted per JVM — identical within one process, and free to differ in the next.
     * A digest that followed that order would verify in the process that computed it and fail in every
     * other one, which is precisely the case that matters once a durable registry exists. The
     * golden vector in {@code ArtifactEvidenceTest} is what pins this, because an order-independence
     * test cannot see it: {@code Map.copyOf} normalises both inputs before this code runs.
     */
    private static byte[] canonicalForm(GeneratedArtifact artifact) {
        var out = new ByteArrayOutputStream(512);
        putText(out, artifact.id());
        putText(out, artifact.language());
        putText(out, artifact.sha256());
        putLong(out, artifact.revision());
        putText(out, artifact.state() == null ? "" : artifact.state().name());
        putInstant(out, artifact.createdAt());
        putInstant(out, artifact.updatedAt());
        Map<String, String> ordered = new TreeMap<>(artifact.metadata());
        putLong(out, ordered.size());
        ordered.forEach((key, value) -> {
            putText(out, key);
            putText(out, value);
        });
        return out.toByteArray();
    }

    private static void putText(ByteArrayOutputStream out, String value) {
        // Length-prefixed and nothing else. A trailing separator byte was here and a mutation run
        // proved it dead: deleting it left every test green, because the length prefix already makes
        // the encoding unambiguous. Two mechanisms where one suffices means one of them is untested.
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        putLong(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void putLong(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((value >>> shift) & 0xff));
        }
    }

    private static void putInstant(ByteArrayOutputStream out, Instant value) {
        Instant instant = value == null ? Instant.EPOCH : value;
        putLong(out, instant.getEpochSecond());
        putLong(out, instant.getNano());
    }

    private static byte[] sha256(byte[] canonicalForm) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonicalForm);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", impossible);
        }
    }

    @Override
    public String toString() {
        return "ArtifactEvidence[" + hex() + "]";
    }
}
