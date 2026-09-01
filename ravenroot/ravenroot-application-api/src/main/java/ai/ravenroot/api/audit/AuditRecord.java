package ai.ravenroot.api.audit;

import ai.ravenroot.api.persistence.EventDigest;
import ai.ravenroot.api.persistence.OpaquePayload;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An {@link AuditEnvelope} as a tenant's chain holds it: the caller's content, its position, and the
 * one digest that binds both together (SEC-13, ADR 0013).
 *
 * <h2>Why one digest and not {@code EventEnvelope}'s two-tier split</h2>
 * <p>{@code EventEnvelope}/{@code JournalRecord} split content from position because a redelivered
 * event must digest identically to be recognised as the same event, and its digest is therefore
 * computed by the producer before any position exists. An audit record has no redelivery concept: it
 * is written exactly once, by the trail, and its entire reason for existing is its place in the
 * tenant's chain. So {@link #digest()} here covers the envelope content <strong>and</strong>
 * {@link #previousDigest()} and {@link #sequence()} together, computed once, by
 * {@link AuditTrail#append(AuditEnvelope)}, under whatever lock serializes appends for the tenant.
 * A digest that did not cover the chain-linkage fields would only prove content integrity and say
 * nothing about position — exactly the property this record exists to prove.</p>
 *
 * <h2>What this digest does, and does not, defend against</h2>
 * <p>Recomputing {@link #digest()} from stored fields and comparing it, as {@link #digestMatchesContent()}
 * does, detects any change to this record's own content or to the {@link #previousDigest()} it was
 * chained under, made without also correspondingly rewriting the record(s) after it. It does
 * <strong>not</strong> defend against an actor with write access to the entire backing store who
 * recomputes the whole downstream chain — the digest is unkeyed, exactly as {@link EventDigest}'s own
 * Javadoc documents for the same reason. That needs a keyed MAC or signature (SEC-22) or a watermark
 * held outside the attacker's write scope; see {@code AuditTrail}'s class Javadoc and ADR 0013.</p>
 *
 * <h2>Redaction</h2>
 * <p>A record produced by {@link AuditTrail#redact} keeps its original {@link #sequence()},
 * {@link #previousDigest()} and {@link #digest()} exactly as they were computed at append time — the
 * chain shape never changes — but its {@link #envelope()} carries a redaction placeholder in place of
 * {@link AuditEnvelope#detail()}. Recomputing the digest from that placeholder would not match the
 * stored one, but that mismatch is not tamper: it is the deliberate, declared consequence of
 * redaction, which is why {@link #digestMatchesContent()} refuses to answer for a redacted record
 * rather than reporting a false tamper signal. Callers must check {@link #redacted()} first.</p>
 * @param envelope original event, or a redaction placeholder
 * @param sequence positive position in the tenant's append-only chain
 * @param previousDigest predecessor digest, absent only for the genesis record
 * @param digest digest sealing the unredacted record content
 * @param recordedAt instant at which the record was appended
 * @param redacted whether event content was replaced by a tombstone
 * @param redactedAt redaction instant, present only for a tombstone
 * @param redactionReason safe reason carried by a tombstone
 * @param redactedBy authenticated actor that performed redaction
 */
public record AuditRecord(AuditEnvelope envelope, long sequence, EventDigest previousDigest, EventDigest digest,
                          Instant recordedAt, boolean redacted, Instant redactedAt, String redactionReason,
                          String redactedBy) {

/**
 * Enforces the mutually exclusive ordinary-record and tombstone state shapes.
 */
    public AuditRecord {
        Objects.requireNonNull(envelope, "envelope");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence starts at one, got " + sequence);
        }
        if (sequence == 1 && previousDigest != null) {
            throw new IllegalArgumentException("the first record of a tenant's chain has no previousDigest");
        }
        if (sequence > 1 && previousDigest == null) {
            throw new IllegalArgumentException("every record after the first must chain to a previousDigest");
        }
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (redacted) {
            Objects.requireNonNull(redactedAt, "redactedAt");
            if (redactionReason == null || redactionReason.isBlank()) {
                throw new IllegalArgumentException("a redacted record must carry a reason");
            }
            if (redactedBy == null || redactedBy.isBlank()) {
                throw new IllegalArgumentException("a redacted record must name who redacted it");
            }
        }
    }

/**
 * Builds the first record of a fresh chain, sealing it with a freshly computed digest.
 * @param envelope first event in a tenant chain
 * @param recordedAt append timestamp
 * @return sequence-one record with no predecessor digest
 */
    public static AuditRecord genesis(AuditEnvelope envelope, Instant recordedAt) {
        EventDigest digest = seal(envelope, 1, null, recordedAt);
        return new AuditRecord(envelope, 1, null, digest, recordedAt, false, null, null, null);
    }

/**
 * Builds the next record after {@code previous}, sealing it with a freshly computed digest.
 * @param previous unredacted or redacted predecessor whose digest links this record
 * @param envelope next event in the chain
 * @param recordedAt append timestamp
 * @return next sealed record with a sequence one higher than {@code previous}
 */
    public static AuditRecord chainedAfter(AuditRecord previous, AuditEnvelope envelope, Instant recordedAt) {
        Objects.requireNonNull(previous, "previous");
        long sequence = previous.sequence() + 1;
        EventDigest digest = seal(envelope, sequence, previous.digest(), recordedAt);
        return new AuditRecord(envelope, sequence, previous.digest(), digest, recordedAt, false, null, null, null);
    }

/**
 * Returns the predecessor digest when this is not the genesis record.
 * @return empty for sequence one, otherwise the predecessor's sealed digest
 */
    public Optional<EventDigest> previousDigestIfAny() {
        return Optional.ofNullable(previousDigest);
    }

/**
 * Redaction preserves the chain shape and only replaces content, per the class Javadoc.
 * @param placeholder sanitized replacement event
 * @param redactedAt instant at which content was removed
 * @param reason safe reason retained with the tombstone
 * @param redactedBy authenticated actor performing the redaction
 * @return tombstone preserving sequence and linkage while removing original event content
 */
    public AuditRecord redactedCopy(OpaquePayload placeholder, Instant redactedAt, String reason, String redactedBy) {
        if (this.redacted) {
            throw new IllegalStateException("record at sequence " + sequence + " is already redacted");
        }
        AuditEnvelope redactedEnvelope = new AuditEnvelope(envelope.envelopeVersion(), envelope.auditId(),
                envelope.tenantId(), envelope.principal(), envelope.category(), envelope.action(),
                envelope.resourceType(), envelope.resourceId(), envelope.outcome(), envelope.reason(),
                envelope.correlationId(), envelope.occurredAt(), placeholder);
        return new AuditRecord(redactedEnvelope, sequence, previousDigest, digest, recordedAt, true,
                Objects.requireNonNull(redactedAt, "redactedAt"), requireText(reason, "reason"),
                requireText(redactedBy, "redactedBy"));
    }

    /**
     * Recomputes {@link #digest()} from this record's current fields and compares it with the digest
     * it carries. {@code false} means the stored bytes and the stored digest disagree — a tamper or a
     * storage corruption, and {@link AuditTrail#verify} must report it, never swallow or retry it.
     *
     * @throws IllegalStateException if this record is redacted; see the class Javadoc
 * @return whether the stored digest equals the digest recomputed from unredacted content
     */
    public boolean digestMatchesContent() {
        return digest.equals(recomputeDigest());
    }

    /**
     * Recomputes the digest from this record's current fields, independent of whether it matches
     * {@link #digest()}. An independent verifier uses this to report <em>what</em> the content
     * currently hashes to, alongside the stored value it disagrees with.
     *
     * @throws IllegalStateException if this record is redacted; see the class Javadoc
 * @return digest recomputed from this unredacted record's canonical content
     */
    public EventDigest recomputeDigest() {
        if (redacted) {
            throw new IllegalStateException(
                    "sequence " + sequence + " is redacted: its content digest cannot be recomputed by design, "
                            + "call redacted() first");
        }
        return seal(envelope, sequence, previousDigest, recordedAt);
    }

    /**
     * A digest over everything redaction does <strong>not</strong> touch: the whole record except
     * {@link AuditEnvelope#detail()}.
     *
     * <p>Redaction replaces only {@code detail}, so this value is identical before and after — which is
     * what makes it usable as the thing a redaction tombstone commits to. {@link AuditTrail#verify}
     * compares it against the seal the tombstone recorded, so a redacted record's remaining fields stay
     * verifiable even though its content digest legitimately no longer matches.
     *
     * <p>On its own this proves nothing: the digest is unkeyed, so anyone who edits the record can
     * recompute it. It carries weight only because the value is stored inside a <em>chained</em>
     * tombstone, and rewriting that means rewriting every record after it.
 * @return digest preserved across redaction so later chain links remain verifiable
     */
    public EventDigest redactionSeal() {
        return sha256(canonicalForm(envelope, sequence, previousDigest, recordedAt, false));
    }

    private static EventDigest seal(AuditEnvelope envelope, long sequence, EventDigest previousDigest,
                                    Instant recordedAt) {
        return sha256(canonicalForm(envelope, sequence, previousDigest, recordedAt, true));
    }

    private static final byte ABSENT = 0;
    private static final byte PRESENT = 1;

    /**
     * The length-prefixed canonical byte form the digest is taken over, following exactly the
     * technique {@code EventEnvelope.canonicalForm} documents and for the same reason: plain
     * concatenation lets two different field splits produce identical bytes, and every variable-width
     * field here is therefore preceded by its length, with absent and present encoded distinctly.
     */
    private static byte[] canonicalForm(AuditEnvelope envelope, long sequence, EventDigest previousDigest,
                                        Instant recordedAt, boolean includeDetail) {
        var out = new ByteArrayOutputStream(256);
        putInt(out, envelope.envelopeVersion());
        putUuid(out, envelope.auditId());
        putText(out, envelope.tenantId());
        putText(out, envelope.principal());
        putText(out, envelope.category().name());
        putText(out, envelope.action());
        putText(out, envelope.resourceType());
        putText(out, envelope.resourceId());
        putText(out, envelope.outcome().name());
        putText(out, envelope.reason());
        putText(out, envelope.correlationId());
        putLong(out, envelope.occurredAt().getEpochSecond());
        putInt(out, envelope.occurredAt().getNano());
        // The one branch, and the reason the seal and the content digest cannot be confused: the
        // ABSENT marker means "detail deliberately excluded", so a record's seal can never collide
        // with the content digest of some other record that merely happens to carry empty detail.
        if (includeDetail) {
            out.write(PRESENT);
            putText(out, envelope.detail().contentType());
            putBytes(out, envelope.detail().bytes());
        } else {
            out.write(ABSENT);
        }
        putLong(out, sequence);
        if (previousDigest == null) {
            out.write(ABSENT);
        } else {
            out.write(PRESENT);
            putBytes(out, previousDigest.value());
        }
        putLong(out, recordedAt.getEpochSecond());
        putInt(out, recordedAt.getNano());
        return out.toByteArray();
    }

    private static void putUuid(ByteArrayOutputStream out, UUID value) {
        putLong(out, value.getMostSignificantBits());
        putLong(out, value.getLeastSignificantBits());
    }

    private static void putText(ByteArrayOutputStream out, String value) {
        putBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void putBytes(ByteArrayOutputStream out, byte[] value) {
        putInt(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void putInt(ByteArrayOutputStream out, int value) {
        out.write(ByteBuffer.allocate(Integer.BYTES).putInt(value).array(), 0, Integer.BYTES);
    }

    private static void putLong(ByteArrayOutputStream out, long value) {
        out.write(ByteBuffer.allocate(Long.BYTES).putLong(value).array(), 0, Long.BYTES);
    }

    /**
     * Computes a SHA-256 digest and wraps it as an {@link EventDigest}, deliberately reusing that
     * type's equality, hex rendering and defensive-copy discipline rather than inventing a parallel
     * one (ADR 0011's {@code EventDigest} is the existing primitive to reuse first).
     * {@code EventDigest.over(byte[])} itself is package-private to {@code ai.ravenroot.api
     * .persistence}, so this computes the same algorithm independently rather than widening that
     * class's surface for a single caller outside its package.
     */
    private static EventDigest sha256(byte[] canonicalForm) {
        try {
            return EventDigest.of(MessageDigest.getInstance("SHA-256").digest(canonicalForm));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", impossible);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
