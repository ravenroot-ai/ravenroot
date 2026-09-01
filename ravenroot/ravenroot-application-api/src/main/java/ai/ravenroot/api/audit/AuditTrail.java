package ai.ravenroot.api.audit;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The durable, append-only, tamper-evident security audit trail port (SEC-13, ADR 0013).
 *
 * <h2>Scope: separate from the PERS-07 event journal</h2>
 * <p>This is a different store from {@code ai.ravenroot.api.persistence.ExecutionStore}'s event
 * journal, on purpose. The journal's retention discards a record once it is both past its window
 * <em>and</em> delivered to every destination — a condition with no analogue here, since nothing
 * "delivers" an audit record. The journal's per-tenant {@code journalOffset} is explicitly "not
 * guaranteed contiguous" (ADR 0011 section 4), which makes it unusable as a gap-detection axis; this
 * port's {@link AuditRecord#sequence()} is contiguous by construction instead. And audit read is a
 * distinct, more sensitive authorization surface than execution replay (see
 * {@code AuthorizedAuditTrail}), which coupling the two stores would blur.
 *
 * <h2>Multi-tenancy: one chain per tenant, never global</h2>
 * <p>Every operation is tenant-scoped and every chain (sequence numbering, digest linkage, chain
 * head) is independent per tenant, matching the physical tenant isolation ADR 0011 documents
 * and for the same reason: a global chain would let one tenant observe whether, and roughly how much,
 * another tenant was doing anything at all, just from watching sequence numbers advance.
 *
 * <h2>The integrity proof, and what it does not defend against</h2>
 * <p>Each {@link AuditRecord} carries a SHA-256 {@code digest} over its own content <em>and</em> its
 * {@code previousDigest} and {@code sequence} (see {@link AuditRecord}'s Javadoc for why this differs
 * from {@code EventEnvelope}'s split). {@link #verify} walks a tenant's chain and reports two distinct
 * defects, because SEC-13 requires both to be detected and reported, not conflated:</p>
 * <ul>
 *   <li><strong>Tamper</strong> — a record present in the chain whose content or linkage digest does
 *   not match what its neighbours say it should be ({@link ChainAnomaly.TamperedContent},
 *   {@link ChainAnomaly.TamperedLinkage}).</li>
 *   <li><strong>Gap</strong> — a sequence number that should exist but does not, whether in the
 *   middle of the chain ({@link ChainAnomaly.Gap}) or at its tail, where a deleted suffix leaves no
 *   surviving record to notice a broken link ({@link ChainAnomaly.HeadMismatch}, checked against a
 *   watermark advanced only by {@link #append}).</li>
 * </ul>
 *
 * <p><strong>This digest is unkeyed, exactly like {@code EventDigest}, and for the same reason it is
 * not authentication.</strong> An actor with read-write access to an adapter's entire backing store —
 * every record and, in an adapter that colocates it, the chain-head watermark too — can recompute a
 * fully self-consistent replacement chain; nothing here can distinguish that from the original. What
 * this design defends against is accidental corruption and a <em>partial</em> tamper: a change to one
 * record, or a deletion, made without also rewriting every record after it (and, in an adapter that
 * stores the watermark separately from the log, without also rewriting the watermark). Closing the
 * full-compromise gap needs either a keyed MAC or signature whose key the attacker does not have
 * (tracked as SEC-22, exactly as ADR 0011 defers the same gap for {@code EventDigest}) or a watermark
 * anchored somewhere genuinely outside the store-writer's own write scope, e.g. a separate credential
 * or an external system. Neither is provided by this port; both require out-of-tree adapter and
 * infrastructure work that this contract does not foreclose.
 *
 * <h2>Retention without breaking append-only</h2>
 * <p>{@link #redact} never deletes a record or shortens the chain. It replaces a record's
 * {@link AuditEnvelope#detail()} with a redaction placeholder while preserving its {@code sequence},
 * {@code previousDigest} and {@code digest} exactly as originally computed, and appends its own
 * {@link AuditCategory#ADMINISTRATION} tombstone record naming the range, the reason and the operator.
 * A legitimate retention pass therefore never produces a sequence gap; {@link #verify} reporting a gap
 * always means an unaccounted-for record, which is what makes the two — expiry and tamper —
 * distinguishable instead of colliding.
 *
 * <h2>Authorization</h2>
 * <p>This port performs none. Read, export and redact access are SEC-03 concerns, enforced by
 * {@code AuthorizedAuditTrail} the same way {@code AuthorizedRavenrootApplication} already gates the
 * application it wraps — including the fact that reading the trail is itself an {@code ACCESS}
 * fact the decorator appends to the very trail it just read.
 */
public interface AuditTrail extends AutoCloseable {

    /**
     * Appends {@code envelope} to its tenant's chain, assigning the next contiguous {@code sequence}
     * and chaining it to the tenant's current head.
     *
     * @throws AuditTrailException with {@link AuditTrailFailure.InvalidRequest} or
     *                              {@link AuditTrailFailure.Unavailable}
 * @param envelope validated audit event to append atomically
 * @return the sealed record appended to the tenant chain
     */
    AuditRecord append(AuditEnvelope envelope);

    /**
     * Reads up to {@code limit} records for {@code tenantId} with {@code sequence > afterSequence}, in
     * ascending sequence order. Never authorizes; see the class Javadoc.
 * @param tenantId tenant whose chain is read
 * @param afterSequence exclusive cursor; zero reads from the chain start
 * @param limit the limit constraint applied while processing the request.
 * @return records after the cursor, in ascending sequence order and bounded by {@code limit}
     */
    List<AuditRecord> read(String tenantId, long afterSequence, int limit);

/**
 * The last record appended for {@code tenantId}, absent for a tenant with an empty chain.
 * @param tenantId tenant whose chain head is requested
 * @return the last record, or empty when that tenant has no records
 */
    Optional<AuditRecord> head(String tenantId);

    /**
     * Walks {@code tenantId}'s entire chain from its genesis and reports every anomaly found. Never
     * throws for a tampered or gapped chain — that is the report, not a failure of verification
     * itself; see {@link ChainVerificationResult}.
 * @param tenantId tenant chain to verify
 * @return verification result including every detected anomaly
     */
    ChainVerificationResult verify(String tenantId);

    /**
     * Redacts the content of every record in {@code [fromSequenceInclusive, toSequenceInclusive]},
     * replacing each with a placeholder while preserving its position in the chain, then appends and
     * returns an {@link AuditCategory#ADMINISTRATION} tombstone recording what happened.
     *
     * @throws AuditTrailException with {@link AuditTrailFailure.RedactionOutOfRange} if the range is
     *                              not entirely covered by existing, unredacted records
 * @param tenantId tenant whose contiguous records are redacted
 * @param fromSequenceInclusive first sequence in the inclusive redaction range
 * @param toSequenceInclusive last sequence in the inclusive redaction range
 * @param reason safe-to-disclose reason retained in the tombstone
 * @param redactedBy authenticated actor performing the redaction
 * @return replacement records preserving the chain's sequence and digest shape
     */
    AuditRecord redact(String tenantId, long fromSequenceInclusive, long toSequenceInclusive, String reason,
                       String redactedBy);

/**
 * This adapter's own retention rule, declared synchronously; see {@link AuditRetentionPolicy}.
 * @return this adapter's declared retention policy
 */
    AuditRetentionPolicy retentionPolicy();

/**
 * The optional facilities this adapter honours; see {@link AuditCapability}.
 * @return immutable set of optional audit facilities honored by this adapter
 */
    Set<AuditCapability> capabilities();

/**
 * Tests whether an optional facility is available from this adapter.
 * @param capability facility to test
 * @return {@code true} only when {@link #capabilities()} includes the facility
 */
    default boolean supports(AuditCapability capability) {
        return capabilities().contains(capability);
    }

    @Override
    void close();
}
