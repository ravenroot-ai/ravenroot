package ai.ravenroot.api.audit;

/**
 * Optional facilities an {@link AuditTrail} adapter may declare, following the
 * {@code ai.ravenroot.api.persistence.StoreCapability} precedent (ADR 0010 section 11, ADR 0011).
 *
 * <p>Enforcement is asymmetric, exactly as for {@code StoreCapability}: absence of a capability skips
 * its conformance assertion, presence never skips it. Declaring a capability an adapter does not
 * honour to route around a skipped assertion invalidates the conformance result.</p>
 */
public enum AuditCapability {
    /**
     * State survives process death. The conformance assertion for this closes the adapter, reopens
     * it against the same backing identity, and checks that every record and the chain head are
     * still there and still verify clean. An in-memory adapter must not declare this.
     */
    DURABLE
}
