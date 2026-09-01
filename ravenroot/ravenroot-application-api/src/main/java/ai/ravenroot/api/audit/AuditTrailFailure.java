package ai.ravenroot.api.audit;

/**
 * The closed classification vocabulary for {@link AuditTrail} write-path failures, following the
 * {@code ExecutionStoreFailure} precedent (ADR 0010 section 12): a shared, closed taxonomy is what
 * lets a caller decide what to do without pattern-matching each adapter's own exception type.
 */
public sealed interface AuditTrailFailure {

/**
 * Human-readable diagnosis. Never contains payload bytes or secrets.
 * @return safe human-readable diagnosis without payload bytes or secrets
 */
    String describe();

/**
 * The request was rejected on its own terms, before any stored state was read or written.
 * @param reason safe rejection explanation
 */
    record InvalidRequest(String reason) implements AuditTrailFailure {
        @Override
        public String describe() {
            return "invalid audit request: " + reason;
        }
    }

/**
 * A {@code redact} range does not lie entirely within the tenant's existing, unredacted chain.
 * @param tenantId tenant whose chain does not cover the requested range
 * @param fromSequence requested first sequence
 * @param toSequence requested last sequence
 * @param chainLength current tenant chain length
 */
    record RedactionOutOfRange(String tenantId, long fromSequence, long toSequence, long chainLength)
            implements AuditTrailFailure {
        @Override
        public String describe() {
            return "redaction range [" + fromSequence + ", " + toSequence + "] is out of range for tenant "
                    + tenantId + ", whose chain currently holds " + chainLength + " record(s)";
        }
    }

/**
 * The trail could not be written to or read from. The write is known not to have applied.
 * @param reason safe store-unavailability diagnosis
 */
    record Unavailable(String reason) implements AuditTrailFailure {
        @Override
        public String describe() {
            return "audit trail unavailable: " + reason;
        }
    }

    /**
     * Stored records exist but cannot be parsed, so the chain's head cannot be established.
     *
     * <p>Distinct from {@link Unavailable}, and the distinction is the caller's next move rather than a
     * shade of meaning. {@code Unavailable} is transient — the disk is busy, the directory is gone,
     * retry later. This is not: the bytes on disk will parse no better on the next attempt, and
     * appending anyway risks reusing a sequence number that an unreadable record already holds, which
     * would forge a collision into the chain while trying to recover from one. The operator must look
     * at the trail, and {@link AuditTrail#verify} is the operation that says where — it reports
     * {@link ChainAnomaly.MalformedRecord} instead of failing, precisely so this failure has somewhere
     * to send them.
 * @param tenantId tenant for which audit evidence is required
 * @param reason reason the trail cannot satisfy that requirement
     */
    record Corrupted(String tenantId, String reason) implements AuditTrailFailure {
        @Override
        public String describe() {
            return "audit trail for tenant " + tenantId + " holds unreadable records, so its head cannot be "
                    + "established; run verify() to locate them: " + reason;
        }
    }
}
